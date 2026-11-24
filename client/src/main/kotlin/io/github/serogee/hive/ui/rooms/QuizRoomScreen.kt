package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.localhost.HostingService
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.home.JoinCard
import io.github.serogee.hive.ui.theme.DangerRed
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = viewModel { QuizRoomViewModel(container.rooms, container.auth) }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val joined by viewModel.joined.collectAsStateWithLifecycle()
    val hosted by viewModel.hosted.collectAsStateWithLifecycle()
    val localRooms by container.rooms.localHostedRooms.collectAsStateWithLifecycle(emptyList())
    val hostingHere by container.rooms.localHostRunning.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) { if (signedIn) viewModel.refreshHosted() }

    Page(
        title = "Quiz Room",
        subtitle = "Multiplayer quiz lobbies & live sessions",
    ) {
        JoinCard(
            onJoinCode = onJoinCode,
            onCreateStack = { scope.launch { onStackCreated(container.stacks.create()) } },
            onHostQuiz = onCreateRoom,
        )

        joined?.let { membership ->
            SectionHeader(title = "Active Room")
            HeroNavyCard(
                badgeText = "JOINED ROOM",
                title = membership.roomTitle,
                subtitle = "Code ${membership.roomCode} · as ${membership.displayName}",
                buttonText = "RECONNECT",
                onButtonClick = { onOpenRoom(membership.roomId.value) },
            )
        }

        SectionHeader(
            title = "Host a Quiz",
            actionText = "New Room",
            onActionClick = onCreateRoom,
        )

        Button(
            onClick = onCreateRoom,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricBlue,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Create Room", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        if (localRooms.isNotEmpty()) {
            SectionHeader(title = "Hosted on this phone")
            localRooms.forEach { room -> HostedRoomCard(room, onClick = { onOpenRoom(room.id.value) }) }
            if (hostingHere) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.rooms.stopLocalHosting()
                            HostingService.stop(context)
                        }
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.5.dp, DangerRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop hosting on this phone", fontWeight = FontWeight.Bold)
                }
            }
        }

        SectionHeader(title = "Online Rooms")
        if (!signedIn) {
            SleekCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sign in to host and join online rooms over the internet. Nearby phone rooms require no account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                    Button(
                        onClick = onSignIn,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoftBlueContainer,
                            contentColor = ElectricBlue,
                        ),
                    ) {
                        Text("Sign in", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@Page
        }
        if (hosted.loading) CircularProgressIndicator(color = ElectricBlue)
        ErrorText(hosted.error)
        if (!hosted.loading && hosted.rooms.isEmpty() && hosted.error == null) {
            Text("No online rooms yet.", color = MutedSlate, style = MaterialTheme.typography.bodyMedium)
        }
        hosted.rooms.forEach { room -> HostedRoomCard(room, onClick = { onOpenRoom(room.id.value) }) }
    }
}

@Composable
private fun HostedRoomCard(room: RoomSummary, onClick: () -> Unit) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BlueBadge(
                topText = room.code.take(4),
                bottomText = "ROOM",
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    room.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Code ${room.code} · ${room.mode.label()} · ${room.status.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }
        }
    }
}
