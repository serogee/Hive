package io.github.serogee.hive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An immutable copy of a stack, as used by a room or a shared copy. */
@Serializable
data class StackSnapshot(
    val id: StackId,
    val title: String,
    val description: String = "",
    val questions: List<Question>,
)

@Serializable
data class Choice(
    val id: ChoiceId,
    val text: String,
)

/** How many typos in a typed answer still count as correct. Longer answers allow more. */
@Serializable
enum class TypoTolerance {
    @SerialName("off")
    OFF,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
    ;

    /** Typos allowed for a correct answer of [length] characters. */
    fun allowed(length: Int): Int = when (this) {
        OFF -> 0
        LOW -> if (length >= 5) 1 else 0
        MEDIUM -> when {
            length >= 8 -> 2
            length >= 4 -> 1
            else -> 0
        }
        HIGH -> when {
            length >= 10 -> 3
            length >= 6 -> 2
            length >= 4 -> 1
            else -> 0
        }
    }
}

/** How a typed answer is compared with the accepted answers. Accents and extra spaces never count. */
@Serializable
data class AnswerMatching(
    val ignoreCase: Boolean = true,
    val ignoreSymbols: Boolean = false,
    val typos: TypoTolerance = TypoTolerance.OFF,
)

/**
 * One authored question. The same question is a quiz item and a review card.
 *
 * [points] defaults to 1. A null [durationSeconds] means no time limit.
 */
@Serializable
sealed interface Question {
    val id: QuestionId
    val prompt: String
    val explanation: String?
    val points: Int
    val durationSeconds: Int?

    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(
        override val id: QuestionId,
        override val prompt: String,
        val choices: List<Choice>,
        val correctChoiceId: ChoiceId,
        override val explanation: String? = null,
        override val points: Int = 1,
        override val durationSeconds: Int? = null,
    ) : Question

    @Serializable
    @SerialName("true_false")
    data class TrueFalse(
        override val id: QuestionId,
        override val prompt: String,
        val answer: Boolean,
        override val explanation: String? = null,
        override val points: Int = 1,
        override val durationSeconds: Int? = null,
    ) : Question

    @Serializable
    @SerialName("identification")
    data class Identification(
        override val id: QuestionId,
        override val prompt: String,
        val answer: String,
        val acceptedAlternatives: List<String> = emptyList(),
        val matching: AnswerMatching = AnswerMatching(),
        override val explanation: String? = null,
        override val points: Int = 1,
        override val durationSeconds: Int? = null,
    ) : Question
}
