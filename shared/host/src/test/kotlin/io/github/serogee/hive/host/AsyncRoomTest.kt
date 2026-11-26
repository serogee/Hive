package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.Membership
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Question 1 of the sample has a 30-second limit and question 2 has none. */
class AsyncRoomTest {
    private val sam = Account(AccountId("account-sam"), "Sam Cruz")

    private inner class Room(val service: RoomService, val host: Membership) {
        val id get() = host.room.id

        suspend fun join(name: String) = service.join(id, null, JoinRoomRequest(name, asGuest = true), null)

        suspend fun Membership.does(command: PlayerCommand) = service.attempt(id, null, participantToken, command)

        suspend fun Membership.state() = service.state(id, null, participantToken)

        suspend fun Membership.answers(question: String, answer: Answer, key: String) =
            service.submitAnswer(id, null, participantToken, SubmitAnswerRequest(QuestionId(question), answer, key))
    }

    private suspend fun TestScope.room(
        deadlineInMillis: Long? = null,
        timeTiebreak: Boolean = false,
        revealPolicy: RevealPolicy = RevealPolicy.AT_END,
    ): Room {
        val service = RoomService(
            store = InMemoryRoomStore(),
            clock = { testScheduler.currentTime },
            requireAccountToHost = true,
            timers = backgroundScope,
        )
        val host = service.createRoom(
            sam,
            CreateRoomRequest(
                SampleStacks.networkingFundamentals,
                RoomMode.ASYNCHRONOUS,
                deadlineAt = deadlineInMillis?.let { testScheduler.currentTime + it },
                revealPolicy = revealPolicy,
                timeTiebreak = timeTiebreak,
            ),
        )
        return Room(service, host)
    }

    @Test
    fun eachPlayersTimerStartsWhenTheServerOpensTheirQuestion() = runTest {
        val room = room()
        val jamie = room.join("Jamie")
        val riley = room.join("Riley")
        room.service.command(room.id, sam, null, HostCommand.Start)

        with(room) {
            val jamieStart = jamie.does(PlayerCommand.StartAttempt)
            advanceTimeBy(20_000)
            val rileyStart = riley.does(PlayerCommand.StartAttempt)

            assertEquals(30_000L, jamieStart.live?.closesAt)
            assertEquals(50_000L, rileyStart.live?.closesAt)
        }
    }

    @Test
    fun reconnectingKeepsTheOriginalExpiry() = runTest {
        val room = room()
        val jamie = room.join("Jamie")
        room.service.command(room.id, sam, null, HostCommand.Start)
        with(room) {
            jamie.does(PlayerCommand.StartAttempt)
            advanceTimeBy(25_000)

            // Rejoining and starting again return the same attempt and the same timer.
            val rejoined = room.service.join(room.id, null, JoinRoomRequest("Jamie", asGuest = true), jamie.participantToken)
            val again = rejoined.does(PlayerCommand.StartAttempt)
            advanceTimeBy(6_000)
            val late = assertFailsWith<HiveException> { jamie.answers("nf-q1", Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "late") }

            assertEquals(30_000L, again.live?.closesAt)
            assertEquals(ErrorCode.QUESTION_NOT_OPEN, late.code)
            assertEquals(false, jamie.state().live?.isOpen)
        }
    }

    @Test
    fun playersAdvanceIndependentlyAndTimeBetweenQuestionsIsExcluded() = runTest {
        val room = room(timeTiebreak = true)
        val jamie = room.join("Jamie")
        val riley = room.join("Riley")
        room.service.command(room.id, sam, null, HostCommand.Start)

        with(room) {
            jamie.does(PlayerCommand.StartAttempt)
            riley.does(PlayerCommand.StartAttempt)
            advanceTimeBy(5_000)
            jamie.answers("nf-q1", Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "j1")
            // Jamie takes a long break before opening question 2.
            advanceTimeBy(100_000)
            val second = jamie.does(PlayerCommand.NextQuestion)
            advanceTimeBy(3_000)
            jamie.answers("nf-q2", Answer.TrueFalse(true), "j2")

            val progress = room.service.state(room.id, sam, null).progress

            assertEquals(2, second.live?.question?.number)
            assertNull(second.live?.closesAt)
            assertEquals(listOf(2, 1), progress.map { it.currentQuestion })
            assertEquals(listOf(2, 0), progress.map { it.answeredCount })

            room.service.command(room.id, sam, null, HostCommand.EndRoom)
            val jamieEntry = room.service.results(room.id, sam, null).leaderboard.first { it.displayName == "Jamie" }
            assertEquals(8_000L, jamieEntry.answerTimeMillis)
        }
    }

