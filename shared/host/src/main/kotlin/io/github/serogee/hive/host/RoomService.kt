package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AttemptView
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.PlayerProgressView
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
import io.github.serogee.hive.core.engine.Progress
import io.github.serogee.hive.core.engine.RankedPlayer
import io.github.serogee.hive.core.engine.RoomProgression
import io.github.serogee.hive.core.engine.SpeedBonus
import io.github.serogee.hive.core.engine.Timing
import io.github.serogee.hive.core.engine.Transition
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.SubmissionId
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
    /** False on a server that cannot verify accounts. Players then join as guests. */
    private val accountsSupported: Boolean = true,
    /** Runs question timers. Each timer closes its question when time runs out. */
    private val timers: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
        if ((request.deadlineAt ?: Long.MAX_VALUE) <= now) {
            throw HiveException(ErrorCode.INVALID_REQUEST, "The quiz deadline has already passed.")
        }
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
                speedScoring = request.speedScoring,
                timeTiebreak = request.timeTiebreak ?: (request.mode == RoomMode.DISCUSSION),
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
            Membership(room.publicSummary(), host.view(), token)
        }
    }

    suspend fun lookup(rawCode: String): RoomSummary {
        val room = store.findRoomByCode(RoomCodes.normalize(rawCode))
            ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "No room uses that code.")
        return room.publicSummary()
    }

    suspend fun hostedRooms(caller: Account): List<RoomSummary> =
        store.roomsHostedBy(caller.id).sortedByDescending { it.createdAt }.map { it.publicSummary() }

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
            if (existing != null) return Membership(room.publicSummary(), existing.view(), existingToken)
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
                return Membership(room.publicSummary(), existing.view(), token)
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
        Membership(room.copy(revision = revision).publicSummary(), participant.view(), token)
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

        val now = clock.nowMillis()
        if (room.mode == RoomMode.ASYNCHRONOUS) return asyncCommand(room, host, command, now)
        // A question whose time ran out counts as closed, so the Host can move on.
        val current = room.progress.let { if (it.questionOpen && !room.acceptsAnswers(now)) it.copy(questionOpen = false) else it }
        val progress = when (val transition = RoomProgression.apply(current, command, room.stack.questions.size)) {
            is Transition.Rejected -> throw HiveException(ErrorCode.INVALID_COMMAND, transition.reason)
            is Transition.Accepted -> transition.progress
        }.let { if (command == HostCommand.Start || command == HostCommand.NextQuestion) it.copy(openedAt = now) else it }
        val revision = room.revision + 1
        store.updateProgress(roomId, progress, revision)
        val updated = room.with(progress, revision)
        val number = (progress.questionIndex ?: 0) + 1
        hub.publish(
            when (command) {
                HostCommand.Start, HostCommand.NextQuestion -> RoomEvent.QuestionOpened(roomId, revision, number)
                HostCommand.CloseQuestion -> RoomEvent.QuestionClosed(roomId, revision, number)
                HostCommand.EndRoom -> RoomEvent.RoomEnded(roomId, revision)
            },
        )
        if (progress.questionOpen) scheduleClose(updated)
        stateFor(updated, host)
    }

    /** In an Asynchronous room, the Host only opens and ends the room. Players set their own pace. */
    private suspend fun asyncCommand(room: RoomRecord, host: ParticipantRecord, command: HostCommand, now: Long): RoomState {
        val progress = when {
            command == HostCommand.Start && room.status == RoomStatus.LOBBY -> Progress(RoomStatus.LIVE, null, false)
            command == HostCommand.EndRoom && room.effectiveStatus(now) != RoomStatus.ENDED ->
                Progress(RoomStatus.ENDED, null, false)
            command == HostCommand.Start || command == HostCommand.EndRoom ->
                throw HiveException(ErrorCode.INVALID_COMMAND, "The room is already ${if (command == HostCommand.Start) "open" else "closed"}.")
            else -> throw HiveException(ErrorCode.INVALID_COMMAND, "Players move through this room at their own pace.")
        }
        val revision = room.revision + 1
        store.updateProgress(room.id, progress, revision)
        val updated = room.with(progress, revision)
        hub.publish(
            if (progress.status == RoomStatus.ENDED) RoomEvent.RoomEnded(room.id, revision) else RoomEvent.QuestionOpened(room.id, revision, 1),
        )
        if (progress.status == RoomStatus.LIVE) scheduleDeadline(updated)
        return stateFor(updated, host)
    }

    /** Ends an Asynchronous room for everyone when its deadline passes. */
    private fun scheduleDeadline(room: RoomRecord) {
        val deadline = room.deadlineAt ?: return
        timers.launch {
            delay((deadline - clock.nowMillis()).coerceAtLeast(0))
            lockFor(room.id).withLock {
                val current = store.findRoom(room.id) ?: return@withLock
                if (current.status != RoomStatus.LIVE) return@withLock
                val revision = current.revision + 1
                store.updateProgress(room.id, Progress(RoomStatus.ENDED, null, false), revision)
                hub.publish(RoomEvent.RoomEnded(room.id, revision))
            }
        }
    }

    /**
     * Starts or advances a Player's own attempt in an Asynchronous room. The room server records
     * when it opens each question for the Player, and that time sets the Player's timer.
     */
    suspend fun attempt(
        roomId: RoomId,
        caller: Account?,
        participantToken: String?,
        command: PlayerCommand,
    ): RoomState = lockFor(roomId).withLock {
        val room = requireRoom(roomId)
        val player = authorize(roomId, caller, participantToken)
        val now = clock.nowMillis()
        if (player.role != RoomRole.PLAYER) throw HiveException(ErrorCode.FORBIDDEN, "Only Players take attempts.")
        if (room.mode != RoomMode.ASYNCHRONOUS) {
            throw HiveException(ErrorCode.INVALID_COMMAND, "The Host moves this room from question to question.")
        }
        if (room.effectiveStatus(now) != RoomStatus.LIVE) {
            throw HiveException(ErrorCode.ROOM_CLOSED, if (room.status == RoomStatus.LOBBY) "The Host has not opened this room yet." else "This room has closed.")
        }

        val current = store.findAttempt(roomId, player.id)
        val next = when (command) {
            PlayerCommand.StartAttempt -> current ?: AttemptRecord(roomId, player.id, 0, now, finished = false)
            PlayerCommand.NextQuestion -> when {
                current == null -> throw HiveException(ErrorCode.INVALID_COMMAND, "Start the attempt first.")
                current.finished -> current
                current.questionIndex + 1 < room.stack.questions.size ->
                    current.copy(questionIndex = current.questionIndex + 1, openedAt = now)
                else -> current.copy(finished = true)
            }
        }
        if (next != current) {
            val revision = room.revision + 1
            store.saveAttempt(next, revision)
            hub.publish(RoomEvent.AttemptUpdated(roomId, revision, player.id))
            return stateFor(room.copy(revision = revision), player)
        }
        stateFor(room, player)
    }
    /** Closes the current question when its time runs out, and tells everyone in the room. */
    private fun scheduleClose(room: RoomRecord) {
        val cutoff = room.cutoff() ?: return
        val index = room.questionIndex
        val openedAt = room.questionOpenedAt
        timers.launch {
            delay((cutoff - clock.nowMillis()).coerceAtLeast(0))
            lockFor(room.id).withLock {
                val current = store.findRoom(room.id) ?: return@withLock
                if (current.questionIndex != index || current.questionOpenedAt != openedAt || !current.questionOpen) return@withLock
                val revision = current.revision + 1
                store.updateProgress(room.id, current.progress.copy(questionOpen = false), revision)
                hub.publish(RoomEvent.QuestionClosed(room.id, revision, (index ?: 0) + 1))
            }
        }
    }

    /**
     * Records a Player's answer to the open question.
     *
     * The answer is saved before the receipt returns. A retry with the same idempotency key returns
     * the first receipt, even after the question closes, so a lost receipt never adds points twice.
     * The room server's clock decides whether an answer arrived in time.
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

        val now = clock.nowMillis()
        val attempt = if (room.mode == RoomMode.ASYNCHRONOUS) store.findAttempt(roomId, player.id) else null
        val index = if (room.mode == RoomMode.ASYNCHRONOUS) attempt?.questionIndex else room.questionIndex
        val open = if (room.mode == RoomMode.ASYNCHRONOUS) {
            attempt != null && !attempt.finished && room.effectiveStatus(now) == RoomStatus.LIVE && Timing.isBefore(now, attempt.cutoff(room))
        } else {
            room.acceptsAnswers(now)
        }
        if (!open || index == null || room.stack.questions[index].id != request.questionId) {
            throw HiveException(ErrorCode.QUESTION_NOT_OPEN, "This question is no longer accepting answers.")
        }
        val question = room.stack.questions[index]
        store.findSubmission(roomId, player.id, question.id)?.let {
            throw HiveException(ErrorCode.ANSWER_ALREADY_RECORDED, "Your answer to this question is already recorded.")
        }
        validate(question, request.answer)

        val correct = Grading.isCorrect(question, request.answer)
        // Time between a Player's questions never counts, because each question has its own opening time.
        val openedAt = if (room.mode == RoomMode.ASYNCHRONOUS) attempt?.openedAt else room.questionOpenedAt
        val responseMillis = openedAt?.let { (now - it).coerceAtLeast(0) }
        val submission = SubmissionRecord(
            id = SubmissionId(UUID.randomUUID().toString()),
            roomId = roomId,
            participantId = player.id,
            questionId = question.id,
            answer = request.answer,
            correct = correct,
            points = Grading.points(question, correct),
            idempotencyKey = request.idempotencyKey,
            acceptedAt = now,
            responseMillis = responseMillis,
            speedBonusTenths = if (room.speedScoring) {
                SpeedBonus.tenths(question.points, question.durationSeconds, responseMillis, correct)
            } else {
                0
            },
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
        if (you.role == RoomRole.PLAYER && room.effectiveStatus(clock.nowMillis()) != RoomStatus.ENDED) {
            throw HiveException(ErrorCode.RESULTS_NOT_READY, "Results appear when the quiz ends.")
        }

        val players = store.participants(roomId).filter { it.role == RoomRole.PLAYER }
        val submissions = store.submissions(roomId)
        val leaderboard = Leaderboard.build(
            players.map { RankedPlayer(it.id, it.displayName, isGuest = it.accountId == null) },
            submissions.map { GradedAnswer(it.participantId, it.correct, it.points, it.speedBonusTenths, it.responseMillis) },
            timeTiebreak = room.timeTiebreak,
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
                    speedBonusTenths = own?.speedBonusTenths ?: 0,
                )
            }
        } else {
            emptyList()
        }
        return RoomResults(room.publicSummary(), leaderboard, questions, yourAnswers)
    }

    private suspend fun stateFor(room: RoomRecord, you: ParticipantRecord): RoomState {
        if (room.mode == RoomMode.ASYNCHRONOUS) return asyncStateFor(room, you)
        val participants = store.participants(room.id)
        val index = room.questionIndex
        val now = clock.nowMillis()
        val live = if (room.status == RoomStatus.LIVE && index != null) {
            val question = room.stack.questions[index]
            val answers = store.submissions(room.id).filter { it.questionId == question.id }
            val open = room.acceptsAnswers(now)
            val reveal = you.role == RoomRole.HOST || (room.revealPolicy == RevealPolicy.AFTER_EACH_QUESTION && !open)
            LiveQuestion(
                question = question.toPlayerQuestion(index + 1),
                isOpen = open,
                questionCount = room.stack.questions.size,
                answeredCount = answers.size,
                playerCount = participants.count { it.role == RoomRole.PLAYER },
                yourAnswer = answers.firstOrNull { it.participantId == you.id }?.receipt(),
                closesAt = if (room.questionOpen) room.cutoff() else null,
                serverTime = now,
                correctAnswer = if (reveal) question.correctAnswer() else null,
                explanation = if (reveal) question.explanation else null,
            )
        } else {
            null
        }
        return RoomState(
            room = room.publicSummary(),
            you = you.view(),
            participants = participants.map { it.view() },
            revision = room.revision,
            live = live,
        )
    }
    private suspend fun asyncStateFor(room: RoomRecord, you: ParticipantRecord): RoomState {
        val now = clock.nowMillis()
        val status = room.effectiveStatus(now)
        val participants = store.participants(room.id)
        val submissions = store.submissions(room.id)
        val players = participants.filter { it.role == RoomRole.PLAYER }
        val attempts = store.attempts(room.id).associateBy { it.participantId }

        val own = attempts[you.id]
        val live = if (status == RoomStatus.LIVE && own != null && !own.finished) {
            val question = room.stack.questions[own.questionIndex]
            val cutoff = own.cutoff(room)
            val open = Timing.isBefore(now, cutoff)
            val reveal = room.revealPolicy == RevealPolicy.AFTER_EACH_QUESTION && !open
            LiveQuestion(
                question = question.toPlayerQuestion(own.questionIndex + 1),
                isOpen = open,
                questionCount = room.stack.questions.size,
                answeredCount = submissions.count { it.questionId == question.id },
                playerCount = players.size,
                yourAnswer = submissions.firstOrNull { it.participantId == you.id && it.questionId == question.id }?.receipt(),
                closesAt = cutoff,
                serverTime = now,
                correctAnswer = if (reveal) question.correctAnswer() else null,
                explanation = if (reveal) question.explanation else null,
            )
        } else {
            null
        }
        val attempt = if (you.role == RoomRole.PLAYER) {
            AttemptView(
                started = own != null,
                finished = own?.finished == true,
                answeredCount = submissions.count { it.participantId == you.id },
            )
        } else {
            null
        }
        val progress = if (you.role == RoomRole.HOST) {
            players.map { player ->
                val attemptOf = attempts[player.id]
                PlayerProgressView(
                    participantId = player.id,
                    displayName = player.displayName,
                    currentQuestion = attemptOf?.takeIf { !it.finished }?.let { it.questionIndex + 1 },
                    answeredCount = submissions.count { it.participantId == player.id },
                    finished = attemptOf?.finished == true,
                )
            }
        } else {
            emptyList()
        }
        return RoomState(
            room = room.publicSummary().copy(status = status),
            you = you.view(),
            participants = participants.map { it.view() },
            revision = room.revision,
            live = live,
            attempt = attempt,
            progress = progress,
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

    private fun RoomRecord.publicSummary() = summary().copy(acceptsAccounts = accountsSupported)

    private suspend fun requireRoom(roomId: RoomId) =
        store.findRoom(roomId) ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "This room no longer exists.")

    private fun deniedMessage(code: ErrorCode) = when (code) {
        ErrorCode.SIGN_IN_REQUIRED -> "Sign in to join with an account, or join as a guest."
        ErrorCode.GUESTS_NOT_ALLOWED -> "This room accepts signed-in Players only."
        ErrorCode.GUESTS_ONLY -> "This room accepts guests only. Join as a guest."
        else -> "You cannot join this room."
    }
}
