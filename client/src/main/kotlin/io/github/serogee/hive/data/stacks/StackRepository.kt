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

    /** Creates an untitled stack with one empty multiple-choice question. */
    suspend fun create(): StackId {
        val draft = StackDraft(StackId(newId()), title = "", description = "", questions = listOf(newQuestion()))
        save(draft)
        return draft.id
    }

    /** Saves an independent copy of [snapshot] with new stack, question, and choice IDs. */
    suspend fun saveCopy(snapshot: StackSnapshot): StackId {
        val raw = snapshot.toDraft()
        val draft = raw.copy(id = StackId(newId()), questions = raw.questions.map(::duplicate))
        save(draft)
        return draft.id
    }

    /** Restores a deleted stack under its original [StackId] with fresh question and choice IDs. */
    suspend fun restore(snapshot: StackSnapshot): StackId {
        val raw = snapshot.toDraft()
        val draft = raw.copy(questions = raw.questions.map(::duplicate))
        save(draft)
        return draft.id
    }

    suspend fun addSample(): StackId = saveCopy(SampleStacks.networkingFundamentals)

    suspend fun save(draft: StackDraft) {
        val existing = dao.stack(draft.id.value)
        val time = now()
        val stack = existing?.copy(title = draft.title, description = draft.description, updatedAt = time, pendingSync = !existing.localOnly)
            ?: StackEntity(draft.id.value, draft.title, draft.description, time, time)
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
