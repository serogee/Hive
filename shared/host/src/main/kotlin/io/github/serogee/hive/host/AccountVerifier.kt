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

    companion object {
        val None = AccountVerifier { null }
    }
}
