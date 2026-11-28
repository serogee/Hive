package io.github.serogee.hive.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stacks")
data class StackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The account's version that this copy is based on. Null when the account does not have it. */
    val accountVersion: Long? = null,
    /** Changed on this phone since the account last got it. */
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
    /** Kept on this phone only, by the choice at sign-in. */
    @ColumnInfo(defaultValue = "0") val localOnly: Boolean = false,
)

/** A deleted stack that the account still has. Sync deletes it there too. */
@Entity(tableName = "stack_deletions")
data class StackDeletionEntity(
    @PrimaryKey val stackId: String,
    val baseVersion: Long,
)

/** Whether a record, such as a finished review, went to the account. */
object RecordSync {
    const val UNSENT = 0
    const val SENT = 1

    /** Kept on this phone only, by the choice at sign-in. */
    const val LOCAL_ONLY = 2
}

/**
 * A quiz that this phone or the account hosted or played. [resultJson] holds a QuizResult. Rows with
 * [online] come from the account's list of online rooms, which replaces them each time.
 */
@Entity(tableName = "quiz_results", indices = [Index("playedAt")])
data class QuizResultEntity(
    @PrimaryKey val id: String,
    val resultJson: String,
    val playedAt: Long,
    val online: Boolean,
    @ColumnInfo(defaultValue = "0") val syncState: Int = RecordSync.UNSENT,
)

/**
 * One question of a stack. [draftJson] holds a QuestionDraft, including its ordered choices with
 * stable IDs and the fields of other question types.
 */
