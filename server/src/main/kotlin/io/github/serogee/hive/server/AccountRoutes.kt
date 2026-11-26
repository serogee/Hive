package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.requireAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get

/** The signed-in account. Mount inside `route("/v1")`. [deleter] is null when this server cannot delete accounts. */
fun Route.accountRoutes(accounts: AccountVerifier, deleter: AccountDeleter? = null) {
    get("/me") {
        call.respond(call.requireAccount(accounts, "You are not signed in."))
    }

    delete("/account") {
        val account = call.requireAccount(accounts, "Sign in to delete your account.")
        val delete = deleter ?: throw HiveException(ErrorCode.AUTH_NOT_CONFIGURED, "This server cannot delete accounts yet.")
        delete.delete(account.id)
        call.respond(HttpStatusCode.NoContent)
    }
}
