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

    @Test
    fun rejoiningWithExistingTokenRejectsEndedRoomsAndEnforcesSingleActiveRoom() = runTest {
        val endedRoom = room()
        val guest = service.join(endedRoom.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service.leave(endedRoom.id, null, guest.participantToken)
        service.command(endedRoom.id, alex, null, HostCommand.EndRoom)

        val closed = assertFailsWith<HiveException> {
            service.join(endedRoom.id, null, JoinRoomRequest("Jamie", asGuest = true), guest.participantToken)
        }
        assertEquals(ErrorCode.ROOM_CLOSED, closed.code)
        assertEquals(true, service.state(endedRoom.id, alex, null).participants.single { it.displayName == "Jamie" }.left)

        // Signed-in player leaves room A, joins room B, then tries to rejoin room A with old token.
        val roomA = room()
        val roomB = room()
        val inA = service.join(roomA.id, sam, JoinRoomRequest(asGuest = false), null)
        service.leave(roomA.id, sam, inA.participantToken)
        service.join(roomB.id, sam, JoinRoomRequest(asGuest = false), null)

        val conflict = assertFailsWith<HiveException> {
            service.join(roomA.id, sam, JoinRoomRequest(asGuest = false, leaveOtherRoom = false), inA.participantToken)
        }
        assertEquals(ErrorCode.ALREADY_IN_ROOM, conflict.code)

        // Explicitly leaving room B lets them rejoin room A with the saved token.
        val backInA = service.join(roomA.id, sam, JoinRoomRequest(asGuest = false, leaveOtherRoom = true), inA.participantToken)
        assertEquals(false, backInA.participant.left)
        assertEquals(true, service.state(roomB.id, alex, null).participants.single { it.displayName == "Sam Cruz" }.left)
    }

    @Test
    fun leftPlayersDoNotCountTowardLivePlayerCount() = runTest {
        val first = room()
        val jamie = service.join(first.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service.join(first.id, null, JoinRoomRequest("Riley", asGuest = true), null)
        service.command(first.id, alex, null, HostCommand.Start)

        assertEquals(2, service.state(first.id, alex, null).live?.playerCount)
        service.leave(first.id, null, jamie.participantToken)
        assertEquals(1, service.state(first.id, alex, null).live?.playerCount)
        service.join(first.id, null, JoinRoomRequest("Jamie", asGuest = true), jamie.participantToken)
        assertEquals(2, service.state(first.id, alex, null).live?.playerCount)
    }
}
