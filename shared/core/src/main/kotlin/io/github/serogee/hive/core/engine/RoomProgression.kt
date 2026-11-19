package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.model.RoomStatus

/** Where a Discussion room is in its stack. [questionIndex] is null before the first question. */
data class Progress(
    val status: RoomStatus,
    val questionIndex: Int?,
    val questionOpen: Boolean,
) {
    companion object {
        val Lobby = Progress(RoomStatus.LOBBY, questionIndex = null, questionOpen = false)
    }
}

sealed interface Transition {
    data class Accepted(val progress: Progress) : Transition

    data class Rejected(val reason: String) : Transition
}

/** Host-paced room transitions. The Host opens, closes, and advances each question. */
object RoomProgression {
    fun apply(current: Progress, command: HostCommand, questionCount: Int): Transition = when (command) {
        HostCommand.Start -> when {
            current.status != RoomStatus.LOBBY -> Transition.Rejected("The quiz has already started.")
            questionCount == 0 -> Transition.Rejected("The stack has no questions.")
            else -> Transition.Accepted(Progress(RoomStatus.LIVE, questionIndex = 0, questionOpen = true))
        }

        HostCommand.CloseQuestion -> when {
            current.status != RoomStatus.LIVE || !current.questionOpen ->
                Transition.Rejected("No question is open.")
            else -> Transition.Accepted(current.copy(questionOpen = false))
        }

        HostCommand.NextQuestion -> {
            val index = current.questionIndex
            when {
                current.status != RoomStatus.LIVE || index == null -> Transition.Rejected("The quiz is not live.")
                current.questionOpen -> Transition.Rejected("Close the current question first.")
                index + 1 >= questionCount -> Transition.Rejected("That was the last question. End the quiz to show results.")
                else -> Transition.Accepted(current.copy(questionIndex = index + 1, questionOpen = true))
            }
        }

        HostCommand.EndRoom -> when (current.status) {
            RoomStatus.ENDED -> Transition.Rejected("The quiz has already ended.")
            else -> Transition.Accepted(current.copy(status = RoomStatus.ENDED, questionOpen = false))
        }
    }
}
