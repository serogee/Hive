package io.github.serogee.hive.data.auth

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.data.remote.HiveApi
import kotlinx.coroutines.flow.Flow

/**
 * Signs a user in to the configured backend.
 *
 * The demo server and Supabase each provide one. Screens use [AuthRepository] and never check which.
 */
interface AuthProvider {
    /** Whether to show the fictional demo accounts on the sign-in form. */
    val showsDemoHint: Boolean

    suspend fun signIn(username: String, password: String): AuthSession

    suspend fun signOut(session: AuthSession)

    /** Confirms that a saved session is still valid. */
    suspend fun restore(session: AuthSession): Account
}

class DemoAuthProvider(private val api: HiveApi) : AuthProvider {
    override val showsDemoHint = true

    override suspend fun signIn(username: String, password: String) = api.demoLogin(username, password)

    override suspend fun signOut(session: AuthSession) = api.demoLogout(session.accessToken)

    override suspend fun restore(session: AuthSession) = api.me(session.accessToken)
}

class AuthRepository(
    private val preferences: AppPreferences,
    private val provider: AuthProvider,
) {
    val session: Flow<AuthSession?> = preferences.session
    val showsDemoHint: Boolean get() = provider.showsDemoHint

    suspend fun signIn(username: String, password: String): AuthSession =
        provider.signIn(username, password).also { preferences.setSession(it) }

    suspend fun signOut() {
        val session = preferences.currentSession() ?: return
        preferences.setSession(null)
        runCatching { provider.signOut(session) }
    }

    /**
     * Checks the saved session at startup. An unreachable server keeps the session, so the user
     * stays signed in while offline.
     */
    suspend fun restore() {
        val session = preferences.currentSession() ?: return
        try {
            val account = provider.restore(session)
            if (account != session.account) preferences.setSession(session.copy(account = account))
        } catch (e: ApiException) {
            if (e.error.code == ErrorCode.SESSION_EXPIRED || e.error.code == ErrorCode.SIGN_IN_REQUIRED) {
                preferences.setSession(null)
            }
        } catch (_: Exception) {
            // Offline. Try again on the next start.
        }
    }
}
