package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A live update sent over the room's WebSocket.
 *
 * Events are hints. After a reconnect, clients get the current [RoomState] so that a missed event
 * cannot leave them out of date. [revision] increases with every change to the room.
 */
@Serializable
sealed interface RoomEvent {
    val roomId: RoomId
    val revision: Long

    /** The first event on every connection. Live events after it are not missed. */
    @Serializable
    @SerialName("subscribed")
    data class Subscribed(
        override val roomId: RoomId,
        override val revision: Long,
    ) : RoomEvent

    @Serializable
    @SerialName("participant_joined")
    data class ParticipantJoined(
        override val roomId: RoomId,
        override val revision: Long,
        val participant: ParticipantView,
    ) : RoomEvent

    @Serializable
    @SerialName("question_opened")
    data class QuestionOpened(
        override val roomId: RoomId,
        override val revision: Long,
        val questionNumber: Int,
    ) : RoomEvent

    /** Sent after an answer is saved. It does not say who answered or what. */
    @Serializable
    @SerialName("answer_recorded")
    data class AnswerRecorded(
        override val roomId: RoomId,
        override val revision: Long,
        val questionNumber: Int,
        val answeredCount: Int,
    ) : RoomEvent

    @Serializable
    @SerialName("question_closed")
    data class QuestionClosed(
        override val roomId: RoomId,
        override val revision: Long,
        val questionNumber: Int,
    ) : RoomEvent

    /** A Player started, advanced, or finished an attempt in an Asynchronous room. */
    @Serializable
    @SerialName("attempt_updated")
    data class AttemptUpdated(
        override val roomId: RoomId,
        override val revision: Long,
        val participantId: ParticipantId,
    ) : RoomEvent

    @Serializable
    @SerialName("room_ended")
    data class RoomEnded(
        override val roomId: RoomId,
        override val revision: Long,
    ) : RoomEvent
}
