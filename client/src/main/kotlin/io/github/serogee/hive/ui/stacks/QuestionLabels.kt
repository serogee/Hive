package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.model.TypoTolerance

fun QuestionType.label() = when (this) {
    QuestionType.MULTIPLE_CHOICE -> "Multiple choice"
    QuestionType.TRUE_FALSE -> "True or False"
    QuestionType.IDENTIFICATION -> "Identification"
}

fun TypoTolerance.label() = when (this) {
    TypoTolerance.OFF -> "Off"
    TypoTolerance.LOW -> "Low"
    TypoTolerance.MEDIUM -> "Medium"
    TypoTolerance.HIGH -> "High"
}

/** What the level allows, for the author. */
fun TypoTolerance.explanation() = when (this) {
    TypoTolerance.OFF -> "The spelling must match."
    TypoTolerance.LOW -> "Allows 1 typo in answers of 5 or more characters."
    TypoTolerance.MEDIUM -> "Allows 1 typo in answers of 4 or more characters, and 2 from 8."
    TypoTolerance.HIGH -> "Allows 1 typo in answers of 4 or more characters, 2 from 6, and 3 from 10."
}

/** Type and points, for example "MULTIPLE CHOICE (2 PTS)". */
fun QuestionDraft.typeAndPoints(): String {
    val unit = if (points == 1) "PT" else "PTS"
    return "${type.label().uppercase()} ($points $unit)"
}

/** The time limit, for example "30 s", or an infinity sign without one. */
fun QuestionDraft.timeLimit(): String = durationSeconds?.let { "$it s" } ?: "\u221E"

/** Type and points on the left, and the time limit on the right. */
@Composable
fun QuestionInfo(question: QuestionDraft, modifier: Modifier = Modifier) {
    val seconds = question.durationSeconds
    val spokenTime = if (seconds == null) "No time limit" else "Time limit $seconds seconds"
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            question.typeAndPoints(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            question.timeLimit(),
            modifier = Modifier.semantics { contentDescription = spokenTime },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
