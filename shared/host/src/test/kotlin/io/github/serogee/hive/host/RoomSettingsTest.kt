package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RoomSettingsTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val store = InMemoryRoomStore()
    private val service = RoomService(store, clock = { 5_000L }, requireAccountToHost = true)
    private val settings = HostCommand.UpdateSettings(
        title = "Friday quiz",
        admission = AdmissionPolicy.GUESTS_ONLY,
        speedScoring = true,
        timeTiebreak = false,
        deadlineAt = 90_000L,
        questionSeconds = 20,
        revealPolicy = RevealPolicy.AFTER_EACH_QUESTION,
    )

    private suspend fun room(mode: RoomMode = RoomMode.DISCUSSION) =
        service.createRoom(alex, CreateRoomRequest(SampleStacks.networkingFundamentals, mode))

    private suspend fun fails(code: ErrorCode, block: suspend () -> Unit) =
        assertEquals(code, assertFailsWith<HiveException> { block() }.code)

    @Test
    fun theHostChangesTheRoomInTheLobby() = runTest {
        RoomMode.entries.forEach { mode ->
            val host = room(mode)
            val state = service.command(host.room.id, alex, null, settings)

            assertEquals("Friday quiz", state.room.title)
            assertEquals(AdmissionPolicy.GUESTS_ONLY, state.room.admission)
            assertTrue(state.room.speedScoring)
            assertEquals(false, state.room.timeTiebreak)
            assertEquals(90_000L, state.room.deadlineAt)
            assertEquals(RevealPolicy.AFTER_EACH_QUESTION, state.room.revealPolicy)
            val stored = checkNotNull(store.findRoom(host.room.id))
            assertTrue(stored.stack.questions.all { it.durationSeconds == 20 })
            assertEquals(stored.revision, state.revision)
        }
    }

    @Test
    fun timersCanBeKeptOrTurnedOff() = runTest {
        val host = room()
        val before = checkNotNull(store.findRoom(host.room.id)).stack

        service.command(host.room.id, alex, null, settings.copy(questionSeconds = null))
        assertEquals(before, checkNotNull(store.findRoom(host.room.id)).stack)

        service.command(host.room.id, alex, null, settings.copy(questionSeconds = 0))
        checkNotNull(store.findRoom(host.room.id)).stack.questions.forEach { assertNull(it.durationSeconds) }
    }

    @Test
    fun settingsAreFixedOnceTheQuizStarts() = runTest {
        val host = room()
        service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service.command(host.room.id, alex, null, HostCommand.Start)
        fails(ErrorCode.INVALID_COMMAND) { service.command(host.room.id, alex, null, settings) }
    }

    @Test
    fun wrongSettingsAreRejected() = runTest {
        val host = room()
        fails(ErrorCode.INVALID_REQUEST) { service.command(host.room.id, alex, null, settings.copy(title = " ")) }
        fails(ErrorCode.INVALID_REQUEST) { service.command(host.room.id, alex, null, settings.copy(deadlineAt = 4_000L)) }
        fails(ErrorCode.INVALID_REQUEST) { service.command(host.room.id, alex, null, settings.copy(questionSeconds = 2)) }
    }

    @Test
    fun onlyTheHostChangesSettings() = runTest {
        val host = room()
        val guest = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        fails(ErrorCode.FORBIDDEN) { service.command(host.room.id, null, guest.participantToken, settings) }
    }

    @Test
    fun aPhoneCannotAskForSignedInPlayers() = runTest {
        val phone = RoomService(InMemoryRoomStore(), clock = { 5_000L }, requireAccountToHost = false, accountsSupported = false)
        val host = phone.createRoom(null, CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION, hostDisplayName = "Ms. Reyes"))
        fails(ErrorCode.INVALID_REQUEST) {
            phone.command(host.room.id, null, host.participantToken, settings.copy(admission = AdmissionPolicy.SIGNED_IN_ONLY))
        }
    }

    @Test
    fun theHostCanEndARoomThatNeverStarted() = runTest {
        RoomMode.entries.forEach { mode ->
            val host = room(mode)
            assertEquals(RoomStatus.ENDED, service.command(host.room.id, alex, null, HostCommand.EndRoom).room.status)
        }
    }
}
