package io.github.serogee.hive.data.server

import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HealthResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ServerState { NOT_CONFIGURED, CHECKING, ONLINE, UNREACHABLE }

/** What is known about the configured server. [mode] and [auth] are from its last answer. */
data class ServerStatus(
    val state: ServerState,
    val mode: HealthMode? = null,
    val auth: HealthAuth = HealthAuth.NOT_CONFIGURED,
)

/**
 * Keeps the status of the configured server. It checks when it starts, when the address changes, and when
 * [checkNow] is called. While a screen watches, it also checks regularly, and less often after failures.
 */
class ServerMonitor(
    serverUrl: Flow<String>,
    private val check: suspend (url: String) -> HealthResponse,
    /** Runs the checks. It must not be the main thread. */
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(ServerStatus(ServerState.CHECKING))
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val watchers = MutableStateFlow(0)

    @Volatile
    private var checkedAt = 0L

    init {
        scope.launch { serverUrl.distinctUntilChanged().collectLatest(::follow) }
    }

    /** Checks soon. The status stays as it is until the answer arrives. */
    fun checkNow() {
        wake.trySend(Unit)
    }

    /** Checks again because the user asked, and says so until the answer arrives. */
    fun retry() {
        _status.update { if (it.state == ServerState.UNREACHABLE) it.copy(state = ServerState.CHECKING) else it }
        checkNow()
    }

    /** Call with [stopWatching] while a screen that needs the server is open. */
    fun startWatching() {
        watchers.update { it + 1 }
        if (now() - checkedAt > FRESH_MILLIS) checkNow()
    }

    fun stopWatching() = watchers.update { (it - 1).coerceAtLeast(0) }

    private suspend fun follow(url: String) {
        if (url.isBlank()) {
            _status.value = ServerStatus(ServerState.NOT_CONFIGURED)
            return
        }
        _status.value = ServerStatus(ServerState.CHECKING)
        var failures = 0
        while (true) {
            val health = answer(url)
            checkedAt = now()
            if (health != null) {
                failures = 0
                _status.value = ServerStatus(ServerState.ONLINE, health.mode, health.auth)
            } else {
                failures++
                _status.update { it.copy(state = ServerState.UNREACHABLE) }
            }
            awaitNextCheck(pauseAfter(failures))
        }
    }

    /** Null when the server does not answer in time, or answers with something else. */
    private suspend fun answer(url: String): HealthResponse? = withTimeoutOrNull(TIMEOUT_MILLIS) {
        try {
            check(url).takeIf { it.isOk }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun awaitNextCheck(pause: Long) = coroutineScope {
        val timer = launch {
            watchers.collectLatest { count ->
                if (count > 0) {
                    delay(pause)
                    checkNow()
                }
            }
        }
        wake.receive()
        timer.cancel()
    }

    companion object {
        /** Between checks while a screen watches and the server answers. */
        const val INTERVAL_MILLIS = 45_000L
        const val FIRST_PAUSE_MILLIS = 5_000L
        const val LONGEST_PAUSE_MILLIS = 60_000L
        const val TIMEOUT_MILLIS = 6_000L
        private const val FRESH_MILLIS = 15_000L

        /** 45 seconds, or 5, 10, 20, 40, and then 60 seconds after failures. */
        fun pauseAfter(failures: Int): Long = when {
            failures <= 0 -> INTERVAL_MILLIS
            failures > 4 -> LONGEST_PAUSE_MILLIS
            else -> FIRST_PAUSE_MILLIS shl (failures - 1)
        }
    }
}
