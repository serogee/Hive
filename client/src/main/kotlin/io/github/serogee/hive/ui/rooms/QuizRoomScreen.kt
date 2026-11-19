package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.home.JoinCard
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
    onJoinCode: (String) -> Unit,
    onOpenRoom: (roomId: String) -> Unit,
    onCreateRoom: () -> Unit,
    onSignIn: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { QuizRoomViewModel(container.rooms, container.auth) }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val joined by viewModel.joined.collectAsStateWithLifecycle()
    val hosted by viewModel.hosted.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) { if (signedIn) viewModel.refreshHosted() }

    Page("Quiz Room") {
        JoinCard(onJoinCode)

        joined?.let { membership ->
            Text("Joined room", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenRoom(membership.roomId.value) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(membership.roomTitle, style = MaterialTheme.typography.titleSmall)
                    Text("Code ${membership.roomCode} · as ${membership.displayName}")
                    Text("Tap to reconnect", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Text("Rooms you host", style = MaterialTheme.typography.titleMedium)
        if (!signedIn) {
            Text("Sign in to host online rooms. Hosting on your own phone without an account comes with local stacks.")
            OutlinedButton(onClick = onSignIn) { Text("Sign in") }
            return@Page
        }
        Button(onClick = onCreateRoom) { Text("Create room") }
        if (hosted.loading) CircularProgressIndicator()
        ErrorText(hosted.error)
        hosted.rooms.forEach { room ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenRoom(room.id.value) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(room.title, style = MaterialTheme.typography.titleSmall)
                    Text("Code ${room.code} · ${room.mode.label()} · ${room.status.label()}")
                }
            }
        }
    }
}
