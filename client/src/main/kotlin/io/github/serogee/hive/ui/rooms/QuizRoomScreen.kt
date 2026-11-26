package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.Gated
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.common.watchOnline
import io.github.serogee.hive.ui.home.JoinCard
import io.github.serogee.hive.ui.home.JoinedRoomCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostedRoomsState(
    val rooms: List<RoomSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class QuizRoomViewModel(private val rooms: RoomRepository, auth: AuthRepository) : ViewModel() {
    val signedIn: StateFlow<Boolean> = auth.session.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val joined: StateFlow<SavedMembership?> = rooms.joinedRoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _hosted = MutableStateFlow(HostedRoomsState())
    val hosted: StateFlow<HostedRoomsState> = _hosted.asStateFlow()

    fun refreshHosted() = viewModelScope.launch {
        _hosted.update { it.copy(loading = true, error = null) }
        try {
            val list = rooms.hostedRooms()
            _hosted.update { HostedRoomsState(rooms = list) }
        } catch (e: Exception) {
            _hosted.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }
}

@Composable
fun QuizRoomScreen(
    onJoinCode: (code: String, hostAddress: String?) -> Unit,
    onOpenRoom: (roomId: String) -> Unit,
    onCreateRoom: () -> Unit,
    onStackCreated: (StackId) -> Unit,
    onSignIn: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val viewModel = viewModel { QuizRoomViewModel(container.rooms, container.auth) }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val joined by viewModel.joined.collectAsStateWithLifecycle()
    val hosted by viewModel.hosted.collectAsStateWithLifecycle()
    val localRooms by container.hostedRooms.collectAsStateWithLifecycle()

    val gate = watchOnline().accountGate()

    LaunchedEffect(signedIn, gate == null) { if (signedIn && gate == null) viewModel.refreshHosted() }

    Page(
        title = "Sessions",
        subtitle = "Multiplayer quiz lobbies and live sessions",
    ) {
        JoinCard(
            onJoinCode = onJoinCode,
            onCreateStack = { scope.launch { onStackCreated(container.stacks.create()) } },
            onHostQuiz = onCreateRoom,
        )

        joined?.let { membership ->
            SectionHeader(title = "Active Session")
            JoinedRoomCard(membership, "Joined session", "Reconnect") { onOpenRoom(membership.roomId.value) }
        }

        // Rooms that ended are not listed.
        val openRooms = localRooms.orEmpty().filter { it.status != RoomStatus.ENDED }
        if (openRooms.isNotEmpty()) {
            SectionHeader(title = "Hosted on this phone")
            openRooms.forEach { room -> HostedRoomCard(room, onClick = { onOpenRoom(room.id.value) }) }
        }

        SectionHeader(title = "Online Sessions")
        if (!signedIn) {
            Gated(gate) { enabled ->
                SleekCard {
                    Text(
                        "Sign in to host and join online sessions over the internet. Nearby phone sessions require no account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HiveButton(
                        text = "Sign in",
                        onClick = onSignIn,
                        enabled = enabled,
                        style = HiveButtonStyle.SECONDARY,
                    )
                }
            }
            return@Page
        }
        Gated(gate) { enabled ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hosted.loading && enabled) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                if (enabled) ErrorText(hosted.error)
                if (!hosted.loading && hosted.rooms.isEmpty() && hosted.error == null) {
                    Text("No online sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                hosted.rooms.forEach { room ->
                    HostedRoomCard(room, onClick = if (enabled) ({ onOpenRoom(room.id.value) }) else null)
                }
            }
        }
    }
}

@Composable
private fun HostedRoomCard(room: RoomSummary, onClick: (() -> Unit)?) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CountBadge(
                topText = room.code.take(4),
                bottomText = "ROOM",
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    room.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Code ${room.code}\n${room.mode.label()}, ${room.status.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
