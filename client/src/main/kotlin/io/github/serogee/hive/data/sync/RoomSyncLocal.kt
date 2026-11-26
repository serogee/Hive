package io.github.serogee.hive.data.sync

import androidx.room.withTransaction
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.api.SyncedStack
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.review.ReviewAttempt
import io.github.serogee.hive.data.history.QuizHistory
import io.github.serogee.hive.data.history.QuizResult
import io.github.serogee.hive.data.local.HiveDatabase
import io.github.serogee.hive.data.local.QuestionEntity
import io.github.serogee.hive.data.local.QuizResultEntity
import io.github.serogee.hive.data.local.RecordSync
import io.github.serogee.hive.data.local.ReviewAttemptEntity
import io.github.serogee.hive.data.local.StackEntity
import io.github.serogee.hive.data.stacks.StackRepository
import kotlinx.serialization.json.jsonObject

/** [SyncLocal] on the Room database. */
class RoomSyncLocal(private val db: HiveDatabase, private val now: () -> Long = System::currentTimeMillis) : SyncLocal {
    private val dao = db.sync()

    override suspend fun pendingStacks() = dao.pendingStacks().map { it.toLocal() }

    override suspend fun stack(id: String) = dao.stack(id)?.toLocal()

    override suspend fun deletions() = dao.deletions().map { StackDeletion(it.stackId, it.baseVersion) }

    override suspend fun deletion(id: String) = dao.deletion(id)?.let { StackDeletion(it.stackId, it.baseVersion) }

    override suspend fun clearDeletion(id: String) = dao.clearDeletion(id)

    override suspend fun takeAccountCopy(stack: SyncedStack) = db.withTransaction {
        val document = stack.document
        if (stack.deleted || document == null) {
            dao.deleteStack(stack.stackId)
        } else {
            val existing = dao.stack(stack.stackId)
            dao.saveStack(
                StackEntity(
                    id = stack.stackId,
                    title = document.title,
                    description = document.description,
                    createdAt = existing?.createdAt ?: stack.updatedAt,
                    updatedAt = stack.updatedAt,
                    accountVersion = stack.version,
                    pendingSync = false,
                    localOnly = false,
                ),
                document.questions.toEntities(stack.stackId),
            )
        }
    }

    override suspend fun markPushed(id: String, version: Long, sentAt: Long) = dao.markPushed(id, version, sentAt)

    override suspend fun saveAsCopy(stack: LocalStack) {
        val id = StackRepository.newId()
        val time = now()
        val questions = stack.document.questions.map { StackRepository.duplicate(it) }
        dao.saveStack(
            StackEntity(
                id = id,
                title = StackMerge.copyTitle(stack.document.title),
                description = stack.document.description,
                createdAt = time,
                updatedAt = time,
                accountVersion = null,
                pendingSync = !stack.localOnly,
                localOnly = stack.localOnly,
            ),
            questions.toEntities(id),
        )
    }

    override suspend fun unlinked() = Unlinked(dao.unlinkedStackCount(), dao.unsentReviewCount(), dao.unsentResultCount())

    override suspend fun keepUnlinkedOnPhone() = dao.keepUnlinkedOnPhone()

    override suspend fun unlinkAll() = dao.unlinkAll()

    override suspend fun signOutSummary() = SignOutSummary(dao.syncedStackCount(), dao.unsyncedEditCount() + dao.unlinkedStackCount())

    override suspend fun deleteSyncedStacks() = dao.deleteSyncedStacks()

    override suspend fun unsentReviews(limit: Int) = dao.unsentReviews(limit).map { row ->
        SyncRecord(row.id, row.updatedAt, HiveJson.parseToJsonElement(row.attemptJson).jsonObject)
    }

    override suspend fun markReviewsSent(ids: List<String>) = dao.markReviewsSent(ids)

    override suspend fun saveReviews(records: List<SyncRecord>) {
        val rows = records.mapNotNull { record ->
            // A record that this app version cannot read stays on the account for a newer one.
            val attempt = runCatching { HiveJson.decodeFromJsonElement(ReviewAttempt.serializer(), record.data) }.getOrNull()
                ?: return@mapNotNull null
            ReviewAttemptEntity(
                id = record.id,
                stackId = attempt.stack.id.value,
                attemptJson = HiveJson.encodeToString(ReviewAttempt.serializer(), attempt),
                finished = true,
                updatedAt = record.at,
                syncState = RecordSync.SENT,
            )
        }
        if (rows.isNotEmpty()) dao.insertReviews(rows)
    }

    override suspend fun unsentResults(limit: Int) = dao.unsentResults(limit).map { row ->
        SyncRecord(row.id, row.playedAt, HiveJson.parseToJsonElement(row.resultJson).jsonObject)
    }

    override suspend fun markResultsSent(ids: List<String>) = dao.markResultsSent(ids)

    override suspend fun saveResults(records: List<SyncRecord>) {
        val rows = records.mapNotNull { record ->
            val result = runCatching { HiveJson.decodeFromJsonElement(QuizResult.serializer(), record.data) }.getOrNull()
                ?: return@mapNotNull null
            QuizResultEntity(record.id, QuizHistory.encode(result), result.playedAt, online = false, syncState = RecordSync.SENT)
        }
        if (rows.isNotEmpty()) dao.insertResults(rows)
    }

    override suspend fun saveOnlineRooms(rooms: List<OnlineRoomEntry>) = dao.replaceOnlineResults(rooms.map(QuizHistory::online))

    override suspend fun hasPendingWork() = dao.pendingStacks().isNotEmpty() || dao.deletions().isNotEmpty() ||
        dao.unsentReviewCount() > 0 || dao.unsentResultCount() > 0

    private suspend fun StackEntity.toLocal() = LocalStack(
        id = id,
        document = StackDocument(
            title = title,
            description = description,
            questions = dao.questions(id).map { HiveJson.decodeFromString(QuestionDraft.serializer(), it.draftJson) },
        ),
        accountVersion = accountVersion,
        pending = pendingSync,
        localOnly = localOnly,
        updatedAt = updatedAt,
    )

    private fun List<QuestionDraft>.toEntities(stackId: String) = mapIndexed { index, question ->
        QuestionEntity(question.id.value, stackId, index, HiveJson.encodeToString(QuestionDraft.serializer(), question))
    }
}
