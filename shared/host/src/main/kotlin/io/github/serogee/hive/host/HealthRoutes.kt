package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.API_VERSION
import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Lets a client check that it reached a Hive server, and what the server can do. Mount inside `route("/v1")`. */
fun Route.healthRoutes(mode: HealthMode, accounts: AccountVerifier?, sync: Boolean = false) {
    val auth = if (accounts?.configured == true) HealthAuth.CONFIGURED else HealthAuth.NOT_CONFIGURED
    get("/health") {
        call.respond(HealthResponse(HealthResponse.OK, API_VERSION, mode, auth, sync))
    }
}
