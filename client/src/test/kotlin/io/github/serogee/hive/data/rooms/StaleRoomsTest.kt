package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class StaleRoomsTest {
    private val day = StaleRooms.OPEN_MILLIS
    private val now = 10 * day

    private fun room(
        id: String,
        createdAt: Long?,
        status: RoomStatus = RoomStatus.LIVE,
        mode: RoomMode = RoomMode.DISCUSSION,
        deadlineAt: Long? = null,
    ) = RoomSummary(
        id = RoomId(id),
        code = "ABC234",
        title = id,
        hostName = "Host",
        mode = mode,
        admission = AdmissionPolicy.EVERYONE,
        status = status,
        questionCount = 3,
        deadlineAt = deadlineAt,
        createdAt = createdAt,
    )

    private fun due(vararg rooms: RoomSummary, snoozes: Map<String, Long> = emptyMap()) =
        StaleRooms.due(rooms.toList(), snoozes, now).map { it.id.value }

    @Test
    fun `rooms open for a day are due, and younger ones are not`() {
        assertEquals(listOf("old", "lobby"), due(room("old", now - 3 * day), room("young", now - day + 1), room("lobby", now - day, RoomStatus.LOBBY)))
    }

    @Test
    fun `ended rooms and rooms without a creation time are never due`() {
        assertEquals(emptyList(), due(room("ended", now - 3 * day, RoomStatus.ENDED), room("unknown", null)))
    }

    @Test
    fun `an Asynchronous room before its deadline is not due, and after it is`() {
        assertEquals(
            listOf("past", "open"),
            due(
                room("later", now - 4 * day, mode = RoomMode.ASYNCHRONOUS, deadlineAt = now + 1),
                room("past", now - 3 * day, mode = RoomMode.ASYNCHRONOUS, deadlineAt = now - 1),
                room("open", now - 2 * day, mode = RoomMode.ASYNCHRONOUS),
            ),
        )
    }

    @Test
    fun `keep open waits until the snooze ends`() {
        val old = room("old", now - 3 * day)
        assertEquals(emptyList(), due(old, snoozes = mapOf("old" to now + 1)))
        assertEquals(listOf("old"), due(old, snoozes = mapOf("old" to now)))
    }

    @Test
    fun `the list is oldest first`() {
        assertEquals(listOf("oldest", "old"), due(room("old", now - 2 * day), room("oldest", now - 5 * day), room("young", now - 1)))
    }

    @Test
    fun `the time open reads in whole days`() {
        assertEquals("1 day", StaleRooms.openFor(now - day - 5, now))
        assertEquals("3 days", StaleRooms.openFor(now - 3 * day - 5, now))
    }
}
