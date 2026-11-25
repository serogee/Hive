package io.github.serogee.hive.ui.rooms

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.AnswerTaps
import io.github.serogee.hive.ui.common.AnswerTile
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAnswerInput
import io.github.serogee.hive.ui.common.NoteBox
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.common.VerdictBox
import io.github.serogee.hive.ui.common.answerTileState
import io.github.serogee.hive.ui.common.choiceLetter
import kotlinx.coroutines.delay

@Composable
private fun QuestionHeader(live: LiveQuestion, onTimeUp: () -> Unit) {
    val question = live.question
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TagPill(text = "QUESTION ${question.number} OF ${live.questionCount}", selected = true)
        TagPill(text = question.typeLabel(), selected = false)
        TagPill(text = if (question.points == 1) "1 pt" else "${question.points} pts", selected = false)
    }
    Countdown(live, onTimeUp)
    Text(
        question.prompt,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
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
        (if (seconds > 0) "Time left: $seconds s" else "Time is up").uppercase(),
        style = MaterialTheme.typography.headlineSmall,
        color = if (seconds <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RevealedAnswer(live: LiveQuestion) {
    val correct = live.correctAnswer ?: return
    VerdictBox(right = true, title = "Correct answer") {
        Text(
            live.question.describe(correct),
            style = MaterialTheme.typography.titleSmall,
        )
        live.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Whether [answer] is the correct one, or null until the room shows the correct answer. */
private fun LiveQuestion.isCorrect(answer: Answer): Boolean? = correctAnswer?.let { it == answer }

@Composable
fun HostLiveContent(live: LiveQuestion, busy: Boolean, onCommand: (HostCommand) -> Unit, onTimeUp: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val isLast = live.question.number == live.questionCount
    val waiting = live.playerCount - live.answeredCount

    SleekCard(active = true) {
        QuestionHeader(live, onTimeUp)
        (live.question as? PlayerQuestion.MultipleChoice)?.choices?.forEachIndexed { index, option ->
            AnswerTile(
                letter = choiceLetter(index),
                text = option.text,
                state = answerTileState(selected = false, correct = live.isCorrect(Answer.SelectedChoice(option.id))),
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "${live.answeredCount} of ${live.playerCount} Players answered",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        RevealedAnswer(live)

        Spacer(Modifier.height(4.dp))
        if (live.isOpen) {
            HiveButton(
                text = "Close Question",
                onClick = { if (waiting > 0) confirm = CLOSE else onCommand(HostCommand.CloseQuestion) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("Answers are locked. Discuss, then continue.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (isLast) {
                HiveButton(
                    text = "End Quiz and Show Results",
                    onClick = { onCommand(HostCommand.EndRoom) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                HiveButton(
                    text = "Next Question",
                    onClick = { onCommand(HostCommand.NextQuestion) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!(isLast && !live.isOpen)) {
            HiveButton(
                text = "End Quiz for Everyone",
                onClick = { confirm = END },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                style = HiveButtonStyle.DANGER,
            )
        }
    }

    when (confirm) {
        CLOSE -> HiveDialog(
            title = "Close this question?",
            message = if (waiting == 1) "1 Player has not answered yet." else "$waiting Players have not answered yet.",
            confirmText = "Close Question",
            onConfirm = {
                confirm = null
                onCommand(HostCommand.CloseQuestion)
            },
            dismissText = "Keep Open",
            onDismiss = { confirm = null },
        )
        END -> HiveDialog(
            title = "End the quiz for everyone?",
            message = "Players can no longer answer. Answers already received are kept.",
            confirmText = "End for Everyone",
            onConfirm = {
                confirm = null
                onCommand(HostCommand.EndRoom)
            },
            dismissText = "Continue Quiz",
            onDismiss = { confirm = null },
            danger = true,
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
    /** False in an Asynchronous room, where the Player moves on without the Host. */
    hostPaced: Boolean = true,
) {
    val question = live.question
    val received = live.yourAnswer
    var choice by rememberSaveable(question.id.value) { mutableStateOf<String?>(null) }
    var truth by rememberSaveable(question.id.value) { mutableStateOf<Boolean?>(null) }
    var text by rememberSaveable(question.id.value) { mutableStateOf("") }
    val sendingThis = pending?.takeIf { it.questionId == question.id }
    val locked = received != null || sendingThis != null || !live.isOpen
    val input = LocalAnswerInput.current
    val taps = remember(question.id, input) { AnswerTaps(input) }

    SleekCard(active = true) {
        QuestionHeader(live, onTimeUp)

        when (question) {
            is PlayerQuestion.MultipleChoice -> question.choices.forEachIndexed { index, option ->
                val selected = (received?.answer as? Answer.SelectedChoice)?.choiceId?.value ?: choice
                AnswerTile(
                    letter = choiceLetter(index),
                    text = option.text,
                    state = answerTileState(
                        selected = selected == option.id.value,
                        correct = live.isCorrect(Answer.SelectedChoice(option.id)),
                    ),
                    onClick = {
                        choice = option.id.value
                        if (taps.locksIn(option.id.value, SystemClock.uptimeMillis())) onSubmit(Answer.SelectedChoice(option.id))
                    },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is PlayerQuestion.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val selected = (received?.answer as? Answer.TrueFalse)?.value ?: truth
                listOf(true, false).forEach { value ->
                    AnswerTile(
                        letter = if (value) "T" else "F",
                        text = if (value) "True" else "False",
                        state = answerTileState(
                            selected = selected == value,
                            correct = live.isCorrect(Answer.TrueFalse(value)),
                        ),
                        onClick = {
                            truth = value
                            if (taps.locksIn("$value", SystemClock.uptimeMillis())) onSubmit(Answer.TrueFalse(value))
                        },
                        enabled = !locked,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is PlayerQuestion.Identification -> HiveTextField(
                value = (received?.answer as? Answer.Text)?.text ?: text,
                onValueChange = { text = it.take(200) },
                label = "Your answer",
                enabled = !locked,
            )
        }

        val answer: Answer? = when (question) {
            is PlayerQuestion.MultipleChoice -> choice?.let { id -> question.choices.firstOrNull { it.id.value == id } }
                ?.let { Answer.SelectedChoice(it.id) }
            is PlayerQuestion.TrueFalse -> truth?.let { Answer.TrueFalse(it) }
            is PlayerQuestion.Identification -> text.trim().takeIf { it.isNotEmpty() }?.let { Answer.Text(it) }
        }

        Spacer(Modifier.height(4.dp))
        when {
            received != null -> NoteBox {
                Text("✓ Answer received by server", style = MaterialTheme.typography.titleMedium)
            }
            sendingThis?.failed == true -> {
                Text("Not sent yet. Check your connection.", color = MaterialTheme.colorScheme.error)
                HiveButton(
                    text = "Send again",
                    onClick = onRetry,
                )
            }
            sendingThis != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Sending answer...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !live.isOpen -> Text("This question is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            question !is PlayerQuestion.Identification && input == AnswerInput.LOCK_ON_TAP -> Text(
                "Your first tap is your answer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> HiveButton(
                text = "Confirm",
                onClick = { answer?.let(onSubmit) },
                enabled = answer != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!live.isOpen) {
            if (hostPaced) Text("Wait for the Host to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            RevealedAnswer(live)
        }
    }
}

private fun PlayerQuestion.typeLabel() = when (this) {
    is PlayerQuestion.MultipleChoice -> "Multiple choice"
    is PlayerQuestion.TrueFalse -> "True or False"
    is PlayerQuestion.Identification -> "Identification"
}

private const val CLOSE = "close"
private const val END = "end"
