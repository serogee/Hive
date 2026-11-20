package io.github.serogee.hive.core.transfer

import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackDraft
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId

/**
 * Reads questions from a CSV file with a header row. Problems name the CSV row, counting the
 * header as row 1.
 *
 * Columns: type, prompt, choices, answer, alternatives, explanation, points, seconds. Separate
 * several choices or alternatives with `|`. For multiple choice, `answer` is the correct choice's
 * text.
 */
object CsvStackImport {
    val COLUMNS = listOf("type", "prompt", "choices", "answer", "alternatives", "explanation", "points", "seconds")
    private val REQUIRED = listOf("type", "prompt", "answer")

    fun decode(text: String, title: String, newId: () -> String): ImportResult {
        val rows = parse(text.removePrefix("﻿")).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ImportResult(null, listOf("The file is empty."))

        val header = rows.first().map { it.trim().lowercase() }
        val missing = REQUIRED.filter { it !in header }
        if (missing.isNotEmpty()) {
            return ImportResult(null, listOf("Row 1 needs the columns ${missing.joinToString(", ")}."))
        }
        fun List<String>.cell(name: String) = header.indexOf(name).let { if (it < 0) "" else getOrElse(it) { "" }.trim() }

        val errors = mutableListOf<String>()
        val questions = rows.drop(1).mapIndexedNotNull { index, row ->
            val label = "Row ${index + 2}"
            val points = row.cell("points").ifEmpty { "${QuestionDraft.DEFAULT_POINTS}" }.toIntOrNull()
            val seconds = row.cell("seconds").takeIf { it.isNotEmpty() }
            if (points == null) errors += "$label has points that are not a whole number."
            if (seconds != null && seconds.toIntOrNull() == null) errors += "$label has seconds that are not a whole number."
            val base = QuestionDraft(
                id = QuestionId(newId()),
                prompt = row.cell("prompt"),
                explanation = row.cell("explanation"),
                points = points ?: QuestionDraft.DEFAULT_POINTS,
                durationSeconds = seconds?.toIntOrNull(),
            )
            val answer = row.cell("answer")
            when (row.cell("type").lowercase().replace(' ', '_')) {
                "multiple_choice", "mc" -> {
                    val choices = row.cell("choices").split('|').map { it.trim() }.filter { it.isNotEmpty() }
                        .map { Choice(ChoiceId(newId()), it) }
                    val correct = choices.firstOrNull { it.text.equals(answer, ignoreCase = true) }
                    if (correct == null) errors += "$label: the answer '$answer' is not one of its choices."
                    base.copy(type = QuestionType.MULTIPLE_CHOICE, choices = choices, correctChoiceId = correct?.id)
                }
                "true_false", "tf" -> {
                    val value = when (answer.lowercase()) {
                        "true", "t" -> true
                        "false", "f" -> false
                        else -> null
                    }
                    if (value == null) errors += "$label needs an answer of true or false."
                    base.copy(type = QuestionType.TRUE_FALSE, trueFalseAnswer = value)
                }
                "identification", "id" -> base.copy(
                    type = QuestionType.IDENTIFICATION,
                    identificationAnswer = answer,
                    acceptedAlternatives = row.cell("alternatives").split('|').map { it.trim() }.filter { it.isNotEmpty() },
                )
                else -> {
                    errors += "$label has the unknown type '${row.cell("type")}'. Use multiple_choice, true_false, or identification."
                    null
                }
            }
        }
        return HiveStackFile.finish(StackDraft(StackId(newId()), title, "", questions), errors)
    }

    /** Splits CSV text into rows of cells. Supports quoted cells with commas, quotes, and line breaks. */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
