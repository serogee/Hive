package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.engine.Clock
import io.github.serogee.hive.core.engine.GradedAnswer
import io.github.serogee.hive.core.engine.Leaderboard
import io.github.serogee.hive.core.engine.RankedPlayer
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.RoomService
import io.github.serogee.hive.host.RoomStore

/** Checks what phones send, and serves each account only its own data. */
class SyncService(
    private val store: SyncStore,
    private val rooms: RoomStore,
    private val roomService: RoomService,
    private val clock: Clock = Clock.System,
) {
    suspend fun changes(account: Account, cursor: Long, limit: Int) =
        store.changes(account.id, cursor.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    suspend fun pushStacks(account: Account, generation: Long, changes: List<StackChange>) = run {
        check(changes.size <= MAX_PUSH, "Send up to $MAX_PUSH stacks at a time.")
        changes.forEach { change ->
            check(change.stackId.isNotBlank() && change.stackId.length <= MAX_ID, "A stack has no valid ID.")
            check(change.deleted || change.document != null, "A stack that is not deleted needs its content.")
            check((change.document?.questions?.size ?: 0) <= MAX_QUESTIONS, "A stack has more than $MAX_QUESTIONS questions.")
        }
        check(changes.map { it.stackId }.toSet().size == changes.size, "Send each stack once.")
        store.pushStacks(account.id, generation, changes, clock.nowMillis())
    }

    suspend fun append(account: Account, kind: RecordKind, records: List<SyncRecord>): Int {
        check(records.size <= MAX_PUSH, "Send up to $MAX_PUSH records at a time.")
        check(records.all { it.id.isNotBlank() && it.id.length <= MAX_ID }, "A record has no valid ID.")
        check(records.all { it.data.toString().length <= MAX_RECORD_CHARS }, "A record is too large.")
        return store.append(account.id, kind, records)
    }

    suspend fun deleteAll(account: Account) = store.deleteAll(account.id)

    /** The account's online rooms, newest first, with its own results loaded in batch queries. */
    suspend fun onlineRooms(account: Account): List<OnlineRoomEntry> {
        val hosted = rooms.roomsHostedBy(account.id).map { it to RoomRole.HOST }
        val played = rooms.roomsPlayedBy(account.id).map { it to RoomRole.PLAYER }
        val now = clock.nowMillis()
        val entries = (hosted + played).sortedByDescending { it.first.createdAt }.take(MAX_ROOMS)
        if (entries.isEmpty()) return emptyList()

        val participantsByRoom = rooms.participantsByRooms(entries.map { it.first.id })
        val endedPlayedRoomIds = entries
            .filter { (room, role) -> role == RoomRole.PLAYER && room.effectiveStatus(now) == RoomStatus.ENDED }
            .map { it.first.id }
        val submissionsByRoom = if (endedPlayedRoomIds.isEmpty()) emptyMap() else rooms.submissionsByRooms(endedPlayedRoomIds)

        return entries.map { (room, role) ->
            val status = room.effectiveStatus(now)
            val participants = participantsByRoom[room.id].orEmpty()
            val players = participants.filter { it.role == RoomRole.PLAYER }
            val own = if (role == RoomRole.PLAYER && status == RoomStatus.ENDED) {
                val you = players.firstOrNull { it.accountId == account.id }
                if (you != null) {
                    val submissions = submissionsByRoom[room.id].orEmpty()
                    Leaderboard.build(
                        players.map { RankedPlayer(it.id, it.displayName, isGuest = it.accountId == null) },
                        submissions.map { GradedAnswer(it.participantId, it.correct, it.points, it.speedBonusTenths, it.responseMillis) },
                        timeTiebreak = room.timeTiebreak,
                    ).firstOrNull { it.participantId == you.id }
                } else {
                    null
                }
            } else {
                null
            }
            OnlineRoomEntry(
                roomId = room.id,
                code = room.code,
                title = room.title,
                role = role,
                mode = room.mode,
                status = status,
                createdAt = room.createdAt,
                questionCount = room.stack.questions.size,
                playerCount = players.size,
                totalTenths = own?.totalTenths,
                correctCount = own?.correctCount,
                rank = own?.rank,
            )
        }
    }

    private fun check(valid: Boolean, message: String) {
        if (!valid) throw HiveException(ErrorCode.INVALID_REQUEST, message)
    }

    companion object {
        const val MAX_PAGE = 200
        const val MAX_PUSH = 100
        const val MAX_ROOMS = 100
        private const val MAX_ID = 100
        private const val MAX_QUESTIONS = 1_000
        private const val MAX_RECORD_CHARS = 100_000
    }
}
