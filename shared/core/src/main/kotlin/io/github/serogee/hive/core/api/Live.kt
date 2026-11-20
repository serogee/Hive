package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.SubmissionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A question as a Player may see it while answering. It contains no answer key. */
@Serializable
sealed interface PlayerQuestion {
    val id: QuestionId

    /** Position in the room's stack, starting at 1. */
    val number: Int
    val prompt: String
    val points: Int
    val durationSeconds: Int?

    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(
        override val id: QuestionId,
        override val number: Int,
        override val prompt: String,
        val choices: List<Choice>,
        override val points: Int,
        override val durationSeconds: Int? = null,
    ) : PlayerQuestion

    @Serializable
    @SerialName("true_false")
    data class TrueFalse(
        override val id: QuestionId,
        override val number: Int,
        override val prompt: String,
        override val points: Int,
        override val durationSeconds: Int? = null,
    ) : PlayerQuestion

    @Serializable
    @SerialName("identification")
    data class Identification(
        override val id: QuestionId,
        override val number: Int,
        override val prompt: String,
        override val points: Int,
        override val durationSeconds: Int? = null,
    ) : PlayerQuestion
}

fun Question.toPlayerQuestion(number: Int): PlayerQuestion = when (this) {
    is Question.MultipleChoice -> PlayerQuestion.MultipleChoice(id, number, prompt, choices, points, durationSeconds)
    is Question.TrueFalse -> PlayerQuestion.TrueFalse(id, number, prompt, points, durationSeconds)
    is Question.Identification -> PlayerQuestion.Identification(id, number, prompt, points, durationSeconds)
}

/** The canonical correct answer, used when answers are revealed. */
fun Question.correctAnswer(): Answer = when (this) {
    is Question.MultipleChoice -> Answer.SelectedChoice(correctChoiceId)
    is Question.TrueFalse -> Answer.TrueFalse(answer)
    is Question.Identification -> Answer.Text(answer)
}

/** Text for showing [answer] to a person, such as the choice's wording. */
fun Question.describe(answer: Answer): String = when (answer) {
    is Answer.SelectedChoice -> (this as? Question.MultipleChoice)?.choices
        ?.firstOrNull { it.id == answer.choiceId }?.text ?: "Unknown choice"
    is Answer.TrueFalse -> if (answer.value) "True" else "False"
    is Answer.Text -> answer.text
}

/** Text for showing [answer] to a person, such as the choice's wording. */
fun PlayerQuestion.describe(answer: Answer): String = when (answer) {
    is Answer.SelectedChoice -> (this as? PlayerQuestion.MultipleChoice)?.choices
        ?.firstOrNull { it.id == answer.choiceId }?.text ?: "Unknown choice"
    is Answer.TrueFalse -> if (answer.value) "True" else "False"
    is Answer.Text -> answer.text
}

/** Actions only the Host may take. */
@Serializable
sealed interface HostCommand {
    /** Opens the first question. */
    @Serializable
    @SerialName("start")
    data object Start : HostCommand

    /** Stops accepting answers. The question stays visible for discussion. */
    @Serializable
    @SerialName("close_question")
    data object CloseQuestion : HostCommand

    @Serializable
    @SerialName("next_question")
    data object NextQuestion : HostCommand

    /** Ends the room for everyone. Accepted answers are kept. */
    @Serializable
    @SerialName("end_room")
    data object EndRoom : HostCommand
}

/** Actions a Player takes in an Asynchronous room, at their own pace. */
@Serializable
sealed interface PlayerCommand {
    /** Opens the first question for this Player. */
    @Serializable
    @SerialName("start_attempt")
    data object StartAttempt : PlayerCommand

    /** Opens this Player's next question, or finishes the attempt after the last one. */
    @Serializable
    @SerialName("next_question")
    data object NextQuestion : PlayerCommand
}

/** A Player's own progress through an Asynchronous room. */
@Serializable
data class AttemptView(
    val started: Boolean,
    val finished: Boolean,
    val answeredCount: Int,
)

/** One Player's progress, as the Host sees it in an Asynchronous room. */
@Serializable
data class PlayerProgressView(
    val participantId: ParticipantId,
    val displayName: String,
    /** The number of the question the Player has open, or null before starting. */
    val currentQuestion: Int?,
    val answeredCount: Int,
    val finished: Boolean,
)

@Serializable
data class SubmitAnswerRequest(
    val questionId: QuestionId,
    val answer: Answer,
    /** Chosen by the client once per answer. A retry with the same key returns the first receipt. */
    val idempotencyKey: String,
)

/** Proof that the room server stored an answer. Sent only after the answer is saved. */
@Serializable
data class AnswerReceipt(
    val submissionId: SubmissionId,
    val questionId: QuestionId,
    val answer: Answer,
    val acceptedAt: Long,
)

/** The current question as one participant may see it. */
@Serializable
data class LiveQuestion(
    val question: PlayerQuestion,
    val isOpen: Boolean,
    val questionCount: Int,
    val answeredCount: Int,
    val playerCount: Int,
    val yourAnswer: AnswerReceipt? = null,
    /** Epoch milliseconds when answers stop, from the room server's clock. Null for no limit. */
    val closesAt: Long? = null,
    /** The room server's clock when it sent this state, so clients can correct their countdown. */
    val serverTime: Long = 0,
    /** Sent to the Host, and to Players when the reveal policy allows it. */
    val correctAnswer: Answer? = null,
    val explanation: String? = null,
)

@Serializable
data class LeaderboardEntry(
    val participantId: ParticipantId,
    val displayName: String,
    val isGuest: Boolean,
    val score: Int,
    /** Players with equal scores share a rank unless the answer-time tiebreak orders them. */
    val rank: Int,
    val correctCount: Int,
    /** Speed bonus in tenths of a point. */
    val speedBonusTenths: Int = 0,
    /** Base points plus speed bonus, in tenths of a point. */
    val totalTenths: Int = score * 10,
    /** Total answer time on correct answers, used to order equal scores. Null when unknown. */
    val answerTimeMillis: Long? = null,
)

@Serializable
data class QuestionResult(
    val questionId: QuestionId,
    val number: Int,
    val prompt: String,
    val answeredCount: Int,
    val correctCount: Int,
    val correctAnswer: Answer,
    val correctAnswerText: String,
    val explanation: String? = null,
)

@Serializable
data class AnswerResult(
    val questionId: QuestionId,
    val answer: Answer?,
    val answerText: String?,
    val correct: Boolean,
    val points: Int,
    val speedBonusTenths: Int = 0,
)

@Serializable
data class RoomResults(
    val room: RoomSummary,
    val leaderboard: List<LeaderboardEntry>,
    val questions: List<QuestionResult>,
    /** The caller's own answers. Empty for the Host. */
    val yourAnswers: List<AnswerResult> = emptyList(),
)
