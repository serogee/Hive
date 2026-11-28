package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.engine.Progress
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun createRoomValidatesStackAndTextAnswersAreLengthCappedForAllTypes() = runTest {
        val service = service()
        val invalidStack = StackSnapshot(
            id = StackId("bad"),
            title = "Bad Stack",
            questions = listOf(Question.TrueFalse(QuestionId("q1"), prompt = " ", answer = true)),
        )
        val badRoom = assertFailsWith<HiveException> {
            service.createRoom(alex, CreateRoomRequest(invalidStack, RoomMode.DISCUSSION))
        }
        assertEquals(ErrorCode.INVALID_REQUEST, badRoom.code)

        val host = service.createRoom(alex, createRequest())
        val guest = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service.command(host.room.id, alex, null, HostCommand.Start)

        val oversized = assertFailsWith<HiveException> {
            service.submitAnswer(
                host.room.id,
                null,
                guest.participantToken,
                SubmitAnswerRequest(QuestionId("nf-q1"), Answer.Text("x".repeat(501)), "big"),
            )
        }
        assertEquals(ErrorCode.INVALID_REQUEST, oversized.code)
    }

    @Test
    fun endingARoomEvictsItsLockAndEventFlow() = runTest {
        val hub = RoomEventHub()
        val service = RoomService(InMemoryRoomStore(), hub = hub, clock = { 1_000L }, requireAccountToHost = true)
        val host = service.createRoom(alex, createRequest())
        service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)

        assertTrue(hub.activeRoomCount() > 0)
        // A lock stays only while a call holds or waits for it.
        assertEquals(0, service.trackedLockCount())

        service.command(host.room.id, alex, null, HostCommand.EndRoom)

        assertEquals(0, hub.activeRoomCount())
        assertEquals(0, service.trackedLockCount())
    }

    @Test
    fun callsThatWaitWhileARoomEndsStillTakeTurns() = runTest {
        val store = GatedStore()
        val service = RoomService(store, clock = { 1_000L }, random = Random(7), requireAccountToHost = true)
        val host = service.createRoom(alex, createRequest())
        val roomId = host.room.id
        val jamie = service.join(roomId, null, JoinRoomRequest("Jamie", asGuest = true), null)
        val kai = service.join(roomId, null, JoinRoomRequest("Kai", asGuest = true), null)
        val revisionBefore = store.findRoom(roomId)!!.revision

        store.endGate = CompletableDeferred()
        store.leaveGate = CompletableDeferred()
        val end = launch { service.command(roomId, alex, null, HostCommand.EndRoom) }
        runCurrent()
        val first = launch { service.leave(roomId, null, jamie.participantToken) }
        runCurrent()
        assertEquals(1, service.trackedLockCount())

        // The end finishes while the first leave waits, then that leave holds the lock.
        store.endGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, store.leavesInside)
        val second = launch { service.leave(roomId, null, kai.participantToken) }
        runCurrent()
        assertEquals(1, store.mostLeavesInside)

        store.leaveGate!!.complete(Unit)
        listOf(end, first, second).forEach { it.join() }
        assertEquals(1, store.mostLeavesInside)
        assertEquals(revisionBefore + 3, store.findRoom(roomId)!!.revision)
        assertEquals(0, service.trackedLockCount())
    }

    @Test
    fun aJoinThatFindsAnotherRoomToLeaveStartsAgainWithItsLock() = runTest {
        val store = GatedStore()
        val service = RoomService(store, clock = { 1_000L }, random = Random(7), requireAccountToHost = true)
        val first = service.createRoom(alex, createRequest())
        val second = service.createRoom(alex, createRequest())
        service.join(first.room.id, sam, JoinRoomRequest(asGuest = false), null)

        // The join looks for rooms to leave before Sam's first room shows up, then finds it under the locks.
        store.staleMemberships = 1
        val joined = service.join(second.room.id, sam, JoinRoomRequest(asGuest = false, leaveOtherRoom = true), null)

        assertEquals(second.room.id, joined.room.id)
        val left = store.participants(first.room.id).single { it.accountId == sam.id }
        assertTrue(left.leftAt != null)
        assertEquals(0, service.trackedLockCount())
    }

    /** Holds chosen store calls until the test lets them go, and counts leaves in progress. */
    private class GatedStore(private val inner: InMemoryRoomStore = InMemoryRoomStore()) : RoomStore by inner {
        var endGate: CompletableDeferred<Unit>? = null
        var leaveGate: CompletableDeferred<Unit>? = null
        var leavesInside = 0
        var mostLeavesInside = 0

        /** How many next calls of [activeMembershipsOf] answer with no memberships. */
        var staleMemberships = 0

        override suspend fun updateProgress(roomId: RoomId, progress: Progress, revision: Long) {
            endGate?.await()
            inner.updateProgress(roomId, progress, revision)
        }

        override suspend fun setLeft(participant: ParticipantRecord, leftAt: Long?, revision: Long) {
            leavesInside++
            mostLeavesInside = maxOf(mostLeavesInside, leavesInside)
            try {
                leaveGate?.await()
                inner.setLeft(participant, leftAt, revision)
            } finally {
                leavesInside--
            }
        }

        override suspend fun activeMembershipsOf(accountId: AccountId): List<ParticipantRecord> {
            if (staleMemberships > 0) {
                staleMemberships--
                return emptyList()
            }
            return inner.activeMembershipsOf(accountId)
        }
    }
}
