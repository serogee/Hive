package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.common.label
import io.github.serogee.hive.ui.theme.SheetShape

/** What happens to the time limits of the room's questions. */
private enum class Timers(val label: String) {
    KEEP("Keep"),
    OFF("No limit"),
    SAME("Same for all"),
}

/** Lets the Host change the room in the lobby. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSettingsSheet(room: RoomSummary, onSave: (HostCommand.UpdateSettings) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(room.title) }
    var admission by rememberSaveable { mutableStateOf(room.admission) }
    var speedScoring by rememberSaveable { mutableStateOf(room.speedScoring) }
    var timeTiebreak by rememberSaveable { mutableStateOf(room.timeTiebreak) }
    var timers by rememberSaveable { mutableStateOf(Timers.KEEP) }
    var seconds by rememberSaveable { mutableStateOf("") }
    var changeDeadline by rememberSaveable { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableStateOf("") }

    val limit = seconds.toIntOrNull()
    val limitFits = timers != Timers.SAME || limit in QuestionDraft.MIN_DURATION_SECONDS..QuestionDraft.MAX_DURATION_SECONDS

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EDIT ROOM", style = MaterialTheme.typography.headlineSmall)
            HiveTextField(value = title, onValueChange = { title = it.take(100) }, label = "Session name")

            Caption("Who can join")
            AdmissionPolicy.entries.forEach { option ->
                val available = room.acceptsAccounts || option != AdmissionPolicy.SIGNED_IN_ONLY
                Choice(option.label(), selected = admission == option, enabled = available) { admission = option }
            }

            Caption("Question timers")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Timers.entries.forEach { option ->
                    HiveSegment(option.label, selected = timers == option, onClick = { timers = option }, modifier = Modifier.weight(1f))
                }
            }
            if (timers == Timers.SAME) {
                HiveTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter(Char::isDigit).take(4) },
                    label = "Seconds for each question",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Caption("Scoring")
            ToggleRow("Speed bonus for timed questions", speedScoring) { speedScoring = it }
            ToggleRow("Order equal scores by answer time", timeTiebreak) { timeTiebreak = it }

            Caption("Deadline")
            ToggleRow("Change the deadline", changeDeadline) { changeDeadline = it }
            if (changeDeadline) {
                HiveTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                    label = "Close session after (minutes from now)",
                    placeholder = "No deadline",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            HiveButton(
                text = "Save",
                onClick = {
                    val closeAfter = minutes.toLongOrNull()?.takeIf { it > 0 }
                    onSave(
                        HostCommand.UpdateSettings(
                            title = title.trim(),
                            admission = admission,
                            speedScoring = speedScoring,
                            timeTiebreak = timeTiebreak,
                            deadlineAt = if (changeDeadline) closeAfter?.let { System.currentTimeMillis() + it * 60_000 } else room.deadlineAt,
                            questionSeconds = when (timers) {
                                Timers.KEEP -> null
                                Timers.OFF -> 0
                                Timers.SAME -> limit
                            },
                        ),
                    )
                },
                enabled = title.isNotBlank() && limitFits,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
