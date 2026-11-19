package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.server.Backend
import io.github.serogee.hive.server.hiveModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DemoServerTest {
    private val directory: Path = Files.createTempDirectory("hive-demo-test")
    private val dbPath: Path = directory.resolve("demo.db")
    private val now = 1_800_000_000_000L

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun ApplicationTestBuilder.demoClient(db: DemoDatabase): HttpClient {
        val accounts = DemoAccounts(db) { now }
        application { hiveModule(Backend(SqliteRoomStore(db), accounts) { demoAuthRoutes(accounts) }) }
        return createClient { install(ContentNegotiation) { json(HiveJson) } }
    }

    private suspend fun HttpClient.login(username: String, password: String = DemoSeed.PASSWORD) =
        post(ApiPaths.DEMO_LOGIN) {
            contentType(ContentType.Application.Json)
            setBody(DemoLoginRequest(username, password))
        }

    @Test
    fun signedInHostAndGuestShareTheSeededLobby() = testApplication {
        val db = DemoDatabase.open(dbPath).also { DemoSeed.reset(it, now) }
        val client = demoClient(db)

        val session: AuthSession = client.login("alex").body()
        val hosted: List<RoomSummary> = client.get(ApiPaths.HOSTED_ROOMS) { bearerAuth(session.accessToken) }.body()
        val room = hosted.single { it.code == DemoSeed.DISCUSSION_CODE }
        val lookedUp: RoomSummary = client.get(ApiPaths.roomByCode("netdsc")).body()
        val guest: Membership = client.post(ApiPaths.participants(room.id.value)) {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest("Jamie", asGuest = true))
        }.body()
        val hostView: RoomState = client.get(ApiPaths.state(room.id.value)) { bearerAuth(session.accessToken) }.body()
        val guestView: RoomState = client.get(ApiPaths.state(room.id.value)) {
            header(ApiPaths.PARTICIPANT_TOKEN_HEADER, guest.participantToken)
        }.body()

        assertEquals(room, lookedUp)
        assertEquals(setOf(DemoSeed.DISCUSSION_CODE, DemoSeed.ENDED_CODE), hosted.map { it.code }.toSet())
        assertEquals(listOf("Alex Rivera", "Jamie"), hostView.participants.map { it.displayName })
        assertEquals(hostView.participants, guestView.participants)
        db.close()
    }

    @Test
    fun seedCoversBothModesAndAnEndedRoom() = testApplication {
        val db = DemoDatabase.open(dbPath).also { DemoSeed.reset(it, now) }
        val client = demoClient(db)

        val async: RoomSummary = client.get(ApiPaths.roomByCode(DemoSeed.ASYNC_CODE)).body()
        val ended: RoomSummary = client.get(ApiPaths.roomByCode(DemoSeed.ENDED_CODE)).body()
        val lateJoin = client.post(ApiPaths.participants(ended.id.value)) {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest("Kai", asGuest = true))
        }

        assertEquals(RoomMode.ASYNCHRONOUS, async.mode)
        assertTrue(async.deadlineAt!! > now)
        assertEquals(RoomStatus.ENDED, ended.status)
        assertEquals(ErrorCode.ROOM_CLOSED, lateJoin.body<ApiError>().code)
        db.close()
    }

    @Test
    fun seededResultsContainATie() = testApplication {
        val db = DemoDatabase.open(dbPath).also { DemoSeed.reset(it, now) }
        val client = demoClient(db)
        val sam: AuthSession = client.login("sam").body()
        val ended: RoomSummary = client.get(ApiPaths.roomByCode(DemoSeed.ENDED_CODE)).body()

        val results: RoomResults = client.get(ApiPaths.results(ended.id.value)) { bearerAuth(sam.accessToken) }.body()

        assertEquals(listOf("Jamie", "Sam Cruz", "Riley"), results.leaderboard.map { it.displayName })
        assertEquals(listOf(3, 3, 1), results.leaderboard.map { it.score })
        assertEquals(listOf(1, 1, 3), results.leaderboard.map { it.rank })
        assertEquals(3, results.yourAnswers.sumOf { it.points })
        assertEquals(3, results.questions.first().answeredCount)
        db.close()
    }

    @Test
    fun loginLogoutAndSessionRestore() = testApplication {
        val db = DemoDatabase.open(dbPath).also { DemoSeed.reset(it, now) }
        val client = demoClient(db)

        val wrong = client.login("sam", "nope")
        val session: AuthSession = client.login("SAM").body()
        val restored: Account = client.get(ApiPaths.ME) { bearerAuth(session.accessToken) }.body()
        val logout = client.post(ApiPaths.DEMO_LOGOUT) { bearerAuth(session.accessToken) }
        val afterLogout = client.get(ApiPaths.ME) { bearerAuth(session.accessToken) }

        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrong.body<ApiError>().code)
        assertEquals("Sam Cruz", restored.displayName)
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(ErrorCode.SESSION_EXPIRED, afterLogout.body<ApiError>().code)
        db.close()
    }

    @Test
    fun demoDataSurvivesRestartUntilReset() = runTest {
        DemoDatabase.open(dbPath).use { db ->
            DemoSeed.reset(db, now)
            DemoAccounts(db) { now }.login("alex", DemoSeed.PASSWORD)
        }

        DemoDatabase.open(dbPath).use { db ->
            DemoSeed.seedIfEmpty(db, now)
            val sessions = db.read { connection ->
                connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM sessions").use { rows -> rows.next(); rows.getInt(1) } }
            }
            assertEquals(1, sessions)

            DemoSeed.reset(db, now)
            val afterReset = db.read { connection ->
                connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM sessions").use { rows -> rows.next(); rows.getInt(1) } }
            }
            assertEquals(0, afterReset)
        }
    }
}
