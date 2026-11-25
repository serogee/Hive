package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account

/**
 * Turns a bearer token into an account.
 *
 * The demo server checks its own sessions. Production checks Supabase access tokens. A local host
 * without account support uses [None].
 */
fun interface AccountVerifier {
    /** Returns null when the token is unknown or expired. */
    suspend fun verify(accessToken: String): Account?

    /** False on a server that was started without its sign-in settings. */
    val configured: Boolean get() = true

    companion object {
        val None = AccountVerifier { null }

        /** Accepts nobody. Endpoints that need an account answer that sign-in is not configured. */
        val NotConfigured = object : AccountVerifier {
            override val configured = false

            override suspend fun verify(accessToken: String): Account? = null
        }
    }
}
