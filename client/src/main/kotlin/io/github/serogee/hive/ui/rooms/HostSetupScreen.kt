package io.github.serogee.hive.ui.rooms

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.data.rooms.RoomOptions
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.GateNote
import io.github.serogee.hive.ui.common.Gated
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.NameField
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.common.watchOnline
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
    private val _state = MutableStateFlow(HostSetupUiState())
    val state: StateFlow<HostSetupUiState> = _state.asStateFlow()

    fun create(stack: StackSnapshot, options: RoomOptions, onPhone: Boolean, hostName: String) =
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            try {
                val membership = if (onPhone) {
                    rooms.createLocalRoom(stack, options, hostName)
                } else {
                    rooms.createRoom(stack, options)
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
    val viewModel = viewModel { HostSetupViewModel(container.rooms) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val preferredName by container.preferredName.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(RoomMode.DISCUSSION) }
    var admission by rememberSaveable { mutableStateOf(AdmissionPolicy.EVERYONE) }
    var onPhone by rememberSaveable { mutableStateOf(true) }
    var hostName by rememberSaveable { mutableStateOf("") }
    var useMyName by rememberSaveable { mutableStateOf(true) }
    var speedScoring by rememberSaveable { mutableStateOf(false) }
    var timeTiebreak by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var revealAfterEach by rememberSaveable { mutableStateOf(false) }
    var deadlineMinutes by rememberSaveable { mutableStateOf("") }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val draft by remember(stackId) { container.stacks.observeDraft(stackId) }.collectAsStateWithLifecycle(null)

    val gate = watchOnline().accountGate()

    LaunchedEffect(state.created) {
        val created = state.created ?: return@LaunchedEffect
        onCreated(created)
    }

    Page(
        title = "Host Setup",
        subtitle = "Configure rules, pace and lobby settings",
    ) {
        val current = draft ?: return@Page
        val stack = StackValidation.toSnapshot(current)

        HeroCard(
            badgeText = "STACK SELECTED",
            title = current.title.ifBlank { "Untitled stack" },
            subtitle = "${current.questions.size} questions included in this session",
        )

        if (stack == null) {
            SleekCard {
                Text("Fix these before hosting:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                StackValidation.validate(current).forEach { ErrorText(it.message) }
                HiveButton(
                    text = "Edit stack",
                    onClick = onEdit,
                )
            }
            return@Page
        }

        val myName = session?.account?.displayName ?: preferredName?.takeIf { it.isNotBlank() }
        SectionHeader(title = "Where to host")
        SleekCard {
            Choice(
                label = "On this phone (Wi-Fi or hotspot, no internet needed)",
                selected = onPhone,
            ) { onPhone = true }
            Gated(gate) { enabled ->
                Choice(
                    label = "Online (on the Hive server over the internet)",
                    selected = !onPhone,
                    enabled = enabled,
                ) { onPhone = false }
            }

            if (onPhone) {
                Spacer(Modifier.height(4.dp))
                NameField("Your name as Host", hostName, { hostName = it }, myName, useMyName) { useMyName = it }
            } else if (session == null && gate == null) {
                Spacer(Modifier.height(4.dp))
                Text("Sign in to host online.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                HiveButton(
                    text = "Sign in",
                    onClick = onSignIn,
                    style = HiveButtonStyle.SECONDARY,
                )
            }
        }

        SectionHeader(title = "Pace")
        SleekCard {
            RoomMode.entries.forEach { option ->
                Choice(option.label(), selected = mode == option) { mode = option }
            }
        }

        SectionHeader(title = "Who can join")
        SleekCard {
            AdmissionPolicy.entries.forEach { option ->
                val unavailable = option == AdmissionPolicy.SIGNED_IN_ONLY && (onPhone || gate != null)
                Choice(option.label(), selected = admission == option && !unavailable, enabled = !unavailable) { admission = option }
            }
            if (!onPhone && gate != null) GateNote(gate)
            if (onPhone) {
                Text(
                    "Sessions on this phone admit Players as guests. Signed-in Players only needs a way to check accounts " +
                        "without internet, which Hive does not have yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val tiebreak = timeTiebreak ?: (mode == RoomMode.DISCUSSION)
        SectionHeader(title = "Scoring & Rules")
        SleekCard {
            ToggleRow("Speed bonus for timed questions", speedScoring) { speedScoring = it }
            if (speedScoring) {
                Text(
                    "A correct answer earns up to 50% extra points, shrinking to zero as its timer runs out. Scores show one decimal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ToggleRow("Order equal scores by answer time", tiebreak) { timeTiebreak = it }
            ToggleRow("Show answer and explanation after each question", revealAfterEach) { revealAfterEach = it }
            Text(
                if (revealAfterEach) {
                    "Players see the correct answer and explanation right after each question."
                } else {
                    "Correct answers and explanations appear when the session ends."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            HiveTextField(
                value = deadlineMinutes,
                onValueChange = { deadlineMinutes = it.filter(Char::isDigit).take(4) },
                label = "Close session after (minutes)",
                placeholder = "No deadline",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                "Question time limits still apply. When both apply, the earlier one ends answering.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val name = myName?.takeIf { useMyName } ?: hostName
        val effectiveAdmission = if (onPhone && admission == AdmissionPolicy.SIGNED_IN_ONLY) AdmissionPolicy.EVERYONE else admission

        HiveButton(
            text = "Create Session",
            onClick = {
                if (onPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val deadline = deadlineMinutes.toLongOrNull()?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000 }
                val revealPolicy = if (revealAfterEach) RevealPolicy.AFTER_EACH_QUESTION else RevealPolicy.AT_END
                viewModel.create(stack, RoomOptions(mode, effectiveAdmission, speedScoring, tiebreak, deadline, revealPolicy), onPhone, name.trim())
            },
            enabled = !state.creating && (if (onPhone) name.isNotBlank() else session != null && gate == null),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.creating) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        ErrorText(state.error)
    }
}

@Composable
internal fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