@Entity(
    tableName = "questions",
    primaryKeys = ["stackId", "id"],
    foreignKeys = [ForeignKey(StackEntity::class, ["id"], ["stackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("stackId")],
)
data class QuestionEntity(
    val id: String,
    val stackId: String,
    val position: Int,
    val draftJson: String,
)

data class StackRow(
    val id: String,
    val title: String,
    val description: String,
    val updatedAt: Long,
    val questionCount: Int,
    val localOnly: Boolean = false,
)

@Dao
interface StackDao {
    @Query(
        """
        SELECT stacks.id, stacks.title, stacks.description, stacks.updatedAt, stacks.localOnly,
            (SELECT COUNT(*) FROM questions WHERE questions.stackId = stacks.id) AS questionCount
        FROM stacks ORDER BY stacks.updatedAt DESC
        """,
    )
    fun observeStacks(): Flow<List<StackRow>>

    @Query("SELECT * FROM stacks WHERE id = :id")
    fun observeStack(id: String): Flow<StackEntity?>

    @Query("SELECT * FROM stacks WHERE id = :id")
    suspend fun stack(id: String): StackEntity?

    @Query("SELECT * FROM questions WHERE stackId = :stackId ORDER BY position")
    fun observeQuestions(stackId: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE stackId = :stackId ORDER BY position")
    suspend fun questions(stackId: String): List<QuestionEntity>

    @Upsert
    suspend fun upsertStack(stack: StackEntity)

    @Upsert
    suspend fun upsertQuestions(questions: List<QuestionEntity>)

    @Query("DELETE FROM questions WHERE stackId = :stackId")
    suspend fun deleteQuestions(stackId: String)

    @Query("DELETE FROM stacks WHERE id = :id")
    suspend fun deleteStack(id: String)

    @Query("SELECT * FROM stack_deletions WHERE stackId = :id")
    suspend fun deletion(id: String): StackDeletionEntity?

    @Query("DELETE FROM stack_deletions WHERE stackId = :id")
    suspend fun clearDeletion(id: String)

    @Upsert
    suspend fun upsertDeletion(deletion: StackDeletionEntity)

    /** Deletes a stack, and remembers to delete it from the account when the account has it. */
    @Transaction
    suspend fun deleteStackAndSync(id: String) {
        val stack = stack(id) ?: return
        val version = stack.accountVersion
        if (version != null && !stack.localOnly) upsertDeletion(StackDeletionEntity(id, version))
        deleteStack(id)
    }

    /**
     * Replaces a stack and all its questions in one transaction. When saving a synced stack that
     * still has a pending deletion (such as restoring a deleted stack before sync), cancels the
     * deletion and preserves its base version so sync updates rather than deletes it.
     */
    @Transaction
    suspend fun saveStack(stack: StackEntity, questions: List<QuestionEntity>) {
        val toSave = if (!stack.localOnly) {
            val pendingDeletion = deletion(stack.id)
            if (pendingDeletion != null) {
                clearDeletion(stack.id)
                stack.copy(accountVersion = stack.accountVersion ?: pendingDeletion.baseVersion)
            } else {
                stack
            }
        } else {
            stack
        }
        upsertStack(toSave)
        deleteQuestions(toSave.id)
        upsertQuestions(questions)
    }
}

/** One self-review attempt. [attemptJson] holds a ReviewAttempt with its own copy of the questions. */
@Entity(tableName = "review_attempts", indices = [Index("stackId")])
data class ReviewAttemptEntity(
    @PrimaryKey val id: String,
    val stackId: String,
    val attemptJson: String,
    val finished: Boolean,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val syncState: Int = RecordSync.UNSENT,
)

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_attempts WHERE id = :id")
    fun observe(id: String): Flow<ReviewAttemptEntity?>

    @Query("SELECT * FROM review_attempts WHERE id = :id")
    suspend fun attempt(id: String): ReviewAttemptEntity?

    /** The most recent unfinished attempt, for Continue review. */
    @Query("SELECT * FROM review_attempts WHERE finished = 0 ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestUnfinished(): Flow<ReviewAttemptEntity?>

    /** Ongoing reviews, newest first, up to [limit]. */
    @Query("SELECT * FROM review_attempts WHERE finished = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeUnfinished(limit: Int = 5): Flow<List<ReviewAttemptEntity>>

    @Query("SELECT * FROM review_attempts WHERE finished = 0 ORDER BY updatedAt DESC")
    suspend fun unfinished(): List<ReviewAttemptEntity>

    @Query("SELECT * FROM review_attempts WHERE stackId = :stackId AND finished = 0 ORDER BY updatedAt DESC LIMIT 1")
    fun observeUnfinishedFor(stackId: String): Flow<ReviewAttemptEntity?>

    /** Finished attempts, newest first. Finishing is the last change to an attempt. */
    @Query("SELECT * FROM review_attempts WHERE finished = 1 ORDER BY updatedAt DESC")
    fun observeFinished(): Flow<List<ReviewAttemptEntity>>

    @Upsert
    suspend fun upsert(attempt: ReviewAttemptEntity)

    @Query("DELETE FROM review_attempts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM review_attempts WHERE stackId = :stackId")
    suspend fun deleteForStack(stackId: String)
}

@Database(
    entities = [
        StackEntity::class,
        QuestionEntity::class,
        ReviewAttemptEntity::class,
        LocalRoomEntity::class,
        LocalParticipantEntity::class,
        LocalSubmissionEntity::class,
        LocalAttemptEntity::class,
        StackDeletionEntity::class,
        QuizResultEntity::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class HiveDatabase : RoomDatabase() {
    abstract fun stacks(): StackDao

    abstract fun reviews(): ReviewDao

    abstract fun localRooms(): LocalRoomDao

    abstract fun sync(): SyncDao

    companion object {
        /** Scopes question primary keys to (stackId, id) so one stack can never overwrite another's questions. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `questions_new` (
                        `id` TEXT NOT NULL,
                        `stackId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `draftJson` TEXT NOT NULL,
                        PRIMARY KEY(`stackId`, `id`),
                        FOREIGN KEY(`stackId`) REFERENCES `stacks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `questions_new` (`id`, `stackId`, `position`, `draftJson`) " +
                        "SELECT `id`, `stackId`, `position`, `draftJson` FROM `questions`",
                )
                db.execSQL("DROP TABLE `questions`")
                db.execSQL("ALTER TABLE `questions_new` RENAME TO `questions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_stackId` ON `questions` (`stackId`)")
            }
        }

        fun create(context: Context): HiveDatabase =
            Room.databaseBuilder(context.applicationContext, HiveDatabase::class.java, "hive.db")
                .addMigrations(MIGRATION_7_8)
                .build()
    }
}
