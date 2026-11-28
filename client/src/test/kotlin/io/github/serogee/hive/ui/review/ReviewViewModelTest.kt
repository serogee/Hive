package io.github.serogee.hive.ui.review

import io.github.serogee.hive.audio.AudioScope
import io.github.serogee.hive.audio.Sfx
import io.github.serogee.hive.audio.SoundCuePlayer
import io.github.serogee.hive.core.api.correctAnswer
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.local.QuestionEntity
import io.github.serogee.hive.data.local.ReviewAttemptEntity
import io.github.serogee.hive.data.local.ReviewDao
import io.github.serogee.hive.data.local.StackDao
import io.github.serogee.hive.data.local.StackDeletionEntity
import io.github.serogee.hive.data.local.StackEntity
import io.github.serogee.hive.data.local.StackRow
import io.github.serogee.hive.data.review.ReviewRepository
import io.github.serogee.hive.data.stacks.StackRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `in-flight save cannot resurrect an abandoned review`() = runTest(dispatcher) {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var blockNextSave = false
        val dao = object : ReviewDao {
            val rows = MutableStateFlow(emptyMap<String, ReviewAttemptEntity>())

            override fun observe(id: String): Flow<ReviewAttemptEntity?> = rows.map { it[id] }

            override suspend fun attempt(id: String): ReviewAttemptEntity? = rows.value[id]

            override fun observeLatestUnfinished(): Flow<ReviewAttemptEntity?> = emptyFlow()

            override fun observeUnfinished(limit: Int): Flow<List<ReviewAttemptEntity>> = emptyFlow()

            override suspend fun unfinished(): List<ReviewAttemptEntity> = rows.value.values.filter { !it.finished }

            override fun observeUnfinishedFor(stackId: String): Flow<ReviewAttemptEntity?> = emptyFlow()

            override fun observeFinished(): Flow<List<ReviewAttemptEntity>> = emptyFlow()

            override suspend fun upsert(attempt: ReviewAttemptEntity) {
                if (blockNextSave) {
                    blockNextSave = false
                    saveStarted.complete(Unit)
                    releaseSave.await()
                }
                rows.value += attempt.id to attempt
            }

            override suspend fun delete(id: String) {
                rows.value -= id
            }

            override suspend fun deleteForStack(stackId: String) {
                rows.value = rows.value.filterValues { it.stackId != stackId }
            }
        }
        val reviews = ReviewRepository(dao, now = { 1_000L })
        val stacks = StackRepository(NoOpStackDao())
        val attempt = reviews.start(SampleStacks.networkingFundamentals, ReviewSettings(ReviewMode.FLASHCARD))

        val vm = ReviewViewModel(attempt.id, reviews, stacks, CoroutineScope(dispatcher))
        advanceUntilIdle()

        // Start a save that suspends mid-flight, then trigger abandon while another save is also queued.
        blockNextSave = true
        vm.mark(true)
        advanceUntilIdle()
        saveStarted.await()

        var abandonDone = false
        vm.abandon { abandonDone = true }
        // Additional edits after abandon must be ignored and never write back.
        vm.mark(false)

        releaseSave.complete(Unit)
        advanceUntilIdle()

        assertTrue(abandonDone)
        assertNull(dao.attempt(attempt.id))
    }

    @Test
    fun `quiz review plays verdict, next, and finish fanfare without replaying on reopen`() = runTest(dispatcher) {
        val dao = InMemoryReviewDao()
        val reviews = ReviewRepository(dao, now = { 1_000L })
        val stacks = StackRepository(NoOpStackDao())
        val twoQuestionStack = SampleStacks.networkingFundamentals.copy(
            questions = SampleStacks.networkingFundamentals.questions.take(2),
        )
        val attempt = reviews.start(twoQuestionStack, ReviewSettings(ReviewMode.QUIZ))
        val recorder = RecordingAudio()

        val vm = ReviewViewModel(attempt.id, reviews, stacks, CoroutineScope(dispatcher), recorder)
        advanceUntilIdle()
        assertTrue(recorder.cues.isEmpty())

        val q1 = twoQuestionStack.questions[0]
        vm.check(q1.correctAnswer())
        vm.advance()
        vm.check(Answer.Text("definitely-wrong-answer"))
        vm.advance()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Sfx.CORRECT to AudioScope.SOLO,
                Sfx.NEXT to AudioScope.SOLO,
                Sfx.INCORRECT to AudioScope.SOLO,
                Sfx.RESULTS_FANFARE to AudioScope.SOLO,
            ),
            recorder.cues,
        )

        // Reopening a finished review must stay silent.
        val reopenRecorder = RecordingAudio()
        ReviewViewModel(attempt.id, reviews, stacks, CoroutineScope(dispatcher), reopenRecorder)
        advanceUntilIdle()
        assertTrue(reopenRecorder.cues.isEmpty())
    }

    @Test
    fun `flashcard review plays mark cues and plays only fanfare on the final card`() = runTest(dispatcher) {
        val dao = InMemoryReviewDao()
        val reviews = ReviewRepository(dao, now = { 1_000L })
        val stacks = StackRepository(NoOpStackDao())
        val twoCardStack = SampleStacks.networkingFundamentals.copy(
            questions = SampleStacks.networkingFundamentals.questions.take(2),
        )
        val attempt = reviews.start(twoCardStack, ReviewSettings(ReviewMode.FLASHCARD))
        val recorder = RecordingAudio()

        val vm = ReviewViewModel(attempt.id, reviews, stacks, CoroutineScope(dispatcher), recorder)
        advanceUntilIdle()

        vm.mark(false)
        vm.mark(true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Sfx.INCORRECT to AudioScope.SOLO,
                Sfx.RESULTS_FANFARE to AudioScope.SOLO,
            ),
            recorder.cues,
        )
    }

    private class RecordingAudio : SoundCuePlayer {
        val cues = mutableListOf<Pair<Sfx, AudioScope>>()

        override fun playSfx(cue: Sfx, scope: AudioScope) {
            cues += cue to scope
        }

        override fun playSfxOnce(key: String, cue: Sfx, scope: AudioScope) {
            cues += cue to scope
        }

        override fun playSfxThrottled(bucket: String, minIntervalMillis: Long, cue: Sfx, scope: AudioScope) {
            cues += cue to scope
        }
    }

    private class InMemoryReviewDao : ReviewDao {
        private val rows = MutableStateFlow(emptyMap<String, ReviewAttemptEntity>())

        override fun observe(id: String): Flow<ReviewAttemptEntity?> = rows.map { it[id] }
        override suspend fun attempt(id: String): ReviewAttemptEntity? = rows.value[id]
        override fun observeLatestUnfinished(): Flow<ReviewAttemptEntity?> = emptyFlow()
        override fun observeUnfinished(limit: Int): Flow<List<ReviewAttemptEntity>> = emptyFlow()
        override suspend fun unfinished(): List<ReviewAttemptEntity> = rows.value.values.filter { !it.finished }
        override fun observeUnfinishedFor(stackId: String): Flow<ReviewAttemptEntity?> = emptyFlow()
        override fun observeFinished(): Flow<List<ReviewAttemptEntity>> = emptyFlow()
        override suspend fun upsert(attempt: ReviewAttemptEntity) {
            rows.value += attempt.id to attempt
        }
        override suspend fun delete(id: String) {
            rows.value -= id
        }
        override suspend fun deleteForStack(stackId: String) {
            rows.value = rows.value.filterValues { it.stackId != stackId }
        }
    }

    private class NoOpStackDao : StackDao {
        override fun observeStacks(): Flow<List<StackRow>> = emptyFlow()
        override fun observeStack(id: String): Flow<StackEntity?> = emptyFlow()
        override suspend fun stack(id: String): StackEntity? = null
        override fun observeQuestions(stackId: String): Flow<List<QuestionEntity>> = emptyFlow()
        override suspend fun questions(stackId: String): List<QuestionEntity> = emptyList()
        override suspend fun upsertStack(stack: StackEntity) = Unit
        override suspend fun upsertQuestions(questions: List<QuestionEntity>) = Unit
        override suspend fun deleteQuestions(stackId: String) = Unit
        override suspend fun deleteStack(id: String) = Unit
        override suspend fun deletion(id: String): StackDeletionEntity? = null
        override suspend fun clearDeletion(id: String) = Unit
        override suspend fun upsertDeletion(deletion: StackDeletionEntity) = Unit
    }
}
