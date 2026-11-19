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
)

object Leaderboard {
    /**
     * Orders Players by points. Players with equal points share a rank, and the next rank skips
     * accordingly (1, 1, 3). Ties keep join order.
     */
    fun build(players: List<RankedPlayer>, answers: List<GradedAnswer>): List<LeaderboardEntry> {
        val byPlayer = answers.groupBy { it.participantId }
        val scored = players.map { player ->
            val own = byPlayer[player.participantId].orEmpty()
            LeaderboardEntry(
                participantId = player.participantId,
                displayName = player.displayName,
                isGuest = player.isGuest,
                score = own.sumOf { it.points },
                rank = 0,
                correctCount = own.count { it.correct },
            )
        }.sortedByDescending { it.score }

        return scored.map { entry ->
            entry.copy(rank = scored.indexOfFirst { it.score == entry.score } + 1)
        }
    }
}
