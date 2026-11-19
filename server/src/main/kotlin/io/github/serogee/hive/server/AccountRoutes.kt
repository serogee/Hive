package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.account
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Lets a client restore a saved session. Works with any [AccountVerifier]. */
fun Route.accountRoutes(accounts: AccountVerifier) {
    get("/me") {
        val account = call.account(accounts)
            ?: throw HiveException(ErrorCode.SIGN_IN_REQUIRED, "You are not signed in.")
        call.respond(account)
    }
}
