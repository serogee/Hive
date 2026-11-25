package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.local.LocalRoomStore
import io.github.serogee.hive.data.remote.HiveApi
import io.github.serogee.hive.localhost.LocalHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Host Setup choices that apply to online and phone-hosted rooms alike. */
data class RoomOptions(
    val mode: RoomMode,
    val admission: AdmissionPolicy,
    val speedScoring: Boolean,
    val timeTiebreak: Boolean,
    /** Epoch milliseconds when the quiz stops accepting answers, or null for no deadline. */
    val deadlineAt: Long?,
) {
    fun request(stack: StackSnapshot, hostDisplayName: String? = null) = CreateRoomRequest(
        stack = stack,
        mode = mode,
        admission = admission,
        hostDisplayName = hostDisplayName,
        deadlineAt = deadlineAt,
        speedScoring = speedScoring,
        timeTiebreak = timeTiebreak,
    )
}

/**
 * Rooms on any server: the configured online server, a room hosted on this phone, or a room on
 * another Host's phone. Screens pass room IDs and never choose a server themselves.
 */
class RoomRepository(
    private val api: HiveApi,
    private val preferences: AppPreferences,
    private val localHost: LocalHost,
    private val localRooms: LocalRoomStore,
) {
    /** The room this device is in as a Player. A room that ended does not count. */
    val joinedRoom: Flow<SavedMembership?> = preferences.joinedRoom.map { it?.takeUnless(SavedMembership::ended) }
    val preferredName: Flow<String?> = preferences.preferredName

    /** Rooms hosted on this phone, newest first. Rooms past their deadline are shown as ended. */
    val localHostedRooms: Flow<List<RoomSummary>> = localRooms.rooms.map { rooms ->
        val now = System.currentTimeMillis()
        rooms.map { it.summary().copy(status = it.effectiveStatus(now), acceptsAccounts = false) }
    }
    val localHostRunning = localHost.running

    /** Looks up a code on the configured server, or on a Host phone at [hostAddress]. */
    suspend fun lookup(code: String, hostAddress: String? = null): RoomSummary = api.lookupRoom(code, hostAddress)

    suspend fun hostedRooms(): List<RoomSummary> = api.hostedRooms()

    /** Creates an online room from a snapshot, so later edits to the stack leave the room unchanged. */
    suspend fun createRoom(stack: StackSnapshot, options: RoomOptions): Membership = api.createRoom(options.request(stack))

    /** Hosts a room on this phone. It works without an account, internet, or remote backend. */
    suspend fun createLocalRoom(stack: StackSnapshot, options: RoomOptions, hostName: String): Membership {
        localHost.start()
        val membership = api.createRoom(options.request(stack, hostDisplayName = hostName), localHost.selfUrl)
        preferences.addHostToken(membership.room.id, membership.participantToken)
        preferences.setPreferredName(hostName)
        return membership
    }

    /** Restarts the embedded server after the app restarts, while a hosted room is still open. */
    suspend fun resumeLocalHosting(): Boolean {
        localRooms.endAbandonedRooms(preferences.hostTokens.first().keys, System.currentTimeMillis())
        if (!localRooms.hasActiveRooms()) return false
        localHost.start()
        return true
    }

    /** Stops the embedded server when every room on this phone has ended. */
    suspend fun stopHostingWhenIdle() {
        if (!localRooms.hasActiveRooms()) localHost.stop()
    }

    /** Whether this phone hosts [roomId]. */
    suspend fun isHostedHere(roomId: RoomId): Boolean = roomId.value in preferences.hostTokens.first()

    fun playerAddresses(): List<String> = localHost.playerAddresses()

    /**
     * The room this device already joined, when it is a different room. A Player is in one room at a
     * time, so joining [roomId] needs an explicit decision to leave it.
     */
    suspend fun otherJoinedRoom(roomId: RoomId): SavedMembership? = joinedRoom.first()?.takeIf { it.roomId != roomId }

    /**
     * Joins as a Player and remembers the membership. Joining the same room again sends the saved
     * credential, so a retry never creates a second participant.
     */
    suspend fun join(
        room: RoomSummary,
        displayName: String?,
        asGuest: Boolean,
        hostAddress: String? = null,
        leaveOtherRoom: Boolean = false,
    ): Membership {
        val serverUrl = hostAddress ?: preferences.currentServerUrl()
        if (leaveOtherRoom) otherJoinedRoom(room.id)?.let { leaveQuietly(it) }
        val saved = preferences.joinedRoom.first()?.takeIf { it.roomId == room.id && it.serverUrl == serverUrl }
        val membership = api.join(room.id, JoinRoomRequest(displayName, asGuest, leaveOtherRoom), saved?.participantToken, hostAddress)
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

    /** Leaves the joined room on its server when it is reachable, and forgets it on this device. */
    suspend fun leaveJoinedRoom() {
        preferences.joinedRoom.first()?.let { leaveQuietly(it) }
        preferences.setJoinedRoom(null)
    }

    /** Frees the Player to join another room, and keeps the credential for the results. */
    suspend fun markJoinedRoomEnded(roomId: RoomId) {
        val joined = preferences.joinedRoom.first()?.takeIf { it.roomId == roomId && !it.ended } ?: return
        preferences.setJoinedRoom(joined.copy(ended = true))
    }

    /** Forgets a room that no longer exists, so that the Player is not stuck in it. */
    suspend fun forgetJoinedRoom(roomId: RoomId) {
        if (preferences.joinedRoom.first()?.roomId == roomId) preferences.setJoinedRoom(null)
    }

    private suspend fun leaveQuietly(membership: SavedMembership) {
        val server = membership.serverUrl.takeUnless { it == preferences.currentServerUrl() }
        runCatching { api.leave(membership.roomId, membership.participantToken, server) }
    }

    /** The server and credential to use for [roomId]. A null server means the configured one. */
    private suspend fun access(roomId: RoomId): Pair<String?, String?> {
        preferences.hostTokens.first()[roomId.value]?.let {
            // The server may have stopped, for example when the phone closed the app. The room needs it.
            localHost.start()
            return localHost.selfUrl to it
        }
        val joined = preferences.joinedRoom.first()?.takeIf { it.roomId == roomId } ?: return null to null
        val server = joined.serverUrl.takeUnless { it == preferences.currentServerUrl() }
        return server to joined.participantToken
    }

    suspend fun state(roomId: RoomId): RoomState =
        access(roomId).let { (server, token) -> api.roomState(roomId, token, server) }

    suspend fun events(roomId: RoomId): Flow<RoomEvent> =
        access(roomId).let { (server, token) -> api.roomEvents(roomId, token, server) }

    suspend fun command(roomId: RoomId, command: HostCommand): RoomState =
        access(roomId).let { (server, token) -> api.command(roomId, command, token, server) }

    suspend fun attempt(roomId: RoomId, command: PlayerCommand): RoomState =
        access(roomId).let { (server, token) -> api.attempt(roomId, command, token, server) }

    suspend fun submitAnswer(roomId: RoomId, request: SubmitAnswerRequest): AnswerReceipt =
        access(roomId).let { (server, token) -> api.submitAnswer(roomId, request, token, server) }

    suspend fun results(roomId: RoomId): RoomResults =
        access(roomId).let { (server, token) -> api.results(roomId, token, server) }
}
