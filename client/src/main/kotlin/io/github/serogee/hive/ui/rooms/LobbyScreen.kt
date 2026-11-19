package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Connection { CONNECTING, LIVE, RECONNECTING }

data class LobbyUiState(
    val room: RoomState? = null,
    val connection: Connection = Connection.CONNECTING,
    /** A failure that reconnecting cannot fix, such as a deleted room. */
    val fatalError: String? = null,
)

/**
 * Keeps one participant's view of a room current.
 *
 * Each connection starts with a snapshot, so a missed event is repaired on reconnect.
 */
class LobbyViewModel(private val roomId: RoomId, private val rooms: RoomRepository) : ViewModel() {
    private val _state = MutableStateFlow(LobbyUiState())
    val state: StateFlow<LobbyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { follow() }
    }

    private suspend fun follow() {
        val token = rooms.participantToken(roomId)
        var backoff = 1_000L
        while (true) {
            try {
                // Fails fast with an ApiException when the room is gone or the credential is unknown.
                refresh(token)
                rooms.events(roomId, token).collect { event ->
                    when (event) {
                        is RoomEvent.Subscribed -> {
                            refresh(token)
                            backoff = 1_000L
                            _state.update { it.copy(connection = Connection.LIVE) }
                        }
                        is RoomEvent.ParticipantJoined -> apply(event, token)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.error.code in FATAL) {
                    _state.update { it.copy(fatalError = e.error.message) }
                    return
                }
            } catch (_: Exception) {
                // Network failure. Reconnect below.
            }
            _state.update { it.copy(connection = Connection.RECONNECTING) }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private suspend fun refresh(token: String?) {
        val snapshot = rooms.state(roomId, token)
        _state.update { it.copy(room = snapshot) }
    }

    private suspend fun apply(event: RoomEvent.ParticipantJoined, token: String?) {
        val current = _state.value.room
        if (current != null && event.revision == current.revision + 1) {
            _state.update {
                it.copy(room = current.copy(participants = current.participants + event.participant, revision = event.revision))
            }
        } else if (current == null || event.revision > current.revision) {
            refresh(token)
        }
    }

    private companion object {
        val FATAL = setOf(ErrorCode.ROOM_NOT_FOUND, ErrorCode.NOT_A_PARTICIPANT)
    }
}

@Composable
fun LobbyScreen(roomId: String, onLeft: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = roomId) { LobbyViewModel(RoomId(roomId), container.rooms) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmLeave by remember { mutableStateOf(false) }

    val room = state.room
    Page(room?.room?.title ?: "Room") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(state.connection.label()) })
            room?.let { Text("${it.room.mode.label()} · ${it.room.questionCount} questions") }
        }
        state.fatalError?.let {
            ErrorText(it)
            return@Page
        }
        if (room == null) {
            CircularProgressIndicator()
            return@Page
        }

        val isHost = room.you.role == RoomRole.HOST
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isHost) {
                    Text("Room code", style = MaterialTheme.typography.labelLarge)
                    Text(room.room.code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text("Players enter this code in Quiz Room. Who can join: ${room.room.admission.label()}")
                } else {
                    Text("You joined as ${room.you.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for ${room.room.hostName} to start.")
                }
            }
        }

        Text("In this room (${room.participants.size})", style = MaterialTheme.typography.titleMedium)
        room.participants.forEach { participant ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val you = if (participant.id == room.you.id) " (you)" else ""
                Text(participant.displayName + you)
                Text(
                    when {
                        participant.role == RoomRole.HOST -> "Host"
                        participant.isGuest -> "Guest"
                        else -> "Account"
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider()
        }

        if (!isHost) {
            OutlinedButton(onClick = { confirmLeave = true }) { Text("Leave room") }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this room?") },
            text = { Text("You can rejoin with the room code, but this device will forget your place.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        container.rooms.leaveJoinedRoom()
                        onLeft()
                    }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

private fun Connection.label() = when (this) {
    Connection.CONNECTING -> "Connecting…"
    Connection.LIVE -> "Connected"
    Connection.RECONNECTING -> "Reconnecting…"
}
