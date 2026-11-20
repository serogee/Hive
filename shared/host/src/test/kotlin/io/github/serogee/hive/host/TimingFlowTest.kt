package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Question 1 of the sample is worth 2 points with a 30-second limit. Question 2 has no limit. */
class TimingFlowTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val q1 = QuestionId("nf-q1")
    private val right = Answer.SelectedChoice(ChoiceId("nf-q1-c3"))

    private class Room(val service: RoomService, val host: Membership, val guest: Membership)

    private suspend fun TestScope.room(
        stack: StackSnapshot = SampleStacks.networkingFundamentals,
        deadlineInMillis: Long? = null,
        speedScoring: Boolean = false,
    ): Room {
        val service = RoomService(
            store = InMemoryRoomStore(),
            clock = { testScheduler.currentTime },
            requireAccountToHost = true,
            timers = backgroundScope,
        )
        val host = service.createRoom(
            alex,
            CreateRoomRequest(
                stack,
                RoomMode.DISCUSSION,
                deadlineAt = deadlineInMillis?.let { testScheduler.currentTime + it },
                speedScoring = speedScoring,
            ),
        )
        val guest = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        return Room(service, host, guest)
    }

    private suspend fun Room.hostDoes(command: HostCommand) = service.command(host.room.id, alex, null, command)

    private suspend fun Room.guestState() = service.state(guest.room.id, null, guest.participantToken)

    private suspend fun Room.answer(key: String, answer: Answer = right) =
        service.submitAnswer(guest.room.id, null, guest.participantToken, SubmitAnswerRequest(q1, answer, key))

    @Test
    fun aTimedQuestionLocksWhenTimeRunsOut() = runTest {
        val room = room()
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            room.service.events(room.host.room.id).filterIsInstance<RoomEvent.QuestionClosed>().first()
        }
        val started = room.hostDoes(HostCommand.Start)

        advanceTimeBy(30_001)
        runCurrent()
        val error = assertFailsWith<HiveException> { room.answer("late") }

        assertEquals(30_000L, started.live?.closesAt)
        assertEquals(1, closed.await().questionNumber)
        assertEquals(false, room.guestState().live?.isOpen)
        assertEquals(ErrorCode.QUESTION_NOT_OPEN, error.code)
    }

    @Test
    fun reconnectingDoesNotResetTheTimer() = runTest {
        val room = room()
        room.hostDoes(HostCommand.Start)
        advanceTimeBy(10_000)

        val rejoined = room.service.join(room.guest.room.id, null, JoinRoomRequest("Jamie", asGuest = true), room.guest.participantToken)
        val state = room.service.state(room.guest.room.id, null, rejoined.participantToken)

        assertEquals(30_000L, state.live?.closesAt)
        assertEquals(10_000L, state.live?.serverTime)
    }

    @Test
    fun theEarlierOfTimerAndDeadlineWins() = runTest {
        val room = room(deadlineInMillis = 20_000)

        val started = room.hostDoes(HostCommand.Start)
        advanceTimeBy(20_001)
        runCurrent()

        assertEquals(20_000L, started.live?.closesAt)
        assertEquals(false, room.guestState().live?.isOpen)
    }

    @Test
    fun aDeadlineAloneLocksAnUntimedQuestion() = runTest {
        val untimed = SampleStacks.networkingFundamentals.copy(
            questions = SampleStacks.networkingFundamentals.questions.map {
                (it as? Question.MultipleChoice)?.copy(durationSeconds = null) ?: it
            },
        )
        val room = room(stack = untimed, deadlineInMillis = 90_000)

        val started = room.hostDoes(HostCommand.Start)
        advanceTimeBy(89_000)
        room.answer("in-time")

        assertEquals(90_000L, started.live?.closesAt)
    }

    @Test
    fun withNeitherLimitTheQuestionStaysOpen() = runTest {
        val room = room()
        room.hostDoes(HostCommand.Start)
        room.hostDoes(HostCommand.CloseQuestion)
        room.hostDoes(HostCommand.NextQuestion)

        advanceTimeBy(3_600_000)
        val live = room.guestState().live

        assertNull(live?.closesAt)
        assertEquals(true, live?.isOpen)
        assertEquals(2, live?.question?.number)
    }

    @Test
    fun theHostCanMoveOnAfterTheTimerWithoutClosing() = runTest {
        val room = room()
        room.hostDoes(HostCommand.Start)
        advanceTimeBy(31_000)

        val next = room.hostDoes(HostCommand.NextQuestion)

        assertEquals(2, next.live?.question?.number)
    }

    @Test
    fun speedBonusIsAddedToOneDecimal() = runTest {
        val room = room(speedScoring = true)
        room.hostDoes(HostCommand.Start)
        advanceTimeBy(6_000)
        room.answer("fast")
        room.hostDoes(HostCommand.EndRoom)

        val results = room.service.results(room.guest.room.id, null, room.guest.participantToken)

        assertEquals(28, results.leaderboard.single().totalTenths)
        assertEquals(8, results.yourAnswers.first().speedBonusTenths)
        assertEquals(6_000L, results.leaderboard.single().answerTimeMillis)
    }

    @Test
    fun withoutSpeedScoringOnlyPointsCount() = runTest {
        val room = room(speedScoring = false)
        room.hostDoes(HostCommand.Start)
        advanceTimeBy(6_000)
        room.answer("fast")
        room.hostDoes(HostCommand.EndRoom)

        val entry = room.service.results(room.host.room.id, alex, null).leaderboard.single()

        assertEquals(20, entry.totalTenths)
        assertEquals(0, entry.speedBonusTenths)
    }

    @Test
    fun twoRoomsAdvanceIndependently() = runTest {
        val first = room()
        val second = room()
        first.hostDoes(HostCommand.Start)
        second.hostDoes(HostCommand.Start)

        first.hostDoes(HostCommand.CloseQuestion)
        first.hostDoes(HostCommand.NextQuestion)
        advanceTimeBy(5_000)
        second.answer("second-room")

        assertEquals(2, first.guestState().live?.question?.number)
        assertEquals(1, second.guestState().live?.question?.number)
        assertEquals(0, first.service.state(first.host.room.id, alex, null).live?.answeredCount)
        assertNotEquals(first.host.room.code, second.host.room.code)
        assertTrue(second.guestState().live?.yourAnswer != null)
    }
}