    @Test
    fun finishingWaitsForTheRoomToCloseBeforeResults() = runTest {
        val room = room()
        val jamie = room.join("Jamie")
        room.service.command(room.id, sam, null, HostCommand.Start)

        with(room) {
            jamie.does(PlayerCommand.StartAttempt)
            repeat(SampleStacks.networkingFundamentals.questions.size) { jamie.does(PlayerCommand.NextQuestion) }
            val finished = jamie.state()
            val early = assertFailsWith<HiveException> { room.service.results(room.id, null, jamie.participantToken) }

            assertEquals(true, finished.attempt?.finished)
            assertNull(finished.live)
            assertEquals(ErrorCode.RESULTS_NOT_READY, early.code)
            assertTrue(room.service.state(room.id, sam, null).progress.single().finished)
        }
    }

    @Test
    fun theDeadlineClosesTheRoomForEveryone() = runTest {
        val room = room(deadlineInMillis = 60_000)
        val jamie = room.join("Jamie")
        room.service.command(room.id, sam, null, HostCommand.Start)

        with(room) {
            jamie.does(PlayerCommand.StartAttempt)
            jamie.does(PlayerCommand.NextQuestion)
            advanceTimeBy(60_001)
            runCurrent()

            val closed = assertFailsWith<HiveException> { jamie.does(PlayerCommand.NextQuestion) }
            val results = room.service.results(room.id, null, jamie.participantToken)

            assertEquals(ErrorCode.ROOM_CLOSED, closed.code)
            assertEquals(RoomStatus.ENDED, jamie.state().room.status)
            assertEquals(1, results.leaderboard.size)
        }
    }

    @Test
    fun theHostOnlyOpensAndEndsTheRoom() = runTest {
        val room = room()
        val jamie = room.join("Jamie")

        val notOpen = assertFailsWith<HiveException> { with(room) { jamie.does(PlayerCommand.StartAttempt) } }
        room.service.command(room.id, sam, null, HostCommand.Start)
        val hostNext = assertFailsWith<HiveException> { room.service.command(room.id, sam, null, HostCommand.NextQuestion) }

        assertEquals(ErrorCode.ROOM_CLOSED, notOpen.code)
        assertEquals(ErrorCode.INVALID_COMMAND, hostNext.code)
    }

    @Test
    fun revealAfterEachQuestionShowsAnswerAndExplanationRightAfterAnswering() = runTest {
        val revealed = room(revealPolicy = RevealPolicy.AFTER_EACH_QUESTION)
        val jamie = revealed.join("Jamie")
        revealed.service.command(revealed.id, sam, null, HostCommand.Start)

        with(revealed) {
            val before = jamie.does(PlayerCommand.StartAttempt)
            assertNull(before.live?.correctAnswer)
            assertNull(before.live?.explanation)

            // Timed Q1: answered while still open -> immediately revealed.
            jamie.answers("nf-q1", Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "k1")
            val afterQ1 = jamie.state().live
            assertEquals(Answer.SelectedChoice(ChoiceId("nf-q1-c3")), afterQ1?.correctAnswer)
            assertTrue(!afterQ1?.explanation.isNullOrBlank())

            // Untimed Q2: answered -> immediately revealed (not stuck waiting for a nonexistent timer).
            jamie.does(PlayerCommand.NextQuestion)
            assertNull(jamie.state().live?.correctAnswer)
            jamie.answers("nf-q2", Answer.TrueFalse(true), "k2")
            val afterQ2 = jamie.state().live
            assertEquals(Answer.TrueFalse(true), afterQ2?.correctAnswer)
            assertTrue(!afterQ2?.explanation.isNullOrBlank())
        }

        // With AT_END, answers and explanations stay hidden until the room ends.
        val hidden = room(revealPolicy = RevealPolicy.AT_END)
        val riley = hidden.join("Riley")
        hidden.service.command(hidden.id, sam, null, HostCommand.Start)
        with(hidden) {
            riley.does(PlayerCommand.StartAttempt)
            riley.answers("nf-q1", Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "r1")
            assertNull(riley.state().live?.correctAnswer)
            assertNull(riley.state().live?.explanation)
        }
    }
}
