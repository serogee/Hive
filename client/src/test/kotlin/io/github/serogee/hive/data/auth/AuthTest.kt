package io.github.serogee.hive.data.auth

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AccountProfile
import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.data.remote.HiveApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthTest {
    private val config = AuthConfig("https://hive-test.supabase.co", "publishable-key", "client-id")
    private var now = 1_000_000L
    private val store = FakeSessions()

    private class FakeSessions : SessionStore {
        override val session = MutableStateFlow<UserSession?>(null)

        override suspend fun currentSession() = session.value

        override suspend fun setSession(session: UserSession?) {
            this.session.value = session
        }
    }

    private fun repository(
        onSessionCleared: suspend () -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AuthRepository {
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(HiveJson) }
        }
        return AuthRepository(store, SupabaseAuth(http, config) { now }, demo = null, onSessionCleared = onSessionCleared) { now }
    }

    private fun MockRequestHandleScope.tokens(access: String, refresh: String = "refresh-$access", name: String = "Ana Reyes") = respond(
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"token_type":"bearer",""" +
            """"user":{"id":"user-1","email":"ana@example.com","user_metadata":{"full_name":"$name","avatar_url":"x"}}}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private suspend fun HttpRequestData.json() = Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    private fun expired() = UserSession(
        "old",
        Account(AccountId("user-1"), "Ana"),
        SignInProvider.GOOGLE,
        refreshToken = "refresh-old",
        expiresAt = now - 1,
        receivedAt = now - 3_600_000,
    )

    @Test
    fun `sign-in needs all three values`() {
        assertEquals(config, AuthConfig.of(" https://hive-test.supabase.co/ ", "publishable-key", "client-id"))
        listOf(
            Triple("", "key", "id"),
            Triple("https://hive-test.supabase.co", "", "id"),
            Triple("https://hive-test.supabase.co", "key", " "),
            Triple("", "", ""),
            Triple(null, null, null),
            Triple("hive-test.supabase.co", "key", "id"),
        ).forEach { (url, key, id) -> assertNull(AuthConfig.of(url, key, id), "$url $key $id") }
    }

    @Test
    fun `without the values, sign-in is off and nothing fails`() = runTest {
        val auth = AuthRepository(store, supabase = null, demo = null)
        assertFalse(auth.googleAvailable)
        assertFalse(auth.demoAvailable)
        assertNull(auth.accessToken())
        auth.restore()
        auth.signOut()
        assertFailsWith<SignInException> { auth.signInWithGoogle { "id-token" } }
    }

    @Test
    fun `a Google ID token becomes a session`() = runTest {
        var hashed = ""
        val auth = repository { request ->
            assertEquals("https://hive-test.supabase.co/auth/v1/token?grant_type=id_token", request.url.toString())
            assertEquals("publishable-key", request.headers["apikey"])
            val body = request.json()
            assertEquals("google", body.getValue("provider").jsonPrimitive.content)
            assertEquals("id-token", body.getValue("id_token").jsonPrimitive.content)
            val nonce = body.getValue("nonce").jsonPrimitive.content
            val digest = MessageDigest.getInstance("SHA-256").digest(nonce.encodeToByteArray())
            assertEquals(hashed, digest.joinToString("") { "%02x".format(it) })
            tokens("first")
        }
        assertTrue(auth.signInWithGoogle { hash -> "id-token".also { hashed = hash } })

        val session = assertNotNull(store.session.value)
        assertEquals(Account(AccountId("user-1"), "Ana Reyes"), session.account)
        assertEquals(SignInProvider.GOOGLE, session.provider)
        assertEquals(now + 3_600_000, session.expiresAt)
        assertEquals("first", auth.accessToken())
    }

    @Test
    fun `cancelling leaves the user signed out`() = runTest {
        val auth = repository { error("must not ask Supabase") }
        assertFalse(auth.signInWithGoogle { null })
        assertNull(store.session.value)
    }

    @Test
    fun `a refused ID token says so`() = runTest {
        val auth = repository { respond("{}", HttpStatusCode.BadRequest) }
        assertFailsWith<SignInException> { auth.signInWithGoogle { "id-token" } }
        assertNull(store.session.value)
    }

    @Test
    fun `an expired access token is refreshed once for requests that arrive together`() = runTest {
        var refreshes = 0
        val auth = repository { request ->
            refreshes++
            assertEquals("refresh_token", request.url.parameters["grant_type"])
            assertEquals("refresh-old", request.json().getValue("refresh_token").jsonPrimitive.content)
            tokens("new")
        }
        store.session.value = expired()
        val both = listOf(async { auth.accessToken() }, async { auth.accessToken() }).map { it.await() }
        assertEquals(listOf<String?>("new", "new"), both)
        assertEquals(1, refreshes)
        assertEquals("refresh-new", store.session.value?.refreshToken)
    }

    @Test
    fun `being offline keeps the session`() = runTest {
        val auth = repository { throw IOException("offline") }
        store.session.value = expired()
        assertFailsWith<IOException> { auth.accessToken() }
        auth.restore()
        auth.sessionRefused()
        assertEquals("old", store.session.value?.accessToken)
    }

    @Test
    fun `a Supabase outage keeps the session`() = runTest {
        listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests).forEach { status ->
            val auth = repository { respond("", status) }
            store.session.value = expired()
            assertFailsWith<IOException> { auth.accessToken() }
            assertNotNull(store.session.value)
        }
    }

    @Test
    fun `a refresh token that ended signs the user out`() = runTest {
        val auth = repository { respond("""{"error_code":"refresh_token_not_found"}""", HttpStatusCode.BadRequest) }
        store.session.value = expired()
        assertNull(auth.accessToken())
        assertNull(store.session.value)
    }

    @Test
    fun `a refused access token is replaced before the session ends`() = runTest {
        var cleared = 0
        val auth = repository(onSessionCleared = { cleared++ }) { tokens("new") }
        store.session.value = expired().copy(expiresAt = now + 3_000_000)
        assertTrue(auth.sessionRefused("old"))
        assertEquals("new", store.session.value?.accessToken)
        assertEquals(0, cleared)

        // A delayed refusal from another concurrent request that carried the old token keeps the new session.
        assertTrue(auth.sessionRefused("old"))
        assertEquals("new", store.session.value?.accessToken)
        assertEquals(0, cleared)

        // The new token is refused as well, so the session ends and cleanup runs.
        assertFalse(auth.sessionRefused("new"))
        assertNull(store.session.value)
        assertEquals(1, cleared)
    }

    @Test
    fun `concurrent 401 SESSION_EXPIRED responses refresh once and retry without signing out`() = runTest {
        var refreshes = 0
        var cleared = 0
        val auth = repository(onSessionCleared = { cleared++ }) {
            refreshes++
            tokens("new")
        }
        store.session.value = expired().copy(expiresAt = now + 3_000_000)

        val apiClient = HttpClient(
            MockEngine { request ->
                if (request.headers[HttpHeaders.Authorization] == "Bearer new") {
                    respond(
                        HiveJson.encodeToString(AccountProfile.serializer(), AccountProfile("Ana Reyes")),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        HiveJson.encodeToString(ApiError.serializer(), ApiError(ErrorCode.SESSION_EXPIRED, "Sign in again.")),
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(HiveJson) }
        }
        val api = HiveApi(
            serverUrl = { "https://hive.example" },
            accessToken = { auth.accessToken() },
            onSessionExpired = { refusedToken -> auth.sessionRefused(refusedToken) },
            http = apiClient,
        )

        val profiles = listOf(async { api.accountProfile() }, async { api.accountProfile() }).map { it.await() }

        assertEquals(listOf(AccountProfile("Ana Reyes"), AccountProfile("Ana Reyes")), profiles)
        assertEquals(1, refreshes)
        assertEquals(0, cleared)
        assertEquals("new", store.session.value?.accessToken)
    }

    @Test
    fun `signing out clears the session, also offline`() = runTest {
        var path = ""
        val auth = repository { request ->
            path = request.url.encodedPath
            throw IOException("offline")
        }
        store.session.value = expired()
        auth.signOut()
        assertNull(store.session.value)
        assertEquals("/auth/v1/logout", path)
    }

    @Test
    fun `a session from an earlier version is a demo session`() {
        val saved = """{"accessToken":"token","account":{"id":"account-alex","displayName":"Alex Rivera"}}"""
        val session = HiveJson.decodeFromString(UserSession.serializer(), saved)
        assertEquals(SignInProvider.DEMO, session.provider)
        assertFalse(session.needsRefresh(Long.MAX_VALUE))
    }
}
