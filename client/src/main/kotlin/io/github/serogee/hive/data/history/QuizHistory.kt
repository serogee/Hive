package io.github.serogee.hive.data.history

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.RoomResults
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.local.QuizResultEntity
import io.github.serogee.hive.data.local.RecordSync
import io.github.serogee.hive.data.local.SyncDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Where a quiz ran. */
@Serializable
enum class QuizPlace(val hosted: String, val played: String) {
    ONLINE("Hosted online", "Played online"),
    NEARBY("Hosted nearby", "Played nearby"),
    THIS_PHONE("Hosted on a phone", "Played on a phone"),
}

/** A quiz that the user hosted or played. A Player's own score is there once the quiz ended. */
@Serializable
data class QuizResult(
    val roomId: String,
    val code: String,
    val title: String,
    val role: RoomRole,
    val place: QuizPlace,
    /** Epoch milliseconds. */
    val playedAt: Long,
    val questionCount: Int,
    val playerCount: Int,
    val ended: Boolean = true,
    val correctCount: Int? = null,
    /** Base points plus speed bonus, in tenths of a point. */
    val totalTenths: Int? = null,
    val rank: Int? = null,
) {
    val label: String get() = if (role == RoomRole.HOST) place.hosted else place.played

    /** Correct answers out of all questions, for a Player of an ended quiz. */
    val scoreFraction: Double?
        get() = correctCount?.takeIf { role == RoomRole.PLAYER && questionCount > 0 }?.let { it.toDouble() / questionCount }
}

/** Hosted and played quizzes, from this phone and from the account. Newest first. */
class QuizHistory(private val dao: SyncDao, private val now: () -> Long = System::currentTimeMillis) {
    val results: Flow<List<QuizResult>> = dao.observeResults().map { rows -> rows.map { decode(it.resultJson) } }

    /** Records each ended room that this phone hosted, once. */
    suspend fun recordHostedHere(rooms: List<RoomSummary>, players: suspend (RoomSummary) -> Int) {
        val ended = rooms.filter { it.status == RoomStatus.ENDED }
        if (ended.isEmpty()) return
        dao.insertResults(
            ended.map { room ->
                local(
                    id = "here:${room.id.value}",
                    result = QuizResult(
                        roomId = room.id.value,
                        code = room.code,
                        title = room.title,
                        role = RoomRole.HOST,
                        place = QuizPlace.THIS_PHONE,
                        playedAt = now(),
                        questionCount = room.questionCount,
                        playerCount = players(room),
                    ),
                )
            },
        )
    }

    /** Records the result of a room on another Host's phone, once it ended. */
    suspend fun recordPlayedNearby(results: RoomResults, participantId: ParticipantId) {
        if (results.room.status != RoomStatus.ENDED) return
        val you = results.leaderboard.firstOrNull { it.participantId == participantId } ?: return
        dao.insertResults(
            listOf(
                local(
                    id = "nearby:${results.room.id.value}:${participantId.value}",
                    result = QuizResult(
                        roomId = results.room.id.value,
                        code = results.room.code,
                        title = results.room.title,
                        role = RoomRole.PLAYER,
                        place = QuizPlace.NEARBY,
                        playedAt = now(),
                        questionCount = results.room.questionCount,
                        playerCount = results.leaderboard.size,
                        correctCount = you.correctCount,
                        totalTenths = you.totalTenths,
                        rank = you.rank,
                    ),
                ),
            ),
        )
    }

    private fun local(id: String, result: QuizResult) =
        QuizResultEntity(id, encode(result), result.playedAt, online = false, syncState = RecordSync.UNSENT)

    companion object {
        fun encode(result: QuizResult): String = HiveJson.encodeToString(QuizResult.serializer(), result)

        fun decode(json: String): QuizResult = HiveJson.decodeFromString(QuizResult.serializer(), json)

        /** The account's online rooms as history rows. */
        fun online(entry: OnlineRoomEntry): QuizResultEntity {
            val result = QuizResult(
                roomId = entry.roomId.value,
                code = entry.code,
                title = entry.title,
                role = entry.role,
                place = QuizPlace.ONLINE,
                playedAt = entry.createdAt,
                questionCount = entry.questionCount,
                playerCount = entry.playerCount,
                ended = entry.status == RoomStatus.ENDED,
                correctCount = entry.correctCount,
                totalTenths = entry.totalTenths,
                rank = entry.rank,
            )
            return QuizResultEntity("online:${entry.roomId.value}:${entry.role.name}", encode(result), entry.createdAt, online = true, syncState = RecordSync.SENT)
        }
    }
}
