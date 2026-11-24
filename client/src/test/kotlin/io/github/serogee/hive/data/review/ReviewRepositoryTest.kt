package io.github.serogee.hive.data.review

import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.review.Review
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.local.ReviewAttemptEntity
import io.github.serogee.hive.data.local.ReviewDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

class ReviewRepositoryTest {
    private val stack: StackSnapshot = SampleStacks.networkingFundamentals
    private var time = 0L
    private val reviews = ReviewRepository(FakeReviewDao(), now = { time })

    @Test
    fun `finished reviews keep their score and the time they finished`() = runTest {
        time = 1_000
        var attempt = reviews.start(stack, ReviewSettings(ReviewMode.FLASHCARD))
        reviews.start(stack, ReviewSettings(ReviewMode.QUIZ))
        stack.questions.indices.forEach { index ->
            time += 1_000
            attempt = Review.mark(attempt, gotIt = index > 0)
            reviews.save(attempt)
        }

        val finished = reviews.finished.first().single()
        assertEquals(attempt, finished.attempt)
        assertEquals(1_000L + stack.questions.size * 1_000L, finished.finishedAt)
        assertEquals("${stack.questions.size - 1}/${stack.questions.size}", finished.score)
    }
}

private class FakeReviewDao : ReviewDao {
    private val rows = MutableStateFlow(emptyMap<String, ReviewAttemptEntity>())

    private fun select(matches: (ReviewAttemptEntity) -> Boolean): Flow<List<ReviewAttemptEntity>> =
        rows.map { all -> all.values.filter(matches).sortedByDescending { it.updatedAt } }

    override fun observe(id: String) = rows.map { it[id] }

    override suspend fun attempt(id: String) = rows.value[id]

    override fun observeLatestUnfinished() = select { !it.finished }.map { it.firstOrNull() }

    override fun observeUnfinishedFor(stackId: String) =
        select { !it.finished && it.stackId == stackId }.map { it.firstOrNull() }

    override fun observeFinished() = select { it.finished }

    override suspend fun upsert(attempt: ReviewAttemptEntity) {
        rows.value += attempt.id to attempt
    }

    override suspend fun deleteForStack(stackId: String) {
        rows.value = rows.value.filterValues { it.stackId != stackId }
    }
}
