package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.remote.HiveApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RoomRepository(
    private val api: HiveApi,
    private val preferences: AppPreferences,
) {
    val joinedRoom: Flow<SavedMembership?> = preferences.joinedRoom
    val preferredName: Flow<String?> = preferences.preferredName

    suspend fun lookup(code: String): RoomSummary = api.lookupRoom(code)

    suspend fun hostedRooms(): List<RoomSummary> = api.hostedRooms()

    /** Until local stacks exist, online rooms use the bundled sample stack. */
    suspend fun createRoom(mode: RoomMode, admission: AdmissionPolicy): Membership =
        api.createRoom(CreateRoomRequest(SampleStacks.networkingFundamentals, mode, admission))

    /**
     * Joins as a Player and remembers the membership. Joining the same room again sends the saved
     * credential, so a retry never creates a second participant.
     */
    suspend fun join(room: RoomSummary, displayName: String?, asGuest: Boolean): Membership {
        val serverUrl = preferences.currentServerUrl()
        val saved = preferences.joinedRoom.first()
            ?.takeIf { it.roomId == room.id && it.serverUrl == serverUrl }
        val membership = api.join(room.id, JoinRoomRequest(displayName, asGuest), saved?.participantToken)
        preferences.setJoinedRoom(
            SavedMembership(
                serverUrl = serverUrl,
                roomId = room.id,
                roomCode = room.code,
                roomTitle = room.title,
                displayName = membership.participant.displayName,
                participantToken = membership.participantToken,
            ),
        )
        return membership
    }

    suspend fun leaveJoinedRoom() = preferences.setJoinedRoom(null)

    /** The saved credential for [roomId], or null when this device joined it with an account. */
    suspend fun participantToken(roomId: RoomId): String? =
        preferences.joinedRoom.first()?.takeIf { it.roomId == roomId }?.participantToken

    suspend fun state(roomId: RoomId, participantToken: String?): RoomState = api.roomState(roomId, participantToken)

    fun events(roomId: RoomId, participantToken: String?): Flow<RoomEvent> = api.roomEvents(roomId, participantToken)

    suspend fun command(roomId: RoomId, command: HostCommand, participantToken: String?): RoomState =
        api.command(roomId, command, participantToken)

    suspend fun submitAnswer(roomId: RoomId, request: SubmitAnswerRequest, participantToken: String?): AnswerReceipt =
        api.submitAnswer(roomId, request, participantToken)

    suspend fun results(roomId: RoomId, participantToken: String?): RoomResults = api.results(roomId, participantToken)
}
