package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Which hosted rooms have been open too long, so that their Host may want to close them. */
object StaleRooms {
    const val OPEN_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Rooms that have been in the lobby or live for [OPEN_MILLIS], oldest first. An Asynchronous room before
     * the deadline that its Host chose is left out, and so is a room snoozed until after [now].
     */
    fun due(rooms: List<RoomSummary>, snoozedUntil: Map<String, Long>, now: Long): List<RoomSummary> = rooms
        .filter { room ->
            val created = room.createdAt ?: return@filter false
            val deadline = room.deadlineAt
            (room.status == RoomStatus.LOBBY || room.status == RoomStatus.LIVE) &&
                now - created >= OPEN_MILLIS &&
                !(room.mode == RoomMode.ASYNCHRONOUS && deadline != null && deadline > now) &&
                (snoozedUntil[room.id.value] ?: 0) <= now
        }
        .sortedBy { it.createdAt ?: 0 }

    /** How long a room has been open, such as "2 days" or "1 day". */
    fun openFor(createdAt: Long, now: Long): String {
        val days = ((now - createdAt) / OPEN_MILLIS).coerceAtLeast(1)
        return if (days == 1L) "1 day" else "$days days"
    }
}

/** Asks the Host about rooms that stay open too long, all in one question, when the app opens. */
class StaleRoomChecker(
    private val rooms: RoomRepository,
    private val preferences: AppPreferences,
    private val signedIn: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _due = MutableStateFlow<List<RoomSummary>>(emptyList())
    val due: StateFlow<List<RoomSummary>> = _due.asStateFlow()

    /** Rooms that Close all could not end, until the user closes the message. */
    private val _failed = MutableStateFlow<List<RoomSummary>>(emptyList())
    val failed: StateFlow<List<RoomSummary>> = _failed.asStateFlow()

    /** How many rooms actually closed in the last [closeAll] call alongside [failed]. */
    private val _closedCount = MutableStateFlow(0)
    val closedCount: StateFlow<Int> = _closedCount.asStateFlow()

    fun now(): Long = clock()

    /** Finds the rooms to ask about, among rooms hosted on this phone and online. */
    suspend fun check() {
        val local = rooms.localHostedRooms.first()
        val online = if (signedIn()) quietly { rooms.hostedRooms() } ?: emptyList() else emptyList()
        _due.value = StaleRooms.due((local + online).distinctBy { it.id }, preferences.roomSnoozes.first(), now())
    }

    /** Ends every room in [list] for everyone. The rooms that could not end are in [failed]. */
    suspend fun closeAll(list: List<RoomSummary>) {
        _due.value = emptyList()
        val notClosed = list.filter { room -> quietly { rooms.command(room.id, HostCommand.EndRoom) } == null }
        _closedCount.value = list.size - notClosed.size
        _failed.value = notClosed
        quietly { rooms.stopHostingWhenIdle() }
    }

    /** Asks again about every room in [list] after another [StaleRooms.OPEN_MILLIS]. */
    suspend fun keepOpenAll(list: List<RoomSummary>) {
        _due.value = emptyList()
        list.forEach { preferences.snoozeRoom(it.id, now() + StaleRooms.OPEN_MILLIS) }
    }

    fun clearFailed() {
        _failed.value = emptyList()
        _closedCount.value = 0
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Offline, or the room is gone. The next open asks again.
        null
    }
}
