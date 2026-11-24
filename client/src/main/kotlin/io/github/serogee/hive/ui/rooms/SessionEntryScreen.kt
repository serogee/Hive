package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.theme.ElectricBlue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionEntryUiState(
    val room: RoomSummary? = null,
    val loading: Boolean = true,
    val joining: Boolean = false,
    val error: String? = null,
    val joined: Membership? = null,
    /** Asks whether to leave the current room. Holds the question to show. */
    val confirmLeave: String? = null,
)

class SessionEntryViewModel(
    private val code: String,
    /** The Host phone's address, or null for the configured server. */
    private val hostAddress: String?,
    private val rooms: RoomRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionEntryUiState())
    val state: StateFlow<SessionEntryUiState> = _state.asStateFlow()
    val account: StateFlow<Account?> = auth.session.map { it?.account }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val preferredName = rooms.preferredName.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val room = rooms.lookup(code, hostAddress)
            _state.update { it.copy(room = room, loading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }

    private var pendingJoin: Pair<String?, Boolean>? = null

    /**
     * Joins the room. A Player is in one room at a time, so when this device or account is already in
     * another room, the Player first confirms leaving it.
     */
    fun join(displayName: String?, asGuest: Boolean, leaveOtherRoom: Boolean = false) {
        val room = _state.value.room ?: return
        viewModelScope.launch {
            _state.update { it.copy(joining = true, error = null, confirmLeave = null) }
            if (!leaveOtherRoom) {
                rooms.otherJoinedRoom(room.id)?.let { other ->
                    pendingJoin = displayName to asGuest
                    _state.update { it.copy(joining = false, confirmLeave = "You are in ${other.roomTitle}. Leave it to join ${room.title}?") }
                    return@launch
                }
            }
            try {
                val membership = rooms.join(room, displayName, asGuest, hostAddress, leaveOtherRoom)
                _state.update { it.copy(joining = false, joined = membership) }
            } catch (e: ApiException) {
                if (e.error.code == ErrorCode.ALREADY_IN_ROOM) {
                    pendingJoin = displayName to asGuest
                    _state.update { it.copy(joining = false, confirmLeave = e.error.message) }
                } else {
                    _state.update { it.copy(joining = false, error = e.error.message) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(joining = false, error = e.userMessage()) }
            }
        }
    }

    fun confirmLeave() {
        val (name, asGuest) = pendingJoin ?: return
        join(name, asGuest, leaveOtherRoom = true)
    }

    fun cancelLeave() = _state.update { it.copy(confirmLeave = null) }
}

@Composable
fun SessionEntryScreen(code: String, hostAddress: String?, onJoined: (Membership) -> Unit, onSignIn: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "$code@$hostAddress") {
        SessionEntryViewModel(code, hostAddress, container.rooms, container.auth)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val preferredName by viewModel.preferredName.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.joined) { state.joined?.let(onJoined) }

    state.confirmLeave?.let { question ->
        AlertDialog(
            onDismissRequest = viewModel::cancelLeave,
            title = { Text("Leave your current room?", fontWeight = FontWeight.Bold) },
            text = { Text(question) },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) { Text("Leave and join") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelLeave) { Text("Stay") } },
        )
    }

    Page(
        title = "Join Room",
        subtitle = "Code $code",
    ) {
        val room = state.room
        when {
            state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            room == null -> {
                ErrorText(state.error)
                Button(
                    onClick = { viewModel.load() },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) { Text("Try again") }
            }
            else -> {
                RoomSummaryCard(room)
                if (room.status == RoomStatus.ENDED) {
                    Text("This room has ended.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    return@Page
                }

                val guestsAllowed = room.admission != AdmissionPolicy.SIGNED_IN_ONLY
                val accountsAllowed = room.admission != AdmissionPolicy.GUESTS_ONLY && room.acceptsAccounts
                if (!room.acceptsAccounts) {
                    Text("This room is hosted on a phone, so everyone joins as a guest.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }

                if (guestsAllowed) {
                    SleekCard {
                        Text("JOIN AS GUEST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        OutlinedTextField(
                            value = name ?: preferredName.orEmpty(),
                            onValueChange = { name = it },
                            label = { Text("Your name in this room") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.join(name ?: preferredName, asGuest = true) },
                            enabled = !state.joining && !(name ?: preferredName).isNullOrBlank(),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricBlue,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) { Text("Join as guest", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    }
                }

                if (accountsAllowed) {
                    val signedIn = account
                    if (signedIn != null) {
                        Button(
                            onClick = { viewModel.join(null, asGuest = false) },
                            enabled = !state.joining,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) { Text("Join as ${signedIn.displayName}", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    } else {
                        OutlinedButton(
                            onClick = onSignIn,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (guestsAllowed) "Sign in to join with your account" else "Sign in to join", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (state.joining) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                ErrorText(state.error)
            }
        }
    }
}

@Composable
fun RoomSummaryCard(room: RoomSummary) {
    HeroNavyCard(
        badgeText = "ROOM FOUND",
        title = room.title,
        subtitle = "Host: ${room.hostName} · ${room.questionCount} questions",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${room.mode.label()} · ${room.status.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Text(
                "Admission: ${room.admission.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = ElectricBlue,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
