package io.github.serogee.hive.data.local

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stacks")
data class StackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One question of a stack. [draftJson] holds a QuestionDraft, including its ordered choices with
 * stable IDs and the fields of other question types.
 */
@Entity(
    tableName = "questions",
    foreignKeys = [ForeignKey(StackEntity::class, ["id"], ["stackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("stackId")],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
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
)

@Dao
interface StackDao {
    @Query(
        """
        SELECT stacks.id, stacks.title, stacks.description, stacks.updatedAt,
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

    /** Replaces a stack and all its questions in one transaction. */
    @Transaction
    suspend fun saveStack(stack: StackEntity, questions: List<QuestionEntity>) {
        upsertStack(stack)
        deleteQuestions(stack.id)
        upsertQuestions(questions)
    }
}

@Database(entities = [StackEntity::class, QuestionEntity::class], version = 1)
abstract class HiveDatabase : RoomDatabase() {
    abstract fun stacks(): StackDao

    companion object {
        fun create(context: Context): HiveDatabase =
            Room.databaseBuilder(context.applicationContext, HiveDatabase::class.java, "hive.db").build()
    }
}
