package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MembershipTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val sam = Account(AccountId("account-sam"), "Sam Cruz")
    private val service = RoomService(InMemoryRoomStore(), clock = { 1_000L }, requireAccountToHost = true)

    private suspend fun room() =
        service.createRoom(alex, CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION)).room

    @Test
    fun anAccountMustChooseToLeaveItsCurrentRoom() = runTest {
        val first = room()
        val second = room()
        service.join(first.id, sam, JoinRoomRequest(asGuest = false), null)

        val error = assertFailsWith<HiveException> { service.join(second.id, sam, JoinRoomRequest(asGuest = false), null) }
        service.join(second.id, sam, JoinRoomRequest(asGuest = false, leaveOtherRoom = true), null)
        val firstRoom = service.state(first.id, alex, null)

        assertEquals(ErrorCode.ALREADY_IN_ROOM, error.code)
        assertTrue(error.message.contains(first.title))
        assertEquals(true, firstRoom.participants.single { it.displayName == "Sam Cruz" }.left)
    }

    @Test
    fun hostingAndEndedRoomsDoNotCount() = runTest {
        val hosted = service.createRoom(sam, CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION)).room
        val ended = room()
        val other = room()
        service.join(ended.id, sam, JoinRoomRequest(asGuest = false), null)
        service.command(ended.id, alex, null, HostCommand.EndRoom)

        service.join(other.id, sam, JoinRoomRequest(asGuest = false), null)

        assertEquals("Sam Cruz", service.state(hosted.id, sam, null).you.displayName)
    }

    @Test
    fun rejoiningTheSameRoomIsNotASecondMembership() = runTest {
        val first = room()
        val joined = service.join(first.id, sam, JoinRoomRequest(asGuest = false), null)

        val again = service.join(first.id, sam, JoinRoomRequest(asGuest = false), null)

        assertEquals(joined.participant.id, again.participant.id)
    }

    @Test
    fun aPlayerWhoLeftCannotAnswerUntilTheyRejoin() = runTest {
        val first = room()
        val guest = service.join(first.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service.command(first.id, alex, null, HostCommand.Start)
        val answer = SubmitAnswerRequest(QuestionId("nf-q1"), Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "k1")

        service.leave(first.id, null, guest.participantToken)
        val blocked = assertFailsWith<HiveException> { service.submitAnswer(first.id, null, guest.participantToken, answer) }
        val back = service.join(first.id, null, JoinRoomRequest("Jamie", asGuest = true), guest.participantToken)
        service.submitAnswer(first.id, null, guest.participantToken, answer)

        assertEquals(ErrorCode.FORBIDDEN, blocked.code)
        assertEquals(guest.participant.id, back.participant.id)
        assertEquals(false, back.participant.left)
    }
}
