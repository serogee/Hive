package io.github.serogee.hive.server

import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.requireAccount
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Lets a client restore a saved session. Works with any [AccountVerifier]. */
fun Route.accountRoutes(accounts: AccountVerifier) {
    get("/me") {
        call.respond(call.requireAccount(accounts, "You are not signed in."))
    }
}
