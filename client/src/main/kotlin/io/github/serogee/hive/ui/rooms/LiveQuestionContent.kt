package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.LiveQuestion
import io.github.serogee.hive.core.api.PlayerQuestion
import io.github.serogee.hive.core.api.describe
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.theme.DangerRed
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.SuccessGreen
import io.github.serogee.hive.ui.theme.hiveColors
import kotlinx.coroutines.delay

@Composable
private fun QuestionHeader(live: LiveQuestion, onTimeUp: () -> Unit) {
    val question = live.question
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TagPill(text = "QUESTION ${question.number} OF ${live.questionCount}", selected = true)
        TagPill(text = question.typeLabel(), selected = false)
        TagPill(text = if (question.points == 1) "1 pt" else "${question.points} pts", selected = false)
    }
    Countdown(live, onTimeUp)
    Text(
        question.prompt,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
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
        if (seconds > 0) "Time left: $seconds s" else "Time is up",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (seconds <= 5) DangerRed else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RevealedAnswer(live: LiveQuestion) {
    val correct = live.correctAnswer ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SuccessGreen.copy(alpha = 0.1f))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "CORRECT ANSWER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hiveColors.successText,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                live.question.describe(correct),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            live.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun HostLiveContent(live: LiveQuestion, busy: Boolean, onCommand: (HostCommand) -> Unit, onTimeUp: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val isLast = live.question.number == live.questionCount
    val waiting = live.playerCount - live.answeredCount

    SleekCard {
        QuestionHeader(live, onTimeUp)
        (live.question as? PlayerQuestion.MultipleChoice)?.choices?.forEach {
            Text("• ${it.text}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "${live.answeredCount} of ${live.playerCount} Players answered",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        RevealedAnswer(live)

        Spacer(Modifier.height(4.dp))
        if (live.isOpen) {
            Button(
                onClick = { if (waiting > 0) confirm = CLOSE else onCommand(HostCommand.CloseQuestion) },
                enabled = !busy,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("Close Question", fontWeight = FontWeight.Bold) }
        } else {
            Text("Answers are locked. Discuss, then continue.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (isLast) {
                Button(
                    onClick = { onCommand(HostCommand.EndRoom) },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text("End Quiz and Show Results", fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = { onCommand(HostCommand.NextQuestion) },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text("Next Question", fontWeight = FontWeight.Bold) }
            }
        }
        if (!(isLast && !live.isOpen)) {
            OutlinedButton(
                onClick = { confirm = END },
                enabled = !busy,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.5.dp, DangerRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("End Quiz for Everyone", fontWeight = FontWeight.Bold) }
        }
    }

    when (confirm) {
        CLOSE -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Close this question?", fontWeight = FontWeight.Bold) },
            text = { Text(if (waiting == 1) "1 Player has not answered yet." else "$waiting Players have not answered yet.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = null
                        onCommand(HostCommand.CloseQuestion)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) { Text("Close Question") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep Open") } },
        )
        END -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("End the quiz for everyone?", fontWeight = FontWeight.Bold) },
            text = { Text("Players can no longer answer. Answers already received are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = null
                        onCommand(HostCommand.EndRoom)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                ) { Text("End for Everyone") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Continue Quiz") } },
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

    SleekCard {
        QuestionHeader(live, onTimeUp)

        when (question) {
            is PlayerQuestion.MultipleChoice -> question.choices.forEach { option ->
                val selected = (received?.answer as? Answer.SelectedChoice)?.choiceId?.value ?: choice
                val isSelected = selected == option.id.value
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !locked) { choice = option.id.value },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            enabled = !locked,
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            option.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            is PlayerQuestion.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val selected = (received?.answer as? Answer.TrueFalse)?.value ?: truth
                listOf(true, false).forEach { value ->
                    val isSelected = selected == value
                    val label = if (value) "True" else "False"
                    Button(
                        onClick = { truth = value },
                        enabled = !locked,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, fontWeight = FontWeight.Bold)
                    }
                }
            }
            is PlayerQuestion.Identification -> OutlinedTextField(
                value = (received?.answer as? Answer.Text)?.text ?: text,
                onValueChange = { text = it.take(200) },
                label = { Text("Your answer") },
                singleLine = true,
                enabled = !locked,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
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
            received != null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SuccessGreen.copy(alpha = 0.1f))
                    .padding(12.dp),
            ) {
                Text(
                    "✓ Answer received by server",
                    color = MaterialTheme.hiveColors.successText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            sendingThis?.failed == true -> {
                Text("Not sent yet. Check your connection.", color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) { Text("Send again", fontWeight = FontWeight.Bold) }
            }
            sendingThis != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Sending answer...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !live.isOpen -> Text("This question is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            else -> Button(
                onClick = { answer?.let(onSubmit) },
                enabled = answer != null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("Submit Answer", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
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
