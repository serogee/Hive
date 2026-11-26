package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.HealthResponse
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.server.Backend
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.server.demo.DemoAccounts
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import io.github.serogee.hive.server.demo.SqliteRoomStore
import io.github.serogee.hive.server.demo.demoAuthRoutes
import io.github.serogee.hive.server.hiveModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncRoutesTest {
    private val directory = Files.createTempDirectory("hive-sync-routes")
    private val now = 1_800_000_000_000L
    private val db = DemoDatabase.open(directory.resolve("demo.db")).also { DemoSeed.reset(it, now) }

    @AfterTest
    fun cleanUp() {
        db.close()
        directory.toFile().deleteRecursively()
    }

    private fun ApplicationTestBuilder.client(): HttpClient {
        val accounts = DemoAccounts(db) { now }
        application { hiveModule(Backend(SqliteRoomStore(db), accounts, HealthMode.DEMO, db.syncStore()) { demoAuthRoutes(accounts) }) }
        return createClient { install(ContentNegotiation) { json(HiveJson) } }
    }

    private suspend fun HttpClient.token(username: String): String = post(ApiPaths.DEMO_LOGIN) {
        contentType(ContentType.Application.Json)
        setBody(DemoLoginRequest(username, DemoSeed.PASSWORD))
    }.body<AuthSession>().accessToken

    @Test
    fun syncNeedsSignIn() = testApplication {
        val client = client()
        assertTrue(client.get(ApiPaths.HEALTH).body<HealthResponse>().sync)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.SYNC_CHANGES).status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete(ApiPaths.SYNC).status)
    }

    @Test
    fun eachAccountReadsAndDeletesOnlyItsOwnStacks() = testApplication {
        val client = client()
        val alex = client.token("alex")
        val sam = client.token("sam")
        val pushed: PushStacksResponse = client.post(ApiPaths.SYNC_STACKS) {
            bearerAuth(alex)
            contentType(ContentType.Application.Json)
            setBody(PushStacksRequest(listOf(StackChange("s1", document = StackDocument("Biology")))))
        }.body()
        assertEquals(1, pushed.results.single().stack.version)

        assertTrue(client.get(ApiPaths.SYNC_CHANGES) { bearerAuth(sam) }.body<SyncChanges>().stacks.isEmpty())
        assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.SYNC) { bearerAuth(sam) }.status)
        val forAlex: SyncChanges = client.get("${ApiPaths.SYNC_CHANGES}?cursor=0&limit=10") { bearerAuth(alex) }.body()
        assertEquals(listOf("Biology"), forAlex.stacks.map { it.document?.title })
    }

    @Test
    fun aStackWithoutContentIsRefused() = testApplication {
        val client = client()
        val response = client.post(ApiPaths.SYNC_STACKS) {
            bearerAuth(client.token("alex"))
            contentType(ContentType.Application.Json)
            setBody(PushStacksRequest(listOf(StackChange("s1"))))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun onlineRoomsListHostedAndPlayedRoomsWithTheOwnScore() = testApplication {
        val client = client()
        val rooms: List<OnlineRoomEntry> = client.get(ApiPaths.SYNC_ROOMS) { bearerAuth(client.token("sam")) }.body()

        assertEquals(setOf(DemoSeed.ASYNC_CODE to RoomRole.HOST, DemoSeed.ENDED_CODE to RoomRole.PLAYER), rooms.map { it.code to it.role }.toSet())
        val played = rooms.single { it.role == RoomRole.PLAYER }
        assertEquals(RoomStatus.ENDED, played.status)
        assertEquals(3, played.playerCount)
        assertNotNull(played.rank)
        assertNotNull(played.totalTenths)
        val hosted = rooms.single { it.role == RoomRole.HOST }
        assertEquals(null, hosted.rank)
    }
}
