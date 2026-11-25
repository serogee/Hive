package io.github.serogee.hive.data.auth

import io.github.serogee.hive.core.api.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** How a user signed in. */
@Serializable
enum class SignInProvider(val label: String) {
    GOOGLE("Signed in with Google"),
    DEMO("Signed in with a demo account"),
}

/** A signed-in user on this phone. */
@Serializable
data class UserSession(
    val accessToken: String,
    val account: Account,
    /** Sessions from before Google sign-in are demo sessions. */
    val provider: SignInProvider = SignInProvider.DEMO,
    /** Gets a new access token. Null for a session that does not expire. */
    val refreshToken: String? = null,
    /** Epoch milliseconds when the access token stops working. */
    val expiresAt: Long? = null,
    /** Epoch milliseconds when the access token was received. */
    val receivedAt: Long = 0,
) {
    /** Whether the access token has expired, or will before a request with it arrives. */
    fun needsRefresh(now: Long): Boolean = refreshToken != null && expiresAt != null && now >= expiresAt - EARLY_MILLIS

    companion object {
        private const val EARLY_MILLIS = 60_000L
    }
}

/** Where the session is kept. */
interface SessionStore {
    val session: Flow<UserSession?>

    suspend fun currentSession(): UserSession?

    suspend fun setSession(session: UserSession?)
}
