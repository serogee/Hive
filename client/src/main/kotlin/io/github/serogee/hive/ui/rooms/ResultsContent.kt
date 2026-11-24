package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.engine.SpeedBonus
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer

/** Host Results or Player Result, styled like the Statistics screen in the reference design. */
@Composable
fun ResultsContent(results: RoomResults, isHost: Boolean) {
    if (!isHost) {
        val points = results.yourAnswers.sumOf { it.points }
        val bonus = results.yourAnswers.sumOf { it.speedBonusTenths }
        val total = results.questions.size
        val correct = results.yourAnswers.count { it.correct }
        val scoreFormatted = SpeedBonus.format(points * 10 + bonus)

        HeroNavyCard(
            badgeText = "QUIZ COMPLETED",
            title = "$scoreFormatted PTS",
            subtitle = "$correct of $total correct" + if (bonus > 0) " · +${SpeedBonus.format(bonus)} speed bonus" else "",
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
        Text("No Players answered.", color = MutedSlate, style = MaterialTheme.typography.bodyMedium)
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
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isFirst) ElectricBlue else SoftBlueContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "#${entry.rank}",
                            color = if (isFirst) Color.White else ElectricBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    Column {
                        Text(
                            entry.displayName + if (entry.isGuest) " (guest)" else "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (entry.speedBonusTenths > 0) {
                            Text(
                                "${entry.score} base + ${SpeedBonus.format(entry.speedBonusTenths)} speed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedSlate,
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
                    fontWeight = FontWeight.Bold,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF22C55E).copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Correct: ${question.correctAnswerText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (isHost) {
                    Text(
                        "${question.correctCount} of ${question.answeredCount} answers correct",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                } else {
                    val verdict = when {
                        own?.answer == null -> "Not answered"
                        own.correct && own.speedBonusTenths > 0 -> "Correct (+${own.points} +${SpeedBonus.format(own.speedBonusTenths)} speed)"
                        own.correct -> "Correct (+${own.points})"
                        else -> "Incorrect"
                    }
                    Text(
                        "Your answer: ${own?.answerText ?: "none"} · $verdict",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCorrect) Color(0xFF16A34A) else Color(0xFFDC2626),
                        fontWeight = FontWeight.Medium,
                    )
                }
                question.explanation?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MutedSlate)
                }
            }
        }
    }
}
