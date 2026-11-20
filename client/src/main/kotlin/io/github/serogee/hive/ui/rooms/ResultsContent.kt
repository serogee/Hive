package io.github.serogee.hive.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.engine.SpeedBonus

/** Host Results or Player Result, depending on [isHost]. */
@Composable
fun ResultsContent(results: RoomResults, isHost: Boolean) {
    if (!isHost) {
        val points = results.yourAnswers.sumOf { it.points }
        val bonus = results.yourAnswers.sumOf { it.speedBonusTenths }
        val total = results.questions.size
        val correct = results.yourAnswers.count { it.correct }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your score: ${SpeedBonus.format(points * 10 + bonus)}", style = MaterialTheme.typography.headlineSmall)
                if (bonus > 0) Text("$points + ${SpeedBonus.format(bonus)} speed")
                Text("$correct of $total correct")
            }
        }
    }

    Text("Leaderboard", style = MaterialTheme.typography.titleMedium)
    if (results.leaderboard.isEmpty()) Text("No Players answered.")
    results.leaderboard.forEach { entry ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${entry.rank}. ${entry.displayName}" + if (entry.isGuest) " (guest)" else "")
            Text(
                if (entry.speedBonusTenths > 0) {
                    "${SpeedBonus.format(entry.totalTenths)} pts (${entry.score} + ${SpeedBonus.format(entry.speedBonusTenths)} speed)"
                } else {
                    "${SpeedBonus.format(entry.totalTenths)} pts"
                },
            )
        }
        HorizontalDivider()
    }

    Text("Questions", style = MaterialTheme.typography.titleMedium)
    results.questions.forEach { question ->
        val own = results.yourAnswers.firstOrNull { it.questionId == question.questionId }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${question.number}. ${question.prompt}", style = MaterialTheme.typography.titleSmall)
            Text("Correct answer: ${question.correctAnswerText}")
            if (isHost) {
                Text("${question.correctCount} of ${question.answeredCount} answers correct")
            } else {
                val verdict = when {
                    own?.answer == null -> "Not answered"
                    own.correct && own.speedBonusTenths > 0 -> "Correct, +${own.points} +${SpeedBonus.format(own.speedBonusTenths)} speed"
                    own.correct -> "Correct, +${own.points}"
                    else -> "Incorrect"
                }
                Text("Your answer: ${own?.answerText ?: "none"} · $verdict")
            }
            question.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
    }
}
