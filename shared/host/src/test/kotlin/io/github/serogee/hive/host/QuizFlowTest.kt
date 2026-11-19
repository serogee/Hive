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
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class QuizFlowTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val q1 = QuestionId("nf-q1")
    private val q2 = QuestionId("nf-q2")
    private val rightQ1 = Answer.SelectedChoice(ChoiceId("nf-q1-c3"))
    private val wrongQ1 = Answer.SelectedChoice(ChoiceId("nf-q1-c1"))

    private class Setup(val service: RoomService, val host: Membership, val guest: Membership)

    private suspend fun setup(reveal: RevealPolicy = RevealPolicy.AT_END): Setup {
        val service = RoomService(InMemoryRoomStore(), clock = { 5_000L }, requireAccountToHost = true)
        val host = service.createRoom(
            alex,
            CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION, revealPolicy = reveal),
        )
        val guest = service.join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        return Setup(service, host, guest)
    }

    private suspend fun Setup.hostDoes(command: HostCommand) = service.command(host.room.id, alex, null, command)

    private suspend fun Setup.answer(member: Membership, question: QuestionId, answer: Answer, key: String) =
        service.submitAnswer(member.room.id, null, member.participantToken, SubmitAnswerRequest(question, answer, key))

    @Test
    fun reconnectAndRetryKeepOneParticipantAnswerAndScore() = runTest {
        val s = setup()
        s.hostDoes(HostCommand.Start)

        val receipt = s.answer(s.guest, q1, rightQ1, "jamie-q1")
        // The Player loses the connection, rejoins with the saved credential, and resends the answer.
        val rejoined = s.service.join(s.guest.room.id, null, JoinRoomRequest("Jamie", asGuest = true), s.guest.participantToken)
        val retry = s.answer(rejoined, q1, rightQ1, "jamie-q1")
        val state = s.service.state(s.guest.room.id, null, rejoined.participantToken)
        s.hostDoes(HostCommand.EndRoom)
        val results = s.service.results(s.host.room.id, alex, null)

        assertEquals(s.guest.participant.id, rejoined.participant.id)
        assertEquals(receipt, retry)
        assertEquals(receipt, state.live?.yourAnswer)
        assertEquals(1, state.live?.answeredCount)
        assertEquals(2, state.participants.size)
        assertEquals(listOf(2), results.leaderboard.map { it.score })
        assertEquals(1, results.questions.first().answeredCount)
    }

    @Test
    fun aSecondDifferentAnswerIsRejected() = runTest {
        val s = setup()
        s.hostDoes(HostCommand.Start)
        s.answer(s.guest, q1, wrongQ1, "first")

        val error = assertFailsWith<HiveException> { s.answer(s.guest, q1, rightQ1, "second") }
        assertEquals(ErrorCode.ANSWER_ALREADY_RECORDED, error.code)
    }

    @Test
    fun closedQuestionsRejectNewAnswersButReturnEarlierReceipts() = runTest {
        val s = setup()
        s.hostDoes(HostCommand.Start)
        val receipt = s.answer(s.guest, q1, rightQ1, "k1")
        s.hostDoes(HostCommand.CloseQuestion)
        val other = s.service.join(s.host.room.id, null, JoinRoomRequest("Riley", asGuest = true), null)

        assertEquals(receipt, s.answer(s.guest, q1, rightQ1, "k1"))
        val error = assertFailsWith<HiveException> { s.answer(other, q1, rightQ1, "k2") }
        assertEquals(ErrorCode.QUESTION_NOT_OPEN, error.code)
    }

    @Test
    fun onlyTheHostRunsTheQuizAndOnlyPlayersAnswer() = runTest {
        val s = setup()

        val notHost = assertFailsWith<HiveException> {
            s.service.command(s.guest.room.id, null, s.guest.participantToken, HostCommand.Start)
        }
        s.hostDoes(HostCommand.Start)
        val hostAnswer = assertFailsWith<HiveException> {
            s.service.submitAnswer(s.host.room.id, alex, null, SubmitAnswerRequest(q1, rightQ1, "h"))
        }
        val badMove = assertFailsWith<HiveException> { s.hostDoes(HostCommand.NextQuestion) }

        assertEquals(ErrorCode.FORBIDDEN, notHost.code)
        assertEquals(ErrorCode.FORBIDDEN, hostAnswer.code)
        assertEquals(ErrorCode.INVALID_COMMAND, badMove.code)
    }

    @Test
    fun answerKeysStayHiddenFromPlayersUntilTheEnd() = runTest {
        val s = setup(RevealPolicy.AT_END)
        s.hostDoes(HostCommand.Start)
        s.hostDoes(HostCommand.CloseQuestion)

        val playerView = s.service.state(s.guest.room.id, null, s.guest.participantToken).live
        val hostView = s.service.state(s.host.room.id, alex, null).live
        val early = assertFailsWith<HiveException> { s.service.results(s.guest.room.id, null, s.guest.participantToken) }

        assertNotNull(playerView)
        assertNull(playerView.correctAnswer)
        assertEquals(rightQ1, hostView?.correctAnswer)
        assertEquals(ErrorCode.RESULTS_NOT_READY, early.code)
    }

    @Test
    fun revealAfterEachQuestionShowsTheAnswerOnceClosed() = runTest {
        val s = setup(RevealPolicy.AFTER_EACH_QUESTION)
        s.hostDoes(HostCommand.Start)
        val whileOpen = s.service.state(s.guest.room.id, null, s.guest.participantToken).live
        s.hostDoes(HostCommand.CloseQuestion)
        val afterClose = s.service.state(s.guest.room.id, null, s.guest.participantToken).live

        assertNull(whileOpen?.correctAnswer)
        assertEquals(rightQ1, afterClose?.correctAnswer)
    }

    @Test
    fun hostAdvancesAndPlayersSeeTheirResultsAtTheEnd() = runTest {
        val s = setup()
        val opened = async(start = CoroutineStart.UNDISPATCHED) {
            s.service.events(s.host.room.id).filterIsInstance<RoomEvent.QuestionOpened>().first { it.questionNumber == 2 }
        }

        s.hostDoes(HostCommand.Start)
        s.answer(s.guest, q1, wrongQ1, "a")
        s.hostDoes(HostCommand.CloseQuestion)
        val second = s.hostDoes(HostCommand.NextQuestion)
        s.answer(s.guest, q2, Answer.TrueFalse(true), "b")
        val ended = s.hostDoes(HostCommand.EndRoom)
        val results = s.service.results(s.guest.room.id, null, s.guest.participantToken)

        assertEquals(2, opened.await().questionNumber)
        assertEquals(2, second.live?.question?.number)
        assertEquals(RoomStatus.ENDED, ended.room.status)
        assertNull(ended.live)
        assertEquals(listOf(false, true), results.yourAnswers.take(2).map { it.correct })
        assertEquals(1, results.leaderboard.single().score)
        assertTrue(results.questions.all { it.correctAnswer != null })
    }
}
