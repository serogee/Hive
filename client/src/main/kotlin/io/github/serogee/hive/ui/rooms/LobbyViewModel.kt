package io.github.serogee.hive.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomId
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
class LobbyViewModel(private val roomId: RoomId, private val rooms: RoomRepository) : ViewModel() {
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
        val pending = if (previous != null && previous.questionId == questionId && previous.answer == answer) {
            previous.copy(failed = false)
        } else {
            PendingAnswer(questionId, answer, UUID.randomUUID().toString())
        }
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
        _state.update { current ->
            // Ignore a snapshot older than the one on screen.
            if (current.room != null && room.revision < current.room.revision) current else current.copy(room = room)
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
            _state.update {
                it.copy(room = current.copy(participants = current.participants + event.participant, revision = event.revision))
            }
        } else if (current == null || event.revision > current.revision) {
            refresh()
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
