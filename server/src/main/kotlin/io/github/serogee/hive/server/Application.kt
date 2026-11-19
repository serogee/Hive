package io.github.serogee.hive.server

import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.RoomService
import io.github.serogee.hive.host.RoomStore
import io.github.serogee.hive.host.healthRoutes
import io.github.serogee.hive.host.installHiveApi
import io.github.serogee.hive.host.roomRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * The storage and sign-in implementations for one configuration.
 *
 * Demo and production use the same routes. Only this choice differs.
 */
class Backend(
    val rooms: RoomStore,
    val accounts: AccountVerifier,
    /** Routes that exist only in this configuration, such as demo login. */
    val extraRoutes: Route.() -> Unit = {},
)

fun Application.hiveModule(backend: Backend) {
    installHiveApi()
    install(CallLogging)

    val roomService = RoomService(backend.rooms, requireAccountToHost = true)

    routing {
        route("/v1") {
            healthRoutes()
            accountRoutes(backend.accounts)
            roomRoutes(roomService, backend.accounts)
            backend.extraRoutes(this)
        }
    }
}
