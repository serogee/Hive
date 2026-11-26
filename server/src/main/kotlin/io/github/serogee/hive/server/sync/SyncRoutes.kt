package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.AppendRecordsRequest
import io.github.serogee.hive.core.api.AppendRecordsResponse
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.requireAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Sync for signed-in accounts. Mount inside `route("/v1")`. Every route serves the caller's own data only. */
fun Route.syncRoutes(sync: SyncService, accounts: AccountVerifier) {
    route("/sync") {
        get("/changes") {
            val account = call.requireAccount(accounts, SIGN_IN)
            val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: SyncService.MAX_PAGE
            call.respond(sync.changes(account, cursor, limit))
        }

        post("/stacks") {
            val account = call.requireAccount(accounts, SIGN_IN)
            val request = call.receive<PushStacksRequest>()
            call.respond(sync.pushStacks(account, request.generation, request.changes))
        }

        post("/reviews") {
            val account = call.requireAccount(accounts, SIGN_IN)
            call.respond(AppendRecordsResponse(sync.append(account, RecordKind.REVIEW, call.receive<AppendRecordsRequest>().records)))
        }

        post("/results") {
            val account = call.requireAccount(accounts, SIGN_IN)
            call.respond(AppendRecordsResponse(sync.append(account, RecordKind.RESULT, call.receive<AppendRecordsRequest>().records)))
        }

        get("/rooms") {
            call.respond(sync.onlineRooms(call.requireAccount(accounts, SIGN_IN)))
        }

        delete {
            sync.deleteAll(call.requireAccount(accounts, SIGN_IN))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private const val SIGN_IN = "Sign in to sync."
