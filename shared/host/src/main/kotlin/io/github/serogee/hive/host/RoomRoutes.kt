package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.RoomId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Room endpoints. Mount inside `route("/v1")`. Pass null [accounts] on a server without accounts,
 * such as the embedded host on a phone.
 */
fun Route.roomRoutes(service: RoomService, accounts: AccountVerifier?) {
    route("/rooms") {
        post {
            // Only a server with accounts needs one here. A phone lets anyone host.
            requireSignInSettings(accounts)
            val caller = call.account(accounts)
            val request = call.receive<CreateRoomRequest>()
            call.respond(HttpStatusCode.Created, service.createRoom(caller, request))
        }

        get("/hosted") {
            val caller = call.requireAccount(accounts, "Sign in to see rooms you host.")
            call.respond(service.hostedRooms(caller))
        }

        get("/by-code/{code}") {
            call.respond(service.lookup(call.parameters["code"].orEmpty()))
        }

        route("/{id}") {
            post("/participants") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                val caller = call.account(accounts)
                val request = call.receive<JoinRoomRequest>()
                if (!request.asGuest) requireSignInSettings(accounts)
                call.respond(service.join(roomId, caller, request, call.participantToken()))
            }

            get("/state") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                call.respond(service.state(roomId, call.account(accounts), call.participantToken()))
            }

            post("/commands") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                val command = call.receive<HostCommand>()
                call.respond(service.command(roomId, call.account(accounts), call.participantToken(), command))
            }

            post("/answers") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                val request = call.receive<SubmitAnswerRequest>()
                call.respond(service.submitAnswer(roomId, call.account(accounts), call.participantToken(), request))
            }

            post("/leave") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                service.leave(roomId, call.account(accounts), call.participantToken())
                call.respond(HttpStatusCode.NoContent)
            }

            post("/attempt") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                val command = call.receive<PlayerCommand>()
                call.respond(service.attempt(roomId, call.account(accounts), call.participantToken(), command))
            }

            get("/results") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                call.respond(service.results(roomId, call.account(accounts), call.participantToken()))
            }

            webSocket("/events") {
                val roomId = RoomId(call.parameters["id"].orEmpty())
                val caller = try {
                    call.account(accounts)
                } catch (e: HiveException) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code.name))
                    return@webSocket
                }
                val token = call.participantToken()
                val authorized = runCatching { service.authorize(roomId, caller, token) }
                if (authorized.isFailure) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ErrorCode.NOT_A_PARTICIPANT.name))
                    return@webSocket
                }

                suspend fun sendEvent(event: RoomEvent) =
                    send(Frame.Text(HiveJson.encodeToString(RoomEvent.serializer(), event)))

                // Subscribe before reading the revision, so no event falls between the two.
                val forwarding = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events(roomId).collect { sendEvent(it) }
                }
                sendEvent(RoomEvent.Subscribed(roomId, service.state(roomId, caller, token).revision))
                try {
                    for (frame in incoming) {
                        // Clients send nothing on this channel; reading detects the close.
                    }
                } finally {
                    forwarding.cancel()
                }
            }
        }
    }
}
