package io.github.serogee.hive.server.postgres

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

/** Stores production rooms in Postgres, so that they survive restarts and deploys. */
class PostgresRoomStore(private val db: PostgresDatabase) : RoomStore {
    override suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord) = db.write { connection ->
        connection.update(
            """
            INSERT INTO hive.rooms (id, code, title, host_name, host_account_id, mode, admission, status,
                stack_json, created_at, deadline_at, revision, reveal_policy, question_index, question_open,
                question_opened_at, speed_scoring, time_tiebreak)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            room.id.value,
            room.code,
            room.title,
            room.hostName,
            room.hostAccountId?.value,
            room.mode.name,
            room.admission.name,
            room.status.name,
            HiveJson.encodeToString(StackSnapshot.serializer(), room.stack),
            room.createdAt,
            room.deadlineAt,
            room.revision,
            room.revealPolicy.name,
            room.questionIndex,
            room.questionOpen,
            room.questionOpenedAt,
            room.speedScoring,
            room.timeTiebreak,
        )
        connection.insert(host)
    }

    override suspend fun findRoom(id: RoomId) = db.read { it.rooms("WHERE id = ?", id.value).firstOrNull() }

    override suspend fun findRoomByCode(code: String) = db.read { it.rooms("WHERE code = ?", code).firstOrNull() }

    override suspend fun roomsHostedBy(accountId: AccountId) = db.read { it.rooms("WHERE host_account_id = ?", accountId.value) }

    override suspend fun roomsPlayedBy(accountId: AccountId) = db.read {
        it.rooms("WHERE id IN (SELECT room_id FROM hive.participants WHERE account_id = ? AND role = ?)", accountId.value, RoomRole.PLAYER.name)
    }

    override suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long) = db.write { connection ->
        connection.update(
            "UPDATE hive.rooms SET status = ?, question_index = ?, question_open = ?, question_opened_at = ?, revision = ? WHERE id = ?",
            progress.status.name,
            progress.questionIndex,
            progress.questionOpen,
            progress.openedAt,
            revision,
            roomId.value,
        )
    }

    override suspend fun updateSettings(room: RoomRecord) = db.write { connection ->
        connection.update(
            "UPDATE hive.rooms SET title = ?, admission = ?, stack_json = ?, deadline_at = ?, reveal_policy = ?, speed_scoring = ?, " +
                "time_tiebreak = ?, revision = ? WHERE id = ?",
            room.title,
            room.admission.name,
            HiveJson.encodeToString(StackSnapshot.serializer(), room.stack),
            room.deadlineAt,
            room.revealPolicy.name,
            room.speedScoring,
            room.timeTiebreak,
            room.revision,
            room.id.value,
        )
    }

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) = db.write { connection ->
        connection.insert(participant)
        connection.setRevision(participant.roomId, revision)
    }

    override suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String) = db.write { connection ->
        connection.update("UPDATE hive.participants SET token_hash = ? WHERE id = ?", tokenHash, participantId.value)
    }

    override suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String) = db.read {
        it.participants("WHERE room_id = ? AND token_hash = ?", roomId.value, tokenHash).firstOrNull()
    }

    override suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId) = db.read {
        it.participants("WHERE room_id = ? AND account_id = ?", roomId.value, accountId.value).firstOrNull()
    }

    override suspend fun participants(roomId: RoomId) = db.read { it.participants("WHERE room_id = ?", roomId.value) }

    override suspend fun insertSubmission(submission: SubmissionRecord, revision: Long) = db.write { connection ->
        connection.update(
            """
            INSERT INTO hive.submissions (id, room_id, participant_id, question_id, answer_json, correct, points,
                idempotency_key, accepted_at, response_millis, speed_bonus_tenths)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            submission.id.value,
            submission.roomId.value,
            submission.participantId.value,
            submission.questionId.value,
            HiveJson.encodeToString(Answer.serializer(), submission.answer),
            submission.correct,
            submission.points,
            submission.idempotencyKey,
            submission.acceptedAt,
            submission.responseMillis,
            submission.speedBonusTenths,
        )
        connection.setRevision(submission.roomId, revision)
    }

    override suspend fun findSubmissionByKey(roomId: RoomId, participantId: ParticipantId, idempotencyKey: String) = db.read {
        it.submissions(
            "WHERE room_id = ? AND participant_id = ? AND idempotency_key = ?",
            roomId.value,
            participantId.value,
            idempotencyKey,
        ).firstOrNull()
    }

    override suspend fun findSubmission(roomId: RoomId, participantId: ParticipantId, questionId: QuestionId) = db.read {
        it.submissions(
            "WHERE room_id = ? AND participant_id = ? AND question_id = ?",
            roomId.value,
            participantId.value,
            questionId.value,
        ).firstOrNull()
    }

    override suspend fun submissions(roomId: RoomId) = db.read { it.submissions("WHERE room_id = ?", roomId.value) }

    override suspend fun activeMembershipsOf(accountId: AccountId) = db.read {
        it.participants("WHERE account_id = ? AND role = ? AND left_at IS NULL", accountId.value, RoomRole.PLAYER.name)
    }

    override suspend fun setLeft(participant: ParticipantRecord, leftAt: Long?, revision: Long) = db.write { connection ->
        connection.update("UPDATE hive.participants SET left_at = ? WHERE id = ?", leftAt, participant.id.value)
        connection.setRevision(participant.roomId, revision)
    }

    override suspend fun findAttempt(roomId: RoomId, participantId: ParticipantId) = db.read {
        it.attempts("WHERE room_id = ? AND participant_id = ?", roomId.value, participantId.value).firstOrNull()
    }

    override suspend fun attempts(roomId: RoomId) = db.read { it.attempts("WHERE room_id = ?", roomId.value) }

    override suspend fun saveAttempt(attempt: AttemptRecord, revision: Long) = db.write { connection ->
        connection.update(
            """
            INSERT INTO hive.attempts (room_id, participant_id, question_index, opened_at, finished) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (room_id, participant_id) DO UPDATE SET question_index = EXCLUDED.question_index,
                opened_at = EXCLUDED.opened_at, finished = EXCLUDED.finished
            """,
            attempt.roomId.value,
            attempt.participantId.value,
            attempt.questionIndex,
            attempt.openedAt,
            attempt.finished,
        )
        connection.setRevision(attempt.roomId, revision)
    }

    private fun Connection.insert(participant: ParticipantRecord) = update(
        """
        INSERT INTO hive.participants (id, room_id, display_name, role, account_id, token_hash, joined_at, left_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        participant.id.value,
        participant.roomId.value,
        participant.displayName,
        participant.role.name,
        participant.accountId?.value,
        participant.tokenHash,
        participant.joinedAt,
        participant.leftAt,
    )

    private fun Connection.setRevision(roomId: RoomId, revision: Long) =
        update("UPDATE hive.rooms SET revision = ? WHERE id = ?", revision, roomId.value)

    private fun Connection.rooms(where: String, vararg values: Any?) =
        query("SELECT * FROM hive.rooms $where ORDER BY created_at, id", values) { it.toRoom() }

    private fun Connection.participants(where: String, vararg values: Any?) =
        query("SELECT * FROM hive.participants $where ORDER BY sequence", values) { it.toParticipant() }

    private fun Connection.submissions(where: String, vararg values: Any?) =
        query("SELECT * FROM hive.submissions $where ORDER BY sequence", values) { it.toSubmission() }

    private fun Connection.attempts(where: String, vararg values: Any?) =
        query("SELECT * FROM hive.attempts $where ORDER BY participant_id", values) { it.toAttempt() }

    private fun Connection.update(sql: String, vararg values: Any?) {
        prepareStatement(sql.trimIndent()).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun <T> Connection.query(sql: String, values: Array<out Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

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
        deadlineAt = longOrNull("deadline_at"),
        revision = getLong("revision"),
        revealPolicy = RevealPolicy.valueOf(getString("reveal_policy")),
        questionIndex = getInt("question_index").takeUnless { wasNull() },
        questionOpen = getBoolean("question_open"),
        questionOpenedAt = longOrNull("question_opened_at"),
        speedScoring = getBoolean("speed_scoring"),
        timeTiebreak = getBoolean("time_tiebreak"),
    )

    private fun ResultSet.toParticipant() = ParticipantRecord(
        id = ParticipantId(getString("id")),
        roomId = RoomId(getString("room_id")),
        displayName = getString("display_name"),
        role = RoomRole.valueOf(getString("role")),
        accountId = getString("account_id")?.let(::AccountId),
        tokenHash = getString("token_hash"),
        joinedAt = getLong("joined_at"),
        leftAt = longOrNull("left_at"),
    )

    private fun ResultSet.toSubmission() = SubmissionRecord(
        id = SubmissionId(getString("id")),
        roomId = RoomId(getString("room_id")),
        participantId = ParticipantId(getString("participant_id")),
        questionId = QuestionId(getString("question_id")),
        answer = HiveJson.decodeFromString(Answer.serializer(), getString("answer_json")),
        correct = getBoolean("correct"),
        points = getInt("points"),
        idempotencyKey = getString("idempotency_key"),
        acceptedAt = getLong("accepted_at"),
        responseMillis = longOrNull("response_millis"),
        speedBonusTenths = getInt("speed_bonus_tenths"),
    )

    private fun ResultSet.toAttempt() = AttemptRecord(
        roomId = RoomId(getString("room_id")),
        participantId = ParticipantId(getString("participant_id")),
        questionIndex = getInt("question_index"),
        openedAt = getLong("opened_at"),
        finished = getBoolean("finished"),
    )
}
