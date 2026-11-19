package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.engine.Grading
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.SubmissionId
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.host.ParticipantRecord
import io.github.serogee.hive.host.RoomRecord
import io.github.serogee.hive.host.SubmissionRecord
import io.github.serogee.hive.host.Tokens
import java.sql.Connection
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Reproducible demo data. IDs and codes never change. Times are relative to the reset time, so the
 * seeded rooms stay usable during a demonstration.
 */
object DemoSeed {
    const val PASSWORD = "hive-demo"

    data class DemoUser(val id: AccountId, val username: String, val displayName: String)

    val alex = DemoUser(AccountId("demo-account-alex"), "alex", "Alex Rivera")
    val sam = DemoUser(AccountId("demo-account-sam"), "sam", "Sam Cruz")

    const val DISCUSSION_CODE = "NETDSC"
    const val ASYNC_CODE = "NETASY"
    const val ENDED_CODE = "NETEND"

    /** Deletes every demo record and writes the seed data again. */
    fun reset(db: DemoDatabase, now: Long) = db.transaction { connection ->
        db.dropAll()
        db.createSchema()
        seed(connection, now)
    }

    /** Writes the seed data into an empty database. */
    fun seedIfEmpty(db: DemoDatabase, now: Long) {
        if (db.isEmpty()) db.transaction { seed(it, now) }
    }

    private fun seed(connection: Connection, now: Long) {
        listOf(alex, sam).forEach { user ->
            connection.prepareStatement(
                "INSERT INTO accounts (id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
            ).use {
                it.setString(1, user.id.value)
                it.setString(2, user.username)
                it.setString(3, Tokens.hash(PASSWORD))
                it.setString(4, user.displayName)
                it.executeUpdate()
            }
        }

        val stack = SampleStacks.networkingFundamentals
        fun room(id: String, code: String, title: String, host: DemoUser, mode: RoomMode, status: RoomStatus, createdAt: Long, deadlineAt: Long?) =
            RoomRecord(
                id = RoomId(id),
                code = code,
                title = title,
                hostName = host.displayName,
                hostAccountId = host.id,
                mode = mode,
                admission = AdmissionPolicy.EVERYONE,
                status = status,
                stack = stack,
                createdAt = createdAt,
                deadlineAt = deadlineAt,
                revision = 1,
            )

        fun participant(id: String, room: RoomRecord, name: String, role: RoomRole, account: DemoUser?, joinedAt: Long) =
            ParticipantRecord(
                id = ParticipantId(id),
                roomId = room.id,
                displayName = name,
                role = role,
                accountId = account?.id,
                // Seeded participants cannot reconnect. Their credential is random and unknown.
                tokenHash = Tokens.hash(Tokens.newToken()),
                joinedAt = joinedAt,
            )

        val discussion = room(
            id = "demo-room-discussion",
            code = DISCUSSION_CODE,
            title = "Networking Fundamentals: Discussion",
            host = alex,
            mode = RoomMode.DISCUSSION,
            status = RoomStatus.LOBBY,
            createdAt = now,
            deadlineAt = null,
        )
        val async = room(
            id = "demo-room-async",
            code = ASYNC_CODE,
            title = "Networking Fundamentals: Take-home",
            host = sam,
            mode = RoomMode.ASYNCHRONOUS,
            status = RoomStatus.LIVE,
            createdAt = now,
            deadlineAt = now + 3.days.inWholeMilliseconds,
        )
        val endedAt = now - 1.days.inWholeMilliseconds
        val ended = room(
            id = "demo-room-ended",
            code = ENDED_CODE,
            title = "Networking Fundamentals: Last week",
            host = alex,
            mode = RoomMode.DISCUSSION,
            status = RoomStatus.ENDED,
            createdAt = endedAt - 1.hours.inWholeMilliseconds,
            deadlineAt = null,
        ).copy(questionIndex = stack.questions.lastIndex)

        val participants = listOf(
            participant("demo-p-discussion-host", discussion, alex.displayName, RoomRole.HOST, alex, now),
            participant("demo-p-async-host", async, sam.displayName, RoomRole.HOST, sam, now),
            participant("demo-p-ended-host", ended, alex.displayName, RoomRole.HOST, alex, ended.createdAt),
            participant("demo-p-ended-jamie", ended, "Jamie", RoomRole.PLAYER, null, ended.createdAt + 1.minutes.inWholeMilliseconds),
            participant("demo-p-ended-riley", ended, "Riley", RoomRole.PLAYER, null, ended.createdAt + 2.minutes.inWholeMilliseconds),
            participant("demo-p-ended-sam", ended, sam.displayName, RoomRole.PLAYER, sam, ended.createdAt + 3.minutes.inWholeMilliseconds),
        )

        listOf(discussion, async, ended).forEach { room ->
            val revision = 1L + participants.count { it.roomId == room.id && it.role == RoomRole.PLAYER } +
                (if (room == ended) endedAnswers.size else 0)
            SqliteRoomStore.insertRoom(connection, room.copy(revision = revision))
        }
        participants.forEach { SqliteRoomStore.insertParticipant(connection, it) }
        endedAnswers.forEachIndexed { index, (playerId, questionNumber, answer) ->
            val question = stack.questions[questionNumber - 1]
            val correct = Grading.isCorrect(question, answer)
            SqliteRoomStore.insertSubmission(
                connection,
                SubmissionRecord(
                    id = SubmissionId("demo-s-ended-$index"),
                    roomId = ended.id,
                    participantId = ParticipantId(playerId),
                    questionId = question.id,
                    answer = answer,
                    correct = correct,
                    points = Grading.points(question, correct),
                    idempotencyKey = "demo-seed-$index",
                    acceptedAt = endedAt - 30.minutes.inWholeMilliseconds + index * 1.minutes.inWholeMilliseconds,
                ),
            )
        }
    }

    /**
     * Answers in the ended room. Jamie and Sam tie on 3 points and share first place; Riley has 1.
     * Each entry is a participant ID, a question number, and the answer.
     */
    private val endedAnswers = listOf(
        Triple("demo-p-ended-jamie", 1, Answer.SelectedChoice(ChoiceId("nf-q1-c3"))),
        Triple("demo-p-ended-riley", 1, Answer.SelectedChoice(ChoiceId("nf-q1-c2"))),
        Triple("demo-p-ended-sam", 1, Answer.SelectedChoice(ChoiceId("nf-q1-c4"))),
        Triple("demo-p-ended-jamie", 2, Answer.TrueFalse(true)),
        Triple("demo-p-ended-riley", 2, Answer.TrueFalse(true)),
        Triple("demo-p-ended-sam", 2, Answer.TrueFalse(false)),
        Triple("demo-p-ended-jamie", 6, Answer.Text("80")),
        Triple("demo-p-ended-sam", 6, Answer.Text("443")),
    )
}