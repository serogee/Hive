package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.model.ParticipantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimingScoringTest {
    private val openedAt = 100_000L

    @Test
    fun allFourTimingCombinations() {
        // Neither limit.
        assertNull(Timing.cutoff(openedAt, durationSeconds = null, deadlineAt = null))
        // Duration only.
        assertEquals(openedAt + 60_000, Timing.cutoff(openedAt, durationSeconds = 60, deadlineAt = null))
        // Deadline only.
        assertEquals(500_000L, Timing.cutoff(openedAt, durationSeconds = null, deadlineAt = 500_000))
        // Both: a 60-second question opened 20 seconds before the deadline allows 20 seconds.
        assertEquals(openedAt + 20_000, Timing.cutoff(openedAt, durationSeconds = 60, deadlineAt = openedAt + 20_000))
    }

    @Test
    fun answersAreRefusedFromTheCutoff() {
        val cutoff = Timing.cutoff(openedAt, 30, null)

        assertTrue(Timing.isBefore(openedAt + 29_999, cutoff))
        assertFalse(Timing.isBefore(openedAt + 30_000, cutoff))
        assertTrue(Timing.isBefore(Long.MAX_VALUE, null))
    }

    @Test
    fun speedBonusShrinksEvenlyToZero() {
        // 2 points, 30 seconds, answered after 6 seconds: 2 × 0.5 × 24/30 = 0.8.
        assertEquals(8, SpeedBonus.tenths(points = 2, durationSeconds = 30, responseMillis = 6_000, correct = true))
        assertEquals(10, SpeedBonus.tenths(2, 30, 0, true))
        assertEquals(0, SpeedBonus.tenths(2, 30, 30_000, true))
        assertEquals(0, SpeedBonus.tenths(2, 30, 6_000, correct = false))
        assertEquals(0, SpeedBonus.tenths(2, durationSeconds = null, responseMillis = 6_000, correct = true))
        assertEquals("2.8", SpeedBonus.format(28))
        assertEquals("3", SpeedBonus.format(30))
    }

    @Test
    fun answerTimeOrdersEqualScoresOnlyWhenEnabled() {
        val fast = RankedPlayer(ParticipantId("fast"), "Fast", true)
        val slow = RankedPlayer(ParticipantId("slow"), "Slow", true)
        val unknown = RankedPlayer(ParticipantId("unknown"), "Unknown", true)
        val answers = listOf(
            GradedAnswer(slow.participantId, correct = true, points = 2, responseMillis = 9_000),
            GradedAnswer(fast.participantId, correct = true, points = 2, responseMillis = 3_000),
            GradedAnswer(unknown.participantId, correct = true, points = 2, responseMillis = null),
        )

        val withTiebreak = Leaderboard.build(listOf(slow, fast, unknown), answers, timeTiebreak = true)
        val without = Leaderboard.build(listOf(slow, fast, unknown), answers, timeTiebreak = false)

        assertEquals(listOf("fast" to 1, "slow" to 1, "unknown" to 1).map { it.second }, without.map { it.rank })
        assertEquals("fast", withTiebreak.first().participantId.value)
        assertEquals(1, withTiebreak.first { it.participantId == fast.participantId }.rank)
        assertEquals(2, withTiebreak.first { it.participantId == slow.participantId }.rank)
        // Known times strictly precede unknown times so ranking stays transitive (1, 2, 3).
        assertEquals(3, withTiebreak.first { it.participantId == unknown.participantId }.rank)
    }

    @Test
    fun equalScoresWithMixedNullTimesProduceStandardCompetitionRanks() {
        val fastA = RankedPlayer(ParticipantId("fast-a"), "Fast A", true)
        val fastB = RankedPlayer(ParticipantId("fast-b"), "Fast B", true)
        val unknownA = RankedPlayer(ParticipantId("unknown-a"), "Unknown A", true)
        val unknownB = RankedPlayer(ParticipantId("unknown-b"), "Unknown B", true)
        val answers = listOf(
            GradedAnswer(fastA.participantId, correct = true, points = 2, responseMillis = 3_000),
            GradedAnswer(fastB.participantId, correct = true, points = 2, responseMillis = 3_000),
            GradedAnswer(unknownA.participantId, correct = true, points = 2, responseMillis = null),
            GradedAnswer(unknownB.participantId, correct = true, points = 2, responseMillis = null),
        )

        val board = Leaderboard.build(listOf(unknownA, fastA, unknownB, fastB), answers, timeTiebreak = true)

        assertEquals(listOf(1, 1, 3, 3), board.map { it.rank })
    }

    @Test
    fun speedBonusCountsTowardTheTotal() {
        val a = RankedPlayer(ParticipantId("a"), "A", true)
        val b = RankedPlayer(ParticipantId("b"), "B", true)
        val answers = listOf(
            GradedAnswer(a.participantId, correct = true, points = 2, speedBonusTenths = 8),
            GradedAnswer(b.participantId, correct = true, points = 2, speedBonusTenths = 3),
        )

        val board = Leaderboard.build(listOf(b, a), answers)

        assertEquals(listOf("a", "b"), board.map { it.participantId.value })
        assertEquals(listOf(28, 23), board.map { it.totalTenths })
        assertEquals(listOf(2, 2), board.map { it.score })
    }
}
