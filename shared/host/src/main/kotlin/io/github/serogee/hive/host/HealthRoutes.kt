package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.API_VERSION
import io.github.serogee.hive.core.api.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Lets a client check that it reached a Hive server. Mount inside `route("/v1")`. */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "ok", apiVersion = API_VERSION))
    }
}
