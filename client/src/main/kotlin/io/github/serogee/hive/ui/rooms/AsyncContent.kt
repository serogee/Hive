package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.model.Answer
import java.text.DateFormat
import java.util.Date

/** Asynchronous Progress: the Host sees where each Player is and can close the room. */
@Composable
fun HostAsyncContent(room: RoomState, busy: Boolean, onCommand: (HostCommand) -> Unit) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val questions = room.room.questionCount

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Room code ${room.room.code}", style = MaterialTheme.typography.titleMedium)
            Text("Players start and move through the $questions questions at their own pace.")
            room.room.deadlineAt?.let { Text("Closes at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}") }
        }
    }
    Text("Asynchronous Progress", style = MaterialTheme.typography.titleMedium)
    if (room.progress.isEmpty()) Text("No Players have joined yet.")
    room.progress.forEach { player ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(player.displayName)
            Text(
                when {
                    player.finished -> "Finished · ${player.answeredCount} answered"
                    player.currentQuestion == null -> "Not started"
                    else -> "Question ${player.currentQuestion} of $questions · ${player.answeredCount} answered"
                },
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider()
    }
    Button(onClick = { confirmEnd = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("End room and show results")
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the room for everyone?") },
            text = { Text("Players who have not finished can no longer answer. Answers already received are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    onCommand(HostCommand.EndRoom)
                }) { Text("End for everyone") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep open") } },
        )
    }
}

/** A Player's own attempt in an Asynchronous room. */
@Composable
fun PlayerAsyncContent(
    room: RoomState,
    pending: PendingAnswer?,
    busy: Boolean,
    onAttempt: (PlayerCommand) -> Unit,
    onSubmit: (Answer) -> Unit,
    onRetry: () -> Unit,
    onTimeUp: () -> Unit,
) {
    val attempt = room.attempt
    val live = room.live
    when {
        attempt == null || !attempt.started -> {
            Text("Answer the ${room.room.questionCount} questions at your own pace. A timed question's clock starts when you open it.")
            Button(onClick = { onAttempt(PlayerCommand.StartAttempt) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Start")
            }
        }
        attempt.finished || live == null -> {
            Text("You finished. You answered ${attempt.answeredCount} of ${room.room.questionCount} questions.", style = MaterialTheme.typography.titleMedium)
            Text("Results appear when the Host ends the room or the deadline passes.")
        }
        else -> {
            PlayerLiveContent(live, pending, onSubmit, onRetry, onTimeUp, hostPaced = false)
            val last = live.question.number == live.questionCount
            val label = if (last) "Finish" else "Next question"
            if (live.yourAnswer != null || !live.isOpen) {
                Button(onClick = { onAttempt(PlayerCommand.NextQuestion) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onAttempt(PlayerCommand.NextQuestion) }, enabled = !busy && pending == null) {
                    Text(if (last) "Skip and finish" else "Skip this question")
                }
            }
        }
    }
}
