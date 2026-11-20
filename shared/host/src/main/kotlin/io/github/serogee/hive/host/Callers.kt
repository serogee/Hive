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
 * means that this server has no accounts, so it ignores bearer tokens.
 */
suspend fun ApplicationCall.account(verifier: AccountVerifier?): Account? {
    if (verifier == null) return null
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") && it.isNotEmpty() }
        ?: throw HiveException(ErrorCode.SESSION_EXPIRED, "Sign in again.")
    return verifier.verify(token) ?: throw HiveException(ErrorCode.SESSION_EXPIRED, "Sign in again.")
}

fun ApplicationCall.participantToken(): String? =
    request.headers[ApiPaths.PARTICIPANT_TOKEN_HEADER]?.takeIf { it.isNotBlank() }
