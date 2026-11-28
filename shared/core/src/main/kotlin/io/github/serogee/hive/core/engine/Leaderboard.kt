package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.LeaderboardEntry
import io.github.serogee.hive.core.model.ParticipantId

data class RankedPlayer(
    val participantId: ParticipantId,
    val displayName: String,
    val isGuest: Boolean,
)

data class GradedAnswer(
    val participantId: ParticipantId,
    val correct: Boolean,
    val points: Int,
    val speedBonusTenths: Int = 0,
    /** Time from the question opening for this Player to the answer arriving, when known. */
    val responseMillis: Long? = null,
)

object Leaderboard {
    /**
     * Orders Players by score: base points plus any speed bonus. With [timeTiebreak], Players with
     * equal scores are ordered by their total answer time on correct answers, with known times ahead
     * of unknown times so ranking stays strictly transitive. Tied Players share a rank, and the next
     * rank skips accordingly (1, 1, 3).
     */
    fun build(players: List<RankedPlayer>, answers: List<GradedAnswer>, timeTiebreak: Boolean = false): List<LeaderboardEntry> {
        val byPlayer = answers.groupBy { it.participantId }
        val entries = players.map { player ->
            val own = byPlayer[player.participantId].orEmpty()
            val correct = own.filter { it.correct }
            val points = own.sumOf { it.points }
            val bonus = own.sumOf { it.speedBonusTenths }
            LeaderboardEntry(
                participantId = player.participantId,
                displayName = player.displayName,
                isGuest = player.isGuest,
                score = points,
                rank = 0,
                correctCount = correct.size,
                speedBonusTenths = bonus,
                totalTenths = points * 10 + bonus,
                answerTimeMillis = if (correct.all { it.responseMillis != null }) correct.sumOf { it.responseMillis ?: 0 } else null,
            )
        }

        fun ahead(a: LeaderboardEntry, b: LeaderboardEntry): Boolean = when {
            a.totalTenths != b.totalTenths -> a.totalTenths > b.totalTenths
            !timeTiebreak -> false
            a.answerTimeMillis == null -> false
            b.answerTimeMillis == null -> true
            else -> a.answerTimeMillis < b.answerTimeMillis
        }

        return entries
            .map { entry -> entry.copy(rank = 1 + entries.count { ahead(it, entry) }) }
            .sortedBy { it.rank }
    }
}
