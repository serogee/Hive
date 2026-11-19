package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.engine.Admission
import io.github.serogee.hive.core.engine.AdmissionDecision
import io.github.serogee.hive.core.engine.Clock
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room behavior shared by every hosting mode.
 *
 * The routes call this service, and this service calls the plain Kotlin rules in the core module.
 * It holds no HTTP or database details.
 */
class RoomService(
    private val store: RoomStore,
    private val hub: RoomEventHub = RoomEventHub(),
    private val clock: Clock = Clock.System,
    private val random: Random = SecureRandom().asKotlinRandom(),
    /** True on the online server. A local host lets anyone host. */
    private val requireAccountToHost: Boolean,
) {
    private val createLock = Mutex()
    private val roomLocks = ConcurrentHashMap<RoomId, Mutex>()

    private fun lockFor(roomId: RoomId) = roomLocks.computeIfAbsent(roomId) { Mutex() }

    fun events(roomId: RoomId): SharedFlow<RoomEvent> = hub.events(roomId)

    suspend fun createRoom(caller: Account?, request: CreateRoomRequest): Membership {
        if (requireAccountToHost && caller == null) {
            throw HiveException(ErrorCode.SIGN_IN_REQUIRED, "Sign in to host an online room.")
        }
        if (request.stack.questions.isEmpty()) {
            throw HiveException(ErrorCode.INVALID_REQUEST, "The stack has no questions.")
        }
        val hostName = DisplayNames.normalize(caller?.displayName ?: request.hostDisplayName)
            ?: throw HiveException(ErrorCode.INVALID_REQUEST, "Enter a host name.")
        val title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: request.stack.title
        val now = clock.nowMillis()
        val token = Tokens.newToken()

        return createLock.withLock {
            var code = RoomCodes.generate(random)
            while (store.findRoomByCode(code) != null) code = RoomCodes.generate(random)

            val room = RoomRecord(
                id = RoomId(UUID.randomUUID().toString()),
                code = code,
                title = title,
                hostName = hostName,
                hostAccountId = caller?.id,
                mode = request.mode,
                admission = request.admission,
                status = RoomStatus.LOBBY,
                stack = request.stack,
                createdAt = now,
                deadlineAt = request.deadlineAt,
                revision = 1,
            )
            val host = ParticipantRecord(
                id = ParticipantId(UUID.randomUUID().toString()),
                roomId = room.id,
                displayName = hostName,
                role = RoomRole.HOST,
                accountId = caller?.id,
                tokenHash = Tokens.hash(token),
                joinedAt = now,
            )
            store.insertRoom(room, host)
            Membership(room.summary(), host.view(), token)
        }
    }

    suspend fun lookup(rawCode: String): RoomSummary {
        val room = store.findRoomByCode(RoomCodes.normalize(rawCode))
            ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "No room uses that code.")
        return room.summary()
    }

    suspend fun hostedRooms(caller: Account): List<RoomSummary> =
        store.roomsHostedBy(caller.id).sortedByDescending { it.createdAt }.map { it.summary() }

    /**
     * Admits a participant, or returns the existing membership.
     *
     * Retrying with [existingToken], or joining again with the same account, never creates a
     * second participant.
     */
    suspend fun join(
        roomId: RoomId,
        caller: Account?,
        request: JoinRoomRequest,
        existingToken: String?,
    ): Membership = lockFor(roomId).withLock {
        val room = requireRoom(roomId)
        if (existingToken != null) {
            val existing = store.findParticipantByToken(roomId, Tokens.hash(existingToken))
            if (existing != null) return Membership(room.summary(), existing.view(), existingToken)
        }
        if (room.status == RoomStatus.ENDED) {
            throw HiveException(ErrorCode.ROOM_CLOSED, "This room has ended.")
        }

        val decision = Admission.decide(room.admission, signedIn = caller != null, asGuest = request.asGuest)
        val name: String
        val accountId = when (decision) {
            is AdmissionDecision.Denied -> throw HiveException(decision.reason, deniedMessage(decision.reason))
            AdmissionDecision.AsGuest -> {
                name = DisplayNames.normalize(request.displayName)
                    ?: throw HiveException(ErrorCode.INVALID_REQUEST, "Enter a display name.")
                null
            }
            AdmissionDecision.AsAccount -> {
                val account = checkNotNull(caller)
                name = DisplayNames.normalize(request.displayName) ?: account.displayName
                account.id
            }
        }

        val token = Tokens.newToken()
        if (accountId != null) {
            val existing = store.findParticipantByAccount(roomId, accountId)
            if (existing != null) {
                store.replaceParticipantToken(existing.id, Tokens.hash(token))
                return Membership(room.summary(), existing.view(), token)
            }
        }

        val participant = ParticipantRecord(
            id = ParticipantId(UUID.randomUUID().toString()),
            roomId = roomId,
            displayName = name,
            role = RoomRole.PLAYER,
            accountId = accountId,
            tokenHash = Tokens.hash(token),
            joinedAt = clock.nowMillis(),
        )
        val revision = room.revision + 1
        store.insertParticipant(participant, revision)
        hub.publish(RoomEvent.ParticipantJoined(roomId, revision, participant.view()))
        Membership(room.copy(revision = revision).summary(), participant.view(), token)
    }

    suspend fun state(roomId: RoomId, caller: Account?, participantToken: String?): RoomState {
        val room = requireRoom(roomId)
        val you = authorize(roomId, caller, participantToken)
        return RoomState(
            room = room.summary(),
            you = you.view(),
            participants = store.participants(roomId).map { it.view() },
            revision = room.revision,
        )
    }

    /** Identifies the caller by reconnect credential first, then by account. */
    suspend fun authorize(roomId: RoomId, caller: Account?, participantToken: String?): ParticipantRecord {
        requireRoom(roomId)
        val participant = participantToken?.let { store.findParticipantByToken(roomId, Tokens.hash(it)) }
            ?: caller?.let { store.findParticipantByAccount(roomId, it.id) }
        return participant ?: throw HiveException(ErrorCode.NOT_A_PARTICIPANT, "Join this room first.")
    }

    private suspend fun requireRoom(roomId: RoomId) =
        store.findRoom(roomId) ?: throw HiveException(ErrorCode.ROOM_NOT_FOUND, "This room no longer exists.")

    private fun deniedMessage(code: ErrorCode) = when (code) {
        ErrorCode.SIGN_IN_REQUIRED -> "Sign in to join with an account, or join as a guest."
        ErrorCode.GUESTS_NOT_ALLOWED -> "This room accepts signed-in Players only."
        ErrorCode.GUESTS_ONLY -> "This room accepts guests only. Join as a guest."
        else -> "You cannot join this room."
    }
}
