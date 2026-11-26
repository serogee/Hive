package io.github.serogee.hive.host

import io.github.serogee.hive.core.engine.Progress
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps rooms in memory. Useful for tests and for short-lived hosting. */
class InMemoryRoomStore : RoomStore {
    private val mutex = Mutex()
    private val rooms = linkedMapOf<RoomId, RoomRecord>()
    private val participants = mutableListOf<ParticipantRecord>()
    private val submissions = mutableListOf<SubmissionRecord>()
    private val attempts = linkedMapOf<Pair<RoomId, ParticipantId>, AttemptRecord>()

    override suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord) = mutex.withLock {
        rooms[room.id] = room
        participants += host
    }

    override suspend fun findRoom(id: RoomId) = mutex.withLock { rooms[id] }

    override suspend fun findRoomByCode(code: String) = mutex.withLock {
        rooms.values.firstOrNull { it.code == code }
    }

    override suspend fun roomsHostedBy(accountId: AccountId) = mutex.withLock {
        rooms.values.filter { it.hostAccountId == accountId }
    }

    override suspend fun roomsPlayedBy(accountId: AccountId) = mutex.withLock {
        val ids = participants.filter { it.accountId == accountId && it.role == RoomRole.PLAYER }.map { it.roomId }.toSet()
        rooms.values.filter { it.id in ids }
    }

    override suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long) = mutex.withLock {
        rooms.computeIfPresent(roomId) { _, room -> room.with(progress, revision) }
        Unit
    }

    override suspend fun updateSettings(room: RoomRecord) = mutex.withLock {
        rooms.computeIfPresent(room.id) { _, current ->
            current.copy(
                title = room.title,
                admission = room.admission,
                stack = room.stack,
                deadlineAt = room.deadlineAt,
                revealPolicy = room.revealPolicy,
                speedScoring = room.speedScoring,
                timeTiebreak = room.timeTiebreak,
                revision = room.revision,
            )
        }
        Unit
    }

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) = mutex.withLock {
        participants += participant
        rooms.computeIfPresent(participant.roomId) { _, room -> room.copy(revision = revision) }
        Unit
    }

    override suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String) = mutex.withLock {
        val index = participants.indexOfFirst { it.id == participantId }
        if (index >= 0) participants[index] = participants[index].copy(tokenHash = tokenHash)
    }

    override suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String) = mutex.withLock {
        participants.firstOrNull { it.roomId == roomId && it.tokenHash == tokenHash }
    }

    override suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId) = mutex.withLock {
        participants.firstOrNull { it.roomId == roomId && it.accountId == accountId }
    }

    override suspend fun participants(roomId: RoomId) = mutex.withLock {
        participants.filter { it.roomId == roomId }
    }

    override suspend fun insertSubmission(submission: SubmissionRecord, revision: Long) = mutex.withLock {
        submissions += submission
        rooms.computeIfPresent(submission.roomId) { _, room -> room.copy(revision = revision) }
        Unit
    }

    override suspend fun findSubmissionByKey(roomId: RoomId, participantId: ParticipantId, idempotencyKey: String) =
        mutex.withLock {
            submissions.firstOrNull {
                it.roomId == roomId && it.participantId == participantId && it.idempotencyKey == idempotencyKey
            }
        }

    override suspend fun findSubmission(roomId: RoomId, participantId: ParticipantId, questionId: QuestionId) =
        mutex.withLock {
            submissions.firstOrNull {
                it.roomId == roomId && it.participantId == participantId && it.questionId == questionId
            }
        }

    override suspend fun submissions(roomId: RoomId) = mutex.withLock {
        submissions.filter { it.roomId == roomId }
    }

    override suspend fun activeMembershipsOf(accountId: AccountId) = mutex.withLock {
        participants.filter { it.accountId == accountId && it.role == RoomRole.PLAYER && it.leftAt == null }
    }

    override suspend fun setLeft(participant: ParticipantRecord, leftAt: Long?, revision: Long) = mutex.withLock {
        val index = participants.indexOfFirst { it.id == participant.id }
        if (index >= 0) participants[index] = participants[index].copy(leftAt = leftAt)
        rooms.computeIfPresent(participant.roomId) { _, room -> room.copy(revision = revision) }
        Unit
    }

    override suspend fun findAttempt(roomId: RoomId, participantId: ParticipantId) = mutex.withLock {
        attempts[roomId to participantId]
    }

    override suspend fun attempts(roomId: RoomId) = mutex.withLock {
        attempts.values.filter { it.roomId == roomId }
    }

    override suspend fun saveAttempt(attempt: AttemptRecord, revision: Long) = mutex.withLock {
        attempts[attempt.roomId to attempt.participantId] = attempt
        rooms.computeIfPresent(attempt.roomId) { _, room -> room.copy(revision = revision) }
        Unit
    }
}
