package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
import java.text.DateFormat
import java.util.Date

/** Asynchronous Progress: the Host sees where each Player is and can close the room. */
@Composable
fun HostAsyncContent(room: RoomState, busy: Boolean, onCommand: (HostCommand) -> Unit) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val questions = room.room.questionCount

    HeroNavyCard(
        badgeText = "ASYNC ROOM",
        title = "Room ${room.room.code}",
        subtitle = "Players move through the $questions questions at their own pace" +
            (room.room.deadlineAt?.let { " · Closes at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: ""),
    )

    SectionHeader(title = "Player Progress")
    if (room.progress.isEmpty()) {
        Text("No Players have joined yet.", color = MutedSlate, style = MaterialTheme.typography.bodyMedium)
    }
    room.progress.forEach { player ->
        val statusText = when {
            player.finished -> "Finished · ${player.answeredCount} answered"
            player.currentQuestion == null -> "Not started"
            else -> "Question ${player.currentQuestion} of $questions · ${player.answeredCount} answered"
        }
        SleekCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(player.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                TagPill(
                    text = statusText,
                    selected = player.finished,
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { confirmEnd = true },
        enabled = !busy,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricBlue,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    ) {
        Text("End Room and Show Results", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the room for everyone?", fontWeight = FontWeight.Bold) },
            text = { Text("Players who have not finished can no longer answer. Answers already received are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEnd = false
                        onCommand(HostCommand.EndRoom)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53E3E)),
                ) { Text("End for Everyone") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep Open") } },
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
            SleekCard {
                Text("SELF-PACED QUIZ", style = MaterialTheme.typography.labelSmall, color = ElectricBlue, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Answer the ${room.room.questionCount} questions at your own pace. A timed question's clock starts when you open it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlate,
                )
                Button(
                    onClick = { onAttempt(PlayerCommand.StartAttempt) },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text("Start Quiz", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        attempt.finished || live == null -> {
            HeroNavyCard(
                badgeText = "COMPLETED",
                title = "Quiz Finished!",
                subtitle = "You answered ${attempt.answeredCount} of ${room.room.questionCount} questions. Results appear when the Host ends the room or the deadline passes.",
            )
        }
        else -> {
            PlayerLiveContent(live, pending, onSubmit, onRetry, onTimeUp, hostPaced = false)
            val last = live.question.number == live.questionCount
            val label = if (last) "Finish" else "Next Question"
            Spacer(Modifier.height(8.dp))
            if (live.yourAnswer != null || !live.isOpen) {
                Button(
                    onClick = { onAttempt(PlayerCommand.NextQuestion) },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                OutlinedButton(
                    onClick = { onAttempt(PlayerCommand.NextQuestion) },
                    enabled = !busy && pending == null,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.5.dp, ElectricBlue),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (last) "Skip and Finish" else "Skip This Question", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
