package io.github.serogee.hive.data.auth

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.model.AccountId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A sign-in that did not work, with a message for the user. */
class SignInException(message: String) : Exception(message)

/** The session is over, so the user has to sign in again. */
class SessionEndedException : Exception("Sign in again.")

/** The few Supabase Auth requests that Hive needs. [http] reads and writes JSON. */
class SupabaseAuth(
    private val http: HttpClient,
    private val config: AuthConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Trades a Google ID token for a session. [nonce] is the value whose hash Google put in the token. */
    suspend fun signIn(idToken: String, nonce: String): UserSession {
        val response = token("id_token", IdTokenRequest("google", idToken, nonce))
        if (response.status.isSuccess()) return response.body<TokenResponse>().toSession()
        throw if (response.isRefusal()) SignInException("Google sign-in was not accepted. Try again later.") else response.failure()
    }

    /** Gets a new access token. Fails with [SessionEndedException] when the session is over. */
    suspend fun refresh(refreshToken: String): UserSession {
        val response = token("refresh_token", RefreshRequest(refreshToken))
        if (response.status.isSuccess()) return response.body<TokenResponse>().toSession()
        throw if (response.isRefusal()) SessionEndedException() else response.failure()
    }

    /** Ends the session on this phone only. Other phones stay signed in. */
    suspend fun signOut(accessToken: String) {
        http.post("${config.supabaseUrl}/auth/v1/logout") {
            parameter("scope", "local")
            header(API_KEY, config.supabaseKey)
            bearerAuth(accessToken)
        }
    }

    private suspend inline fun <reified T> token(grant: String, body: T): HttpResponse =
        http.post("${config.supabaseUrl}/auth/v1/token") {
            parameter("grant_type", grant)
            header(API_KEY, config.supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Whether Supabase said no, rather than being unable to answer. */
    private fun HttpResponse.isRefusal() = status.value in 400..499 && status != HttpStatusCode.TooManyRequests && status != HttpStatusCode.RequestTimeout

    private fun HttpResponse.failure() = IOException("Supabase answered with HTTP ${status.value}.")

    private fun TokenResponse.toSession(): UserSession {
        val received = now()
        val name = listOf("full_name", "name").firstNotNullOfOrNull { key ->
            (user.metadata?.get(key) as? JsonPrimitive)?.contentOrNull?.let { DisplayNames.normalize(it.take(DisplayNames.MAX_LENGTH)) }
        }
        return UserSession(
            accessToken = accessToken,
            account = Account(AccountId(user.id), name ?: "Player"),
            provider = SignInProvider.GOOGLE,
            refreshToken = refreshToken,
            expiresAt = received + expiresIn * 1_000,
            receivedAt = received,
        )
    }

    @Serializable
    private data class IdTokenRequest(val provider: String, @SerialName("id_token") val idToken: String, val nonce: String)

    @Serializable
    private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        /** Seconds. */
        @SerialName("expires_in") val expiresIn: Long,
        val user: User,
    )

    @Serializable
    private data class User(val id: String, @SerialName("user_metadata") val metadata: JsonObject? = null)

    private companion object {
        const val API_KEY = "apikey"
    }
}
