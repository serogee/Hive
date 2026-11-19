package io.github.serogee.hive.core.api

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
}
