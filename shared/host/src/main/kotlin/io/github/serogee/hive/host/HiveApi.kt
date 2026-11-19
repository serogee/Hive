package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** Installs the JSON, error, and WebSocket handling that every Hive server uses. */
fun Application.installHiveApi() {
    install(ContentNegotiation) { json(HiveJson) }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    install(StatusPages) {
        exception<HiveException> { call, cause ->
            call.respond(cause.code.httpStatus(), cause.toApiError())
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(ErrorCode.INVALID_REQUEST, "The request is not valid."),
            )
        }
    }
}

fun ErrorCode.httpStatus(): HttpStatusCode = when (this) {
    ErrorCode.INVALID_REQUEST -> HttpStatusCode.BadRequest
    ErrorCode.INVALID_CREDENTIALS,
    ErrorCode.SESSION_EXPIRED,
    ErrorCode.SIGN_IN_REQUIRED,
    -> HttpStatusCode.Unauthorized
    ErrorCode.NOT_A_PARTICIPANT,
    ErrorCode.FORBIDDEN,
    ErrorCode.GUESTS_NOT_ALLOWED,
    ErrorCode.GUESTS_ONLY,
    -> HttpStatusCode.Forbidden
    ErrorCode.ROOM_NOT_FOUND -> HttpStatusCode.NotFound
    ErrorCode.ROOM_CLOSED,
    ErrorCode.INVALID_COMMAND,
    ErrorCode.QUESTION_NOT_OPEN,
    ErrorCode.ANSWER_ALREADY_RECORDED,
    ErrorCode.RESULTS_NOT_READY,
    -> HttpStatusCode.Conflict
}
