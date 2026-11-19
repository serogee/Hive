package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.host.HiveException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Simulated login. Registered only in the demo configuration. */
fun Route.demoAuthRoutes(accounts: DemoAccounts) {
    route("/demo/auth") {
        post("/login") {
            val request = call.receive<DemoLoginRequest>()
            call.respond(accounts.login(request.username, request.password))
        }

        post("/logout") {
            val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                ?: throw HiveException(ErrorCode.SESSION_EXPIRED, "You are not signed in.")
            accounts.logout(token)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
