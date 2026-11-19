package io.github.serogee.hive.host

import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps rooms in memory. Useful for tests and for short-lived hosting. */
class InMemoryRoomStore : RoomStore {
    private val mutex = Mutex()
    private val rooms = linkedMapOf<RoomId, RoomRecord>()
    private val participants = mutableListOf<ParticipantRecord>()

    override suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord) = mutex.withLock {
        rooms[room.id] = room
        participants += host
    }

    override suspend fun findRoom(id: RoomId) = mutex.withLock { rooms[id] }

    override suspend fun findRoomByCode(code: String) = mutex.withLock {
        rooms.values.firstOrNull { it.code == code }
    }

    override suspend fun roomsHostedBy(accountId: AccountId) = mutex.withLock {
        rooms.values.filter { it.hostAccountId == accountId }
    }

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) = mutex.withLock {
        participants += participant
        rooms.computeIfPresent(participant.roomId) { _, room -> room.copy(revision = revision) }
        Unit
    }

    override suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String) = mutex.withLock {
        val index = participants.indexOfFirst { it.id == participantId }
        if (index >= 0) participants[index] = participants[index].copy(tokenHash = tokenHash)
    }

    override suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String) = mutex.withLock {
        participants.firstOrNull { it.roomId == roomId && it.tokenHash == tokenHash }
    }

    override suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId) = mutex.withLock {
        participants.firstOrNull { it.roomId == roomId && it.accountId == accountId }
    }

    override suspend fun participants(roomId: RoomId) = mutex.withLock {
        participants.filter { it.roomId == roomId }
    }
}
