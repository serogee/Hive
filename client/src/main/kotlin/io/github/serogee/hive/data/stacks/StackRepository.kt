package io.github.serogee.hive.data.stacks

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionDrafts
import io.github.serogee.hive.core.editor.StackDraft
import io.github.serogee.hive.core.editor.toDraft
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.local.QuestionEntity
import io.github.serogee.hive.data.local.StackDao
import io.github.serogee.hive.data.local.StackEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class StackSummary(
    val id: StackId,
    val title: String,
    val description: String,
    val questionCount: Int,
    val updatedAt: Long,
    val localOnly: Boolean = false,
)

/** Stacks saved on this phone. Works without an account or a network. */
class StackRepository(private val dao: StackDao, private val now: () -> Long = System::currentTimeMillis) {
    val stacks: Flow<List<StackSummary>> = dao.observeStacks().map { rows ->
        rows.map { StackSummary(StackId(it.id), it.title, it.description, it.questionCount, it.updatedAt, it.localOnly) }
    }

    fun observeLocalOnly(id: StackId): Flow<Boolean> = dao.observeStack(id.value).map { it?.localOnly == true }

    fun observeDraft(id: StackId): Flow<StackDraft?> =
        combine(dao.observeStack(id.value), dao.observeQuestions(id.value)) { stack, questions ->
            stack?.toDraft(questions)
        }

    suspend fun draft(id: StackId): StackDraft? = dao.stack(id.value)?.toDraft(dao.questions(id.value))

    suspend fun isPendingSync(id: StackId): Boolean = dao.stack(id.value)?.pendingSync == true

    /**
     * Creates an untitled stack with one empty multiple-choice question. Held out of sync until the
     * user explicitly saves it, so cancelled drafts are never pushed to the account.
     */
    suspend fun create(): StackId {
        val draft = StackDraft(StackId(newId()), title = "", description = "", questions = listOf(newQuestion()))
        save(draft, sync = false)
        return draft.id
    }

    /** Saves an independent copy of [draft] with new stack, question, and choice IDs. */
    suspend fun saveCopy(draft: StackDraft): StackId {
        val copy = draft.copy(id = StackId(newId()), questions = draft.questions.map(::duplicate))
        save(copy)
        return copy.id
    }

    /** Saves an independent copy of [snapshot] with new stack, question, and choice IDs. */
    suspend fun saveCopy(snapshot: StackSnapshot): StackId = saveCopy(snapshot.toDraft())

    /** Restores a deleted stack under its original [StackId] with fresh question and choice IDs. */
    suspend fun restore(snapshot: StackSnapshot): StackId {
        val raw = snapshot.toDraft()
        val draft = raw.copy(questions = raw.questions.map(::duplicate))
        save(draft)
        return draft.id
    }

    suspend fun addSample(): StackId = saveCopy(SampleStacks.networkingFundamentals)

    /**
     * Saves [draft] locally. With [sync], the stack waits to sync unless it is kept on this phone only.
     * Without it, the stack keeps its current sync state: a new stack stays out of sync, and changes
     * that already wait to sync still do, so that a pull never replaces them.
     */
    suspend fun save(draft: StackDraft, sync: Boolean = true) {
        val existing = dao.stack(draft.id.value)
        val time = now()
        val pending = if (sync) existing?.localOnly != true else existing?.pendingSync == true
        val stack = existing?.copy(title = draft.title, description = draft.description, updatedAt = time, pendingSync = pending)
            ?: StackEntity(draft.id.value, draft.title, draft.description, time, time, pendingSync = pending)
        dao.saveStack(
            stack,
            draft.questions.mapIndexed { index, question ->
                QuestionEntity(
                    id = question.id.value,
                    stackId = draft.id.value,
                    position = index,
                    draftJson = HiveJson.encodeToString(QuestionDraft.serializer(), question),
                )
            },
        )
    }

    suspend fun delete(id: StackId) = dao.deleteStackAndSync(id.value)

    private fun StackEntity.toDraft(questions: List<QuestionEntity>) = StackDraft(
        id = StackId(id),
        title = title,
        description = description,
        questions = questions.map { HiveJson.decodeFromString(QuestionDraft.serializer(), it.draftJson) },
    )

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun newQuestion() = QuestionDrafts.blank(::newId)

        fun duplicate(question: QuestionDraft) = QuestionDrafts.duplicate(question, ::newId)
    }
}
