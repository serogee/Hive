package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
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
)

class SessionEntryViewModel(
    private val code: String,
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
            val room = rooms.lookup(code)
            _state.update { it.copy(room = room, loading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }

    fun join(displayName: String?, asGuest: Boolean) {
        val room = _state.value.room ?: return
        viewModelScope.launch {
            _state.update { it.copy(joining = true, error = null) }
            try {
                val membership = rooms.join(room, displayName, asGuest)
                _state.update { it.copy(joining = false, joined = membership) }
            } catch (e: Exception) {
                _state.update { it.copy(joining = false, error = e.userMessage()) }
            }
        }
    }
}

@Composable
fun SessionEntryScreen(code: String, onJoined: (Membership) -> Unit, onSignIn: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = code) { SessionEntryViewModel(code, container.rooms, container.auth) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val preferredName by viewModel.preferredName.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.joined) { state.joined?.let(onJoined) }

    Page("Join room $code") {
        val room = state.room
        when {
            state.loading -> CircularProgressIndicator()
            room == null -> {
                ErrorText(state.error)
                OutlinedButton(onClick = { viewModel.load() }) { Text("Try again") }
            }
            else -> {
                RoomSummaryCard(room)
                if (room.status == RoomStatus.ENDED) {
                    Text("This room has ended.")
                    return@Page
                }

                val guestsAllowed = room.admission != AdmissionPolicy.SIGNED_IN_ONLY
                val accountsAllowed = room.admission != AdmissionPolicy.GUESTS_ONLY
                if (guestsAllowed) {
                    OutlinedTextField(
                        value = name ?: preferredName.orEmpty(),
                        onValueChange = { name = it },
                        label = { Text("Your name in this room") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.join(name ?: preferredName, asGuest = true) },
                        enabled = !state.joining && !(name ?: preferredName).isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Join as guest") }
                }
                if (accountsAllowed) {
                    val signedIn = account
                    if (signedIn != null) {
                        Button(
                            onClick = { viewModel.join(null, asGuest = false) },
                            enabled = !state.joining,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Join as ${signedIn.displayName}") }
                    } else {
                        OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text(if (guestsAllowed) "Sign in to join with your account" else "Sign in to join")
                        }
                    }
                }
                if (state.joining) CircularProgressIndicator()
                ErrorText(state.error)
            }
        }
    }
}

@Composable
fun RoomSummaryCard(room: RoomSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(room.title, style = MaterialTheme.typography.titleLarge)
            Text("Host: ${room.hostName}")
            Text("${room.mode.label()} · ${room.questionCount} questions · ${room.status.label()}")
            Text("Who can join: ${room.admission.label()}")
        }
    }
}
