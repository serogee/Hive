package io.github.serogee.hive.data.remote

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AccountProfile
import io.github.serogee.hive.core.api.AppendRecordsRequest
import io.github.serogee.hive.core.api.AppendRecordsResponse
import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.DemoLoginRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HealthResponse
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.model.RoomId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** A failure that the server described with an [ApiError]. */
class ApiException(val error: ApiError) : Exception(error.message)

/** A client that reads and writes JSON, and gives up on a server that does not answer. */
fun jsonHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(ContentNegotiation) { json(HiveJson) }
    install(WebSockets) { pingIntervalMillis = 15.seconds.inWholeMilliseconds }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 10_000
    }
}

/**
 * HTTP and WebSocket client for any Hive server: demo, production, or a Host phone.
 *
 * Screens never see which kind of server this is.
 */
class HiveApi(
    private val serverUrl: suspend () -> String,
    private val accessToken: suspend () -> String?,
    /** Called with the refused bearer token. Returns true if a fresh token is now ready to retry with. */
    private val onSessionExpired: suspend (refusedToken: String?) -> Boolean,
    private val http: HttpClient = jsonHttpClient(),
) {

    suspend fun health(url: String): HealthResponse =
        http.get(url.trimEnd('/') + ApiPaths.HEALTH).unwrap()

    suspend fun demoLogin(username: String, password: String): AuthSession =
        http.post(url(ApiPaths.DEMO_LOGIN)) {
            contentType(ContentType.Application.Json)
            setBody(DemoLoginRequest(username, password))
        }.unwrap()

    suspend fun demoLogout(token: String) {
        http.post(url(ApiPaths.DEMO_LOGOUT)) { bearerAuth(token) }
    }

    suspend fun me(token: String): Account = http.get(url(ApiPaths.ME)) { bearerAuth(token) }.unwrap()

    /*
     * Room calls take a [server]. Null means the configured server, which also receives the account's
     * bearer token. Any other address, such as a Host phone, never receives it.
     */

    suspend fun lookupRoom(code: String, server: String? = null): RoomSummary =
        http.get(url(ApiPaths.roomByCode(code), server)).unwrap()

    suspend fun createRoom(request: CreateRoomRequest, server: String? = null): Membership =
        request(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.ROOMS, server)) {
                signedIn(server)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun hostedRooms(): List<RoomSummary> =
        request { http.get(url(ApiPaths.HOSTED_ROOMS)) { signedIn(null) } }

    suspend fun join(roomId: RoomId, request: JoinRoomRequest, existingToken: String?, server: String? = null): Membership =
        request(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.participants(roomId.value), server)) {
                signedIn(server)
                participant(existingToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun roomState(roomId: RoomId, participantToken: String?, server: String? = null): RoomState =
        request(retryOnRefresh = server == null) {
            http.get(url(ApiPaths.state(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
            }
        }

    suspend fun command(roomId: RoomId, command: HostCommand, participantToken: String?, server: String? = null): RoomState =
        request(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.commands(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
                contentType(ContentType.Application.Json)
                setBody(command)
            }
        }

    suspend fun leave(roomId: RoomId, participantToken: String?, server: String? = null) {
        requestUnit(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.leave(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
            }
        }
    }

    suspend fun attempt(roomId: RoomId, command: PlayerCommand, participantToken: String?, server: String? = null): RoomState =
        request(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.attempt(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
                contentType(ContentType.Application.Json)
                setBody(command)
            }
        }

    suspend fun submitAnswer(
        roomId: RoomId,
        request: SubmitAnswerRequest,
        participantToken: String?,
        server: String? = null,
    ): AnswerReceipt =
        request(retryOnRefresh = server == null) {
            http.post(url(ApiPaths.answers(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun results(roomId: RoomId, participantToken: String?, server: String? = null): RoomResults =
        request(retryOnRefresh = server == null) {
            http.get(url(ApiPaths.results(roomId.value), server)) {
                signedIn(server)
                participant(participantToken)
            }
        }

    suspend fun syncChanges(cursor: Long): SyncChanges = request {
        http.get(url(ApiPaths.SYNC_CHANGES)) {
            signedIn(null)
            parameter("cursor", cursor)
        }
    }

    suspend fun pushStacks(request: PushStacksRequest): PushStacksResponse = request {
        http.post(url(ApiPaths.SYNC_STACKS)) {
            signedIn(null)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun appendRecords(path: String, records: List<SyncRecord>): AppendRecordsResponse = request {
        http.post(url(path)) {
            signedIn(null)
            contentType(ContentType.Application.Json)
            setBody(AppendRecordsRequest(records))
        }
    }

    suspend fun onlineRooms(): List<OnlineRoomEntry> =
        request { http.get(url(ApiPaths.SYNC_ROOMS)) { signedIn(null) } }

    suspend fun accountProfile(): AccountProfile =
        request { http.get(url(ApiPaths.ACCOUNT_PROFILE)) { signedIn(null) } }

    suspend fun setAccountProfile(profile: AccountProfile): AccountProfile = request {
        http.put(url(ApiPaths.ACCOUNT_PROFILE)) {
            signedIn(null)
            contentType(ContentType.Application.Json)
            setBody(profile)
        }
    }

    /** Deletes the signed-in account, and everything the server keeps about it. */
    suspend fun deleteAccount() {
        requestUnit { http.delete(url(ApiPaths.ACCOUNT)) { signedIn(null) } }
    }

    /** Deletes everything the account synced. */
    suspend fun deleteSyncData() {
        requestUnit { http.delete(url(ApiPaths.SYNC)) { signedIn(null) } }
    }

    /** Live room events. The flow ends when the connection closes. */
    fun roomEvents(roomId: RoomId, participantToken: String?, server: String? = null): Flow<RoomEvent> = channelFlow {
        val wsUrl = url(ApiPaths.events(roomId.value), server).replaceFirst("http", "ws")
        val token = if (server == null) accessToken() else null
        http.webSocket(wsUrl, request = {
            token?.let { bearerAuth(it) }
            participant(participantToken)
        }) {
            for (frame in incoming) {
                if (frame is Frame.Text) send(HiveJson.decodeFromString(RoomEvent.serializer(), frame.readText()))
            }
        }
    }

    private suspend fun url(path: String, server: String? = null) = (server ?: serverUrl()).trimEnd('/') + path

    private suspend fun HttpRequestBuilder.signedIn(server: String?) {
        if (server == null) accessToken()?.let { bearerAuth(it) }
    }

    private fun HttpRequestBuilder.participant(token: String?) {
        if (token != null) header(ApiPaths.PARTICIPANT_TOKEN_HEADER, token)
    }

    private fun HttpResponse.bearerToken(): String? =
        request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private suspend fun HttpResponse.apiError(): ApiError = runCatching { body<ApiError>() }.getOrElse {
        ApiError(ErrorCode.INVALID_REQUEST, "The server answered with HTTP ${status.value}.")
    }

    private suspend inline fun <reified T> request(retryOnRefresh: Boolean = true, crossinline call: suspend () -> HttpResponse): T {
        val first = call()
        if (first.status.isSuccess()) return first.body()
        val error = first.apiError()
        if (error.code == ErrorCode.SESSION_EXPIRED) {
            val refreshed = onSessionExpired(first.bearerToken())
            if (retryOnRefresh && refreshed) {
                val retry = call()
                if (retry.status.isSuccess()) return retry.body()
                val retryError = retry.apiError()
                if (retryError.code == ErrorCode.SESSION_EXPIRED) onSessionExpired(retry.bearerToken())
                throw ApiException(retryError)
            }
        }
        throw ApiException(error)
    }

    private suspend inline fun requestUnit(retryOnRefresh: Boolean = true, crossinline call: suspend () -> HttpResponse) {
        val first = call()
        if (first.status.isSuccess()) return
        val error = first.apiError()
        if (error.code == ErrorCode.SESSION_EXPIRED) {
            val refreshed = onSessionExpired(first.bearerToken())
            if (retryOnRefresh && refreshed) {
                val retry = call()
                if (retry.status.isSuccess()) return
                val retryError = retry.apiError()
                if (retryError.code == ErrorCode.SESSION_EXPIRED) onSessionExpired(retry.bearerToken())
                throw ApiException(retryError)
            }
        }
        throw ApiException(error)
    }

    private suspend inline fun <reified T> HttpResponse.unwrap(): T {
        if (status.isSuccess()) return body()
        val error = apiError()
        if (error.code == ErrorCode.SESSION_EXPIRED) onSessionExpired(bearerToken())
        throw ApiException(error)
    }
}
