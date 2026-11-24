package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
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
    Page(
        title = room?.room?.title ?: "Quiz Room",
        subtitle = room?.let { "${it.room.mode.label()} · ${it.room.questionCount} questions" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill(
                text = state.connection.label(),
                selected = state.connection == Connection.LIVE,
            )
            room?.let {
                TagPill(
                    text = it.room.status.label(),
                    selected = false,
                )
            }
        }
        state.fatalError?.let {
            ErrorText(it)
            return@Page
        }
        if (room == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            return@Page
        }

        val isHost = room.you.role == RoomRole.HOST
        when (room.room.status) {
            RoomStatus.LOBBY -> LobbyContent(room, isHost, busy = state.busy, onStart = { viewModel.command(HostCommand.Start) })
            RoomStatus.LIVE -> {
                val live = room.live
                when {
                    room.room.mode == RoomMode.ASYNCHRONOUS && isHost ->
                        HostAsyncContent(room, state.busy, onCommand = viewModel::command)
                    room.room.mode == RoomMode.ASYNCHRONOUS -> PlayerAsyncContent(
                        room = room,
                        pending = state.pending,
                        busy = state.busy,
                        onAttempt = viewModel::attempt,
                        onSubmit = { answer -> live?.let { viewModel.submit(it.question.id, answer) } },
                        onRetry = viewModel::retrySubmit,
                        onTimeUp = { viewModel.refreshNow() },
                    )
                    live == null -> Text("Waiting for the first question.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    isHost -> HostLiveContent(live, state.busy, onCommand = viewModel::command, onTimeUp = { viewModel.refreshNow() })
                    else -> PlayerLiveContent(
                        live = live,
                        pending = state.pending,
                        onSubmit = { viewModel.submit(live.question.id, it) },
                        onRetry = viewModel::retrySubmit,
                        onTimeUp = { viewModel.refreshNow() },
                    )
                }
            }
            RoomStatus.ENDED -> {
                val results = state.results
                if (results == null) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) else ResultsContent(results, isHost)
            }
        }
        ErrorText(state.error)

        if (!isHost && room.room.status != RoomStatus.ENDED) {
            HiveButton(
                text = "Leave room",
                onClick = { confirmLeave = true },
                modifier = Modifier.fillMaxWidth(),
                style = HiveButtonStyle.DANGER,
            )
        }
    }

    if (confirmLeave) {
        HiveDialog(
            title = "Leave this room?",
            message = "You can rejoin with the room code, but this device will forget your place.",
            confirmText = "Leave",
            onConfirm = {
                confirmLeave = false
                scope.launch {
                    container.rooms.leaveJoinedRoom()
                    onLeft()
                }
            },
            dismissText = "Stay",
            onDismiss = { confirmLeave = false },
            danger = true,
        )
    }
}

@Composable
private fun LobbyContent(room: RoomState, isHost: Boolean, busy: Boolean, onStart: () -> Unit) {
    if (isHost) {
        HeroCard(
            badgeText = "ROOM CODE",
            title = room.room.code,
            subtitle = "Players enter this code in Quiz Room · Admission: ${room.room.admission.label()}",
        ) {
            if (!room.room.acceptsAccounts) {
                val addresses = LocalAppContainer.current.rooms.playerAddresses()
                Spacer(Modifier.height(4.dp))
                Text(
                    "Players on this phone's Wi-Fi or hotspot also enter this address:",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (addresses.isEmpty()) {
                    Text(
                        "Connect to Wi-Fi or turn on the hotspot to get an address.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                addresses.forEach {
                    Text(
                        it.removePrefix("http://"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    } else {
        SleekCard {
            Text(
                "You joined as ${room.you.displayName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Waiting for ${room.room.hostName} to start the quiz...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (isHost) {
        val players = room.participants.count { it.role == RoomRole.PLAYER }
        val start = if (room.room.mode == RoomMode.ASYNCHRONOUS) "Open room" else "Start quiz"
        HiveButton(
            text = if (players > 0) start else "Waiting for Players...",
            onClick = onStart,
            enabled = !busy && players > 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionHeader(title = "In this room (${room.participants.size})")

    room.participants.forEach { participant ->
        val you = if (participant.id == room.you.id) " (you)" else ""
        val roleLabel = when {
            participant.left -> "Left"
            participant.role == RoomRole.HOST -> "Host"
            participant.isGuest -> "Guest"
            else -> "Account"
        }
        val isMe = participant.id == room.you.id

        SleekCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    participant.displayName + you,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TagPill(
                    text = roleLabel,
                    selected = participant.role == RoomRole.HOST,
                )
            }
        }
    }
}

private fun Connection.label() = when (this) {
    Connection.CONNECTING -> "Connecting…"
    Connection.LIVE -> "Connected"
    Connection.RECONNECTING -> "Reconnecting…"
}
