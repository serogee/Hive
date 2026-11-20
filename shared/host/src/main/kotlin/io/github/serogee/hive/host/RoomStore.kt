package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.engine.Progress
import io.github.serogee.hive.core.engine.Timing
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.model.SubmissionId

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
    val revealPolicy: RevealPolicy = RevealPolicy.AT_END,
    /** Index of the current question, or null before the first one. */
    val questionIndex: Int? = null,
    val questionOpen: Boolean = false,
    /** When the room server opened the current question. */
    val questionOpenedAt: Long? = null,
    val speedScoring: Boolean = false,
    val timeTiebreak: Boolean = false,
) {
    val progress: Progress get() = Progress(status, questionIndex, questionOpen, questionOpenedAt)

    fun with(progress: Progress, revision: Long) = copy(
        status = progress.status,
        questionIndex = progress.questionIndex,
        questionOpen = progress.questionOpen,
        questionOpenedAt = progress.openedAt,
        revision = revision,
    )

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
        revealPolicy = revealPolicy,
        speedScoring = speedScoring,
        timeTiebreak = timeTiebreak,
    )

    /** When answers to the current question stop, from its time limit and the quiz deadline. */
    fun cutoff(): Long? = Timing.cutoff(questionOpenedAt, questionIndex?.let { stack.questions[it].durationSeconds }, deadlineAt)

    /** Whether the current question accepts answers at [now]. An expired timer closes it. */
    fun acceptsAnswers(now: Long): Boolean = status == RoomStatus.LIVE && questionOpen && Timing.isBefore(now, cutoff())
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

/** One accepted answer. A participant has at most one per question. */
data class SubmissionRecord(
    val id: SubmissionId,
    val roomId: RoomId,
    val participantId: ParticipantId,
    val questionId: QuestionId,
    val answer: Answer,
    val correct: Boolean,
    val points: Int,
    val idempotencyKey: String,
    val acceptedAt: Long,
    /** Time from the question opening to the answer arriving, when the opening time is known. */
    val responseMillis: Long? = null,
    val speedBonusTenths: Int = 0,
) {
    fun receipt() = AnswerReceipt(id, questionId, answer, acceptedAt)
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

    /** Stores the room's new progress and revision. */
    suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long)

    /** Adds [participant] and stores [revision] as the room's new revision. */
    suspend fun insertParticipant(participant: ParticipantRecord, revision: Long)

    /** Replaces a participant's reconnect credential, for example when an account rejoins. */
    suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String)

    suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String): ParticipantRecord?

    suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId): ParticipantRecord?

    /** Returns participants in join order. */
    suspend fun participants(roomId: RoomId): List<ParticipantRecord>

    /** Saves an accepted answer and stores [revision] as the room's new revision. */
    suspend fun insertSubmission(submission: SubmissionRecord, revision: Long)

    suspend fun findSubmissionByKey(roomId: RoomId, participantId: ParticipantId, idempotencyKey: String): SubmissionRecord?

    suspend fun findSubmission(roomId: RoomId, participantId: ParticipantId, questionId: QuestionId): SubmissionRecord?

    /** Returns accepted answers in the order they arrived. */
    suspend fun submissions(roomId: RoomId): List<SubmissionRecord>
}
