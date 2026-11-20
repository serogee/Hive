package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.LiveQuestion
import io.github.serogee.hive.core.api.PlayerQuestion
import io.github.serogee.hive.core.api.describe
import io.github.serogee.hive.core.model.Answer
import kotlinx.coroutines.delay

@Composable
private fun QuestionHeader(live: LiveQuestion, onTimeUp: () -> Unit) {
    val question = live.question
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Question ${question.number} of ${live.questionCount}", style = MaterialTheme.typography.labelLarge)
        AssistChip(onClick = {}, label = { Text(question.typeLabel()) })
        Text(if (question.points == 1) "1 point" else "${question.points} points", style = MaterialTheme.typography.labelLarge)
    }
    Countdown(live, onTimeUp)
    Text(question.prompt, style = MaterialTheme.typography.titleLarge)
}

/**
 * Shows the time left. The room server's clock is the authority, so the countdown corrects for the
 * difference between this phone's clock and the server's.
 */
@Composable
private fun Countdown(live: LiveQuestion, onTimeUp: () -> Unit) {
    val closesAt = live.closesAt ?: return
    if (!live.isOpen) return
    val offset = remember(live.serverTime) { live.serverTime - System.currentTimeMillis() }
    val left by produceState(initialValue = closesAt - live.serverTime, closesAt, offset) {
        while (true) {
            value = closesAt - (System.currentTimeMillis() + offset)
            if (value <= 0) break
            delay(250)
        }
    }
    LaunchedEffect(left <= 0) { if (left <= 0) onTimeUp() }
    val seconds = ((left + 999) / 1000).coerceAtLeast(0)
    Text(
        if (seconds > 0) "Time left: $seconds s" else "Time is up",
        style = MaterialTheme.typography.titleMedium,
        color = if (seconds <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RevealedAnswer(live: LiveQuestion) {
    val correct = live.correctAnswer ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Correct answer: ${live.question.describe(correct)}", style = MaterialTheme.typography.titleSmall)
            live.explanation?.let { Text(it) }
        }
    }
}

@Composable
fun HostLiveContent(live: LiveQuestion, busy: Boolean, onCommand: (HostCommand) -> Unit, onTimeUp: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val isLast = live.question.number == live.questionCount
    val waiting = live.playerCount - live.answeredCount

    QuestionHeader(live, onTimeUp)
    (live.question as? PlayerQuestion.MultipleChoice)?.choices?.forEach { Text("• ${it.text}") }
    Text(
        "${live.answeredCount} of ${live.playerCount} Players answered",
        style = MaterialTheme.typography.titleMedium,
    )
    RevealedAnswer(live)

    if (live.isOpen) {
        Button(
            onClick = { if (waiting > 0) confirm = CLOSE else onCommand(HostCommand.CloseQuestion) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Close question") }
    } else {
        Text("Answers are locked. Discuss, then continue.")
        if (isLast) {
            Button(onClick = { onCommand(HostCommand.EndRoom) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("End quiz and show results")
            }
        } else {
            Button(onClick = { onCommand(HostCommand.NextQuestion) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Next question")
            }
        }
    }
    if (!(isLast && !live.isOpen)) {
        OutlinedButton(onClick = { confirm = END }, enabled = !busy) { Text("End quiz for everyone") }
    }

    when (confirm) {
        CLOSE -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Close this question?") },
            text = { Text(if (waiting == 1) "1 Player has not answered yet." else "$waiting Players have not answered yet.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onCommand(HostCommand.CloseQuestion)
                }) { Text("Close question") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep open") } },
        )
        END -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("End the quiz for everyone?") },
            text = { Text("Players can no longer answer. Answers already received are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onCommand(HostCommand.EndRoom)
                }) { Text("End for everyone") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Continue quiz") } },
        )
    }
}

@Composable
fun PlayerLiveContent(
    live: LiveQuestion,
    pending: PendingAnswer?,
    onSubmit: (Answer) -> Unit,
    onRetry: () -> Unit,
    onTimeUp: () -> Unit,
) {
    val question = live.question
    val received = live.yourAnswer
    var choice by rememberSaveable(question.id.value) { mutableStateOf<String?>(null) }
    var truth by rememberSaveable(question.id.value) { mutableStateOf<Boolean?>(null) }
    var text by rememberSaveable(question.id.value) { mutableStateOf("") }
    val sendingThis = pending?.takeIf { it.questionId == question.id }
    val locked = received != null || sendingThis != null || !live.isOpen

    QuestionHeader(live, onTimeUp)

    when (question) {
        is PlayerQuestion.MultipleChoice -> question.choices.forEach { option ->
            val selected = (received?.answer as? Answer.SelectedChoice)?.choiceId?.value ?: choice
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == option.id.value, enabled = !locked) { choice = option.id.value },
            ) {
                RadioButton(selected = selected == option.id.value, onClick = null, enabled = !locked)
                Text(option.text)
            }
        }
        is PlayerQuestion.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = (received?.answer as? Answer.TrueFalse)?.value ?: truth
            listOf(true, false).forEach { value ->
                val label = if (value) "True" else "False"
                if (selected == value) {
                    Button(onClick = { truth = value }, enabled = !locked) { Text(label) }
                } else {
                    OutlinedButton(onClick = { truth = value }, enabled = !locked) { Text(label) }
                }
            }
        }
        is PlayerQuestion.Identification -> OutlinedTextField(
            value = (received?.answer as? Answer.Text)?.text ?: text,
            onValueChange = { text = it.take(200) },
            label = { Text("Your answer") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val answer: Answer? = when (question) {
        is PlayerQuestion.MultipleChoice -> choice?.let { id -> question.choices.firstOrNull { it.id.value == id } }
            ?.let { Answer.SelectedChoice(it.id) }
        is PlayerQuestion.TrueFalse -> truth?.let { Answer.TrueFalse(it) }
        is PlayerQuestion.Identification -> text.trim().takeIf { it.isNotEmpty() }?.let { Answer.Text(it) }
    }

    when {
        received != null -> Text("Answer received", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        sendingThis?.failed == true -> {
            Text("Not sent yet. Check your connection.", color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Send again") }
        }
        sendingThis != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Sending…")
        }
        !live.isOpen -> Text("This question is closed.")
        else -> Button(
            onClick = { answer?.let(onSubmit) },
            enabled = answer != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Submit answer") }
    }

    if (!live.isOpen) {
        Text("Wait for the Host to continue.")
        RevealedAnswer(live)
    }
}

private fun PlayerQuestion.typeLabel() = when (this) {
    is PlayerQuestion.MultipleChoice -> "Multiple choice"
    is PlayerQuestion.TrueFalse -> "True or False"
    is PlayerQuestion.Identification -> "Identification"
}

private const val CLOSE = "close"
private const val END = "end"
