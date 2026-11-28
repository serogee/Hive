package io.github.serogee.hive.ui.stacks

import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.local.QuestionEntity
import io.github.serogee.hive.data.local.StackDao
import io.github.serogee.hive.data.local.StackDeletionEntity
import io.github.serogee.hive.data.local.StackEntity
import io.github.serogee.hive.data.local.StackRow
import io.github.serogee.hive.data.stacks.StackRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class StackEditorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeStackDao()
    private val stacks = StackRepository(dao, now = { 1L })

    @BeforeTest
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private fun editor(id: StackId, isNew: Boolean) = StackEditorViewModel(id, stacks, CoroutineScope(dispatcher), isNew)

    private suspend fun saved(title: String): StackId {
        val id = stacks.create()
        stacks.save(stacks.draft(id)!!.copy(title = title))
        return id
    }

    @Test
    fun `no question is open at first`() = runTest(dispatcher) {
        assertNull(editor(saved("Biology"), isNew = false).state.value.activeQuestionId)
    }

    @Test
    fun `changes reach storage only with Save`() = runTest(dispatcher) {
        val id = saved("Biology")
        val editor = editor(id, isNew = false)
        editor.setTitle("Chemistry")
        assertTrue(editor.state.value.unsaved)
        assertEquals("Biology", stacks.draft(id)?.title)

        editor.save()
        assertFalse(editor.state.value.unsaved)
        assertEquals("Chemistry", stacks.draft(id)?.title)
    }

    @Test
    fun `changing a value back leaves nothing to save`() = runTest(dispatcher) {
        val editor = editor(saved("Biology"), isNew = false)
        editor.setTitle("Chemistry")
        editor.setTitle("Biology")
        assertFalse(editor.state.value.unsaved)
    }

    @Test
    fun `closing the app saves, and the changes can still be thrown away`() = runTest(dispatcher) {
        val id = saved("Biology")
        val editor = editor(id, isNew = false)
        editor.setTitle("Chemistry")
        editor.saveOnClose()
        assertEquals("Chemistry", stacks.draft(id)?.title)

        editor.discard()
        assertEquals("Biology", stacks.draft(id)?.title)
    }

    @Test
    fun `leaving a new stack without saving removes it`() = runTest(dispatcher) {
        val id = stacks.create()
        val editor = editor(id, isNew = true)
        editor.setTitle("Chemistry")
        editor.discard()
        assertNull(stacks.draft(id))
    }

    @Test
    fun `a new stack that the closing app saved is kept after a restart`() = runTest(dispatcher) {
        val id = stacks.create()
        editor(id, isNew = true).apply {
            setTitle("Chemistry")
            saveOnClose()
        }
        editor(id, isNew = true).discard()
        assertEquals("Chemistry", assertNotNull(stacks.draft(id)).title)
    }

    @Test
    fun `a moved question stays open`() = runTest(dispatcher) {
        val editor = editor(saved("Biology"), isNew = false)
        editor.addQuestionAfter(null)
        val id = assertNotNull(editor.state.value.activeQuestionId)
        assertEquals(1, editor.state.value.draft!!.questions.indexOfFirst { it.id == id })

        editor.move(id, -1)
        assertEquals(0, editor.state.value.draft!!.questions.indexOfFirst { it.id == id })
        assertEquals(id, editor.state.value.activeQuestionId)

        editor.move(id, -1)
        assertEquals(0, editor.state.value.draft!!.questions.indexOfFirst { it.id == id })
    }

    @Test
    fun `new drafts and saveOnClose stay out of sync until Save is pressed`() = runTest(dispatcher) {
        val id = stacks.create()
        assertFalse(stacks.isPendingSync(id))

        val newEditor = editor(id, isNew = true)
        newEditor.setTitle("Draft")
        newEditor.saveOnClose()
        assertFalse(stacks.isPendingSync(id))

        newEditor.save()
        assertTrue(stacks.isPendingSync(id))
    }

    @Test
    fun `closing the app keeps saved changes waiting to sync`() = runTest(dispatcher) {
        val id = saved("Biology")
        assertTrue(stacks.isPendingSync(id))

        val editor = editor(id, isNew = false)
        editor.setTitle("Unconfirmed")
        editor.saveOnClose()
        assertTrue(stacks.isPendingSync(id))

        editor.discard()
        assertEquals("Biology", stacks.draft(id)?.title)
        assertTrue(stacks.isPendingSync(id))
    }

    @Test
    fun `closing the app marks a synced stack as changed here`() = runTest(dispatcher) {
        val id = saved("Biology")
        dao.markSynced(id.value, version = 3)
        assertFalse(stacks.isPendingSync(id))

        val editor = editor(id, isNew = false)
        editor.setTitle("Chemistry")
        editor.saveOnClose()
        // A pull then keeps this phone's changes as a copy beside the account's version.
        assertTrue(stacks.isPendingSync(id))
        assertEquals("Chemistry", stacks.draft(id)?.title)
    }

    private class FakeStackDao : StackDao {
        private val stacks = mutableMapOf<String, StackEntity>()
        private val questions = mutableMapOf<String, List<QuestionEntity>>()

        override fun observeStacks(): Flow<List<StackRow>> = emptyFlow()

        override fun observeStack(id: String): Flow<StackEntity?> = emptyFlow()

        override suspend fun stack(id: String) = stacks[id]

        override fun observeQuestions(stackId: String): Flow<List<QuestionEntity>> = emptyFlow()

        override suspend fun questions(stackId: String) = questions[stackId].orEmpty().sortedBy { it.position }

        override suspend fun upsertStack(stack: StackEntity) {
            stacks[stack.id] = stack
        }

        override suspend fun upsertQuestions(questions: List<QuestionEntity>) {
            questions.groupBy { it.stackId }.forEach { (stackId, list) -> this.questions[stackId] = list }
        }

        override suspend fun deleteQuestions(stackId: String) {
            questions.remove(stackId)
        }

        override suspend fun deleteStack(id: String) {
            stacks.remove(id)
            questions.remove(id)
        }

        override suspend fun deletion(id: String): StackDeletionEntity? = null

        override suspend fun clearDeletion(id: String) = Unit

        override suspend fun upsertDeletion(deletion: StackDeletionEntity) = Unit

        /** Acts as a finished push: the account has [version], and nothing waits to sync. */
        fun markSynced(id: String, version: Long) {
            stacks[id] = stacks.getValue(id).copy(accountVersion = version, pendingSync = false)
        }
    }
}
