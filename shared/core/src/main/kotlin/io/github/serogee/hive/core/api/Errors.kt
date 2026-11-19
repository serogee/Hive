package io.github.serogee.hive.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    @SerialName("invalid_request")
    INVALID_REQUEST,

    @SerialName("invalid_credentials")
    INVALID_CREDENTIALS,

    @SerialName("session_expired")
    SESSION_EXPIRED,

    @SerialName("sign_in_required")
    SIGN_IN_REQUIRED,

    @SerialName("not_a_participant")
    NOT_A_PARTICIPANT,

    @SerialName("forbidden")
    FORBIDDEN,

    @SerialName("room_not_found")
    ROOM_NOT_FOUND,

    @SerialName("room_closed")
    ROOM_CLOSED,

    @SerialName("guests_not_allowed")
    GUESTS_NOT_ALLOWED,

    @SerialName("guests_only")
    GUESTS_ONLY,
}

/** The body of every unsuccessful response. */
@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
)
