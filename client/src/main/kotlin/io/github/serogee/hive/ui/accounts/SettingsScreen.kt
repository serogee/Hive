package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.data.server.OnlineFacts
import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.GateNote
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveIconButton
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.common.watchOnline
import io.github.serogee.hive.ui.theme.AnswerColorChoice
import io.github.serogee.hive.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The look of the app, the server, updates, and the data on this phone. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val serverUrl = container.preferences.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val theme = container.preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePreference.SYSTEM)
    val answerColors = container.preferences.answerColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnswerColorChoice.APP_HONEY)
    val answerInput = container.preferences.answerInput.stateIn(viewModelScope, SharingStarted.Eagerly, AnswerInput.CONFIRM)

    private val _serverStatus = MutableStateFlow<String?>(null)
    val serverStatus: StateFlow<String?> = _serverStatus.asStateFlow()

    fun setTheme(theme: ThemePreference) = viewModelScope.launch { container.preferences.setTheme(theme) }

    fun setAnswerColors(choice: AnswerColorChoice) = viewModelScope.launch { container.preferences.setAnswerColors(choice) }

    fun setAnswerInput(input: AnswerInput) = viewModelScope.launch { container.preferences.setAnswerInput(input) }

    /** Saves the address only when a Hive server answers there. */
    fun saveServer(rawUrl: String) = viewModelScope.launch {
        val url = rawUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        _serverStatus.value = "Checking $url…"
        try {
            val health = container.api.health(url)
            if (url != serverUrl.value) {
                // Sessions belong to one server, so end the old one there first.
                container.signOut()
                container.preferences.setServerUrl(url)
            }
            _serverStatus.value = "Connected to $url (API v${health.apiVersion})"
        } catch (e: Exception) {
            _serverStatus.value = "No Hive server at $url. ${e.userMessage()}"
        }
    }

    /** Leaves the joined room on its server too, so that the Host sees the Player leave. */
    fun resetPhone() = viewModelScope.launch { container.rooms.leaveJoinedRoom() }
}

/** Settings, opened from the gear button in Account. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { SettingsViewModel(container) }
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val answerColors by viewModel.answerColors.collectAsStateWithLifecycle()
    val answerInput by viewModel.answerInput.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val online = watchOnline()

    var showAnswerColors by rememberSaveable { mutableStateOf(false) }
    var showAnswerInput by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }

    Page(
        title = "Settings",
        leading = { HiveIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
    ) {
        Caption("Look")
        SleekCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Auto" to ThemePreference.SYSTEM,
                        "Light" to ThemePreference.SOFT_LIGHT,
                        "Dark" to ThemePreference.SOFT_DARK,
                    ).forEach { (label, choice) ->
                        HiveSegment(label, selected = theme == choice, onClick = { viewModel.setTheme(choice) }, modifier = Modifier.weight(1f))
                    }
                }
                SettingsNote("Dark is pure black, which saves battery on AMOLED screens.")
            }
        }
        SleekCard {
            Column {
                SettingsRow("Answer colours", answerColors.label) { showAnswerColors = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Answering", answerInput.label) { showAnswerInput = true }
            }
        }

        Caption("Server")
        SleekCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServerStatusLine(online, onRetry = container.server::retry)
                var address by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
                HiveTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = "Server address",
                    placeholder = "http://10.0.2.2:8080",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                HiveButton(text = "Test and save", onClick = { viewModel.saveServer(address) }, modifier = Modifier.fillMaxWidth())
                serverStatus?.let { SettingsNote(it) }
            }
        }

        Caption("Updates")
        SleekCard { UpdatesSection() }

        Caption("This phone")
        SleekCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { showReset = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Reset data on this phone",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showAnswerColors) {
        AnswerColorsSheet(selected = answerColors, onSelect = viewModel::setAnswerColors, onDismiss = { showAnswerColors = false })
    }
    if (showAnswerInput) {
        AnswerInputSheet(selected = answerInput, onSelect = viewModel::setAnswerInput, onDismiss = { showAnswerInput = false })
    }
    if (showReset) {
        HiveDialog(
            title = "Reset data on this phone",
            message = "This leaves the session you joined and forgets it on this phone. Your stacks, reviews, hosted sessions, " +
                "name, sign-in, and settings stay.",
            confirmText = "Reset",
            onConfirm = {
                viewModel.resetPhone()
                showReset = false
            },
            dismissText = "Cancel",
            onDismiss = { showReset = false },
            danger = true,
        )
    }
}

/** A row that opens a sheet, with its current [value]. */
@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Says whether the server can be used. Signed-in users see a lost connection in its own look. */
@Composable
internal fun ServerStatusLine(online: OnlineFacts, onRetry: () -> Unit) {
    val state = online.server.state
    val label = when (state) {
        ServerState.ONLINE -> "Server: online"
        ServerState.UNREACHABLE -> "Server: down"
        ServerState.NOT_CONFIGURED -> "Server: not set up"
        ServerState.CHECKING -> "Server: checking"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (state == ServerState.ONLINE) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == ServerState.UNREACHABLE) HiveTextButton("Retry", onClick = onRetry)
        }
        if (online.signedIn) online.serverGate()?.let { GateNote(it) }
    }
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
