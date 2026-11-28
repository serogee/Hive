package io.github.serogee.hive.ui.rooms

import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomRole
import kotlin.test.Test
import kotlin.test.assertEquals

class LobbyParticipantsTest {
    private val host = ParticipantView(ParticipantId("p-host"), "Alex", RoomRole.HOST, isGuest = false, left = false)
    private val jamieLeft = ParticipantView(ParticipantId("p-jamie"), "Jamie", RoomRole.PLAYER, isGuest = true, left = true)
    private val jamieBack = jamieLeft.copy(left = false)
    private val riley = ParticipantView(ParticipantId("p-riley"), "Riley", RoomRole.PLAYER, isGuest = true, left = false)

    @Test
    fun `rejoining participant replaces existing entry instead of duplicating it`() {
        val merged = mergeJoinedParticipant(listOf(host, jamieLeft), jamieBack)

        assertEquals(listOf(host, jamieBack), merged)
    }

    @Test
    fun `new participant is appended to the list`() {
        val merged = mergeJoinedParticipant(listOf(host, jamieBack), riley)

        assertEquals(listOf(host, jamieBack, riley), merged)
    }
}
