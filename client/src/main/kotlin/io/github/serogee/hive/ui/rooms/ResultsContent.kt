package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.engine.SpeedBonus
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.common.VerdictTag
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey

/** Host Results or Player Result, depending on [isHost]. */
@Composable
fun ResultsContent(results: RoomResults, isHost: Boolean) {
    if (!isHost) {
        val points = results.yourAnswers.sumOf { it.points }
        val bonus = results.yourAnswers.sumOf { it.speedBonusTenths }
        val total = results.questions.size
        val correct = results.yourAnswers.count { it.correct }
        val scoreFormatted = SpeedBonus.format(points * 10 + bonus)

        HeroCard(
            badgeText = "QUIZ COMPLETED",
            title = "$scoreFormatted PTS",
            subtitle = "$correct of $total correct" + if (bonus > 0) ", +${SpeedBonus.format(bonus)} speed bonus" else "",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "$points pts",
                label = "base\nscore",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "+${SpeedBonus.format(bonus)}",
                label = "speed\nbonus",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "$correct / $total",
                label = "questions\ncorrect",
                modifier = Modifier.weight(1f),
            )
        }
    }

    SectionHeader(title = "Leaderboard")
    if (results.leaderboard.isEmpty()) {
        Text("No Players answered.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }

    results.leaderboard.forEach { entry ->
        val isFirst = entry.rank == 1
        SleekCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HexBadge(
                        text = "${entry.rank}",
                        size = 40.dp,
                        color = if (isFirst) Honey else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isFirst) Graphite else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Column {
                        Text(
                            entry.displayName + if (entry.isGuest) " (guest)" else "",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (entry.speedBonusTenths > 0) {
                            Text(
                                "${entry.score} base + ${SpeedBonus.format(entry.speedBonusTenths)} speed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                TagPill(
                    text = "${SpeedBonus.format(entry.totalTenths)} pts",
                    selected = isFirst,
                )
            }
        }
    }

    SectionHeader(title = "Questions Breakdown")

    results.questions.forEach { question ->
        val own = results.yourAnswers.firstOrNull { it.questionId == question.questionId }
        val isCorrect = own?.correct == true
        SleekCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${question.number}. ${question.prompt}",
                    style = MaterialTheme.typography.titleMedium,
                )

                VerdictTag(right = true, text = "Correct: ${question.correctAnswerText}")

                if (isHost) {
                    Text(
                        "${question.correctCount} of ${question.answeredCount} answers correct",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val verdict = when {
                        own?.answer == null -> "Not answered"
                        own.correct && own.speedBonusTenths > 0 -> "Correct (+${own.points} +${SpeedBonus.format(own.speedBonusTenths)} speed)"
                        own.correct -> "Correct (+${own.points})"
                        else -> "Incorrect"
                    }
                    VerdictTag(right = isCorrect, text = "Your answer: ${own?.answerText ?: "none"}\n$verdict")
                }
                question.explanation?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
