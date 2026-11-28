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

    private fun quiz(day: LocalDate, role: RoomRole, room: String, correct: Int? = null, questions: Int = 4, ended: Boolean = true) = QuizResult(
        roomId = room,
        code = "ABCDEF",
        title = "Quiz",
        role = role,
        place = QuizPlace.ONLINE,
        playedAt = at(day),
        questionCount = questions,
        playerCount = 3,
        ended = ended,
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
    fun `the week shows the active days from Monday to Sunday`() {
        // 2026-11-26 is a Thursday.
        val stats = stats(listOf(review(today, 1), review(today.minusDays(3), 1)), listOf(quiz(today.minusDays(7), RoomRole.PLAYER, "a", correct = 1)))
        assertEquals(listOf(true, false, false, true, false, false, false), stats.weekActive)
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

    @Test
    fun `unended online sessions do not count toward streaks, quizzes played, or rooms hosted`() {
        val stats = stats(
            quizzes = listOf(
                quiz(today, RoomRole.PLAYER, "live-1", ended = false),
                quiz(today, RoomRole.HOST, "lobby-1", ended = false),
            ),
        )
        assertEquals(0, stats.streakDays)
        assertEquals(0, stats.quizzesPlayed)
        assertEquals(List(7) { false }, stats.weekActive)
        assertEquals(0, stats.roomsHosted)
    }

    @Test
    fun `zero-answered finished reviews do not affect streaks or average score`() {
        val emptyEnded = FinishedReview(
            Review.finish(Review.start("empty", stack, ReviewSettings(ReviewMode.QUIZ), Random(1))),
            at(today),
        )
        val stats = stats(reviews = listOf(emptyEnded, review(today.minusDays(2), right = stack.questions.size)))
        assertEquals(0, stats.streakDays)
        assertEquals(100, stats.averagePercent)
    }

    @Test
    fun `duplicate played entries for one room count once in average score`() {
        val stats = stats(
            quizzes = listOf(
                quiz(today, RoomRole.PLAYER, "room-1", correct = 4, questions = 4),
                quiz(today, RoomRole.PLAYER, "room-1", correct = 0, questions = 4),
                quiz(today, RoomRole.PLAYER, "room-2", correct = 2, questions = 4),
            ),
        )
        assertEquals(2, stats.quizzesPlayed)
        assertEquals(75, stats.averagePercent)
    }
}
