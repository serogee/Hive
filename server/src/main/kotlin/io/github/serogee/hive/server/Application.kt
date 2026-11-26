package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.RoomService
import io.github.serogee.hive.host.RoomStore
import io.github.serogee.hive.host.healthRoutes
import io.github.serogee.hive.host.installHiveApi
import io.github.serogee.hive.host.roomRoutes
import io.github.serogee.hive.server.sync.SyncService
import io.github.serogee.hive.server.sync.SyncStore
import io.github.serogee.hive.server.sync.syncRoutes
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
    val mode: HealthMode = HealthMode.DEMO,
    /** Account sync, or null on a server that keeps nothing. */
    val sync: SyncStore? = null,
    /** Deletes accounts, or null on a server that cannot. */
    val deleter: AccountDeleter? = null,
    /** Keeps display names, or null on a server that keeps no accounts. */
    val profiles: AccountProfiles? = null,
    /** Routes that exist only in this configuration, such as demo login. */
    val extraRoutes: Route.() -> Unit = {},
)

fun Application.hiveModule(backend: Backend) {
    installHiveApi()
    install(CallLogging)

    val roomService = RoomService(backend.rooms, requireAccountToHost = true)

    routing {
        route("/v1") {
            healthRoutes(backend.mode, backend.accounts, sync = backend.sync != null)
            accountRoutes(backend.accounts, backend.deleter, backend.profiles)
            roomRoutes(roomService, backend.accounts)
            backend.sync?.let { syncRoutes(SyncService(it, backend.rooms, roomService), backend.accounts) }
            backend.extraRoutes(this)
        }
    }
}
