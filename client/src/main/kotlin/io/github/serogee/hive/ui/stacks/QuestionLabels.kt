package io.github.serogee.hive.ui.stacks

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

/** Type, points, and time limit, for example "Multiple choice · 2 points · 30 s". */
fun QuestionDraft.summary(): String {
    val points = if (points == 1) "1 point" else "$points points"
    val time = durationSeconds?.let { "$it s" } ?: "No time limit"
    return "${type.label()} · $points · $time"
}