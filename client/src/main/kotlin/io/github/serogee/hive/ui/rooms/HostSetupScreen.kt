package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.rooms.RoomRepository
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
    private val _state = MutableStateFlow(HostSetupUiState())
    val state: StateFlow<HostSetupUiState> = _state.asStateFlow()

    fun create(mode: RoomMode, admission: AdmissionPolicy) = viewModelScope.launch {
        _state.update { it.copy(creating = true, error = null) }
        try {
            val membership = rooms.createRoom(mode, admission)
            _state.update { it.copy(creating = false, created = membership) }
        } catch (e: Exception) {
            _state.update { it.copy(creating = false, error = e.userMessage()) }
        }
    }
}

@Composable
fun HostSetupScreen(onCreated: (Membership) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HostSetupViewModel(container.rooms) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(RoomMode.DISCUSSION) }
    var admission by rememberSaveable { mutableStateOf(AdmissionPolicy.EVERYONE) }

    LaunchedEffect(state.created) { state.created?.let(onCreated) }

    Page("Host Setup") {
        val stack = SampleStacks.networkingFundamentals
        Text("Stack", style = MaterialTheme.typography.titleMedium)
        Text("${stack.title} (sample) · ${stack.questions.size} questions")
        Text("Hosting: Online, on the configured server")

        Text("Pace", style = MaterialTheme.typography.titleMedium)
        RoomMode.entries.forEach { option ->
            Choice(option.label(), selected = mode == option) { mode = option }
        }

        Text("Who can join", style = MaterialTheme.typography.titleMedium)
        AdmissionPolicy.entries.forEach { option ->
            Choice(option.label(), selected = admission == option) { admission = option }
        }

        Button(onClick = { viewModel.create(mode, admission) }, enabled = !state.creating) { Text("Create room") }
        if (state.creating) CircularProgressIndicator()
        ErrorText(state.error)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
