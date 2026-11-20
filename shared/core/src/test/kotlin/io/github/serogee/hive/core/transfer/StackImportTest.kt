package io.github.serogee.hive.core.transfer

import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StackImportTest {
    private var next = 0
    private val newId = { "gen-${next++}" }

    @Test
    fun exportedFilesImportWithEveryField() {
        val sample = SampleStacks.networkingFundamentals

        val result = HiveStackFile.decode(HiveStackFile.encode(sample), newId)
        val snapshot = assertNotNull(StackValidation.toSnapshot(assertNotNull(result.draft)))

        assertEquals(emptyList(), result.errors)
        assertEquals(sample.questions, snapshot.questions)
        assertEquals(sample.title, snapshot.title)
    }

    @Test
    fun theAiPromptExampleIsAValidFile() {
        val json = AiFormattingPrompt.text.substringAfter("Use this structure:").substringBefore("Rules:")

        val result = HiveStackFile.decode(json, newId)

        assertEquals(emptyList(), result.errors)
        assertEquals(3, result.questionCount)
    }

    @Test
    fun jsonProblemsNameTheQuestion() {
        val json = """
            {"title": "T", "questions": [
              {"type": "true_false", "prompt": "ok", "answer": true},
              {"type": "essay", "prompt": "?"},
              {"type": "multiple_choice", "prompt": "Pick", "choices": [{"text": "A", "correct": true}, {"text": "B", "correct": true}]}
            ]}
        """.trimIndent()

        val errors = HiveStackFile.decode(json, newId).errors

        assertTrue(errors.any { it.startsWith("Question 2 has the unknown type 'essay'") })
        assertTrue("Question 3 marks more than one correct choice." in errors)
        assertEquals(listOf("The file is not valid JSON."), HiveStackFile.decode("{nope", newId).errors)
    }

    @Test
    fun validationProblemsUseQuestionNumbers() {
        val json = """{"title": "T", "questions": [{"type": "identification", "prompt": "Name it"}]}"""

        assertEquals(listOf("Question 1 needs a correct answer."), HiveStackFile.decode(json, newId).errors)
    }

    @Test
    fun csvRowsBecomeQuestions() {
        val csv = """
            type,prompt,choices,answer,alternatives,explanation,points,seconds
            multiple_choice,"Which layer routes packets, mostly?",Physical|Network|Transport,Network,,,2,30
            true_false,UDP retransmits lost datagrams.,,false,,"UDP has no ""retries"".",,
            identification,Which protocol resolves names?,,DNS,Domain Name System|DNS server,,3,
        """.trimIndent()

        val result = CsvStackImport.decode(csv, "Networking", newId)
        val questions = assertNotNull(result.draft).questions

        assertEquals(emptyList(), result.errors)
        assertEquals(listOf(QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE, QuestionType.IDENTIFICATION), questions.map { it.type })
        assertEquals("Which layer routes packets, mostly?", questions[0].prompt)
        assertEquals("Network", questions[0].choices.first { it.id == questions[0].correctChoiceId }.text)
        assertEquals("UDP has no \"retries\".", questions[1].explanation)
        assertEquals(listOf("Domain Name System", "DNS server"), questions[2].acceptedAlternatives)
        assertEquals(30, questions[0].durationSeconds)
    }

    @Test
    fun csvProblemsNameTheRow() {
        val csv = "type,prompt,choices,answer\nmultiple_choice,Pick,A|B,C\ntrue_false,Is it?,,maybe\n"

        val errors = CsvStackImport.decode(csv, "T", newId).errors

        assertEquals(listOf("Row 2: the answer 'C' is not one of its choices.", "Row 3 needs an answer of true or false."), errors)
        assertEquals(listOf("Row 1 needs the columns answer."), CsvStackImport.decode("type,prompt\n", "T", newId).errors)
    }

    @Test
    fun fiftyValidQuestionsImport() {
        val rows = (1..50).joinToString("\n") { "identification,Question $it?,,Answer $it,,,," }
        val result = CsvStackImport.decode("type,prompt,choices,answer,alternatives,explanation,points,seconds\n$rows", "Fifty", newId)

        assertEquals(emptyList(), result.errors)
        assertEquals(50, result.questionCount)
        assertEquals((1..50).map { "Answer $it" }, result.draft!!.questions.map { it.identificationAnswer })
    }
}
