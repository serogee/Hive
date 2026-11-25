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

    @SerialName("invalid_command")
    INVALID_COMMAND,

    @SerialName("question_not_open")
    QUESTION_NOT_OPEN,

    @SerialName("answer_already_recorded")
    ANSWER_ALREADY_RECORDED,

    @SerialName("results_not_ready")
    RESULTS_NOT_READY,

    @SerialName("already_in_room")
    ALREADY_IN_ROOM,

    /** The server was started without its sign-in settings. */
    @SerialName("auth_not_configured")
    AUTH_NOT_CONFIGURED,

    /** The server cannot check sign-ins now. The session can still be valid. */
    @SerialName("auth_unavailable")
    AUTH_UNAVAILABLE,
}

/** The body of every unsuccessful response. */
@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
)
