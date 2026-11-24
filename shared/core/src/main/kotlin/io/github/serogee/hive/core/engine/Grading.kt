package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import java.text.Normalizer

/** Checks answers. Hosted quizzes and self-review use the same rules. */
object Grading {
    fun isCorrect(question: Question, answer: Answer): Boolean = when (question) {
        is Question.MultipleChoice -> when (answer) {
            is Answer.SelectedChoice -> answer.choiceId == question.correctChoiceId
            is Answer.Text -> question.choices
                .firstOrNull { it.id == question.correctChoiceId }
                ?.let { normalize(it.text) == normalize(answer.text) } == true
            is Answer.TrueFalse -> false
        }

        is Question.TrueFalse -> when (answer) {
            is Answer.TrueFalse -> answer.value == question.answer
            is Answer.Text -> parseBoolean(answer.text) == question.answer
            is Answer.SelectedChoice -> false
        }

        is Question.Identification -> answer is Answer.Text &&
            (listOf(question.answer) + question.acceptedAlternatives).any { AnswerMatcher.matches(it, answer.text, question.matching) }
    }

    /** Points for one answer. Only correctness counts. */
    fun points(question: Question, correct: Boolean): Int = if (correct) question.points else 0

    /**
     * Makes typed answers comparable: ignores case, accents, surrounding spaces, repeated spaces,
     * and a trailing period.
     */
    fun normalize(text: String, ignoreCase: Boolean = true): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .let { if (ignoreCase) it.lowercase() else it }
            .trim()
            .removeSuffix(".")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseBoolean(text: String): Boolean? = when (normalize(text)) {
        "true", "t" -> true
        "false", "f" -> false
        else -> null
    }
}
