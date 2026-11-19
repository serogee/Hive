package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.API_VERSION
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.HealthResponse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.hiveModule(config: ServerConfig) {
    check(config.mode == ServerMode.DEMO) {
        "Production mode needs Supabase settings, which are not available yet."
    }

    install(ContentNegotiation) { json() }
    install(CallLogging)

    routing {
        get(ApiPaths.HEALTH) {
            call.respond(HealthResponse(status = "ok", apiVersion = API_VERSION))
        }
    }
}
