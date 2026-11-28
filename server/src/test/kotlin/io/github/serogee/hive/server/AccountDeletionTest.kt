package io.github.serogee.hive.server

import com.sun.net.httpserver.HttpServer
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.server.demo.DemoAccounts
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import io.github.serogee.hive.server.demo.SqliteRoomStore
import io.github.serogee.hive.server.demo.demoAuthRoutes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AccountDeletionTest {
    private val directory = Files.createTempDirectory("hive-account-deletion")
    private val now = 1_800_000_000_000L
    private val db = DemoDatabase.open(directory.resolve("demo.db")).also { DemoSeed.reset(it, now) }

    @AfterTest
    fun cleanUp() {
        db.close()
        directory.toFile().deleteRecursively()
    }

    private fun ApplicationTestBuilder.client(deleter: AccountDeleter?): HttpClient {
        val accounts = DemoAccounts(db) { now }
        application { hiveModule(Backend(SqliteRoomStore(db), accounts, HealthMode.DEMO, db.syncStore(), deleter) { demoAuthRoutes(accounts) }) }
        return createClient {
            install(ContentNegotiation) { json(HiveJson) }
            install(WebSockets)
        }
    }

    private suspend fun HttpClient.token(username: String): String = post(ApiPaths.DEMO_LOGIN) {
        contentType(ContentType.Application.Json)
        setBody(DemoLoginRequest(username, DemoSeed.PASSWORD))
    }.body<AuthSession>().accessToken

    private fun query(sql: String): List<String> = runBlocking {
        db.read { connection -> connection.createStatement().use { s -> s.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1) ?: "null") } } } }
    }

    @Test
    fun deletingNeedsSignInAndASetUpServer() = testApplication {
        val client = client(deleter = null)
        assertEquals(HttpStatusCode.Unauthorized, client.delete(ApiPaths.ACCOUNT).status)
        val refused = client.delete(ApiPaths.ACCOUNT) { bearerAuth(client.token("alex")) }
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals(listOf("2"), query("SELECT COUNT(*) FROM accounts"))
    }

    @Test
    fun anAccountIsDeletedWithItsDataAndStaysLikeAGuestInOtherRooms() = testApplication {
        val signInDeleted = mutableListOf<AccountId>()
        val client = client(AccountDeleter(db.accountData(), signIn = { signInDeleted += it }))
        val sam = client.token("sam")
        client.post(ApiPaths.SYNC_STACKS) {
            bearerAuth(sam)
            contentType(ContentType.Application.Json)
            setBody(PushStacksRequest(listOf(StackChange("s1", document = StackDocument("Biology")))))
        }

        assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.ACCOUNT) { bearerAuth(sam) }.status)

        assertEquals(listOf(DemoSeed.sam.id), signInDeleted)
        assertEquals(listOf("demo-account-alex"), query("SELECT id FROM accounts"))
        assertEquals(listOf("0"), query("SELECT COUNT(*) FROM sync_stacks"))
        assertEquals(listOf("0"), query("SELECT COUNT(*) FROM sessions WHERE account_id = 'demo-account-sam'"))
        // Sam hosted the take-home room, which ends without a host account.
        assertEquals(listOf("ENDED|null"), query("SELECT status || '|' || IFNULL(host_account_id, 'null') FROM rooms WHERE id = 'demo-room-async'"))
        // Sam played Alex's ended room: the result and the name stay, like a guest's, without the account.
        assertEquals(listOf("Sam Cruz"), query("SELECT display_name FROM participants WHERE id = 'demo-p-ended-sam' AND account_id IS NULL"))
        // Sam's token no longer works.
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.ME) { bearerAuth(sam) }.status)
    }

    @Test
    fun deletingClosesOpenHostedQuestionsBroadcastsEndAndRevokesParticipantTokens() = testApplication {
        val client = client(AccountDeleter(db.accountData(), signIn = {}))
        val alex = client.token("alex")
        val sam = client.token("sam")
        // Alex starts the discussion room so question 1 is open; Sam joins as an account player and a guest listens.
        client.post(ApiPaths.commands("demo-room-discussion")) {
            bearerAuth(alex)
            contentType(ContentType.Application.Json)
            setBody<HostCommand>(HostCommand.Start)
        }
        val samMembership = client.post(ApiPaths.participants("demo-room-discussion")) {
            bearerAuth(sam)
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest(asGuest = false))
        }.body<Membership>()
        val guest = client.post(ApiPaths.participants("demo-room-discussion")) {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest("Jamie", asGuest = true))
        }.body<Membership>()

        // Deleting Sam revokes Sam's participant token even though Sam's name stays in the room.
        assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.ACCOUNT) { bearerAuth(sam) }.status)
        val revoked = client.get(ApiPaths.state("demo-room-discussion")) {
            header(ApiPaths.PARTICIPANT_TOKEN_HEADER, samMembership.participantToken)
        }
        assertEquals(HttpStatusCode.Forbidden, revoked.status)

        // Deleting Alex ends the open room, closes the open question, and broadcasts RoomEnded to connected guests.
        val subscribed = CompletableDeferred<Unit>()
        val endedEvent = CompletableDeferred<RoomEvent>()
        val received = coroutineScope {
            val listener = launch {
                client.webSocket(ApiPaths.events("demo-room-discussion"), request = {
                    header(ApiPaths.PARTICIPANT_TOKEN_HEADER, guest.participantToken)
                }) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val event = HiveJson.decodeFromString(RoomEvent.serializer(), frame.readText())
                        if (event is RoomEvent.Subscribed) subscribed.complete(Unit)
                        if (event is RoomEvent.RoomEnded) {
                            endedEvent.complete(event)
                            break
                        }
                    }
                }
            }
            withTimeout(5_000) { subscribed.await() }
            assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.ACCOUNT) { bearerAuth(alex) }.status)
            val event = withTimeout(5_000) { endedEvent.await() }
            listener.join()
            event
        }

        assertIs<RoomEvent.RoomEnded>(received)
        assertEquals(RoomId("demo-room-discussion"), received.roomId)
        assertEquals(listOf("ENDED|0|Alex Rivera"), query("SELECT status || '|' || question_open || '|' || host_name FROM rooms WHERE id = 'demo-room-discussion'"))
    }

    @Test
    fun aFailedSignInDeleteLeavesServerDataUntouched() = testApplication {
        val client = client(
            AccountDeleter(
                db.accountData(),
                signIn = { throw HiveException(ErrorCode.AUTH_UNAVAILABLE, "Supabase down") },
            ),
        )
        val sam = client.token("sam")
        client.post(ApiPaths.SYNC_STACKS) {
            bearerAuth(sam)
            contentType(ContentType.Application.Json)
            setBody(PushStacksRequest(listOf(StackChange("s1", document = StackDocument("Biology")))))
        }

        val failed = client.delete(ApiPaths.ACCOUNT) { bearerAuth(sam) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertEquals(listOf("2"), query("SELECT COUNT(*) FROM accounts"))
        assertEquals(listOf("1"), query("SELECT COUNT(*) FROM sync_stacks"))
        assertEquals(listOf("LIVE"), query("SELECT status FROM rooms WHERE id = 'demo-room-async'"))
    }

    @Test
    fun supabaseGetsTheSecretKeyInTheApikeyHeaderOnly() = runBlocking {
        val seen = mutableListOf<String>()
        var status = 200
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seen += "${exchange.requestMethod} ${exchange.requestURI} apikey=${exchange.requestHeaders.getFirst("apikey")} " +
                    "authorization=${exchange.requestHeaders.getFirst("Authorization")}"
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }
        try {
            val admin = SupabaseAdmin("http://127.0.0.1:${server.address.port}", Secret("sb_secret_test"))
            admin.delete(AccountId("user-1"))
            assertEquals("DELETE /auth/v1/admin/users/user-1 apikey=sb_secret_test authorization=null", seen.single())

            status = 404
            admin.delete(AccountId("user-1"))

            status = 500
            val failed = assertFailsWith<HiveException> { admin.delete(AccountId("user-1")) }
            assertEquals(ErrorCode.AUTH_UNAVAILABLE, failed.code)
            assertTrue("sb_secret" !in failed.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theSecretKeyIsNeverPrinted() {
        val config = ServerConfig.from(emptyArray(), mapOf("SUPABASE_SECRET_KEY" to "sb_secret_test", "HIVE_MODE" to "production"))
        assertTrue("sb_secret_test" !in config.toString())
        assertEquals("sb_secret_test", config.supabaseSecretKey?.reveal())
        assertNull(ServerConfig.from(emptyArray(), mapOf("SUPABASE_SECRET_KEY" to " ")).supabaseSecretKey)
    }

    @Test
    fun deleterBlocksCachedTokensBeforeDeletingServerRows() = runBlocking {
        val steps = mutableListOf<String>()
        val deleter = AccountDeleter(
            data = {
                steps += "delete-db"
                emptyList()
            },
            signIn = { steps += "delete-auth" },
            forget = { steps += "forget-cache" },
        )

        deleter.delete(AccountId("account-1"))

        assertEquals(listOf("delete-auth", "forget-cache", "delete-db"), steps)
    }
}
