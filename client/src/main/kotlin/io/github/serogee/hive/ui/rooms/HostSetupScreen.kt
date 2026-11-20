package io.github.serogee.hive.ui.rooms

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.localhost.HostingService
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostSetupUiState(
    val creating: Boolean = false,
    val error: String? = null,
    val created: Membership? = null,
)

class HostSetupViewModel(private val rooms: RoomRepository) : ViewModel() {
    // Snapshot taken when the room is created. Later edits to the stack leave the room unchanged.
    private val _state = MutableStateFlow(HostSetupUiState())
    val state: StateFlow<HostSetupUiState> = _state.asStateFlow()

    fun create(stack: StackSnapshot, mode: RoomMode, admission: AdmissionPolicy, onPhone: Boolean, hostName: String) =
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            try {
                val membership = if (onPhone) {
                    rooms.createLocalRoom(stack, mode, admission, hostName)
                } else {
                    rooms.createRoom(stack, mode, admission)
                }
                _state.update { it.copy(creating = false, created = membership) }
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, error = e.userMessage()) }
            }
        }
}

@Composable
fun HostSetupScreen(stackId: StackId, onEdit: () -> Unit, onSignIn: () -> Unit, onCreated: (Membership) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel { HostSetupViewModel(container.rooms) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by container.auth.session.collectAsStateWithLifecycle(null)
    val preferredName by container.rooms.preferredName.collectAsStateWithLifecycle(null)
    var mode by rememberSaveable { mutableStateOf(RoomMode.DISCUSSION) }
    var admission by rememberSaveable { mutableStateOf(AdmissionPolicy.EVERYONE) }
    var onPhone by rememberSaveable { mutableStateOf(true) }
    var hostName by rememberSaveable { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val draft by remember(stackId) { container.stacks.observeDraft(stackId) }.collectAsStateWithLifecycle(null)

    LaunchedEffect(state.created) {
        val created = state.created ?: return@LaunchedEffect
        if (onPhone) HostingService.start(context)
        onCreated(created)
    }

    Page("Host Setup") {
        val current = draft ?: return@Page
        val stack = StackValidation.toSnapshot(current)
        Text("Stack", style = MaterialTheme.typography.titleMedium)
        Text("${current.title.ifBlank { "Untitled stack" }} · ${current.questions.size} questions")
        if (stack == null) {
            Text("Fix these before hosting:", style = MaterialTheme.typography.titleSmall)
            StackValidation.validate(current).forEach { ErrorText(it.message) }
            Button(onClick = onEdit) { Text("Edit stack") }
            return@Page
        }

        Text("Where to host", style = MaterialTheme.typography.titleMedium)
        Choice("On this phone. Players join over Wi-Fi or this phone's hotspot, with no internet.", selected = onPhone) {
            onPhone = true
        }
        Choice("Online, on the Hive server", selected = !onPhone) { onPhone = false }

        val name = hostName ?: session?.account?.displayName ?: preferredName.orEmpty()
        if (onPhone) {
            OutlinedTextField(
                value = name,
                onValueChange = { hostName = it.take(30) },
                label = { Text("Your name as Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (session == null) {
            Text("Sign in to host online.")
            OutlinedButton(onClick = onSignIn) { Text("Sign in") }
        }

        Text("Pace", style = MaterialTheme.typography.titleMedium)
        RoomMode.entries.forEach { option ->
            Choice(option.label(), selected = mode == option) { mode = option }
        }

        Text("Who can join", style = MaterialTheme.typography.titleMedium)
        AdmissionPolicy.entries.forEach { option ->
            val unavailable = onPhone && option == AdmissionPolicy.SIGNED_IN_ONLY
            Choice(option.label(), selected = admission == option && !unavailable, enabled = !unavailable) { admission = option }
        }
        if (onPhone) {
            Text(
                "Rooms on this phone admit Players as guests. Signed-in Players only needs a way to check accounts " +
                    "without internet, which Hive does not have yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val effectiveAdmission = if (onPhone && admission == AdmissionPolicy.SIGNED_IN_ONLY) AdmissionPolicy.EVERYONE else admission
        Button(
            onClick = {
                if (onPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.create(stack, mode, effectiveAdmission, onPhone, name.trim())
            },
            enabled = !state.creating && (if (onPhone) name.isNotBlank() else session != null),
        ) { Text("Create room") }
        if (state.creating) CircularProgressIndicator()
        ErrorText(state.error)
    }
}
@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}
