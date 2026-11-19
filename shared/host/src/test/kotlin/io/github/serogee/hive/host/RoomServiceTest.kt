package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class RoomServiceTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val sam = Account(AccountId("account-sam"), "Sam Cruz")

    private fun service(requireAccountToHost: Boolean = true) = RoomService(
        store = InMemoryRoomStore(),
        clock = { 1_000L },
        random = Random(7),
        requireAccountToHost = requireAccountToHost,
    )

    private fun createRequest(admission: AdmissionPolicy = AdmissionPolicy.EVERYONE) = CreateRoomRequest(
        stack = SampleStacks.networkingFundamentals,
        mode = RoomMode.DISCUSSION,
        admission = admission,
    )

    @Test
    fun onlineHostingNeedsAnAccount() = runTest {
        val error = assertFailsWith<HiveException> { service().createRoom(null, createRequest()) }
        assertEquals(ErrorCode.SIGN_IN_REQUIRED, error.code)
    }

    @Test
    fun localHostingWorksWithoutAnAccount() = runTest {
        val membership = service(requireAccountToHost = false).createRoom(
            null,
            createRequest().copy(hostDisplayName = "Ms. Reyes"),
        )

        assertEquals(RoomRole.HOST, membership.participant.role)
        assertTrue(membership.participant.isGuest)
        assertEquals("Ms. Reyes", membership.room.hostName)
    }

    @Test
    fun hostAndGuestSeeTheSameMembership() = runTest {
        val service = service()
        val host = service.createRoom(alex, createRequest())
        val guest = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)

        val hostView = service.state(host.room.id, alex, null)
        val guestView = service.state(host.room.id, null, guest.participantToken)

        assertEquals(listOf("Alex Rivera", "Jamie"), hostView.participants.map { it.displayName })
        assertEquals(hostView.participants, guestView.participants)
        assertEquals(guest.participant, guestView.you)
        assertEquals(2, hostView.revision)
    }

    @Test
    fun retryingAJoinReturnsTheSameParticipant() = runTest {
        val service = service()
        val host = service.createRoom(alex, createRequest())
        val first = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)

        val retry = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), first.participantToken)

        assertEquals(first.participant.id, retry.participant.id)
        assertEquals(2, service.state(host.room.id, alex, null).participants.size)
    }

    @Test
    fun guestsWithTheSameNameStayDistinct() = runTest {
        val service = service()
        val host = service.createRoom(alex, createRequest())

        val first = service.join(host.room.id, null, JoinRoomRequest("Alex", asGuest = true), null)
        val second = service.join(host.room.id, null, JoinRoomRequest("Alex", asGuest = true), null)

        assertNotEquals(first.participant.id, second.participant.id)
    }

    @Test
    fun anAccountRejoinsAsItsExistingParticipant() = runTest {
        val service = service()
        val host = service.createRoom(alex, createRequest())
        val first = service.join(host.room.id, sam, JoinRoomRequest(asGuest = false), null)

        val again = service.join(host.room.id, sam, JoinRoomRequest(asGuest = false), null)

        assertEquals(first.participant.id, again.participant.id)
        assertEquals(again.participant, service.state(host.room.id, null, again.participantToken).you)
    }

    @Test
    fun admissionPolicyIsEnforced() = runTest {
        val service = service()
        val room = service.createRoom(alex, createRequest(AdmissionPolicy.SIGNED_IN_ONLY)).room

        val error = assertFailsWith<HiveException> {
            service.join(room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        }
        assertEquals(ErrorCode.GUESTS_NOT_ALLOWED, error.code)
    }

    @Test
    fun outsidersCannotReadRoomState() = runTest {
        val service = service()
        val room = service.createRoom(alex, createRequest()).room

        val error = assertFailsWith<HiveException> { service.state(room.id, sam, null) }
        assertEquals(ErrorCode.NOT_A_PARTICIPANT, error.code)
    }

    @Test
    fun joinsAreBroadcastOnlyToTheirRoom() = runTest {
        val service = service()
        val roomA = service.createRoom(alex, createRequest()).room
        val roomB = service.createRoom(alex, createRequest()).room
        val eventA = async(start = CoroutineStart.UNDISPATCHED) { service.events(roomA.id).first() }

        service.join(roomB.id, null, JoinRoomRequest("Riley", asGuest = true), null)
        service.join(roomA.id, null, JoinRoomRequest("Jamie", asGuest = true), null)

        val event = eventA.await() as RoomEvent.ParticipantJoined
        assertEquals(roomA.id, event.roomId)
        assertEquals("Jamie", event.participant.displayName)
    }

    @Test
    fun codesAreUniqueAndCaseInsensitive() = runTest {
        val service = service()
        val room = service.createRoom(alex, createRequest()).room

        assertEquals(room.id, service.lookup(room.code.lowercase()).id)
        assertNotEquals(room.code, service.createRoom(alex, createRequest()).room.code)
    }
}
