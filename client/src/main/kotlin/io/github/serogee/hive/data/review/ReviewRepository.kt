package io.github.serogee.hive.data.review

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.review.Review
import io.github.serogee.hive.core.review.ReviewAttempt
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.data.local.ReviewAttemptEntity
import io.github.serogee.hive.data.local.ReviewDao
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A review that reached its summary, and when it did. */
data class FinishedReview(val attempt: ReviewAttempt, val finishedAt: Long) {
    /** Correct or mastered cards out of all cards, such as "8/10". */
    val score: String get() = "${attempt.correctCount}/${attempt.order.size}"
}

/** Self-review attempts saved on this phone, so a review can resume at the same card. */
class ReviewRepository(private val dao: ReviewDao, private val now: () -> Long = System::currentTimeMillis) {
    val latestUnfinished: Flow<ReviewAttempt?> = dao.observeLatestUnfinished().map { it?.decode() }

    /** Newest first. */
    val finished: Flow<List<FinishedReview>> = dao.observeFinished().map { rows ->
        rows.map { FinishedReview(it.decode(), it.updatedAt) }
    }

    fun unfinishedFor(stackId: String): Flow<ReviewAttempt?> = dao.observeUnfinishedFor(stackId).map { it?.decode() }

    fun observe(id: String): Flow<ReviewAttempt?> = dao.observe(id).map { it?.decode() }

    suspend fun start(stack: StackSnapshot, settings: ReviewSettings): ReviewAttempt =
        Review.start(UUID.randomUUID().toString(), stack, settings, Random.Default).also { save(it) }

    /** Starts a new attempt with only the missed or marked cards of [attempt]. */
    suspend fun retry(attempt: ReviewAttempt): ReviewAttempt =
        Review.retry(UUID.randomUUID().toString(), attempt).also { save(it) }

    suspend fun save(attempt: ReviewAttempt) = dao.upsert(
        ReviewAttemptEntity(
            id = attempt.id,
            stackId = attempt.stack.id.value,
            attemptJson = HiveJson.encodeToString(ReviewAttempt.serializer(), attempt),
            finished = attempt.isFinished,
            updatedAt = now(),
        ),
    )

    private fun ReviewAttemptEntity.decode() = HiveJson.decodeFromString(ReviewAttempt.serializer(), attemptJson)
}
