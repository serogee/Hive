package io.github.serogee.hive.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A room that this phone hosts. Its stack snapshot is stored with it. */
@Entity(tableName = "local_rooms", indices = [Index(value = ["code"], unique = true)])
data class LocalRoomEntity(
    @PrimaryKey val id: String,
    val code: String,
    val title: String,
    val hostName: String,
    val mode: String,
    val admission: String,
    val status: String,
    val stackJson: String,
    val createdAt: Long,
    val deadlineAt: Long?,
    val revision: Long,
    val revealPolicy: String,
    val questionIndex: Int?,
    val questionOpen: Boolean,
    val questionOpenedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val speedScoring: Boolean = false,
    @ColumnInfo(defaultValue = "0") val timeTiebreak: Boolean = false,
)

@Entity(
    tableName = "local_participants",
    foreignKeys = [ForeignKey(LocalRoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("roomId"), Index(value = ["roomId", "tokenHash"])],
)
data class LocalParticipantEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val displayName: String,
    val role: String,
    val tokenHash: String,
    val joinedAt: Long,
    /** Keeps join order. */
    val sequence: Long,
)

@Entity(
    tableName = "local_submissions",
    foreignKeys = [ForeignKey(LocalRoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("roomId"),
        Index(value = ["participantId", "questionId"], unique = true),
        Index(value = ["participantId", "idempotencyKey"], unique = true),
    ],
)
data class LocalSubmissionEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val participantId: String,
    val questionId: String,
    val answerJson: String,
    val correct: Boolean,
    val points: Int,
    val idempotencyKey: String,
    val acceptedAt: Long,
    val sequence: Long,
    val responseMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val speedBonusTenths: Int = 0,
)

/** A Player's attempt in an Asynchronous room hosted on this phone. */
@Entity(
    tableName = "local_attempts",
    primaryKeys = ["roomId", "participantId"],
    foreignKeys = [ForeignKey(LocalRoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.CASCADE)],
)
data class LocalAttemptEntity(
    val roomId: String,
    val participantId: String,
    val questionIndex: Int,
    val openedAt: Long,
    val finished: Boolean,
)

@Dao
interface LocalRoomDao {
    @Insert
    suspend fun insertRoom(room: LocalRoomEntity)

    @Insert
    suspend fun insertParticipant(participant: LocalParticipantEntity)

    @Insert
    suspend fun insertSubmission(submission: LocalSubmissionEntity)

    @Query("SELECT * FROM local_rooms WHERE id = :id")
    suspend fun room(id: String): LocalRoomEntity?

    @Query("SELECT * FROM local_rooms ORDER BY createdAt DESC")
    fun observeRooms(): Flow<List<LocalRoomEntity>>

    @Query("SELECT * FROM local_rooms WHERE status != 'ENDED'")
    suspend fun activeRooms(): List<LocalRoomEntity>

    @Query("SELECT * FROM local_rooms WHERE code = :code")
    suspend fun roomByCode(code: String): LocalRoomEntity?

