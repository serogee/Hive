package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(
    val stack: StackSnapshot,
    val mode: RoomMode,
    val admission: AdmissionPolicy = AdmissionPolicy.EVERYONE,
    val title: String? = null,
    /** Required when the caller is not signed in. */
    val hostDisplayName: String? = null,
    /** Epoch milliseconds after which the room accepts no answers. */
    val deadlineAt: Long? = null,
)

/** What anyone with the room code may see before joining. It contains no answers. */
@Serializable
data class RoomSummary(
    val id: RoomId,
    val code: String,
    val title: String,
    val hostName: String,
    val mode: RoomMode,
    val admission: AdmissionPolicy,
    val status: RoomStatus,
    val questionCount: Int,
    val deadlineAt: Long? = null,
)

@Serializable
data class ParticipantView(
    val id: ParticipantId,
    val displayName: String,
    val role: RoomRole,
    val isGuest: Boolean,
)

@Serializable
data class JoinRoomRequest(
    /** Required to join as a guest. A signed-in Player defaults to the account name. */
    val displayName: String? = null,
    val asGuest: Boolean,
)

/** Returned when a room is created or joined. Keep [participantToken] to reconnect. */
@Serializable
data class Membership(
    val room: RoomSummary,
    val participant: ParticipantView,
    val participantToken: String,
)

/** The room as one participant may currently see it. */
@Serializable
data class RoomState(
    val room: RoomSummary,
    val you: ParticipantView,
    val participants: List<ParticipantView>,
    val revision: Long,
)
