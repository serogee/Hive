package io.github.serogee.hive.data.auth

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.data.remote.ApiException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sign-in with the fictional accounts of a demo server. Only debug apps have it. */
interface DemoAuth {
    suspend fun signIn(username: String, password: String): AuthSession

    suspend fun signOut(accessToken: String)

    /** Confirms that a saved session is still valid. */
    suspend fun restore(accessToken: String): Account
}

/**
 * Who is signed in on this phone. Being unable to reach a server never ends a session. Only a refusal
 * does.
 */
class AuthRepository(
    private val sessions: SessionStore,
    /** Null when Google sign-in is not set up. */
    private val supabase: SupabaseAuth?,
    /** Null in release apps. */
    private val demo: DemoAuth?,
    /** Runs when an expired or revoked session is cleared automatically (not on explicit signOut). */
    private val onSessionCleared: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()

    val session: Flow<UserSession?> = sessions.session
    val googleAvailable: Boolean get() = supabase != null
    val demoAvailable: Boolean get() = demo != null

    /**
     * Signs in with the ID token from [requestIdToken], which gets the hashed nonce to ask for and returns
     * null when the user cancels. Returns whether the user is signed in.
     */
    suspend fun signInWithGoogle(requestIdToken: suspend (hashedNonce: String) -> String?): Boolean {
        val supabase = supabase ?: throw SignInException("Sign-in is not set up.")
        // Google gets the hash and Supabase the value, so a stolen ID token is of no use elsewhere.
        val nonce = newNonce()
        val idToken = requestIdToken(sha256(nonce)) ?: return false
        sessions.setSession(supabase.signIn(idToken, nonce))
        return true
    }

    suspend fun signInWithDemo(username: String, password: String) {
        val demo = demo ?: throw SignInException("This app has no demo sign-in.")
        val session = demo.signIn(username, password)
        sessions.setSession(UserSession(session.accessToken, session.account, SignInProvider.DEMO, receivedAt = now()))
    }

    suspend fun signOut() {
        val session = sessions.currentSession() ?: return
        sessions.setSession(null)
        ignoringFailure {
            when (session.provider) {
                SignInProvider.GOOGLE -> supabase?.signOut(session.accessToken)
                SignInProvider.DEMO -> demo?.signOut(session.accessToken)
            }
        }
    }

    /** A working access token, or null when nobody is signed in. Fails when it cannot be refreshed now. */
    suspend fun accessToken(): String? {
        val session = sessions.currentSession() ?: return null
        return if (session.needsRefresh(now())) refresh(session)?.accessToken else session.accessToken
    }

    /**
     * Called when the server refused [refusedToken]. Tries a new one before the session ends.
     *
     * Returns whether a refreshed token is now available (either just refreshed or already replaced
     * by a concurrent request).
     */
    suspend fun sessionRefused(refusedToken: String? = null): Boolean {
        val session = sessions.currentSession() ?: return false
        // Another concurrent request already replaced the refused token; keep the new session.
        if (refusedToken != null && session.accessToken != refusedToken) return true
        // A token that was refused right after it was received will be refused again.
        val isNew = now() - session.receivedAt < NEW_MILLIS
        if (supabase == null || session.refreshToken == null || isNew) {
            clearSessionIfMatching(session.accessToken)
            return false
        }
        return try {
            refresh(session) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline. The session stays, and the next request tries again.
            false
        }
    }

    /**
     * Checks the saved session at startup. An unreachable server keeps the session, so the user
     * stays signed in while offline.
     */
    suspend fun restore() = ignoringFailure {
        val session = sessions.currentSession() ?: return@ignoringFailure
        when (session.provider) {
            SignInProvider.GOOGLE -> accessToken()
            SignInProvider.DEMO -> restoreDemo(session)
        }
    }

    private suspend fun restoreDemo(session: UserSession) {
        val demo = demo ?: return clearSessionIfMatching(session.accessToken)
        try {
            val account = demo.restore(session.accessToken)
            if (account != session.account) sessions.setSession(session.copy(account = account))
        } catch (e: ApiException) {
            if (e.error.code == ErrorCode.SESSION_EXPIRED || e.error.code == ErrorCode.SIGN_IN_REQUIRED) {
                clearSessionIfMatching(session.accessToken)
            }
        }
    }

    private suspend fun clearSessionIfMatching(accessToken: String) {
        val cleared = refreshLock.withLock {
            val current = sessions.currentSession() ?: return@withLock false
            if (current.accessToken != accessToken) return@withLock false
            sessions.setSession(null)
            true
        }
        if (cleared) onSessionCleared()
    }

    /** Replaces the tokens of [stale]. Requests that arrive together share one refresh. */
    private suspend fun refresh(stale: UserSession): UserSession? {
        var cleared = false
        val renewed = refreshLock.withLock {
            val current = sessions.currentSession() ?: return@withLock null
            if (current.accessToken != stale.accessToken) return@withLock current
            val supabase = supabase ?: return@withLock current
            val refreshToken = current.refreshToken ?: return@withLock current
            val next = try {
                supabase.refresh(refreshToken)
            } catch (_: SessionEndedException) {
                null
            }
            // The user can sign out while the request is on its way.
            if (sessions.currentSession()?.refreshToken == refreshToken) {
                sessions.setSession(next)
                cleared = next == null
            }
            next
        }
        if (cleared) onSessionCleared()
        return renewed
    }

    private suspend fun ignoringFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline. The session stays, and the next request tries again.
        }
    }

    private companion object {
        const val NEW_MILLIS = 60_000L

        fun newNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).toHex()

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHex()

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
