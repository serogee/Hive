package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomRoutesTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val verifier = AccountVerifier { token -> alex.takeIf { token == "alex-token" } }

    private fun ApplicationTestBuilder.hiveClient(): HttpClient {
        application {
            installHiveApi()
            routing {
                route("/v1") {
                    healthRoutes()
                    roomRoutes(RoomService(InMemoryRoomStore(), requireAccountToHost = true), verifier)
                }
            }
        }
        return createClient {
            install(ContentNegotiation) { json(HiveJson) }
            install(WebSockets)
        }
    }

    @Test
    fun hostCreatesAndGuestJoinsOverHttp() = testApplication {
        val client = hiveClient()

        val host: Membership = client.post(ApiPaths.ROOMS) {
            bearerAuth("alex-token")
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION))
        }.body()
        val summary: RoomSummary = client.get(ApiPaths.roomByCode(host.room.code)).body()
        val guest: Membership = client.post(ApiPaths.participants(summary.id.value)) {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest("Jamie", asGuest = true))
        }.body()
        val hostState: RoomState = client.get(ApiPaths.state(summary.id.value)) { bearerAuth("alex-token") }.body()

        assertEquals("Alex Rivera", summary.hostName)
        assertEquals(listOf("Alex Rivera", "Jamie"), hostState.participants.map { it.displayName })
        assertEquals(guest.participant, hostState.participants.last())
    }

    @Test
    fun errorsUseTheSharedErrorBody() = testApplication {
        val client = hiveClient()

        val missing = client.get(ApiPaths.roomByCode("ZZZZZZ"))
        val signedOut = client.post(ApiPaths.ROOMS) {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION))
        }
        val expired = client.get(ApiPaths.HOSTED_ROOMS) { bearerAuth("old-token") }

        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(ErrorCode.ROOM_NOT_FOUND, missing.body<ApiError>().code)
        assertEquals(ErrorCode.SIGN_IN_REQUIRED, signedOut.body<ApiError>().code)
        assertEquals(ErrorCode.SESSION_EXPIRED, expired.body<ApiError>().code)
    }

    @Test
    fun hostReceivesJoinEvents() = testApplication {
        val client = hiveClient()
        val host: Membership = client.post(ApiPaths.ROOMS) {
            bearerAuth("alex-token")
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION))
        }.body()

        client.webSocket(ApiPaths.events(host.room.id.value), request = {
            header(ApiPaths.PARTICIPANT_TOKEN_HEADER, host.participantToken)
        }) {
            val first = HiveJson.decodeFromString(RoomEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            assertEquals(RoomEvent.Subscribed(host.room.id, 1), first)

            client.post(ApiPaths.participants(host.room.id.value)) {
                contentType(ContentType.Application.Json)
                setBody(JoinRoomRequest("Jamie", asGuest = true))
            }
            val frame = incoming.receive() as Frame.Text
            val event = HiveJson.decodeFromString(RoomEvent.serializer(), frame.readText())

            assertEquals("Jamie", (event as RoomEvent.ParticipantJoined).participant.displayName)
        }
    }
}
