package io.github.serogee.hive.core.editor

import io.github.serogee.hive.core.model.AnswerMatching
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QuestionType {
    @SerialName("multiple_choice")
    MULTIPLE_CHOICE,

    @SerialName("true_false")
    TRUE_FALSE,

    @SerialName("identification")
    IDENTIFICATION,
}

/**
 * A question while it is being written. It may be incomplete.
 *
 * It keeps the fields of every type, so that switching the type and switching back loses nothing.
 */
@Serializable
data class QuestionDraft(
    val id: QuestionId,
    val type: QuestionType = QuestionType.MULTIPLE_CHOICE,
    val prompt: String = "",
    val choices: List<Choice> = emptyList(),
    val correctChoiceId: ChoiceId? = null,
    val trueFalseAnswer: Boolean? = null,
    val identificationAnswer: String = "",
    val acceptedAlternatives: List<String> = emptyList(),
    val matching: AnswerMatching = AnswerMatching(),
    val explanation: String = "",
    val points: Int = DEFAULT_POINTS,
    /** Null means no time limit. */
    val durationSeconds: Int? = null,
) {
    companion object {
        const val DEFAULT_POINTS = 1
        const val MAX_POINTS = 100
        const val MIN_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 3600
    }
}

data class StackDraft(
    val id: StackId,
    val title: String,
    val description: String,
    val questions: List<QuestionDraft>,
)

/** One problem that stops a stack from being hosted or reviewed. */
data class ValidationIssue(
    /** Null for a problem with the stack itself, such as a missing title. */
    val questionId: QuestionId?,
    val message: String,
)

object StackValidation {
    /** Lists every problem, in question order, with the question number in each message. */
    fun validate(draft: StackDraft): List<ValidationIssue> = buildList {
        if (draft.title.isBlank()) add(ValidationIssue(null, "Give this stack a title."))
        if (draft.questions.isEmpty()) add(ValidationIssue(null, "Add at least one question."))
        val seenQuestionIds = mutableSetOf<QuestionId>()
        draft.questions.forEachIndexed { index, question ->
            val number = index + 1
            if (question.id.value.isBlank()) {
                add(ValidationIssue(question.id, "Question $number needs an ID."))
            } else if (!seenQuestionIds.add(question.id)) {
                add(ValidationIssue(question.id, "Question $number has a duplicate ID."))
            }
            questionIssues(question, number).forEach { add(ValidationIssue(question.id, it)) }
        }
    }

    fun questionIssues(question: QuestionDraft, number: Int): List<String> = buildList {
        val label = "Question $number"
        if (question.prompt.isBlank()) add("$label needs a prompt.")
        when (question.type) {
            QuestionType.MULTIPLE_CHOICE -> {
                val seenChoiceIds = mutableSetOf<ChoiceId>()
                question.choices.forEachIndexed { index, choice ->
                    if (choice.id.value.isBlank()) {
                        add("$label, choice ${index + 1} needs an ID.")
                    } else if (!seenChoiceIds.add(choice.id)) {
                        add("$label, choice ${index + 1} has a duplicate ID.")
                    }
                    if (choice.text.isBlank()) add("$label, choice ${index + 1} is empty.")
                }
                if (question.choices.size < 2) add("$label needs at least two choices.")
                if (question.choices.none { it.id == question.correctChoiceId }) add("$label needs a correct answer.")
            }
            QuestionType.TRUE_FALSE ->
                if (question.trueFalseAnswer == null) add("$label needs a correct answer.")
            QuestionType.IDENTIFICATION ->
                if (question.identificationAnswer.isBlank()) add("$label needs a correct answer.")
        }
        if (question.points !in 1..QuestionDraft.MAX_POINTS) {
            add("$label needs between 1 and ${QuestionDraft.MAX_POINTS} points.")
        }
        val duration = question.durationSeconds
        if (duration != null && duration !in QuestionDraft.MIN_DURATION_SECONDS..QuestionDraft.MAX_DURATION_SECONDS) {
            add("$label needs a time limit between ${QuestionDraft.MIN_DURATION_SECONDS} and ${QuestionDraft.MAX_DURATION_SECONDS} seconds.")
        }
    }

