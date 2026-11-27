package io.github.serogee.hive.core.editor

import io.github.serogee.hive.core.model.AnswerMatching
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.TypoTolerance
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackValidationTest {
    private fun draft(vararg questions: QuestionDraft) =
        StackDraft(StackId("s1"), "Networking", "", questions.toList())

    private val complete = QuestionDraft(
        id = QuestionId("q1"),
        prompt = "Which layer routes packets?",
        choices = listOf(Choice(ChoiceId("c1"), "Network"), Choice(ChoiceId("c2"), "Physical")),
        correctChoiceId = ChoiceId("c1"),
    )

    @Test
    fun theSampleStackRoundTripsThroughDrafts() {
        val sample = SampleStacks.networkingFundamentals

        assertEquals(sample, StackValidation.toSnapshot(sample.toDraft()))
    }

    @Test
    fun answerMatchingSurvivesEditing() {
        val matching = AnswerMatching(ignoreCase = false, ignoreSymbols = true, typos = TypoTolerance.HIGH)
        val identification = QuestionDraft(
            id = QuestionId("q1"),
            type = QuestionType.IDENTIFICATION,
            prompt = "Which protocol resolves names?",
            identificationAnswer = "DNS",
            matching = matching,
        )

        val question = assertNotNull(StackValidation.toSnapshot(draft(identification))).questions.single()

        assertEquals(matching, (question as Question.Identification).matching)
        assertEquals(matching, question.toDraft().matching)
    }

    @Test
    fun issuesNameTheQuestionAndField() {
        val missingAnswer = complete.copy(id = QuestionId("q4"), correctChoiceId = null)
        val stack = draft(complete, complete.copy(id = QuestionId("q2")), complete.copy(id = QuestionId("q3")), missingAnswer)

        val issues = StackValidation.validate(stack)

        assertEquals(listOf("Question 4 needs a correct answer."), issues.map { it.message })
        assertEquals(QuestionId("q4"), issues.single().questionId)
        assertNull(StackValidation.toSnapshot(stack))
    }

    @Test
    fun emptyStacksAndBlankFieldsAreReported() {
        val messages = StackValidation.validate(StackDraft(StackId("s"), " ", "", emptyList())).map { it.message }
        val blank = StackValidation.questionIssues(QuestionDraft(QuestionId("q"), choices = listOf(Choice(ChoiceId("c"), ""))), 1)

        assertEquals(listOf("Give this stack a title.", "Add at least one question."), messages)
        assertTrue("Question 1 needs a prompt." in blank)
        assertTrue("Question 1, choice 1 is empty." in blank)
        assertTrue("Question 1 needs at least two choices." in blank)
    }

    @Test
    fun switchingTypesKeepsTheOtherTypesFields() {
        val switched = complete.copy(type = QuestionType.TRUE_FALSE, trueFalseAnswer = false)
        val back = switched.copy(type = QuestionType.MULTIPLE_CHOICE)

        assertEquals(complete.choices, back.choices)
        val snapshot = assertNotNull(StackValidation.toSnapshot(draft(back)))
        assertEquals(complete.correctChoiceId, (snapshot.questions.single() as Question.MultipleChoice).correctChoiceId)
    }

    @Test
    fun duplicatesGetNewIdsAndKeepTheCorrectChoice() {
        var next = 0
        val copy = QuestionDrafts.duplicate(complete) { "id-${next++}" }

        assertEquals("id-2", copy.id.value)
        assertEquals(listOf("id-0", "id-1"), copy.choices.map { it.id.value })
        assertEquals(ChoiceId("id-0"), copy.correctChoiceId)
        assertEquals(complete.choices.map { it.text }, copy.choices.map { it.text })
    }

    @Test
    fun pointsAndTimeLimitsHaveBounds() {
        val issues = StackValidation.questionIssues(complete.copy(points = 0, durationSeconds = 2), 1)

        assertEquals(
            listOf("Question 1 needs between 1 and 100 points.", "Question 1 needs a time limit between 5 and 3600 seconds."),
            issues,
        )
    }

    @Test
    fun duplicateAndBlankIdsAreReported() {
        val duplicateChoices = complete.copy(
            id = QuestionId("q2"),
            choices = listOf(Choice(ChoiceId("c1"), "Network"), Choice(ChoiceId("c1"), "Physical")),
        )
        val blankIds = complete.copy(
            id = QuestionId(" "),
            choices = listOf(Choice(ChoiceId(""), "Network"), Choice(ChoiceId("c2"), "Physical")),
            correctChoiceId = ChoiceId("c2"),
        )
        val stack = draft(complete, complete, duplicateChoices, blankIds)

        val messages = StackValidation.validate(stack).map { it.message }

        assertTrue("Question 2 has a duplicate ID." in messages)
        assertTrue("Question 3, choice 2 has a duplicate ID." in messages)
        assertTrue("Question 4 needs an ID." in messages)
        assertTrue("Question 4, choice 1 needs an ID." in messages)
        assertNull(StackValidation.toSnapshot(stack))
    }
}