    @Query("UPDATE local_rooms SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)

    @Query(
        "UPDATE local_rooms SET status = :status, questionIndex = :questionIndex, questionOpen = :questionOpen, " +
            "questionOpenedAt = :openedAt, revision = :revision WHERE id = :id",
    )
    suspend fun setProgress(id: String, status: String, questionIndex: Int?, questionOpen: Boolean, openedAt: Long?, revision: Long)

    @Query("UPDATE local_participants SET tokenHash = :tokenHash WHERE id = :id")
    suspend fun setToken(id: String, tokenHash: String)

    @Query("SELECT * FROM local_participants WHERE roomId = :roomId AND tokenHash = :tokenHash")
    suspend fun participantByToken(roomId: String, tokenHash: String): LocalParticipantEntity?

    @Query("SELECT * FROM local_participants WHERE roomId = :roomId ORDER BY sequence")
    suspend fun participants(roomId: String): List<LocalParticipantEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM local_participants")
    suspend fun lastParticipantSequence(): Long

    @Query("SELECT * FROM local_submissions WHERE roomId = :roomId ORDER BY sequence")
    suspend fun submissions(roomId: String): List<LocalSubmissionEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM local_submissions")
    suspend fun lastSubmissionSequence(): Long

    @Query("SELECT * FROM local_attempts WHERE roomId = :roomId AND participantId = :participantId")
    suspend fun attempt(roomId: String, participantId: String): LocalAttemptEntity?

    @Query("SELECT * FROM local_attempts WHERE roomId = :roomId")
    suspend fun attempts(roomId: String): List<LocalAttemptEntity>

    @Upsert
    suspend fun upsertAttempt(attempt: LocalAttemptEntity)

    @Transaction
    suspend fun saveAttempt(attempt: LocalAttemptEntity, revision: Long) {
        upsertAttempt(attempt)
        setRevision(attempt.roomId, revision)
    }

    @Transaction
    suspend fun createRoom(room: LocalRoomEntity, host: LocalParticipantEntity) {
        insertRoom(room)
        insertParticipant(host)
    }

    @Transaction
    suspend fun addParticipant(participant: LocalParticipantEntity, revision: Long) {
        insertParticipant(participant)
        setRevision(participant.roomId, revision)
    }

    @Transaction
    suspend fun addSubmission(submission: LocalSubmissionEntity, revision: Long) {
        insertSubmission(submission)
        setRevision(submission.roomId, revision)
    }
}

/**
 * Rooms hosted on this phone, saved in Room so that they survive the app restarting. Rooms hosted
 * on a phone have no accounts, so every participant is a guest.
 */
class LocalRoomStore(private val dao: LocalRoomDao) : RoomStore {
    override suspend fun insertRoom(room: RoomRecord, host: ParticipantRecord) =
        dao.createRoom(room.toEntity(), host.toEntity(dao.lastParticipantSequence() + 1))

    /** Rooms this phone hosts, newest first. */
    val rooms: Flow<List<RoomRecord>> = dao.observeRooms().map { list -> list.map { it.toRecord() } }

    suspend fun hasActiveRooms(): Boolean = dao.activeRooms().isNotEmpty()

    override suspend fun findRoom(id: RoomId) = dao.room(id.value)?.toRecord()

    override suspend fun findRoomByCode(code: String) = dao.roomByCode(code)?.toRecord()

    override suspend fun roomsHostedBy(accountId: AccountId): List<RoomRecord> = emptyList()

    override suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long) =
        dao.setProgress(roomId.value, progress.status.name, progress.questionIndex, progress.questionOpen, progress.openedAt, revision)

    override suspend fun insertParticipant(participant: ParticipantRecord, revision: Long) =
        dao.addParticipant(participant.toEntity(dao.lastParticipantSequence() + 1), revision)

    override suspend fun replaceParticipantToken(participantId: ParticipantId, tokenHash: String) =
        dao.setToken(participantId.value, tokenHash)

    override suspend fun findParticipantByToken(roomId: RoomId, tokenHash: String) =
        dao.participantByToken(roomId.value, tokenHash)?.toRecord()

    override suspend fun findParticipantByAccount(roomId: RoomId, accountId: AccountId): ParticipantRecord? = null

    override suspend fun participants(roomId: RoomId) = dao.participants(roomId.value).map { it.toRecord() }

    override suspend fun insertSubmission(submission: SubmissionRecord, revision: Long) =
        dao.addSubmission(submission.toEntity(dao.lastSubmissionSequence() + 1), revision)

    override suspend fun findSubmissionByKey(roomId: RoomId, participantId: ParticipantId, idempotencyKey: String) =
        submissions(roomId).firstOrNull { it.participantId == participantId && it.idempotencyKey == idempotencyKey }

    override suspend fun findSubmission(roomId: RoomId, participantId: ParticipantId, questionId: QuestionId) =
        submissions(roomId).firstOrNull { it.participantId == participantId && it.questionId == questionId }

