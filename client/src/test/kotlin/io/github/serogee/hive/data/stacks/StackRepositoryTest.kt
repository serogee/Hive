package io.github.serogee.hive.data.stacks

import io.github.serogee.hive.core.editor.QuestionType
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

    /** Simulates a global primary key on QuestionEntity.id to prove saveCopy never collides across stacks. */
    private class GlobalIdSensitiveStackDao : StackDao {
        private val stacks = mutableMapOf<String, StackEntity>()
        private val questionsById = linkedMapOf<String, QuestionEntity>()

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

        override suspend fun upsertDeletion(deletion: StackDeletionEntity) = Unit
    }
}
