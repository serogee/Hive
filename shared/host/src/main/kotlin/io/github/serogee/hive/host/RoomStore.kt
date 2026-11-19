package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackSnapshot

data class RoomRecord(
    val id: RoomId,
    val code: String,
    val title: String,
    val hostName: String,
    val hostAccountId: AccountId?,
    val mode: RoomMode,
    val admission: AdmissionPolicy,
    val status: RoomStatus,
    val stack: StackSnapshot,
    val createdAt: Long,
    val deadlineAt: Long?,
    val revision: Long,
) {
    fun summary() = RoomSummary(
        id = id,
        code = code,
        title = title,
        hostName = hostName,
        mode = mode,
        admission = admission,
        status = status,
        questionCount = stack.questions.size,
        deadlineAt = deadlineAt,
    )
}

data class ParticipantRecord(
    val id: ParticipantId,
    val roomId: RoomId,
    val displayName: String,
    val role: RoomRole,
    /** Null for a guest. */
    val accountId: AccountId?,
    /** SHA-256 of the reconnect credential. The credential itself is never stored. */
    val tokenHash: String,
    val joinedAt: Long,
) {
    fun view() = ParticipantView(id, displayName, role, isGuest = accountId == null)
}

/**
 * Persistence for rooms owned by one server.
 *
 * The Android host, the demo server, and the production server each provide an implementation.
 * [RoomService] serializes writes for a room, so implementations need only be safe for concurrent
 * calls on different rooms.
 */
interface RoomStore {
    suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord)

    suspend fun findRoom(id: RoomId): RoomRecord?

    suspend fun findRoomByCode(code: String): RoomRecord?

    suspend fun roomsHostedBy(accountId: AccountId): List<RoomRecord>

    /** Adds [participant] and stores [revision] as the room's new revision. */
    suspend fun insertParticipant(participant: ParticipantRecord, revision: Long)

    /** Replaces a participant's reconnect credential, for example when an account rejoins. */
    suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String)

    suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String): ParticipantRecord?

    suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId): ParticipantRecord?

    /** Returns participants in join order. */
    suspend fun participants(roomId: RoomId): List<ParticipantRecord>
}
