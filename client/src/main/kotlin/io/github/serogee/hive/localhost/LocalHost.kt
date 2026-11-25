package io.github.serogee.hive.localhost

import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.host.RoomService
import io.github.serogee.hive.host.RoomStore
import io.github.serogee.hive.host.healthRoutes
import io.github.serogee.hive.host.installHiveApi
import io.github.serogee.hive.host.roomRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The room server embedded in the app. One server serves every room this phone hosts, and rooms
 * are told apart by room ID. It needs no internet or remote backend: Players reach it over the
 * same Wi-Fi network or this phone's hotspot.
 */
class LocalHost(store: RoomStore) {
    val service = RoomService(store, requireAccountToHost = false, accountsSupported = false)
    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** The address this app uses to reach its own server. */
    val selfUrl = "http://127.0.0.1:$PORT"

    suspend fun start() = mutex.withLock {
        if (server != null) return@withLock
        // Start on a plain thread. Starting inside a coroutine makes the server's jobs children of
        // that coroutine, so the caller would wait for the server to stop before continuing.
        val started = CompletableDeferred<EmbeddedServer<*, *>>()
        thread(name = "hive-local-host", isDaemon = true) {
            try {
                val engine = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
                    installHiveApi()
                    routing {
                        route("/v1") {
                            healthRoutes(HealthMode.PHONE, accounts = null)
                            roomRoutes(service, accounts = null)
                        }
                    }
                }
                started.complete(engine.start(wait = false))
            } catch (e: Throwable) {
                started.completeExceptionally(e)
            }
        }
        server = started.await()
        _running.value = true
    }

    suspend fun stop() = mutex.withLock {
        val current = server ?: return@withLock
        withContext(Dispatchers.IO) { current.stop(gracePeriodMillis = 500, timeoutMillis = 2_000) }
        server = null
        _running.value = false
    }

    /** Addresses that Players on the same network or hotspot can enter, Wi-Fi and hotspot first. */
    fun playerAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence().filterIsInstance<Inet4Address>() }
            .map { "http://${it.hostAddress}:$PORT" }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    companion object {
        const val PORT = 8090
    }
}
