package io.github.serogee.hive.data.history

import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.data.review.FinishedReview
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Numbers for the Account page, from reviews and quizzes. */
data class ActivityStats(
    val quizzesPlayed: Int,
    val roomsHosted: Int,
    /** Days in a row with a finished review or a quiz, up to today or yesterday. */
    val streakDays: Int,
    /** Correct answers out of all, in percent, over finished reviews and played quizzes. Null without any. */
    val averagePercent: Int?,
) {
    companion object {
        fun of(
            reviews: List<FinishedReview>,
            quizzes: List<QuizResult>,
            /** Rooms that this phone hosts or hosted, whether they ended or not. */
            hostedHereIds: Set<String>,
            today: LocalDate,
            zone: ZoneId,
        ): ActivityStats {
            val days = (reviews.map { it.finishedAt } + quizzes.map { it.playedAt })
                .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                .toSet()
            val scores = reviews.mapNotNull { review ->
                review.attempt.order.size.takeIf { it > 0 }?.let { review.attempt.correctCount.toDouble() / it }
            } + quizzes.mapNotNull { it.scoreFraction }
            return ActivityStats(
                quizzesPlayed = quizzes.filter { it.role == RoomRole.PLAYER }.distinctBy { it.roomId }.size,
                roomsHosted = (quizzes.filter { it.role == RoomRole.HOST }.map { it.roomId } + hostedHereIds).toSet().size,
                streakDays = streak(days, today),
                averagePercent = scores.takeIf { it.isNotEmpty() }?.let { (it.average() * 100).roundToInt() },
            )
        }

        /** A streak that has no activity today yet still counts until the day ends. */
        fun streak(days: Set<LocalDate>, today: LocalDate): Int {
            var day = if (today in days) today else today.minusDays(1)
            var count = 0
            while (day in days) {
                count++
                day = day.minusDays(1)
            }
            return count
        }
    }
}
