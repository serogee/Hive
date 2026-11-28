package io.github.serogee.hive.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What account sync reads and writes. Stacks with localOnly never leave this phone. */
@Dao
interface SyncDao {
    /** How much is waiting to go to the account. Changes start a sync after a short pause. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM stacks WHERE pendingSync = 1 AND localOnly = 0)
            + (SELECT COUNT(*) FROM stack_deletions)
            + (SELECT COUNT(*) FROM review_attempts WHERE finished = 1 AND syncState = 0)
            + (SELECT COUNT(*) FROM quiz_results WHERE online = 0 AND syncState = 0)
        """,
    )
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM stacks WHERE pendingSync = 1 AND localOnly = 0")
    suspend fun pendingStacks(): List<StackEntity>

    @Query("SELECT * FROM stacks WHERE id = :id")
    suspend fun stack(id: String): StackEntity?

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

    @Transaction
    suspend fun saveStack(stack: StackEntity, questions: List<QuestionEntity>) {
        upsertStack(stack)
        deleteQuestions(stack.id)
        upsertQuestions(questions)
    }

    /** Links the stack to the account's [version]. It stays pending when it changed after [sentAt]. */
    @Query("UPDATE stacks SET accountVersion = :version, pendingSync = CASE WHEN updatedAt = :sentAt THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun markPushed(id: String, version: Long, sentAt: Long)

    @Query("SELECT * FROM stack_deletions")
    suspend fun deletions(): List<StackDeletionEntity>

    @Query("SELECT * FROM stack_deletions WHERE stackId = :id")
    suspend fun deletion(id: String): StackDeletionEntity?

    @Query("DELETE FROM stack_deletions WHERE stackId = :id")
    suspend fun clearDeletion(id: String)

    @Upsert
    suspend fun upsertDeletion(deletion: StackDeletionEntity)

    @Query("UPDATE stacks SET localOnly = 1, accountVersion = NULL, pendingSync = 0 WHERE id = :id")
    suspend fun setStackLocalOnly(id: String)

    @Query("UPDATE stacks SET localOnly = 0, accountVersion = :accountVersion, pendingSync = 1 WHERE id = :id")
    suspend fun setStackSynced(id: String, accountVersion: Long?)

    @Query("SELECT * FROM stacks WHERE accountVersion IS NOT NULL AND localOnly = 0")
    suspend fun syncedStacks(): List<StackEntity>

    @Query("SELECT * FROM stacks WHERE localOnly = 1")
    suspend fun localOnlyStacks(): List<StackEntity>

    @Query("UPDATE stacks SET localOnly = 1, accountVersion = NULL, pendingSync = 0")
    suspend fun setAllStacksLocalOnly()

    @Query("UPDATE review_attempts SET syncState = 0 WHERE syncState = 2")
    suspend fun syncLocalOnlyReviews()

    @Query("UPDATE quiz_results SET syncState = 0 WHERE syncState = 2 AND online = 0")
    suspend fun syncLocalOnlyResults()

    /** Marks one stack as unsynced on this phone, and queues server deletion when the account has it. */
    @Transaction
    suspend fun markStackUnsynced(id: String) {
        val existing = stack(id) ?: return
        val version = existing.accountVersion
        if (version != null && !existing.localOnly) upsertDeletion(StackDeletionEntity(id, version))
        setStackLocalOnly(id)
    }

    /** Marks one stack to sync with the account again, restoring any unsent deletion baseVersion. */
    @Transaction
    suspend fun markStackSynced(id: String) {
        val existing = stack(id) ?: return
        val pendingDeletion = deletion(id)
        if (pendingDeletion != null) clearDeletion(id)
        setStackSynced(id, pendingDeletion?.baseVersion ?: existing.accountVersion)
    }

    /** Marks every local-only stack and history record to sync with the account. */
    @Transaction
    suspend fun syncAllStacks() {
        localOnlyStacks().forEach { row ->
            val pendingDeletion = deletion(row.id)
            if (pendingDeletion != null) clearDeletion(row.id)
            setStackSynced(row.id, pendingDeletion?.baseVersion ?: row.accountVersion)
        }
        syncLocalOnlyReviews()
        syncLocalOnlyResults()
    }

    /** Marks every stack as unsynced on this phone, and queues server deletion for all synced stacks. */
    @Transaction
    suspend fun unsyncAllStacks() {
        syncedStacks().forEach { row ->
            val version = row.accountVersion
            if (version != null) upsertDeletion(StackDeletionEntity(row.id, version))
        }
        setAllStacksLocalOnly()
    }

    @Query("SELECT COUNT(*) FROM stacks WHERE accountVersion IS NULL AND localOnly = 0")
    suspend fun unlinkedStackCount(): Int

    @Query("SELECT COUNT(*) FROM review_attempts WHERE finished = 1 AND syncState = 0")
    suspend fun unsentReviewCount(): Int

    @Query("SELECT COUNT(*) FROM quiz_results WHERE online = 0 AND syncState = 0")
    suspend fun unsentResultCount(): Int

    @Query("SELECT COUNT(*) FROM stacks WHERE accountVersion IS NOT NULL AND localOnly = 0 AND pendingSync = 0")
    suspend fun syncedStackCount(): Int

    /** Stacks that the account has, with changes that it does not have yet. */
    @Query("SELECT COUNT(*) FROM stacks WHERE accountVersion IS NOT NULL AND localOnly = 0 AND pendingSync = 1")
    suspend fun unsyncedEditCount(): Int

    @Query("DELETE FROM stacks WHERE accountVersion IS NOT NULL AND localOnly = 0 AND pendingSync = 0")
    suspend fun deleteSyncedStacks()

    /** Keeps what is not on the account on this phone only. */
    @Transaction
    suspend fun keepUnlinkedOnPhone() {
        keepStacksOnPhone()
        keepReviewsOnPhone()
        keepResultsOnPhone()
    }

    @Query("UPDATE stacks SET localOnly = 1, pendingSync = 0 WHERE accountVersion IS NULL AND localOnly = 0")
    suspend fun keepStacksOnPhone()

    @Query("UPDATE review_attempts SET syncState = 2 WHERE syncState = 0")
    suspend fun keepReviewsOnPhone()

    @Query("UPDATE quiz_results SET syncState = 2 WHERE syncState = 0 AND online = 0")
    suspend fun keepResultsOnPhone()

    /** Forgets every link to an account, for another account or after the account's data was deleted. */
    @Transaction
    suspend fun unlinkAll() {
        unlinkStacks()
        clearDeletions()
        unlinkReviews()
        unlinkResults()
        deleteOnlineResults()
    }

    @Query("UPDATE stacks SET accountVersion = NULL, pendingSync = 1 WHERE localOnly = 0")
    suspend fun unlinkStacks()

    @Query("DELETE FROM stack_deletions")
    suspend fun clearDeletions()

    @Query("UPDATE review_attempts SET syncState = 0 WHERE syncState = 1")
    suspend fun unlinkReviews()

    @Query("UPDATE quiz_results SET syncState = 0 WHERE syncState = 1 AND online = 0")
    suspend fun unlinkResults()

    @Query("SELECT * FROM review_attempts WHERE finished = 1 AND syncState = 0 ORDER BY updatedAt LIMIT :limit")
    suspend fun unsentReviews(limit: Int): List<ReviewAttemptEntity>

    @Query("UPDATE review_attempts SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markReviewsSent(ids: List<String>)

    /** Reviews from the account. A review that this phone has already stays as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviews(reviews: List<ReviewAttemptEntity>)

    @Query("SELECT * FROM quiz_results WHERE online = 0 AND syncState = 0 ORDER BY playedAt LIMIT :limit")
    suspend fun unsentResults(limit: Int): List<QuizResultEntity>

    @Query("UPDATE quiz_results SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markResultsSent(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResults(results: List<QuizResultEntity>)

    @Query("DELETE FROM quiz_results WHERE online = 1")
    suspend fun deleteOnlineResults()

    @Transaction
    suspend fun replaceOnlineResults(results: List<QuizResultEntity>) {
        deleteOnlineResults()
        insertResults(results)
    }

    @Query("SELECT * FROM quiz_results ORDER BY playedAt DESC")
    fun observeResults(): Flow<List<QuizResultEntity>>
}
