package io.github.serogee.hive.core.review

import io.github.serogee.hive.core.engine.Grading
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackSnapshot
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewMode {
    @SerialName("quiz")
    QUIZ,

    @SerialName("flashcard")
    FLASHCARD,
}

@Serializable
data class ReviewSettings(
    val mode: ReviewMode = ReviewMode.QUIZ,
    /** Answer every question by typing. The original choices stay available with Show choices. */
    val preferTyping: Boolean = false,
    val shuffled: Boolean = false,
)

/** How one card went. Quiz Review scores answers; Flashcard Review records the learner's own mark. */
@Serializable
enum class Outcome {
    @SerialName("correct")
    CORRECT,

    @SerialName("incorrect")
    INCORRECT,

    @SerialName("got_it")
    GOT_IT,

    @SerialName("review_again")
    REVIEW_AGAIN,
}

/**
 * One pass through a stack. It keeps its own copy of the questions and the order actually used,
 * so resuming returns to the same item even after the stack changes.
 */
@Serializable
data class ReviewAttempt(
    val id: String,
    val stack: StackSnapshot,
    val settings: ReviewSettings,
    val order: List<QuestionId>,
    /** Index into [order] of the current card. Equal to the size of [order] when finished. */
    val position: Int = 0,
    val outcomes: Map<String, Outcome> = emptyMap(),
    /** Saved answers in Quiz Review, so pausing or stepping back still shows what was submitted. */
    val answers: Map<String, Answer> = emptyMap(),
) {
    val isFinished: Boolean get() = position >= order.size

    val current: Question? get() = order.getOrNull(position)?.let { id -> stack.questions.firstOrNull { it.id == id } }

    fun outcomeOf(id: QuestionId): Outcome? = outcomes[id.value]

    fun answerOf(id: QuestionId): Answer? = answers[id.value]

    /** Cards already answered or marked, counting immediately upon submission rather than after advancing. */
    val answeredCount: Int get() = order.count { it.value in outcomes }

    val canGoPrevious: Boolean get() = position > 0 && !isFinished

    val correctCount: Int get() = outcomes.values.count { it == Outcome.CORRECT || it == Outcome.GOT_IT }

    /** Missed questions, cards marked Review again, and (once finished) unanswered cards. */
    val toRetry: List<QuestionId>
        get() = order.filter {
            val outcome = outcomes[it.value]
            outcome == Outcome.INCORRECT || outcome == Outcome.REVIEW_AGAIN || (isFinished && outcome == null)
        }
}

object Review {
    fun start(id: String, stack: StackSnapshot, settings: ReviewSettings, random: Random): ReviewAttempt {
        val ids = stack.questions.map { it.id }
        return ReviewAttempt(id, stack, settings, if (settings.shuffled) ids.shuffled(random) else ids)
    }

    /** Checks a Quiz Review answer and records the result. The card stays current until [next]. */
    fun answer(attempt: ReviewAttempt, answer: Answer): Pair<ReviewAttempt, Boolean> {
        val question = checkNotNull(attempt.current) { "The review has finished." }
        val correct = Grading.isCorrect(question, answer)
        val outcome = if (correct) Outcome.CORRECT else Outcome.INCORRECT
        return attempt.copy(
            outcomes = attempt.outcomes + (question.id.value to outcome),
            answers = attempt.answers + (question.id.value to answer),
        ) to correct
    }

    /** Records a Flashcard Review mark and moves to the next card. */
    fun mark(attempt: ReviewAttempt, gotIt: Boolean): ReviewAttempt {
        val question = checkNotNull(attempt.current) { "The review has finished." }
        val outcome = if (gotIt) Outcome.GOT_IT else Outcome.REVIEW_AGAIN
        return next(attempt.copy(outcomes = attempt.outcomes + (question.id.value to outcome)))
    }

    fun next(attempt: ReviewAttempt): ReviewAttempt = attempt.copy(position = (attempt.position + 1).coerceAtMost(attempt.order.size))

    /** Steps back to the previous card so the learner can inspect past answers. */
    fun previous(attempt: ReviewAttempt): ReviewAttempt =
        if (attempt.isFinished) attempt else attempt.copy(position = (attempt.position - 1).coerceAtLeast(0))

    /** Ends an ongoing review at its current progress. */
    fun finish(attempt: ReviewAttempt): ReviewAttempt = attempt.copy(position = attempt.order.size)

    /** A new attempt with only the missed or marked cards, in the same order. */
    fun retry(id: String, attempt: ReviewAttempt): ReviewAttempt =
        ReviewAttempt(id, attempt.stack, attempt.settings, attempt.toRetry)
}
