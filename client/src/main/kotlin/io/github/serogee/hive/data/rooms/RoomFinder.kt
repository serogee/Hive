package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.localhost.NearbyRooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** A room, and the Host phone's address, which is null for a room on the configured server. */
data class FoundRoom(val room: RoomSummary, val hostAddress: String?)

/** Finds a room by its code, on the configured server and on phones nearby. */
class RoomFinder(
    private val lookup: suspend (code: String, hostAddress: String?) -> RoomSummary,
    private val nearby: NearbyRooms,
) {
    /**
     * Looks at [hostAddress] only, when there is one. Otherwise looks nearby and online at the same time,
     * so that neither keeps the other waiting, and takes the first room found. Fails with the online
     * failure when there is no room.
     */
    suspend fun find(code: String, hostAddress: String? = null): FoundRoom {
        if (hostAddress != null) return FoundRoom(lookup(code, hostAddress), hostAddress)
        return coroutineScope {
            val near = async { attempt { findNearby(code) } }
            val online = async { attempt { FoundRoom(lookup(code, null), null) } }
            val first = select {
                near.onAwait { it }
                online.onAwait { it }
            }
            val found = first.getOrNull() ?: near.await().getOrNull()
            val result = if (found != null) Result.success(found) else online.await()
            coroutineContext.cancelChildren()
            result.getOrThrow() ?: error("No session uses that code.")
        }
    }

    /** Null when no phone nearby answers for [code]. */
    suspend fun findNearby(code: String): FoundRoom? = nearby.find(code)?.let { FoundRoom(lookup(code, it), it) }

    private suspend fun attempt(block: suspend () -> FoundRoom?): Result<FoundRoom?> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** Addresses that people type. */
object HostAddress {
    /** Accepts "192.168.43.1", "192.168.43.1:8090", or a full URL. Returns null for a blank address. */
    fun normalize(raw: String): String? {
        val address = raw.trim().ifEmpty { return null }
        val withScheme = if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address"
        val hostPart = withScheme.substringAfter("://").substringBefore('/')
        return if (':' in hostPart) withScheme.trimEnd('/') else "${withScheme.trimEnd('/')}:${LocalHost.PORT}"
    }
}
