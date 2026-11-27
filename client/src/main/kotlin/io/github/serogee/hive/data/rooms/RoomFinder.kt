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
    /**
     * Accepts "192.168.43.1", "192.168.43.1:8090", or a full URL. Appends [LocalHost.PORT] only
     * for `http://` URLs without an explicit port, placing it on the host authority before any path.
     * Returns null for a blank or hostless address.
     */
    fun normalize(raw: String): String? {
        val address = raw.trim().ifEmpty { return null }
        val lower = address.lowercase()
        val (scheme, rest) = when {
            lower.startsWith("https://") -> "https" to address.substring("https://".length)
            lower.startsWith("http://") -> "http" to address.substring("http://".length)
            else -> "http" to address
        }
        val slashIndex = rest.indexOf('/')
        val authority = if (slashIndex >= 0) rest.substring(0, slashIndex) else rest
        if (authority.isBlank()) return null
        val path = if (slashIndex >= 0) rest.substring(slashIndex).trimEnd('/') else ""
        val normalizedAuthority = if (scheme == "http" && ':' !in authority) "$authority:${LocalHost.PORT}" else authority
        return "$scheme://$normalizedAuthority$path"
    }

    /**
     * Normalizes an online server URL typed in Settings. Adds `http://` only when neither `http://`
     * nor `https://` is present, and returns null when blank or hostless.
     */
    fun normalizeServerUrl(raw: String): String? {
        val address = raw.trim().ifEmpty { return null }
        val lower = address.lowercase()
        val (scheme, rest) = when {
            lower.startsWith("https://") -> "https" to address.substring("https://".length)
            lower.startsWith("http://") -> "http" to address.substring("http://".length)
            else -> "http" to address
        }
        val cleaned = rest.trimEnd('/')
        val authority = cleaned.substringBefore('/')
        if (authority.isBlank()) return null
        return "$scheme://$cleaned"
    }
}
