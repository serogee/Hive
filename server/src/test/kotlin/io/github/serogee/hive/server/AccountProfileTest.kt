package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AccountProfile
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.server.demo.DemoAccounts
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import io.github.serogee.hive.server.demo.SqliteRoomStore
import io.github.serogee.hive.server.demo.demoAuthRoutes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

class AccountProfileTest {
    private val directory = Files.createTempDirectory("hive-profile")
    private val now = 1_800_000_000_000L
    private val db = DemoDatabase.open(directory.resolve("demo.db")).also { DemoSeed.reset(it, now) }

    @AfterTest
    fun cleanUp() {
        db.close()
        directory.toFile().deleteRecursively()
    }

    private fun ApplicationTestBuilder.client(withProfiles: Boolean = true): HttpClient {
        val accounts = DemoAccounts(db) { now }
        application {
            hiveModule(Backend(SqliteRoomStore(db), accounts, HealthMode.DEMO, db.syncStore(), profiles = accounts.takeIf { withProfiles }) { demoAuthRoutes(accounts) })
        }
        return createClient { install(ContentNegotiation) { json(HiveJson) } }
    }

    private suspend fun HttpClient.token(username: String): String = post(ApiPaths.DEMO_LOGIN) {
        contentType(ContentType.Application.Json)
        setBody(DemoLoginRequest(username, DemoSeed.PASSWORD))
    }.body<AuthSession>().accessToken

    private suspend fun HttpClient.rename(token: String, name: String) = put(ApiPaths.ACCOUNT_PROFILE) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(AccountProfile(name))
    }

    @Test
    fun aChosenNameIsKeptAndUsedForSessions() = testApplication {
        val client = client()
        val alex = client.token("alex")
        assertEquals(AccountProfile("Alex Rivera"), client.get(ApiPaths.ACCOUNT_PROFILE) { bearerAuth(alex) }.body())

        val saved = client.rename(alex, "  Ms.   Rivera ")
        assertEquals(AccountProfile("Ms. Rivera"), saved.body())
        assertEquals(AccountProfile("Ms. Rivera"), client.get(ApiPaths.ACCOUNT_PROFILE) { bearerAuth(alex) }.body())
        assertEquals("Ms. Rivera", client.get(ApiPaths.ME) { bearerAuth(alex) }.body<Account>().displayName)

        val hosted: Membership = client.post(ApiPaths.ROOMS) {
            bearerAuth(alex)
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION))
        }.body()
        assertEquals("Ms. Rivera", hosted.room.hostName)
    }

    @Test
    fun namesThatAreEmptyOrTooLongAreRefused() = testApplication {
        val client = client()
        val alex = client.token("alex")
        listOf("   ", "x".repeat(31)).forEach { name ->
            assertEquals(HttpStatusCode.BadRequest, client.rename(alex, name).status, name)
        }
        assertEquals(AccountProfile("Alex Rivera"), client.get(ApiPaths.ACCOUNT_PROFILE) { bearerAuth(alex) }.body())
    }

    @Test
    fun theProfileNeedsSignInAndAServerThatKeepsAccounts() = testApplication {
        val client = client(withProfiles = false)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.ACCOUNT_PROFILE).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.rename(client.token("sam"), "Sam").status)
    }
}
