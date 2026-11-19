package io.github.serogee.hive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RoomMode {
    @SerialName("discussion")
    DISCUSSION,

    @SerialName("asynchronous")
    ASYNCHRONOUS,
}

/** Who may join a room. The default is [EVERYONE]. */
@Serializable
enum class AdmissionPolicy {
    @SerialName("everyone")
    EVERYONE,

    @SerialName("signed_in_only")
    SIGNED_IN_ONLY,

    @SerialName("guests_only")
    GUESTS_ONLY,
}

@Serializable
enum class RoomStatus {
    @SerialName("lobby")
    LOBBY,

    @SerialName("live")
    LIVE,

    @SerialName("ended")
    ENDED,
}

@Serializable
enum class RoomRole {
    @SerialName("host")
    HOST,

    @SerialName("player")
    PLAYER,
}