    override suspend fun submissions(roomId: RoomId) = dao.submissions(roomId.value).map { it.toRecord() }

    override suspend fun findAttempt(roomId: RoomId, participantId: ParticipantId) =
        dao.attempt(roomId.value, participantId.value)?.toRecord()

    override suspend fun attempts(roomId: RoomId) = dao.attempts(roomId.value).map { it.toRecord() }

    override suspend fun saveAttempt(attempt: AttemptRecord, revision: Long) = dao.saveAttempt(
        LocalAttemptEntity(attempt.roomId.value, attempt.participantId.value, attempt.questionIndex, attempt.openedAt, attempt.finished),
        revision,
    )

    private fun LocalAttemptEntity.toRecord() =
        AttemptRecord(RoomId(roomId), ParticipantId(participantId), questionIndex, openedAt, finished)

    private fun RoomRecord.toEntity() = LocalRoomEntity(
        id = id.value,
        code = code,
        title = title,
        hostName = hostName,
        mode = mode.name,
        admission = admission.name,
        status = status.name,
        stackJson = HiveJson.encodeToString(StackSnapshot.serializer(), stack),
        createdAt = createdAt,
        deadlineAt = deadlineAt,
        revision = revision,
        revealPolicy = revealPolicy.name,
        questionIndex = questionIndex,
        questionOpen = questionOpen,
        questionOpenedAt = questionOpenedAt,
        speedScoring = speedScoring,
        timeTiebreak = timeTiebreak,
    )

    private fun LocalRoomEntity.toRecord() = RoomRecord(
        id = RoomId(id),
        code = code,
        title = title,
        hostName = hostName,
        hostAccountId = null,
        mode = RoomMode.valueOf(mode),
        admission = AdmissionPolicy.valueOf(admission),
        status = RoomStatus.valueOf(status),
        stack = HiveJson.decodeFromString(StackSnapshot.serializer(), stackJson),
        createdAt = createdAt,
        deadlineAt = deadlineAt,
        revision = revision,
        revealPolicy = RevealPolicy.valueOf(revealPolicy),
        questionIndex = questionIndex,
        questionOpen = questionOpen,
        questionOpenedAt = questionOpenedAt,
        speedScoring = speedScoring,
        timeTiebreak = timeTiebreak,
    )

    private fun ParticipantRecord.toEntity(sequence: Long) = LocalParticipantEntity(
        id = id.value,
        roomId = roomId.value,
        displayName = displayName,
        role = role.name,
        tokenHash = tokenHash,
        joinedAt = joinedAt,
        sequence = sequence,
    )

    private fun LocalParticipantEntity.toRecord() = ParticipantRecord(
        id = ParticipantId(id),
        roomId = RoomId(roomId),
        displayName = displayName,
        role = RoomRole.valueOf(role),
        accountId = null,
        tokenHash = tokenHash,
        joinedAt = joinedAt,
    )

    private fun SubmissionRecord.toEntity(sequence: Long) = LocalSubmissionEntity(
        id = id.value,
        roomId = roomId.value,
        participantId = participantId.value,
        questionId = questionId.value,
        answerJson = HiveJson.encodeToString(Answer.serializer(), answer),
        correct = correct,
        points = points,
        idempotencyKey = idempotencyKey,
        acceptedAt = acceptedAt,
        sequence = sequence,
        responseMillis = responseMillis,
        speedBonusTenths = speedBonusTenths,
    )

    private fun LocalSubmissionEntity.toRecord() = SubmissionRecord(
        id = SubmissionId(id),
        roomId = RoomId(roomId),
        participantId = ParticipantId(participantId),
        questionId = QuestionId(questionId),
        answer = HiveJson.decodeFromString(Answer.serializer(), answerJson),
        correct = correct,
        points = points,
        idempotencyKey = idempotencyKey,
        acceptedAt = acceptedAt,
        responseMillis = responseMillis,
        speedBonusTenths = speedBonusTenths,
    )
}
