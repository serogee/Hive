package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HealthResponse
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.InMemoryRoomStore
import io.github.serogee.hive.host.RoomService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionServerTest {
    private val store = InMemoryRoomStore()

    private fun ApplicationTestBuilder.client(accounts: AccountVerifier): HttpClient {
        application { hiveModule(Backend(store, accounts, HealthMode.PRODUCTION)) }
        return createClient { install(ContentNegotiation) { json(HiveJson) } }
    }

    private suspend fun HttpResponse.assertNotConfigured() {
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals(ErrorCode.AUTH_NOT_CONFIGURED, body<ApiError>().code)
    }

    @Test
    fun healthSaysWhetherSignInIsConfigured() {
        testApplication {
            val health: HealthResponse = client(AccountVerifier.NotConfigured).get(ApiPaths.HEALTH).body()
            assertTrue(health.isOk)
            assertEquals(HealthMode.PRODUCTION, health.mode)
            assertEquals(HealthAuth.NOT_CONFIGURED, health.auth)
        }
        testApplication {
            val health: HealthResponse = client { null }.get(ApiPaths.HEALTH).body()
            assertEquals(HealthAuth.CONFIGURED, health.auth)
        }
    }

    @Test
    fun endpointsThatNeedAnAccountSayThatSignInIsNotConfigured() = testApplication {
        val client = client(AccountVerifier.NotConfigured)
        client.get(ApiPaths.ME).assertNotConfigured()
        client.get(ApiPaths.ME) { bearerAuth("some-token") }.assertNotConfigured()
        client.get(ApiPaths.HOSTED_ROOMS).assertNotConfigured()
        client.post(ApiPaths.ROOMS) {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION))
        }.assertNotConfigured()
    }

    @Test
    fun guestsCanStillJoinWithoutSignIn() = testApplication {
        val host = Account(AccountId("host"), "Host")
        val room = RoomService(store, requireAccountToHost = true)
            .createRoom(host, CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION)).room
        val client = client(AccountVerifier.NotConfigured)
        suspend fun join(asGuest: Boolean) = client.post(ApiPaths.participants(room.id.value)) {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest("Jamie", asGuest))
        }
        assertEquals(HttpStatusCode.OK, join(asGuest = true).status)
        join(asGuest = false).assertNotConfigured()
    }

    @Test
    fun theServerStartsWithoutAUsableSupabaseAddress() {
        listOf(null, "hive-test.supabase.co", "http://[broken").forEach { assertFalse(productionAccounts(it).configured, "$it") }
        assertTrue(productionAccounts("https://hive-test.supabase.co").configured)
    }

    @Test
    fun theSupabaseAddressComesFromTheEnvironment() {
        fun url(value: String?) = ServerConfig.from(emptyArray(), listOfNotNull(value?.let { "SUPABASE_URL" to it }).toMap()).supabaseUrl

        assertEquals("https://hive-test.supabase.co", url(" https://hive-test.supabase.co/ "))
        assertEquals(null, url("  "))
        assertEquals(null, url(null))
    }
}
