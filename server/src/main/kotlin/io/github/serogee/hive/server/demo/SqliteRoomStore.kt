package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.engine.Progress
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.model.SubmissionId
import io.github.serogee.hive.host.AttemptRecord
import io.github.serogee.hive.host.ParticipantRecord
import io.github.serogee.hive.host.RoomRecord
import io.github.serogee.hive.host.RoomStore
import io.github.serogee.hive.host.SubmissionRecord
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

    override suspend fun roomsPlayedBy(accountId: AccountId) = db.read { connection ->
        connection.queryRooms(
            "SELECT * FROM rooms WHERE id IN (SELECT room_id FROM participants WHERE account_id = ? AND role = 'PLAYER')",
            accountId.value,
        )
    }

    override suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long) = db.write { connection ->
        connection.prepareStatement(
            "UPDATE rooms SET status = ?, question_index = ?, question_open = ?, question_opened_at = ?, revision = ? WHERE id = ?",
        ).use {
            it.setString(1, progress.status.name)
            if (progress.questionIndex != null) it.setInt(2, progress.questionIndex!!) else it.setNull(2, Types.INTEGER)
            it.setInt(3, if (progress.questionOpen) 1 else 0)
            if (progress.openedAt != null) it.setLong(4, progress.openedAt!!) else it.setNull(4, Types.INTEGER)
            it.setLong(5, revision)
            it.setString(6, roomId.value)
            it.executeUpdate()
        }
        Unit
    }

    override suspend fun updateSettings(room: RoomRecord) = db.write { connection ->
        connection.prepareStatement(
            "UPDATE rooms SET title = ?, admission = ?, stack_json = ?, deadline_at = ?, reveal_policy = ?, speed_scoring = ?, " +
                "time_tiebreak = ?, revision = ? WHERE id = ?",
        ).use {
            it.setString(1, room.title)
            it.setString(2, room.admission.name)
            it.setString(3, HiveJson.encodeToString(StackSnapshot.serializer(), room.stack))
            if (room.deadlineAt != null) it.setLong(4, room.deadlineAt!!) else it.setNull(4, Types.INTEGER)
            it.setString(5, room.revealPolicy.name)
            it.setInt(6, if (room.speedScoring) 1 else 0)
            it.setInt(7, if (room.timeTiebreak) 1 else 0)
            it.setLong(8, room.revision)
            it.setString(9, room.id.value)
            it.executeUpdate()
        }
        Unit
    }

    override suspend fun insertSubmission(submission: SubmissionRecord, revision: Long) = db.write { connection ->
        insertSubmission(connection, submission)
        setRevision(connection, submission.roomId, revision)
    }

    override suspend fun findSubmissionByKey(roomId: RoomId, participantId: ParticipantId, idempotencyKey: String) =
        db.read { connection ->
            connection.querySubmissions(
                "SELECT * FROM submissions WHERE room_id = ? AND participant_id = ? AND idempotency_key = ?",
                roomId.value,
                participantId.value,
                idempotencyKey,
            ).firstOrNull()
        }

    override suspend fun findSubmission(roomId: RoomId, participantId: ParticipantId, questionId: QuestionId) =
        db.read { connection ->
            connection.querySubmissions(
                "SELECT * FROM submissions WHERE room_id = ? AND participant_id = ? AND question_id = ?",
                roomId.value,
                participantId.value,
                questionId.value,
            ).firstOrNull()
        }

    override suspend fun submissions(roomId: RoomId) = db.read { connection ->
        connection.querySubmissions("SELECT * FROM submissions WHERE room_id = ? ORDER BY rowid", roomId.value)
    }

    override suspend fun activeMembershipsOf(accountId: AccountId) = db.read { connection ->
        connection.queryParticipants(
            "SELECT * FROM participants WHERE account_id = ? AND role = 'PLAYER' AND left_at IS NULL ORDER BY rowid",
            accountId.value,
        )
    }

    override suspend fun setLeft(participant: ParticipantRecord, leftAt: Long?, revision: Long) = db.write { connection ->
        connection.prepareStatement("UPDATE participants SET left_at = ? WHERE id = ?").use {
            if (leftAt != null) it.setLong(1, leftAt) else it.setNull(1, Types.INTEGER)
            it.setString(2, participant.id.value)
            it.executeUpdate()
        }
        setRevision(connection, participant.roomId, revision)
    }

    override suspend fun findAttempt(roomId: RoomId, participantId: ParticipantId) = db.read { connection ->
        connection.query(
            "SELECT * FROM attempts WHERE room_id = ? AND participant_id = ?",
            arrayOf(roomId.value, participantId.value),
        ) { it.toAttempt() }.firstOrNull()
    }

    override suspend fun attempts(roomId: RoomId) = db.read { connection ->
        connection.query("SELECT * FROM attempts WHERE room_id = ? ORDER BY rowid", arrayOf(roomId.value)) { it.toAttempt() }
    }

    override suspend fun saveAttempt(attempt: AttemptRecord, revision: Long) = db.write { connection ->
        connection.prepareStatement(
            "INSERT OR REPLACE INTO attempts (room_id, participant_id, question_index, opened_at, finished) VALUES (?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, attempt.roomId.value)
            it.setString(2, attempt.participantId.value)
            it.setInt(3, attempt.questionIndex)
            it.setLong(4, attempt.openedAt)
            it.setInt(5, if (attempt.finished) 1 else 0)
            it.executeUpdate()
        }
        setRevision(connection, attempt.roomId, revision)
    }

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) = db.write { connection ->
        insertParticipant(connection, participant)
        setRevision(connection, participant.roomId, revision)
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
                    stack_json, created_at, deadline_at, revision, reveal_policy, question_index, question_open,
                    question_opened_at, speed_scoring, time_tiebreak)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                it.setString(13, room.revealPolicy.name)
                if (room.questionIndex != null) it.setInt(14, room.questionIndex!!) else it.setNull(14, Types.INTEGER)
                it.setInt(15, if (room.questionOpen) 1 else 0)
                if (room.questionOpenedAt != null) it.setLong(16, room.questionOpenedAt!!) else it.setNull(16, Types.INTEGER)
                it.setInt(17, if (room.speedScoring) 1 else 0)
                it.setInt(18, if (room.timeTiebreak) 1 else 0)
                it.executeUpdate()
            }
        }

        fun insertParticipant(connection: Connection, participant: ParticipantRecord) {
            connection.prepareStatement(
                """
                INSERT INTO participants (id, room_id, display_name, role, account_id, token_hash, joined_at, left_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, participant.id.value)
                it.setString(2, participant.roomId.value)
                it.setString(3, participant.displayName)
                it.setString(4, participant.role.name)
                it.setString(5, participant.accountId?.value)
                it.setString(6, participant.tokenHash)
                it.setLong(7, participant.joinedAt)
                if (participant.leftAt != null) it.setLong(8, participant.leftAt!!) else it.setNull(8, Types.INTEGER)
                it.executeUpdate()
            }
        }

        fun insertSubmission(connection: Connection, submission: SubmissionRecord) {
            connection.prepareStatement(
                """
                INSERT INTO submissions (id, room_id, participant_id, question_id, answer_json, correct, points,
                    idempotency_key, accepted_at, response_millis, speed_bonus_tenths)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, submission.id.value)
                it.setString(2, submission.roomId.value)
                it.setString(3, submission.participantId.value)
                it.setString(4, submission.questionId.value)
                it.setString(5, HiveJson.encodeToString(Answer.serializer(), submission.answer))
                it.setInt(6, if (submission.correct) 1 else 0)
                it.setInt(7, submission.points)
                it.setString(8, submission.idempotencyKey)
                it.setLong(9, submission.acceptedAt)
                if (submission.responseMillis != null) it.setLong(10, submission.responseMillis!!) else it.setNull(10, Types.INTEGER)
                it.setInt(11, submission.speedBonusTenths)
                it.executeUpdate()
            }
        }

        private fun setRevision(connection: Connection, roomId: RoomId, revision: Long) {
            connection.prepareStatement("UPDATE rooms SET revision = ? WHERE id = ?").use {
                it.setLong(1, revision)
                it.setString(2, roomId.value)
                it.executeUpdate()
            }
        }

        private fun ResultSet.toAttempt() = AttemptRecord(
            roomId = RoomId(getString("room_id")),
            participantId = ParticipantId(getString("participant_id")),
            questionIndex = getInt("question_index"),
            openedAt = getLong("opened_at"),
            finished = getInt("finished") == 1,
        )

        private fun Connection.querySubmissions(sql: String, vararg args: String) = query(sql, args) { it.toSubmission() }

        private fun ResultSet.toSubmission() = SubmissionRecord(
            id = SubmissionId(getString("id")),
            roomId = RoomId(getString("room_id")),
            participantId = ParticipantId(getString("participant_id")),
            questionId = QuestionId(getString("question_id")),
            answer = HiveJson.decodeFromString(Answer.serializer(), getString("answer_json")),
            correct = getInt("correct") == 1,
            points = getInt("points"),
            idempotencyKey = getString("idempotency_key"),
            acceptedAt = getLong("accepted_at"),
            responseMillis = getLong("response_millis").takeUnless { wasNull() },
            speedBonusTenths = getInt("speed_bonus_tenths"),
        )

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
            revealPolicy = RevealPolicy.valueOf(getString("reveal_policy")),
            questionIndex = getInt("question_index").takeUnless { wasNull() },
            questionOpen = getInt("question_open") == 1,
            questionOpenedAt = getLong("question_opened_at").takeUnless { wasNull() },
            speedScoring = getInt("speed_scoring") == 1,
            timeTiebreak = getInt("time_tiebreak") == 1,
        )

        private fun ResultSet.toParticipant() = ParticipantRecord(
            id = ParticipantId(getString("id")),
            roomId = RoomId(getString("room_id")),
            displayName = getString("display_name"),
            role = RoomRole.valueOf(getString("role")),
            accountId = getString("account_id")?.let(::AccountId),
            tokenHash = getString("token_hash"),
            joinedAt = getLong("joined_at"),
            leftAt = getLong("left_at").takeUnless { wasNull() },
        )
    }
}
