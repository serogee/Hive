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

    /** Called when the server refused the access token. Tries a new one before the session ends. */
    suspend fun sessionRefused() {
        val session = sessions.currentSession() ?: return
        // A token that was refused right after it was received will be refused again.
        val isNew = now() - session.receivedAt < NEW_MILLIS
        if (supabase == null || session.refreshToken == null || isNew) {
            sessions.setSession(null)
        } else {
            ignoringFailure { refresh(session) }
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
        val demo = demo ?: return sessions.setSession(null)
        try {
            val account = demo.restore(session.accessToken)
            if (account != session.account) sessions.setSession(session.copy(account = account))
        } catch (e: ApiException) {
            if (e.error.code == ErrorCode.SESSION_EXPIRED || e.error.code == ErrorCode.SIGN_IN_REQUIRED) sessions.setSession(null)
        }
    }

    /** Replaces the tokens of [stale]. Requests that arrive together share one refresh. */
    private suspend fun refresh(stale: UserSession): UserSession? = refreshLock.withLock {
        val current = sessions.currentSession() ?: return null
        if (current.accessToken != stale.accessToken) return current
        val supabase = supabase ?: return current
        val refreshToken = current.refreshToken ?: return current
        val renewed = try {
            supabase.refresh(refreshToken)
        } catch (_: SessionEndedException) {
            null
        }
        // The user can sign out while the request is on its way.
        if (sessions.currentSession()?.refreshToken == refreshToken) sessions.setSession(renewed)
        renewed
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
