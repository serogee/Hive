package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuizRulesTest {
    private val questions = SampleStacks.networkingFundamentals.questions

    private fun Transition.progress() = assertIs<Transition.Accepted>(this).progress

    @Test
    fun hostOpensClosesAndAdvancesQuestions() {
        val started = RoomProgression.apply(Progress.Lobby, HostCommand.Start, 2).progress()
        val closed = RoomProgression.apply(started, HostCommand.CloseQuestion, 2).progress()
        val second = RoomProgression.apply(closed, HostCommand.NextQuestion, 2).progress()

        assertEquals(Progress(RoomStatus.LIVE, 0, questionOpen = true), started)
        assertEquals(Progress(RoomStatus.LIVE, 0, questionOpen = false), closed)
        assertEquals(Progress(RoomStatus.LIVE, 1, questionOpen = true), second)
    }

    @Test
    fun invalidTransitionsAreRejected() {
        val open = Progress(RoomStatus.LIVE, 0, questionOpen = true)
        val lastClosed = Progress(RoomStatus.LIVE, 1, questionOpen = false)

        assertIs<Transition.Rejected>(RoomProgression.apply(open, HostCommand.Start, 2))
        assertIs<Transition.Rejected>(RoomProgression.apply(open, HostCommand.NextQuestion, 2))
        assertIs<Transition.Rejected>(RoomProgression.apply(lastClosed, HostCommand.NextQuestion, 2))
        assertIs<Transition.Rejected>(RoomProgression.apply(Progress.Lobby, HostCommand.CloseQuestion, 2))
    }

    @Test
    fun endingTheRoomClosesTheQuestion() {
        val ended = RoomProgression.apply(Progress(RoomStatus.LIVE, 0, true), HostCommand.EndRoom, 2).progress()

        assertEquals(RoomStatus.ENDED, ended.status)
        assertFalse(ended.questionOpen)
        assertIs<Transition.Rejected>(RoomProgression.apply(ended, HostCommand.EndRoom, 2))
    }

    @Test
    fun multipleChoiceIsCheckedByStableId() {
        val question = questions[0] as Question.MultipleChoice
        val reordered = question.copy(choices = question.choices.reversed())

        assertTrue(Grading.isCorrect(reordered, Answer.SelectedChoice(ChoiceId("nf-q1-c3"))))
        assertFalse(Grading.isCorrect(reordered, Answer.SelectedChoice(ChoiceId("nf-q1-c1"))))
        assertTrue(Grading.isCorrect(question, Answer.Text(" network ")))
    }

    @Test
    fun typedAnswersIgnoreCaseAccentsAndSpacing() {
        val identification = questions[2] as Question.Identification

        assertTrue(Grading.isCorrect(identification, Answer.Text("dns")))
        assertTrue(Grading.isCorrect(identification, Answer.Text("domain  name system.")))
        assertFalse(Grading.isCorrect(identification, Answer.Text("DHCP")))
        assertEquals("cafe", Grading.normalize("Café"))
    }

    @Test
    fun trueFalseAcceptsButtonsAndTyping() {
        val question = questions[1] as Question.TrueFalse

        assertTrue(Grading.isCorrect(question, Answer.TrueFalse(true)))
        assertTrue(Grading.isCorrect(question, Answer.Text("True")))
        assertFalse(Grading.isCorrect(question, Answer.Text("maybe")))
    }

    @Test
    fun equalScoresShareARank() {
        val jamie = RankedPlayer(ParticipantId("jamie"), "Jamie", true)
        val alex1 = RankedPlayer(ParticipantId("alex-1"), "Alex", true)
        val alex2 = RankedPlayer(ParticipantId("alex-2"), "Alex", true)
        val answers = listOf(
            GradedAnswer(jamie.participantId, correct = true, points = 3),
            GradedAnswer(alex1.participantId, correct = true, points = 2),
            GradedAnswer(alex1.participantId, correct = true, points = 1),
            GradedAnswer(alex2.participantId, correct = false, points = 0),
        )

        val board = Leaderboard.build(listOf(jamie, alex1, alex2), answers)

        assertEquals(listOf(1, 1, 3), board.map { it.rank })
        assertEquals(listOf("jamie", "alex-1", "alex-2"), board.map { it.participantId.value })
        assertEquals(listOf(3, 3, 0), board.map { it.score })
        assertEquals(2, board[1].correctCount)
    }
}
