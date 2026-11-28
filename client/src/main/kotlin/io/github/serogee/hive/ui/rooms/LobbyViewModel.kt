package io.github.serogee.hive.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.serogee.hive.audio.AudioScope
import io.github.serogee.hive.audio.NoOpSoundCuePlayer
import io.github.serogee.hive.audio.Sfx
import io.github.serogee.hive.audio.SoundCuePlayer
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.PlayerQuestion
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.userMessage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Connection { CONNECTING, LIVE, RECONNECTING }

/** A Player's answer that has not been confirmed yet. */
data class PendingAnswer(
    val questionId: QuestionId,
    val answer: Answer,
    val idempotencyKey: String,
    val failed: Boolean = false,
)

data class LobbyUiState(
    val room: RoomState? = null,
    val connection: Connection = Connection.CONNECTING,
    val results: RoomResults? = null,
    val pending: PendingAnswer? = null,
    val busy: Boolean = false,
    /** A failure the user can retry, shown inline. */
    val error: String? = null,
    /** A failure that reconnecting cannot fix, such as a deleted room. */
    val fatalError: String? = null,
    /** Whether this phone hosts the room. */
    val hostedHere: Boolean = false,
)

/**
 * Keeps one participant's view of a room current and sends their actions.
 *
 * Each connection starts with a snapshot, so a missed event is repaired on reconnect.
 */
