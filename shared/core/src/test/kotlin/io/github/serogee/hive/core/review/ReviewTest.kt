package io.github.serogee.hive.core.review

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewTest {
    private val stack = SampleStacks.networkingFundamentals

    @Test
    fun quizReviewScoresAnswersAndListsMissedQuestions() {
        var attempt = Review.start("a1", stack, ReviewSettings(), Random(1))

        val (afterFirst, firstCorrect) = Review.answer(attempt, Answer.SelectedChoice(ChoiceId("nf-q1-c3")))
        attempt = Review.next(afterFirst)
        val (afterSecond, secondCorrect) = Review.answer(attempt, Answer.Text("false"))
        attempt = Review.next(afterSecond)

        assertTrue(firstCorrect)
        assertFalse(secondCorrect)
        assertEquals(2, attempt.position)
        assertEquals(listOf(stack.questions[1].id), attempt.toRetry)
        assertEquals(1, attempt.correctCount)
    }

    @Test
    fun typedAnswersWorkForEveryQuestionType() {
        val answers = listOf("Network", "true", "dns", "switch", "False", "443")
        var attempt = Review.start("a1", stack, ReviewSettings(preferTyping = true), Random(1))

        val results = answers.map { text ->
            val (scored, correct) = Review.answer(attempt, Answer.Text(text))
            attempt = Review.next(scored)
            correct
        }

        assertEquals(List(6) { true }, results)
        assertTrue(attempt.isFinished)
    }

    @Test
    fun flashcardMarksDriveTheRetryList() {
        var attempt = Review.start("a1", stack, ReviewSettings(mode = ReviewMode.FLASHCARD), Random(1))
        repeat(stack.questions.size) { index -> attempt = Review.mark(attempt, gotIt = index % 2 == 0) }

        val retry = Review.retry("a2", attempt)

        assertTrue(attempt.isFinished)
        assertEquals(listOf(1, 3, 5).map { stack.questions[it].id }, retry.order)
        assertEquals(0, retry.position)
        assertEquals(ReviewMode.FLASHCARD, retry.settings.mode)
    }

    @Test
    fun shuffledOrderIsSavedSoResumeReturnsToTheSameCard() {
        val attempt = Review.next(Review.start("a1", stack, ReviewSettings(shuffled = true), Random(3)))

        val restored = HiveJson.decodeFromString(ReviewAttempt.serializer(), HiveJson.encodeToString(ReviewAttempt.serializer(), attempt))

        assertNotEquals(stack.questions.map { it.id }, attempt.order)
        assertEquals(attempt.order.toSet(), stack.questions.map { it.id }.toSet())
        assertEquals(attempt.current, restored.current)
        assertTrue(restored.current is Question)
    }

    @Test
    fun fiftyScoredAnswersMatchTheirExpectedResults() {
        val identification = stack.questions[2] as Question.Identification
        val cases = (1..50).map { i ->
            when (i % 5) {
                0 -> "DNS" to true
                1 -> " domain name system " to true
                2 -> "Dns." to true
                3 -> "DHCP" to false
                else -> "D N S" to false
            }
        }

        val results = cases.map { (text, _) ->
            Review.answer(ReviewAttempt("a", stack, ReviewSettings(), listOf(identification.id)), Answer.Text(text)).second
        }

        assertEquals(cases.map { it.second }, results)
    }
}
