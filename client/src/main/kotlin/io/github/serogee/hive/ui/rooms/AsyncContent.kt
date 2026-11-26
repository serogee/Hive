package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import java.text.DateFormat
import java.util.Date

/** Asynchronous Progress: the Host sees where each Player is and can close the room. */
@Composable
fun HostAsyncContent(room: RoomState, busy: Boolean, onCommand: (HostCommand) -> Unit) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val questions = room.room.questionCount

    HeroCard(
        badgeText = "ASYNC ROOM",
        title = "Session ${room.room.code}",
        subtitle = "Players move through the $questions questions at their own pace" +
            (room.room.deadlineAt?.let { "\nCloses at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: ""),
    )

    SectionHeader(title = "Player Progress")
    if (room.progress.isEmpty()) {
        Text("No Players have joined yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    room.progress.forEach { player ->
        val statusText = when {
            player.finished -> "Finished, ${player.answeredCount} answered"
            player.currentQuestion == null -> "Not started"
            else -> "Question ${player.currentQuestion} of $questions, ${player.answeredCount} answered"
        }
        SleekCard {
            Text(player.displayName, style = MaterialTheme.typography.titleMedium)
            TagPill(
                text = statusText,
                selected = player.finished,
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    HiveButton(
        text = "End Session and Show Results",
        onClick = { confirmEnd = true },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )

    if (confirmEnd) {
        HiveDialog(
            title = "End the session for everyone?",
            message = "Players who have not finished can no longer answer. Answers already received are kept.",
            confirmText = "End for Everyone",
            onConfirm = {
                confirmEnd = false
                onCommand(HostCommand.EndRoom)
            },
            dismissText = "Keep Open",
            onDismiss = { confirmEnd = false },
            danger = true,
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
    externalActions: Boolean = false,
    onDraftAnswer: (Answer?) -> Unit = {},
) {
    val attempt = room.attempt
    val live = room.live
    when {
        attempt == null || !attempt.started -> {
            SleekCard {
                Caption("Self-paced quiz", color = MaterialTheme.colorScheme.primary)
                Text(
                    "Answer the ${room.room.questionCount} questions at your own pace. A timed question's clock starts when you open it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HiveButton(
                    text = "Start quiz",
                    onClick = { onAttempt(PlayerCommand.StartAttempt) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        attempt.finished || live == null -> {
            HeroCard(
                badgeText = "COMPLETED",
                title = "Quiz Finished!",
                subtitle = "You answered ${attempt.answeredCount} of ${room.room.questionCount} questions. Results appear when the Host ends the session or the deadline passes.",
            )
        }
        else -> {
            PlayerLiveContent(
                live = live,
                pending = pending,
                onSubmit = onSubmit,
                onRetry = onRetry,
                onTimeUp = onTimeUp,
                hostPaced = false,
                externalActions = externalActions,
                onDraftAnswer = onDraftAnswer,
            )
            if (!externalActions) {
                val last = live.question.number == live.questionCount
                val label = if (last) "Finish" else "Next"
                Spacer(Modifier.height(8.dp))
                val sendingThis = pending?.takeIf { it.questionId == live.question.id }
                if (live.yourAnswer != null || !live.isOpen) {
                    HiveButton(
                        text = label,
                        onClick = { onAttempt(PlayerCommand.NextQuestion) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    HiveButton(
                        text = if (last) "Skip and finish" else "Skip",
                        onClick = { onAttempt(PlayerCommand.NextQuestion) },
                        enabled = !busy && sendingThis == null,
                        modifier = Modifier.fillMaxWidth(),
                        style = HiveButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}