class LobbyViewModel(
    private val roomId: RoomId,
    private val rooms: RoomRepository,
    private val audio: SoundCuePlayer = NoOpSoundCuePlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(LobbyUiState())
    val state: StateFlow<LobbyUiState> = _state.asStateFlow()
    init {
        viewModelScope.launch { _state.update { it.copy(hostedHere = rooms.isHostedHere(roomId)) } }
        viewModelScope.launch { follow() }
    }

    fun command(command: HostCommand) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val room = rooms.command(roomId, command)
            show(room)
            _state.update { it.copy(busy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.userMessage()) }
        }
    }

    /**
     * Sends an answer. A retry reuses the same idempotency key, so the server records it once even
     * when the first receipt was lost.
     */
    fun submit(questionId: QuestionId, answer: Answer) {
        val previous = _state.value.pending
        val isRetry = previous != null && previous.questionId == questionId && previous.answer == answer
        val pending = if (isRetry) {
            previous.copy(failed = false)
        } else {
            PendingAnswer(questionId, answer, UUID.randomUUID().toString())
        }
        if (!isRetry) audio.playSfx(Sfx.LOCK_IN, AudioScope.MULTIPLAYER)
        _state.update { it.copy(pending = pending, error = null) }
        viewModelScope.launch {
            try {
                rooms.submitAnswer(roomId, SubmitAnswerRequest(questionId, answer, pending.idempotencyKey))
                _state.update { it.copy(pending = null) }
                refresh()
            } catch (e: ApiException) {
                _state.update { it.copy(pending = null, error = e.error.message) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pending = pending.copy(failed = true), error = e.userMessage()) }
            }
        }
    }

    /** Starts or advances this Player's own attempt in an Asynchronous room. */
    fun attempt(command: PlayerCommand) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            show(rooms.attempt(roomId, command))
            _state.update { it.copy(busy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.userMessage()) }
        }
    }

    /** Gets a new snapshot, for example when a countdown reaches zero. */
    fun refreshNow() = viewModelScope.launch { runCatching { refresh() } }

    fun retrySubmit() {
        val pending = _state.value.pending ?: return
        submit(pending.questionId, pending.answer)
    }

    private suspend fun follow() {
        var backoff = 1_000L
        // Nothing changes after the results, and the Host phone can stop its server.
        while (_state.value.results == null) {
            try {
                // Fails fast with an ApiException when the room is gone or the credential is unknown.
                refresh()
                if (_state.value.results != null) return
                rooms.events(roomId).collect { event ->
                    when (event) {
                        is RoomEvent.Subscribed -> {
                            refresh()
                            backoff = 1_000L
                            _state.update { it.copy(connection = Connection.LIVE) }
                        }
                        is RoomEvent.ParticipantJoined -> applyJoin(event)
                        is RoomEvent.AnswerRecorded -> applyAnswerCount(event)
                        is RoomEvent.QuestionOpened, is RoomEvent.QuestionClosed, is RoomEvent.RoomEnded,
                        is RoomEvent.AttemptUpdated, is RoomEvent.ParticipantLeft, is RoomEvent.SettingsChanged,
                        -> refresh()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.error.code in FATAL) {
                    rooms.forgetJoinedRoom(roomId)
                    _state.update { it.copy(fatalError = e.error.message) }
                    return
                }
            } catch (_: Exception) {
                // Network failure. Reconnect below.
            }
            _state.update { it.copy(connection = Connection.RECONNECTING) }
            delay(backoff)
            val moved = try {
                rooms.followMovedHost(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (moved) {
                backoff = 1_000L
                continue
            }
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private suspend fun refresh() {
        show(rooms.state(roomId))
    }

    private suspend fun show(room: RoomState) {
        var previousRoom: RoomState? = null
        var applied = false
        _state.update { current ->
            // Ignore a snapshot older than the one on screen.
            if (current.room != null && room.revision < current.room.revision) {
                current
            } else {
                previousRoom = current.room
                applied = true
                current.copy(room = room)
            }
        }
        if (applied) {
            previousRoom?.let { prev -> playTransitionCues(prev, room) }
        }
        if (room.room.status == RoomStatus.ENDED) rooms.markJoinedRoomEnded(roomId)
        if (room.room.status == RoomStatus.ENDED && _state.value.results == null) {
            try {
                val results = rooms.results(roomId)
                _state.update { it.copy(results = results) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.error.message) }
            }
        }
    }

    private suspend fun applyJoin(event: RoomEvent.ParticipantJoined) {
        val current = _state.value.room
        if (current != null && event.revision == current.revision + 1) {
            val updated = current.copy(
                participants = mergeJoinedParticipant(current.participants, event.participant),
                revision = event.revision,
            )
            _state.update { it.copy(room = updated) }
            playTransitionCues(current, updated)
        } else if (current == null || event.revision > current.revision) {
            refresh()
        }
    }

    private fun playTransitionCues(previous: RoomState, next: RoomState) {
        lobbyTransitionCues(previous, next).forEach { cue ->
            when (cue) {
                is RoomAudioCue.Once -> audio.playSfxOnce(cue.key, cue.cue, AudioScope.MULTIPLAYER)
                is RoomAudioCue.Throttled -> audio.playSfxThrottled(cue.bucket, cue.minIntervalMillis, cue.cue, AudioScope.MULTIPLAYER)
            }
        }
    }

    private suspend fun applyAnswerCount(event: RoomEvent.AnswerRecorded) {
        val current = _state.value.room
        val live = current?.live
        if (live != null && event.revision == current.revision + 1 && live.question.number == event.questionNumber) {
            _state.update {
                it.copy(room = current.copy(live = live.copy(answeredCount = event.answeredCount), revision = event.revision))
            }
        } else if (current == null || event.revision > current.revision) {
            refresh()
        }
    }

    private companion object {
        val FATAL = setOf(ErrorCode.ROOM_NOT_FOUND, ErrorCode.NOT_A_PARTICIPANT)
    }
}

/** Replaces an existing participant by ID on rejoin instead of appending a duplicate row. */
internal fun mergeJoinedParticipant(
    participants: List<ParticipantView>,
    joined: ParticipantView,
): List<ParticipantView> =
    if (participants.any { it.id == joined.id }) {
        participants.map { if (it.id == joined.id) joined else it }
    } else {
        participants + joined
    }

internal sealed interface RoomAudioCue {
    data class Once(val key: String, val cue: Sfx) : RoomAudioCue

    data class Throttled(val bucket: String, val minIntervalMillis: Long, val cue: Sfx) : RoomAudioCue
}

internal const val JOIN_CUE_THROTTLE_MILLIS = 1_200L

/**
 * Computes sound cues triggered by a server-driven state transition from [previous] to [next].
 *
 * The initial snapshot is never passed here, so reopening an ended room or reconnecting to a closed
 * question stays silent.
 */
internal fun lobbyTransitionCues(previous: RoomState, next: RoomState): List<RoomAudioCue> {
    val roomKey = next.room.id.value
    if (previous.room.status != RoomStatus.ENDED && next.room.status == RoomStatus.ENDED) {
        return listOf(RoomAudioCue.Once("results:$roomKey", Sfx.RESULTS_FANFARE))
    }

    val cues = mutableListOf<RoomAudioCue>()
    val isHost = next.you.role == RoomRole.HOST

    if (isHost && next.room.status == RoomStatus.LOBBY) {
        val prevActivePlayers = previous.participants
            .filter { it.role == RoomRole.PLAYER && !it.left }
            .map { it.id }
            .toSet()
        val hasNewActivePlayer = next.participants.any {
            it.role == RoomRole.PLAYER && !it.left && it.id !in prevActivePlayers
        }
        if (hasNewActivePlayer) {
            cues += RoomAudioCue.Throttled("join:$roomKey", JOIN_CUE_THROTTLE_MILLIS, Sfx.PLAYER_JOIN)
        }
    }

    val startedDiscussion = previous.room.status == RoomStatus.LOBBY && next.room.status == RoomStatus.LIVE &&
        (next.room.mode == RoomMode.DISCUSSION || isHost)
    val startedAsyncAttempt = next.room.mode == RoomMode.ASYNCHRONOUS && !isHost &&
        previous.attempt?.started != true && next.attempt?.started == true
    if (startedDiscussion) {
        cues += RoomAudioCue.Once("start:$roomKey", Sfx.QUIZ_START)
    } else if (startedAsyncAttempt) {
        cues += RoomAudioCue.Once("start-attempt:$roomKey", Sfx.QUIZ_START)
    }

    val prevLive = previous.live
    val nextLive = next.live
    if (previous.room.status == RoomStatus.LIVE && next.room.status == RoomStatus.LIVE &&
        prevLive != null && nextLive != null && prevLive.question.id != nextLive.question.id
    ) {
        cues += RoomAudioCue.Once("next:$roomKey:${nextLive.question.id.value}", Sfx.NEXT)
    }

    var playedVerdict = false
    val revealedCorrect = nextLive?.correctAnswer
    val yourAnswer = nextLive?.yourAnswer
    if (!isHost &&
        next.room.revealPolicy == RevealPolicy.AFTER_EACH_QUESTION &&
        nextLive != null &&
        revealedCorrect != null &&
        yourAnswer != null
    ) {
        val newlyRevealed = prevLive == null ||
            prevLive.question.id != nextLive.question.id ||
            prevLive.correctAnswer == null
        val exactChoiceQuestion = nextLive.question is PlayerQuestion.MultipleChoice ||
            nextLive.question is PlayerQuestion.TrueFalse
        if (newlyRevealed && exactChoiceQuestion) {
            val correct = yourAnswer.answer == revealedCorrect
            cues += RoomAudioCue.Once(
                key = "verdict:$roomKey:${nextLive.question.id.value}",
                cue = if (correct) Sfx.CORRECT else Sfx.INCORRECT,
            )
            playedVerdict = true
        }
    }

    val questionJustClosed = prevLive != null &&
        nextLive != null &&
        prevLive.question.id == nextLive.question.id &&
        prevLive.isOpen &&
        !nextLive.isOpen
    if (questionJustClosed && !playedVerdict) {
        cues += RoomAudioCue.Once("timeup:$roomKey:${nextLive.question.id.value}", Sfx.TIME_UP)
    }

    return cues
}
