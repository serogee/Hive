package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.host.ParticipantRecord
import io.github.serogee.hive.host.RoomRecord
import io.github.serogee.hive.host.RoomStore
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/** Stores demo rooms and participants in [DemoDatabase]. */
class SqliteRoomStore(private val db: DemoDatabase) : RoomStore {
    override suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord) = db.write { connection ->
        insertRoom(connection, room)
        insertParticipant(connection, host)
    }

    override suspend fun findRoom(id: RoomId) = db.read { connection ->
        connection.queryRooms("SELECT * FROM rooms WHERE id = ?", id.value).firstOrNull()
    }

    override suspend fun findRoomByCode(code: String) = db.read { connection ->
        connection.queryRooms("SELECT * FROM rooms WHERE code = ?", code).firstOrNull()
    }

    override suspend fun roomsHostedBy(accountId: AccountId) = db.read { connection ->
        connection.queryRooms("SELECT * FROM rooms WHERE host_account_id = ?", accountId.value)
    }

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) = db.write { connection ->
        insertParticipant(connection, participant)
        connection.prepareStatement("UPDATE rooms SET revision = ? WHERE id = ?").use {
            it.setLong(1, revision)
            it.setString(2, participant.roomId.value)
            it.executeUpdate()
        }
        Unit
    }

    override suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String) = db.write { connection ->
        connection.prepareStatement("UPDATE participants SET token_hash = ? WHERE id = ?").use {
            it.setString(1, tokenHash)
            it.setString(2, participantId.value)
            it.executeUpdate()
        }
        Unit
    }

    override suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String) = db.read { connection ->
        connection.queryParticipants(
            "SELECT * FROM participants WHERE room_id = ? AND token_hash = ?",
            roomId.value,
            tokenHash,
        ).firstOrNull()
    }

    override suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId) = db.read { connection ->
        connection.queryParticipants(
            "SELECT * FROM participants WHERE room_id = ? AND account_id = ? ORDER BY rowid",
            roomId.value,
            accountId.value,
        ).firstOrNull()
    }

    override suspend fun participants(roomId: RoomId) = db.read { connection ->
        connection.queryParticipants(
            "SELECT * FROM participants WHERE room_id = ? ORDER BY rowid",
            roomId.value,
        )
    }

    companion object {
        fun insertRoom(connection: Connection, room: RoomRecord) {
            connection.prepareStatement(
                """
                INSERT INTO rooms (id, code, title, host_name, host_account_id, mode, admission, status,
                    stack_json, created_at, deadline_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, room.id.value)
                it.setString(2, room.code)
                it.setString(3, room.title)
                it.setString(4, room.hostName)
                it.setString(5, room.hostAccountId?.value)
                it.setString(6, room.mode.name)
                it.setString(7, room.admission.name)
                it.setString(8, room.status.name)
                it.setString(9, HiveJson.encodeToString(StackSnapshot.serializer(), room.stack))
                it.setLong(10, room.createdAt)
                if (room.deadlineAt != null) it.setLong(11, room.deadlineAt!!) else it.setNull(11, Types.INTEGER)
                it.setLong(12, room.revision)
                it.executeUpdate()
            }
        }

        fun insertParticipant(connection: Connection, participant: ParticipantRecord) {
            connection.prepareStatement(
                """
                INSERT INTO participants (id, room_id, display_name, role, account_id, token_hash, joined_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, participant.id.value)
                it.setString(2, participant.roomId.value)
                it.setString(3, participant.displayName)
                it.setString(4, participant.role.name)
                it.setString(5, participant.accountId?.value)
                it.setString(6, participant.tokenHash)
                it.setLong(7, participant.joinedAt)
                it.executeUpdate()
            }
        }

        private fun Connection.queryRooms(sql: String, vararg args: String) = query(sql, args) { it.toRoom() }

        private fun Connection.queryParticipants(sql: String, vararg args: String) = query(sql, args) { it.toParticipant() }

        private fun <T> Connection.query(sql: String, args: Array<out String>, map: (ResultSet) -> T): List<T> =
            prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(map(rows)) }
                }
            }

        private fun ResultSet.toRoom() = RoomRecord(
            id = RoomId(getString("id")),
            code = getString("code"),
            title = getString("title"),
            hostName = getString("host_name"),
            hostAccountId = getString("host_account_id")?.let(::AccountId),
            mode = RoomMode.valueOf(getString("mode")),
            admission = AdmissionPolicy.valueOf(getString("admission")),
            status = RoomStatus.valueOf(getString("status")),
            stack = HiveJson.decodeFromString(StackSnapshot.serializer(), getString("stack_json")),
            createdAt = getLong("created_at"),
            deadlineAt = getLong("deadline_at").takeUnless { wasNull() },
            revision = getLong("revision"),
        )

        private fun ResultSet.toParticipant() = ParticipantRecord(
            id = ParticipantId(getString("id")),
            roomId = RoomId(getString("room_id")),
            displayName = getString("display_name"),
            role = RoomRole.valueOf(getString("role")),
            accountId = getString("account_id")?.let(::AccountId),
            tokenHash = getString("token_hash"),
            joinedAt = getLong("joined_at"),
        )
    }
}
