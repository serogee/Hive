package io.github.serogee.hive.core.engine

import kotlin.math.roundToInt

/**
 * Answer cutoffs. A question's time limit and the quiz deadline are both optional. When both
 * apply, the earlier one wins: a 60-second question opened 20 seconds before the deadline allows
 * 20 seconds.
 */
object Timing {
    /** Epoch milliseconds after which answers are refused, or null when nothing limits the time. */
    fun cutoff(openedAt: Long?, durationSeconds: Int?, deadlineAt: Long?): Long? {
        val questionEnd = if (openedAt != null && durationSeconds != null) openedAt + durationSeconds * 1000L else null
        return listOfNotNull(questionEnd, deadlineAt).minOrNull()
    }

    fun isBefore(now: Long, cutoff: Long?): Boolean = cutoff == null || now < cutoff
}

object SpeedBonus {
    /**
     * Extra score, in tenths of a point, for a correct answer to a timed question:
     * points × 50% × (time left ÷ time limit). Answers to untimed questions earn no bonus.
     */
    fun tenths(points: Int, durationSeconds: Int?, responseMillis: Long?, correct: Boolean): Int {
        if (!correct || durationSeconds == null || durationSeconds <= 0 || responseMillis == null) return 0
        val limit = durationSeconds * 1000.0
        val left = (limit - responseMillis).coerceIn(0.0, limit)
        return (points * 10 * 0.5 * left / limit).roundToInt()
    }

    /** Formats tenths of a point with at most one decimal, for example 28 as "2.8" and 30 as "3". */
    fun format(tenths: Int): String = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}