    /** Returns the hostable stack, or null when [validate] reports a problem. */
    fun toSnapshot(draft: StackDraft): StackSnapshot? {
        if (validate(draft).isNotEmpty()) return null
        return StackSnapshot(
            id = draft.id,
            title = draft.title.trim(),
            description = draft.description.trim(),
            questions = draft.questions.map { it.toQuestion() },
        )
    }

    private fun QuestionDraft.toQuestion(): Question {
        val explanation = explanation.trim().ifEmpty { null }
        return when (type) {
            QuestionType.MULTIPLE_CHOICE -> Question.MultipleChoice(
                id = id,
                prompt = prompt.trim(),
                choices = choices.map { it.copy(text = it.text.trim()) },
                correctChoiceId = checkNotNull(correctChoiceId),
                explanation = explanation,
                points = points,
                durationSeconds = durationSeconds,
            )
            QuestionType.TRUE_FALSE -> Question.TrueFalse(
                id = id,
                prompt = prompt.trim(),
                answer = checkNotNull(trueFalseAnswer),
                explanation = explanation,
                points = points,
                durationSeconds = durationSeconds,
            )
            QuestionType.IDENTIFICATION -> Question.Identification(
                id = id,
                prompt = prompt.trim(),
                answer = identificationAnswer.trim(),
                acceptedAlternatives = acceptedAlternatives.map { it.trim() }.filter { it.isNotEmpty() },
                matching = matching,
                explanation = explanation,
                points = points,
                durationSeconds = durationSeconds,
            )
        }
    }
}

/** Creates new questions and copies. [newId] supplies stable random IDs. */
object QuestionDrafts {
    /** A new multiple-choice question with two empty choices. */
    fun blank(newId: () -> String) = QuestionDraft(
        id = QuestionId(newId()),
        choices = listOf(Choice(ChoiceId(newId()), ""), Choice(ChoiceId(newId()), "")),
    )

    /** Copies a question with new question and choice IDs. The copy keeps the same correct choice. */
    fun duplicate(question: QuestionDraft, newId: () -> String): QuestionDraft {
        var correctChoiceId: ChoiceId? = null
        val choices = question.choices.map { choice ->
            val freshId = ChoiceId(newId())
            if (correctChoiceId == null && choice.id == question.correctChoiceId) {
                correctChoiceId = freshId
            }
            choice.copy(id = freshId)
        }
        return question.copy(
            id = QuestionId(newId()),
            choices = choices,
            correctChoiceId = correctChoiceId,
        )
    }
}

fun Question.toDraft(): QuestionDraft = when (this) {
    is Question.MultipleChoice -> QuestionDraft(
        id = id,
        type = QuestionType.MULTIPLE_CHOICE,
        prompt = prompt,
        choices = choices,
        correctChoiceId = correctChoiceId,
        explanation = explanation.orEmpty(),
        points = points,
        durationSeconds = durationSeconds,
    )
    is Question.TrueFalse -> QuestionDraft(
        id = id,
        type = QuestionType.TRUE_FALSE,
        prompt = prompt,
        trueFalseAnswer = answer,
        explanation = explanation.orEmpty(),
        points = points,
        durationSeconds = durationSeconds,
    )
    is Question.Identification -> QuestionDraft(
        id = id,
        type = QuestionType.IDENTIFICATION,
        prompt = prompt,
        identificationAnswer = answer,
        acceptedAlternatives = acceptedAlternatives,
        matching = matching,
        explanation = explanation.orEmpty(),
        points = points,
        durationSeconds = durationSeconds,
    )
}

fun StackSnapshot.toDraft() = StackDraft(id, title, description, questions.map { it.toDraft() })
