package io.github.serogee.hive.data.stacks

import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.core.transfer.HiveStackFile
import io.github.serogee.hive.data.local.QuestionEntity
import io.github.serogee.hive.data.local.StackDao
import io.github.serogee.hive.data.local.StackDeletionEntity
import io.github.serogee.hive.data.local.StackEntity
import io.github.serogee.hive.data.local.StackRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class StackRepositoryTest {
    @Test
    fun `importing the same exported Hive JSON twice gives each copy fresh IDs and keeps both stacks intact`() = runTest {
        val dao = GlobalIdSensitiveStackDao()
        val repository = StackRepository(dao, now = { 1L })
        val exported = HiveStackFile.encode(SampleStacks.networkingFundamentals)

        val firstDecoded = assertNotNull(HiveStackFile.decode(exported, StackRepository::newId).draft)
        val secondDecoded = assertNotNull(HiveStackFile.decode(exported, StackRepository::newId).draft)

        val firstId = repository.saveCopy(firstDecoded)
        val secondId = repository.saveCopy(secondDecoded)

        val first = assertNotNull(repository.draft(firstId))
        val second = assertNotNull(repository.draft(secondId))

        assertNotEquals(firstId, secondId)
        assertEquals(SampleStacks.networkingFundamentals.questions.size, first.questions.size)
        assertEquals(SampleStacks.networkingFundamentals.questions.size, second.questions.size)

        val originalQuestionIds = SampleStacks.networkingFundamentals.questions.map { it.id }.toSet()
        val firstQuestionIds = first.questions.map { it.id }.toSet()
        val secondQuestionIds = second.questions.map { it.id }.toSet()
        assertTrue((firstQuestionIds intersect originalQuestionIds).isEmpty())
        assertTrue((secondQuestionIds intersect originalQuestionIds).isEmpty())
        assertTrue((firstQuestionIds intersect secondQuestionIds).isEmpty())

        val firstMc = first.questions.first { it.type == QuestionType.MULTIPLE_CHOICE }
        val secondMc = second.questions.first { it.type == QuestionType.MULTIPLE_CHOICE }
        assertTrue((firstMc.choices.map { it.id }.toSet() intersect secondMc.choices.map { it.id }.toSet()).isEmpty())
        assertTrue(firstMc.choices.any { it.id == firstMc.correctChoiceId })
        assertTrue(secondMc.choices.any { it.id == secondMc.correctChoiceId })
    }

    @Test
    fun `restoring a deleted synced stack before sync clears its pending deletion and keeps its base version`() = runTest {
        val dao = GlobalIdSensitiveStackDao()
        val repository = StackRepository(dao, now = { 10L })
        val stackId = repository.addSample()
        val snapshot = assertNotNull(StackValidation.toSnapshot(assertNotNull(repository.draft(stackId))))
        // Mark the stack as already synced at version 3.
        dao.upsertStack(assertNotNull(dao.stack(stackId.value)).copy(accountVersion = 3L, pendingSync = false))

        repository.delete(stackId)
        assertNull(dao.stack(stackId.value))
        assertEquals(StackDeletionEntity(stackId.value, baseVersion = 3L), dao.deletion(stackId.value))

        repository.restore(snapshot)

        assertNull(dao.deletion(stackId.value))
        val restored = assertNotNull(dao.stack(stackId.value))
        assertEquals(3L, restored.accountVersion)
        assertTrue(restored.pendingSync)
        assertEquals(false, restored.localOnly)
    }

    @Test
    fun `editing a local-only stack with a pending server deletion keeps the pending deletion`() = runTest {
        val dao = GlobalIdSensitiveStackDao()
        val repository = StackRepository(dao, now = { 10L })
        val stackId = repository.addSample()
        // Simulate an unsynced stack whose server deletion has not synced yet.
        dao.upsertStack(assertNotNull(dao.stack(stackId.value)).copy(localOnly = true, accountVersion = null, pendingSync = false))
        dao.upsertDeletion(StackDeletionEntity(stackId.value, baseVersion = 2L))

        val edited = assertNotNull(repository.draft(stackId)).copy(title = "Edited locally")
        repository.save(edited)

        assertEquals(StackDeletionEntity(stackId.value, baseVersion = 2L), dao.deletion(stackId.value))
        val saved = assertNotNull(dao.stack(stackId.value))
        assertEquals("Edited locally", saved.title)
        assertTrue(saved.localOnly)
        assertNull(saved.accountVersion)
        assertEquals(false, saved.pendingSync)
    }

    /** Simulates a global primary key on QuestionEntity.id to prove saveCopy never collides across stacks. */
    private class GlobalIdSensitiveStackDao : StackDao {
        private val stacks = mutableMapOf<String, StackEntity>()
        private val questionsById = linkedMapOf<String, QuestionEntity>()
        private val deletions = mutableMapOf<String, StackDeletionEntity>()

        override fun observeStacks(): Flow<List<StackRow>> = emptyFlow()

        override fun observeStack(id: String): Flow<StackEntity?> = emptyFlow()

        override suspend fun stack(id: String) = stacks[id]

        override fun observeQuestions(stackId: String): Flow<List<QuestionEntity>> = emptyFlow()

        override suspend fun questions(stackId: String) =
            questionsById.values.filter { it.stackId == stackId }.sortedBy { it.position }

        override suspend fun upsertStack(stack: StackEntity) {
            stacks[stack.id] = stack
        }

        override suspend fun upsertQuestions(questions: List<QuestionEntity>) {
            questions.forEach { questionsById[it.id] = it }
        }

        override suspend fun deleteQuestions(stackId: String) {
            questionsById.entries.removeIf { it.value.stackId == stackId }
        }

        override suspend fun deleteStack(id: String) {
            stacks.remove(id)
            deleteQuestions(id)
        }

        override suspend fun deletion(id: String): StackDeletionEntity? = deletions[id]

        override suspend fun clearDeletion(id: String) {
            deletions.remove(id)
        }

        override suspend fun upsertDeletion(deletion: StackDeletionEntity) {
            deletions[deletion.stackId] = deletion
        }
    }
}
