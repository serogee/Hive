package io.github.serogee.hive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Player's response to one question. */
@Serializable
sealed interface Answer {
    @Serializable
    @SerialName("choice")
    data class SelectedChoice(val choiceId: ChoiceId) : Answer

    @Serializable
    @SerialName("true_false")
    data class TrueFalse(val value: Boolean) : Answer

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Answer
}

/** When Players may see the correct answers. The default is [AT_END]. */
@Serializable
enum class RevealPolicy {
    @SerialName("after_each_question")
    AFTER_EACH_QUESTION,

    @SerialName("at_end")
    AT_END,
}
