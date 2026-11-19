package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.AnswerResult
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.LiveQuestion
import io.github.serogee.hive.core.api.QuestionResult
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.api.correctAnswer
import io.github.serogee.hive.core.api.describe
import io.github.serogee.hive.core.api.toPlayerQuestion
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.engine.Admission
import io.github.serogee.hive.core.engine.AdmissionDecision
import io.github.serogee.hive.core.engine.Clock
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.engine.GradedAnswer
import io.github.serogee.hive.core.engine.Grading
import io.github.serogee.hive.core.engine.Leaderboard
import io.github.serogee.hive.core.engine.RankedPlayer
import io.github.serogee.hive.core.engine.RoomProgression
import io.github.serogee.hive.core.engine.Transition
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.SubmissionId
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room behavior shared by every hosting mode.
 *
 * The routes call this service, and this service calls the plain Kotlin rules in the core module.
 * It holds no HTTP or database details.
 */
class RoomService(
    private val store: RoomStore,
    private val hub: RoomEventHub = RoomEventHub(),
    private val clock: Clock = Clock.System,
    private val random: Random = SecureRandom().asKotlinRandom(),
    /** True on the online server. A local host lets anyone host. */
    private val requireAccountToHost: Boolean,
) {
    private val createLock = Mutex()
    private val roomLocks = ConcurrentHashMap<RoomId, Mutex>()

    private fun lockFor(roomId: RoomId) = roomLocks.computeIfAbsent(roomId) { Mutex() }

    fun events(roomId: RoomId): SharedFlow<RoomEvent> = hub.events(roomId)

    suspend fun createRoom(caller: Account?, request: CreateRoomRequest): Membership {
        if (requireAccountToHost && caller == null) {
            throw HiveException(ErrorCode.SIGN_IN_REQUIRED, "Sign in to host an online room.")
        }
        if (request.stack.questions.isEmpty()) {
            throw HiveException(ErrorCode.INVALID_REQUEST, "The stack has no questions.")
        }
        val hostName = DisplayNames.normalize(caller?.displayName ?: request.hostDisplayName)
            ?: throw HiveException(ErrorCode.INVALID_REQUEST, "Enter a host name.")
        val title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: request.stack.title
        val now = clock.nowMillis()
        val token = Tokens.newToken()

        return createLock.withLock {
            var code = RoomCodes.generate(random)
            while (store.findRoomByCode(code) != null) code = RoomCodes.generate(random)

            val room = RoomRecord(
                id = RoomId(UUID.randomUUID().toString()),
                code = code,
                title = title,
                hostName = hostName,
                hostAccountId = caller?.id,
                mode = request.mode,
                admission = request.admission,
                status = RoomStatus.LOBBY,
                stack = request.stack,
                createdAt = now,
                deadlineAt = request.deadlineAt,
                revision = 1,
                revealPolicy = request.revealPolicy,
            )
            val host = ParticipantRecord(
                id = ParticipantId(UUID.randomUUID().toString()),
                roomId = room.id,
                displayName = hostName,
                role = RoomRole.HOST,
                accountId = caller?.id,
                tokenHash = Tokens.hash(token),
                joinedAt = now,
            )
            store.insertRoom(room, host)
            Membership(room.summary(), host.view(), token)
        }
    }

    suspend fun lookup(rawCode: String): RoomSummary {
        val room = store.findRoomByCode(RoomCodes.normalize(rawCode))
            ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "No room uses that code.")
        return room.summary()
    }

    suspend fun hostedRooms(caller: Account): List<RoomSummary> =
        store.roomsHostedBy(caller.id).sortedByDescending { it.createdAt }.map { it.summary() }

    /**
     * Admits a participant, or returns the existing membership.
     *
     * Retrying with [existingToken], or joining again with the same account, never creates a
     * second participant.
     */
    suspend fun join(
        roomId: RoomId,
        caller: Account?,
        request: JoinRoomRequest,
        existingToken: String?,
    ): Membership = lockFor(roomId).withLock {
        val room = requireRoom(roomId)
        if (existingToken != null) {
            val existing = store.findParticipantByToken(roomId, Tokens.hash(existingToken))
            if (existing != null) return Membership(room.summary(), existing.view(), existingToken)
        }
        if (room.status == RoomStatus.ENDED) {
            throw HiveException(ErrorCode.ROOM_CLOSED, "This room has ended.")
        }

        val decision = Admission.decide(room.admission, signedIn = caller != null, asGuest = request.asGuest)
        val name: String
        val accountId = when (decision) {
            is AdmissionDecision.Denied -> throw HiveException(decision.reason, deniedMessage(decision.reason))
            AdmissionDecision.AsGuest -> {
                name = DisplayNames.normalize(request.displayName)
                    ?: throw HiveException(ErrorCode.INVALID_REQUEST, "Enter a display name.")
                null
            }
            AdmissionDecision.AsAccount -> {
                val account = checkNotNull(caller)
                name = DisplayNames.normalize(request.displayName) ?: account.displayName
                account.id
            }
        }

        val token = Tokens.newToken()
        if (accountId != null) {
            val existing = store.findParticipantByAccount(roomId, accountId)
            if (existing != null) {
                store.replaceParticipantToken(existing.id, Tokens.hash(token))
                return Membership(room.summary(), existing.view(), token)
            }
        }

        val participant = ParticipantRecord(
            id = ParticipantId(UUID.randomUUID().toString()),
            roomId = roomId,
            displayName = name,
            role = RoomRole.PLAYER,
            accountId = accountId,
            tokenHash = Tokens.hash(token),
            joinedAt = clock.nowMillis(),
        )
        val revision = room.revision + 1
        store.insertParticipant(participant, revision)
        hub.publish(RoomEvent.ParticipantJoined(roomId, revision, participant.view()))
        Membership(room.copy(revision = revision).summary(), participant.view(), token)
    }

    suspend fun state(roomId: RoomId, caller: Account?, participantToken: String?): RoomState {
        val room = requireRoom(roomId)
        val you = authorize(roomId, caller, participantToken)
        return stateFor(room, you)
    }

    /** Applies a Host command and returns the Host's new view of the room. */
    suspend fun command(
        roomId: RoomId,
        caller: Account?,
        participantToken: String?,
        command: HostCommand,
    ): RoomState = lockFor(roomId).withLock {
        val room = requireRoom(roomId)
        val host = authorize(roomId, caller, participantToken)
        if (host.role != RoomRole.HOST) throw HiveException(ErrorCode.FORBIDDEN, "Only the Host can do this.")

        val progress = when (val transition = RoomProgression.apply(room.progress, command, room.stack.questions.size)) {
            is Transition.Rejected -> throw HiveException(ErrorCode.INVALID_COMMAND, transition.reason)
            is Transition.Accepted -> transition.progress
        }
        val revision = room.revision + 1
        store.updateProgress(roomId, progress, revision)
        val number = (progress.questionIndex ?: 0) + 1
        hub.publish(
            when (command) {
                HostCommand.Start, HostCommand.NextQuestion -> RoomEvent.QuestionOpened(roomId, revision, number)
                HostCommand.CloseQuestion -> RoomEvent.QuestionClosed(roomId, revision, number)
                HostCommand.EndRoom -> RoomEvent.RoomEnded(roomId, revision)
            },
        )
        stateFor(room.with(progress, revision), host)
    }

    /**
     * Records a Player's answer to the open question.
     *
     * The answer is saved before the receipt returns. A retry with the same idempotency key returns
     * the first receipt, even after the question closes, so a lost receipt never adds points twice.
     */
    suspend fun submitAnswer(
        roomId: RoomId,
        caller: Account?,
        participantToken: String?,
        request: SubmitAnswerRequest,
    ): AnswerReceipt = lockFor(roomId).withLock {
        val room = requireRoom(roomId)
        val player = authorize(roomId, caller, participantToken)
        if (player.role != RoomRole.PLAYER) throw HiveException(ErrorCode.FORBIDDEN, "The Host does not answer questions.")
        if (request.idempotencyKey.isBlank() || request.idempotencyKey.length > 100) {
            throw HiveException(ErrorCode.INVALID_REQUEST, "The answer needs an idempotency key of up to 100 characters.")
        }

        store.findSubmissionByKey(roomId, player.id, request.idempotencyKey)?.let { return it.receipt() }

        val index = room.questionIndex
        if (room.status != RoomStatus.LIVE || !room.questionOpen || index == null ||
            room.stack.questions[index].id != request.questionId
        ) {
            throw HiveException(ErrorCode.QUESTION_NOT_OPEN, "This question is no longer accepting answers.")
        }
        val question = room.stack.questions[index]
        store.findSubmission(roomId, player.id, question.id)?.let {
            throw HiveException(ErrorCode.ANSWER_ALREADY_RECORDED, "Your answer to this question is already recorded.")
        }
        validate(question, request.answer)

        val correct = Grading.isCorrect(question, request.answer)
        val submission = SubmissionRecord(
            id = SubmissionId(UUID.randomUUID().toString()),
            roomId = roomId,
            participantId = player.id,
            questionId = question.id,
            answer = request.answer,
            correct = correct,
            points = Grading.points(question, correct),
            idempotencyKey = request.idempotencyKey,
            acceptedAt = clock.nowMillis(),
        )
        val revision = room.revision + 1
        store.insertSubmission(submission, revision)
        val answered = store.submissions(roomId).count { it.questionId == question.id }
        hub.publish(RoomEvent.AnswerRecorded(roomId, revision, index + 1, answered))
        submission.receipt()
    }

    /** The Host can see results at any time. Players see them after the room ends. */
    suspend fun results(roomId: RoomId, caller: Account?, participantToken: String?): RoomResults {
        val room = requireRoom(roomId)
        val you = authorize(roomId, caller, participantToken)
        if (you.role == RoomRole.PLAYER && room.status != RoomStatus.ENDED) {
            throw HiveException(ErrorCode.RESULTS_NOT_READY, "Results appear when the Host ends the quiz.")
        }

        val players = store.participants(roomId).filter { it.role == RoomRole.PLAYER }
        val submissions = store.submissions(roomId)
        val leaderboard = Leaderboard.build(
            players.map { RankedPlayer(it.id, it.displayName, isGuest = it.accountId == null) },
            submissions.map { GradedAnswer(it.participantId, it.correct, it.points) },
        )
        val questions = room.stack.questions.mapIndexed { index, question ->
            val answers = submissions.filter { it.questionId == question.id }
            QuestionResult(
                questionId = question.id,
                number = index + 1,
                prompt = question.prompt,
                answeredCount = answers.size,
                correctCount = answers.count { it.correct },
                correctAnswer = question.correctAnswer(),
                correctAnswerText = question.describe(question.correctAnswer()),
                explanation = question.explanation,
            )
        }
        val yourAnswers = if (you.role == RoomRole.PLAYER) {
            room.stack.questions.map { question ->
                val own = submissions.firstOrNull { it.participantId == you.id && it.questionId == question.id }
                AnswerResult(
                    questionId = question.id,
                    answer = own?.answer,
                    answerText = own?.answer?.let(question::describe),
                    correct = own?.correct == true,
                    points = own?.points ?: 0,
                )
            }
        } else {
            emptyList()
        }
        return RoomResults(room.summary(), leaderboard, questions, yourAnswers)
    }

    private suspend fun stateFor(room: RoomRecord, you: ParticipantRecord): RoomState {
        val participants = store.participants(room.id)
        val index = room.questionIndex
        val live = if (room.status == RoomStatus.LIVE && index != null) {
            val question = room.stack.questions[index]
            val answers = store.submissions(room.id).filter { it.questionId == question.id }
            val reveal = you.role == RoomRole.HOST ||
                (room.revealPolicy == RevealPolicy.AFTER_EACH_QUESTION && !room.questionOpen)
            LiveQuestion(
                question = question.toPlayerQuestion(index + 1),
                isOpen = room.questionOpen,
                questionCount = room.stack.questions.size,
                answeredCount = answers.size,
                playerCount = participants.count { it.role == RoomRole.PLAYER },
                yourAnswer = answers.firstOrNull { it.participantId == you.id }?.receipt(),
                correctAnswer = if (reveal) question.correctAnswer() else null,
                explanation = if (reveal) question.explanation else null,
            )
        } else {
            null
        }
        return RoomState(
            room = room.summary(),
            you = you.view(),
            participants = participants.map { it.view() },
            revision = room.revision,
            live = live,
        )
    }

    private fun validate(question: Question, answer: Answer) {
        val valid = when (question) {
            is Question.MultipleChoice -> answer is Answer.Text ||
                (answer is Answer.SelectedChoice && question.choices.any { it.id == answer.choiceId })
            is Question.TrueFalse -> answer is Answer.TrueFalse || answer is Answer.Text
            is Question.Identification -> answer is Answer.Text && answer.text.length <= 500
        }
        if (!valid) throw HiveException(ErrorCode.INVALID_REQUEST, "That answer does not fit this question.")
    }

    /** Identifies the caller by reconnect credential first, then by account. */
    suspend fun authorize(roomId: RoomId, caller: Account?, participantToken: String?): ParticipantRecord {
        requireRoom(roomId)
        val participant = participantToken?.let { store.findParticipantByToken(roomId, Tokens.hash(it)) }
            ?: caller?.let { store.findParticipantByAccount(roomId, it.id) }
        return participant ?: throw HiveException(ErrorCode.NOT_A_PARTICIPANT, "Join this room first.")
    }

    private suspend fun requireRoom(roomId: RoomId) =
        store.findRoom(roomId) ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "This room no longer exists.")

    private fun deniedMessage(code: ErrorCode) = when (code) {
        ErrorCode.SIGN_IN_REQUIRED -> "Sign in to join with an account, or join as a guest."
        ErrorCode.GUESTS_NOT_ALLOWED -> "This room accepts signed-in Players only."
        ErrorCode.GUESTS_ONLY -> "This room accepts guests only. Join as a guest."
        else -> "You cannot join this room."
    }
}
