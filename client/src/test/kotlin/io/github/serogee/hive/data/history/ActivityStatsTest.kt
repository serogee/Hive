package io.github.serogee.hive.data.history

import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.review.Review
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.review.FinishedReview
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityStatsTest {
    private val zone = ZoneId.of("Asia/Manila")
    private val today = LocalDate.of(2026, 11, 26)
    private val stack = SampleStacks.networkingFundamentals

    private fun at(day: LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A finished review with [right] cards right. */
    private fun review(day: LocalDate, right: Int): FinishedReview {
        var attempt = Review.start("r-$day-$right", stack, ReviewSettings(ReviewMode.FLASHCARD), Random(1))
        attempt.order.indices.forEach { attempt = Review.mark(attempt, gotIt = it < right) }
        return FinishedReview(attempt, at(day))
    }

    private fun quiz(day: LocalDate, role: RoomRole, room: String, correct: Int? = null, questions: Int = 4) = QuizResult(
        roomId = room,
        code = "ABCDEF",
        title = "Quiz",
        role = role,
        place = QuizPlace.ONLINE,
        playedAt = at(day),
        questionCount = questions,
        playerCount = 3,
        correctCount = correct,
    )

    private fun stats(reviews: List<FinishedReview> = emptyList(), quizzes: List<QuizResult> = emptyList(), hostedHere: Set<String> = emptySet()) =
        ActivityStats.of(reviews, quizzes, hostedHere, today, zone)

    @Test
    fun `nothing yet`() {
        val stats = stats()
        assertEquals(0, stats.streakDays)
        assertNull(stats.averagePercent)
        assertEquals(0, stats.quizzesPlayed)
    }

    @Test
    fun `a streak counts days in a row up to today`() {
        val days = listOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(3, ActivityStats.streak(days.toSet(), today))
    }

    @Test
    fun `a streak lasts until a day passes without activity`() {
        assertEquals(2, ActivityStats.streak(setOf(today.minusDays(1), today.minusDays(2)), today))
        assertEquals(0, ActivityStats.streak(setOf(today.minusDays(2)), today))
    }

    @Test
    fun `reviews and quizzes make one streak`() {
        val stats = stats(listOf(review(today, 1)), listOf(quiz(today.minusDays(1), RoomRole.PLAYER, "a", correct = 2)))
        assertEquals(2, stats.streakDays)
    }

    @Test
    fun `the average covers reviews and played quizzes, but not hosted ones`() {
        val size = stack.questions.size
        val stats = stats(
            reviews = listOf(review(today, right = size)),
            quizzes = listOf(quiz(today, RoomRole.PLAYER, "a", correct = 0), quiz(today, RoomRole.HOST, "b")),
        )
        assertEquals(50, stats.averagePercent)
    }

    @Test
    fun `hosted rooms are counted once from this phone and the account`() {
        val stats = stats(
            quizzes = listOf(quiz(today, RoomRole.HOST, "a"), quiz(today, RoomRole.HOST, "b"), quiz(today, RoomRole.PLAYER, "c", correct = 1)),
            hostedHere = setOf("b", "d"),
        )
        assertEquals(3, stats.roomsHosted)
        assertEquals(1, stats.quizzesPlayed)
    }
}
