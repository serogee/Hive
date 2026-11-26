package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.AccountProfile
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.requireAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/**
 * The signed-in account. Mount inside `route("/v1")`. [deleter] is null when this server cannot delete
 * accounts, and [profiles] is null when it keeps no accounts.
 */
fun Route.accountRoutes(
    accounts: AccountVerifier,
    deleter: AccountDeleter? = null,
    profiles: AccountProfiles? = null,
    onRoomsEnded: suspend (List<EndedRoom>) -> Unit = {},
) {
    get("/me") {
        call.respond(call.requireAccount(accounts, "You are not signed in."))
    }

    get("/account/profile") {
        val account = call.requireAccount(accounts, "Sign in to see your display name.")
        val name = profiles?.displayName(account.id) ?: account.displayName
        call.respond(AccountProfile(name))
    }

    put("/account/profile") {
        val account = call.requireAccount(accounts, "Sign in to change your display name.")
        val keep = profiles ?: throw HiveException(ErrorCode.AUTH_NOT_CONFIGURED, "This server cannot keep display names.")
        val name = DisplayNames.normalize(call.receive<AccountProfile>().displayName)
            ?: throw HiveException(ErrorCode.INVALID_REQUEST, "Enter a name of up to ${DisplayNames.MAX_LENGTH} characters.")
        keep.setDisplayName(account.id, name)
        call.respond(AccountProfile(name))
    }

    delete("/account") {
        val account = call.requireAccount(accounts, "Sign in to delete your account.")
        val delete = deleter ?: throw HiveException(ErrorCode.AUTH_NOT_CONFIGURED, "This server cannot delete accounts yet.")
        onRoomsEnded(delete.delete(account.id))
        call.respond(HttpStatusCode.NoContent)
    }
}
