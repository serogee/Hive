package io.github.serogee.hive.ui.rooms

import io.github.serogee.hive.audio.Sfx
import io.github.serogee.hive.core.api.AnswerReceipt
import io.github.serogee.hive.core.api.AttemptView
import io.github.serogee.hive.core.api.LiveQuestion
import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.api.PlayerQuestion
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RevealPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.model.SubmissionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LobbyAudioTest {
    private val host = ParticipantView(ParticipantId("host"), "Host", RoomRole.HOST, isGuest = false)
    private val player1 = ParticipantView(ParticipantId("p1"), "Sam", RoomRole.PLAYER, isGuest = true)
    private val player2 = ParticipantView(ParticipantId("p2"), "Lee", RoomRole.PLAYER, isGuest = true)

    private val q1Mc = PlayerQuestion.MultipleChoice(
        id = QuestionId("q1"),
        number = 1,
        prompt = "2 + 2?",
        choices = listOf(Choice(ChoiceId("a"), "4"), Choice(ChoiceId("b"), "5")),
        points = 1,
    )
    private val q2Tf = PlayerQuestion.TrueFalse(
        id = QuestionId("q2"),
        number = 2,
        prompt = "Earth is round",
        points = 1,
    )
    private val q3Id = PlayerQuestion.Identification(
        id = QuestionId("q3"),
        number = 3,
        prompt = "Capital of France",
        points = 1,
    )

    @Test
    fun `join chime plays only on Host phone and uses throttled bucket`() {
        val beforeHost = state(role = RoomRole.HOST, status = RoomStatus.LOBBY, participants = listOf(host))
        val afterHost = beforeHost.copy(participants = listOf(host, player1), revision = 2)
        assertEquals(
            listOf(RoomAudioCue.Throttled("join:r1", JOIN_CUE_THROTTLE_MILLIS, Sfx.PLAYER_JOIN)),
            lobbyTransitionCues(beforeHost, afterHost),
        )

        val beforePlayer = state(role = RoomRole.PLAYER, status = RoomStatus.LOBBY, participants = listOf(host, player1))
        val afterPlayer = beforePlayer.copy(participants = listOf(host, player1, player2), revision = 3)
        assertTrue(lobbyTransitionCues(beforePlayer, afterPlayer).isEmpty())
    }

    @Test
    fun `discussion start, next question, close, and end transitions emit expected cues`() {
        val lobby = state(role = RoomRole.HOST, status = RoomStatus.LOBBY)
        val liveQ1 = state(role = RoomRole.HOST, status = RoomStatus.LIVE, live = live(q1Mc, isOpen = true))
        val closedQ1 = liveQ1.copy(live = live(q1Mc, isOpen = false), revision = 3)
        val liveQ2 = liveQ1.copy(live = live(q2Tf, isOpen = true), revision = 4)
        val ended = state(role = RoomRole.HOST, status = RoomStatus.ENDED, revision = 5)

        assertEquals(listOf(RoomAudioCue.Once("start:r1", Sfx.QUIZ_START)), lobbyTransitionCues(lobby, liveQ1))
        assertEquals(listOf(RoomAudioCue.Once("timeup:r1:q1", Sfx.TIME_UP)), lobbyTransitionCues(liveQ1, closedQ1))
        assertEquals(listOf(RoomAudioCue.Once("next:r1:q2", Sfx.NEXT)), lobbyTransitionCues(closedQ1, liveQ2))
        assertEquals(listOf(RoomAudioCue.Once("results:r1", Sfx.RESULTS_FANFARE)), lobbyTransitionCues(liveQ2, ended))
        // Staying in ENDED does not replay fanfare.
        assertTrue(lobbyTransitionCues(ended, ended.copy(revision = 6)).isEmpty())
    }

    @Test
    fun `multiplayer verdict plays only for MC and TrueFalse when revealed after each question`() {
        val rightReceipt = AnswerReceipt(SubmissionId("s1"), q1Mc.id, Answer.SelectedChoice(ChoiceId("a")), 10L)
        val wrongReceipt = AnswerReceipt(SubmissionId("s2"), q2Tf.id, Answer.TrueFalse(false), 10L)
        val typedReceipt = AnswerReceipt(SubmissionId("s3"), q3Id.id, Answer.Text("paris"), 10L)

        // MC correct reveal on close plays CORRECT without stacking TIME_UP.
        val openMc = state(
            role = RoomRole.PLAYER,
            status = RoomStatus.LIVE,
            reveal = RevealPolicy.AFTER_EACH_QUESTION,
            live = live(q1Mc, isOpen = true, yourAnswer = rightReceipt),
        )
        val revealedMc = openMc.copy(
            live = live(q1Mc, isOpen = false, yourAnswer = rightReceipt, correctAnswer = Answer.SelectedChoice(ChoiceId("a"))),
            revision = 2,
        )
        assertEquals(listOf(RoomAudioCue.Once("verdict:r1:q1", Sfx.CORRECT)), lobbyTransitionCues(openMc, revealedMc))

        // TrueFalse incorrect reveal plays INCORRECT without stacking TIME_UP.
        val openTf = state(
            role = RoomRole.PLAYER,
            status = RoomStatus.LIVE,
            reveal = RevealPolicy.AFTER_EACH_QUESTION,
            live = live(q2Tf, isOpen = true, yourAnswer = wrongReceipt),
        )
        val revealedTf = openTf.copy(
            live = live(q2Tf, isOpen = false, yourAnswer = wrongReceipt, correctAnswer = Answer.TrueFalse(true)),
            revision = 2,
        )
        assertEquals(listOf(RoomAudioCue.Once("verdict:r1:q2", Sfx.INCORRECT)), lobbyTransitionCues(openTf, revealedTf))

        // Identification reveal does not play incorrect on case mismatch; falls back to TIME_UP on close.
        val openId = state(
            role = RoomRole.PLAYER,
            status = RoomStatus.LIVE,
            reveal = RevealPolicy.AFTER_EACH_QUESTION,
            live = live(q3Id, isOpen = true, yourAnswer = typedReceipt),
        )
        val revealedId = openId.copy(
            live = live(q3Id, isOpen = false, yourAnswer = typedReceipt, correctAnswer = Answer.Text("Paris")),
            revision = 2,
        )
        assertEquals(listOf(RoomAudioCue.Once("timeup:r1:q3", Sfx.TIME_UP)), lobbyTransitionCues(openId, revealedId))
    }

    @Test
    fun `async Player hears quiz start only when starting their attempt`() {
        val lobby = state(mode = RoomMode.ASYNCHRONOUS, role = RoomRole.PLAYER, status = RoomStatus.LOBBY)
        val opened = state(
            mode = RoomMode.ASYNCHRONOUS,
            role = RoomRole.PLAYER,
            status = RoomStatus.LIVE,
            attempt = AttemptView(started = false, finished = false, answeredCount = 0),
        )
        val started = opened.copy(
            attempt = AttemptView(started = true, finished = false, answeredCount = 0),
            live = live(q1Mc, isOpen = true),
            revision = 3,
        )
        assertTrue(lobbyTransitionCues(lobby, opened).isEmpty())
        assertEquals(listOf(RoomAudioCue.Once("start-attempt:r1", Sfx.QUIZ_START)), lobbyTransitionCues(opened, started))
    }

    private fun live(
        question: PlayerQuestion,
        isOpen: Boolean,
        yourAnswer: AnswerReceipt? = null,
        correctAnswer: Answer? = null,
    ) = LiveQuestion(
        question = question,
        isOpen = isOpen,
        questionCount = 3,
        answeredCount = 0,
        playerCount = 2,
        yourAnswer = yourAnswer,
        correctAnswer = correctAnswer,
    )

    private fun state(
        mode: RoomMode = RoomMode.DISCUSSION,
        role: RoomRole,
        status: RoomStatus,
        reveal: RevealPolicy = RevealPolicy.AT_END,
        participants: List<ParticipantView> = listOf(host, player1),
        live: LiveQuestion? = null,
        attempt: AttemptView? = null,
        revision: Long = 1,
    ): RoomState = RoomState(
        room = RoomSummary(
            id = RoomId("r1"),
            code = "HIVE42",
            title = "Quiz",
            hostName = "Host",
            mode = mode,
            admission = AdmissionPolicy.EVERYONE,
            status = status,
            questionCount = 3,
            revealPolicy = reveal,
        ),
        you = if (role == RoomRole.HOST) host else player1,
        participants = participants,
        revision = revision,
        live = live,
        attempt = attempt,
    )
}
