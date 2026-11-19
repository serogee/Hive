package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.RoomMode
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
            room.id
        }

        DemoDatabase.open(dbPath).use { db ->
            val state = RoomService(SqliteRoomStore(db), requireAccountToHost = true).state(roomId, host, null)

            assertEquals(listOf("Host", "Jamie"), state.participants.map { it.displayName })
            assertEquals(2, state.revision)
            assertEquals(SampleStacks.networkingFundamentals.questions.size, state.room.questionCount)
        }
    }
}
