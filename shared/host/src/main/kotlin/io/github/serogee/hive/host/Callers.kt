package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.ErrorCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * Returns the signed-in account, or null when the request has no bearer token.
 *
 * An unknown or expired token fails, so that the client can sign the user out. A null [verifier]
 * means that this server has no accounts, so it ignores bearer tokens. So does a server without its
 * sign-in settings, because it cannot tell a good token from a bad one.
 */
suspend fun ApplicationCall.account(verifier: AccountVerifier?): Account? {
    if (verifier == null || !verifier.configured) return null
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") && it.isNotEmpty() }
        ?: throw HiveException(ErrorCode.SESSION_EXPIRED, "Sign in again.")
    return verifier.verify(token) ?: throw HiveException(ErrorCode.SESSION_EXPIRED, "Sign in again.")
}

/** Returns the signed-in account, or fails with [message] when there is none. */
suspend fun ApplicationCall.requireAccount(verifier: AccountVerifier?, message: String): Account {
    requireSignInSettings(verifier)
    return account(verifier) ?: throw HiveException(ErrorCode.SIGN_IN_REQUIRED, message)
}

/** Fails when this server should have accounts but was started without its sign-in settings. */
fun requireSignInSettings(verifier: AccountVerifier?) {
    if (verifier?.configured == false) {
        throw HiveException(ErrorCode.AUTH_NOT_CONFIGURED, "Sign-in is not set up on this server.")
    }
}

fun ApplicationCall.participantToken(): String? =
    request.headers[ApiPaths.PARTICIPANT_TOKEN_HEADER]?.takeIf { it.isNotBlank() }
