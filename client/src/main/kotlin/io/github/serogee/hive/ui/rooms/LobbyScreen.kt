package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.label
import kotlinx.coroutines.launch

/** One room from lobby to results, for the Host or a Player. */
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
            room?.let { Text("${it.room.mode.label()} · ${it.room.questionCount} questions · ${it.room.status.label()}") }
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
        when (room.room.status) {
            RoomStatus.LOBBY -> LobbyContent(room, isHost, busy = state.busy, onStart = { viewModel.command(HostCommand.Start) })
            RoomStatus.LIVE -> {
                val live = room.live
                if (live == null) {
                    Text("This room is open. Self-paced rooms arrive in a later update.")
                } else if (isHost) {
                    HostLiveContent(live, state.busy, onCommand = viewModel::command)
                } else {
                    PlayerLiveContent(
                        live = live,
                        pending = state.pending,
                        onSubmit = { viewModel.submit(live.question.id, it) },
                        onRetry = viewModel::retrySubmit,
                    )
                }
            }
            RoomStatus.ENDED -> {
                val results = state.results
                if (results == null) CircularProgressIndicator() else ResultsContent(results, isHost)
            }
        }
        ErrorText(state.error)

        if (!isHost && room.room.status != RoomStatus.ENDED) {
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

@Composable
private fun LobbyContent(room: RoomState, isHost: Boolean, busy: Boolean, onStart: () -> Unit) {
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

    if (isHost) {
        val players = room.participants.count { it.role == RoomRole.PLAYER }
        Button(onClick = onStart, enabled = !busy && players > 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (players > 0) "Start quiz" else "Waiting for Players")
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
}

private fun Connection.label() = when (this) {
    Connection.CONNECTING -> "Connecting…"
    Connection.LIVE -> "Connected"
    Connection.RECONNECTING -> "Reconnecting…"
}
