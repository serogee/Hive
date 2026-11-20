package io.github.serogee.hive.ui.stacks

import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType

fun QuestionType.label() = when (this) {
    QuestionType.MULTIPLE_CHOICE -> "Multiple choice"
    QuestionType.TRUE_FALSE -> "True or False"
    QuestionType.IDENTIFICATION -> "Identification"
}

/** Type, points, and time limit, for example "Multiple choice · 2 points · 30 s". */
fun QuestionDraft.summary(): String {
    val points = if (points == 1) "1 point" else "$points points"
    val time = durationSeconds?.let { "$it s" } ?: "No time limit"
    return "${type.label()} · $points · $time"
}