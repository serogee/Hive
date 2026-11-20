package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.host.RoomService
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class SqliteRoomStoreTest {
    private val directory = Files.createTempDirectory("hive-store-test")
    private val dbPath = directory.resolve("demo.db")
    private val host = Account(AccountId("account-host"), "Host")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun roomsAndParticipantsSurviveReopening() = runTest {
        var openedAt = 0L
        val roomId = DemoDatabase.open(dbPath).use { db ->
            db.write { connection ->
                connection.createStatement().use {
                    it.executeUpdate("INSERT INTO accounts VALUES ('account-host', 'host', 'x', 'Host')")
                }
            }
            val service = RoomService(SqliteRoomStore(db), requireAccountToHost = true)
            val room = service.createRoom(host, CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION)).room
            val guest = service.join(room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
            service.join(room.id, null, JoinRoomRequest("Jamie", asGuest = true), guest.participantToken)
            openedAt = service.command(room.id, host, null, HostCommand.Start).live!!.closesAt!! - 30_000
            service.submitAnswer(
                room.id,
                null,
                guest.participantToken,
                SubmitAnswerRequest(QuestionId("nf-q1"), Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "k1"),
            )
            room.id
        }

        DemoDatabase.open(dbPath).use { db ->
            val state = RoomService(SqliteRoomStore(db), requireAccountToHost = true).state(roomId, host, null)

            assertEquals(listOf("Host", "Jamie"), state.participants.map { it.displayName })
            assertEquals(4, state.revision)
            assertEquals(RoomStatus.LIVE, state.room.status)
            assertEquals(1, state.live?.answeredCount)
            assertEquals(true, state.live?.isOpen)
            // The timer keeps its original end after a restart.
            assertEquals(openedAt + 30_000, state.live?.closesAt)
            assertEquals(SampleStacks.networkingFundamentals.questions.size, state.room.questionCount)
        }
    }
}
