package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.AnswerMatching
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.TypoTolerance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerMatcherTest {
    private fun matches(
        expected: String,
        given: String,
        ignoreCase: Boolean = true,
        ignoreSymbols: Boolean = false,
        typos: TypoTolerance = TypoTolerance.OFF,
    ) = AnswerMatcher.matches(expected, given, AnswerMatching(ignoreCase, ignoreSymbols, typos))

    @Test
    fun defaultsKeepTheEarlierRules() {
        assertTrue(matches("Café", " CAFE. "))
        assertFalse(matches("e-mail", "email"))
        assertFalse(matches("Photosynthesis", "Photosynthesi"))
    }

    @Test
    fun capitalLettersCanCount() {
        assertTrue(matches("DNS", "DNS", ignoreCase = false))
        assertFalse(matches("DNS", "dns", ignoreCase = false))
        assertTrue(matches("Café", "Cafe", ignoreCase = false))
    }

    @Test
    fun symbolsCanBeIgnored() {
        assertTrue(matches("e-mail", "email", ignoreSymbols = true))
        assertTrue(matches("TCP/IP", "tcp ip", ignoreSymbols = true))
        assertTrue(matches("TCP/IP", "tcpip", ignoreSymbols = true))
        assertTrue(matches("Newton's law", "newtons law!", ignoreSymbols = true))
        assertTrue(matches("ñandú", "ÑANDÚ?", ignoreSymbols = true))
        assertFalse(matches("e-mail", "gmail", ignoreSymbols = true))
    }

    @Test
    fun answersMadeOfSymbolsAreComparedAsWritten() {
        assertTrue(matches("&&", "&&", ignoreSymbols = true))
        assertFalse(matches("&&", "||", ignoreSymbols = true))
        assertFalse(matches("&&", "", ignoreSymbols = true, typos = TypoTolerance.HIGH))
    }

    @Test
    fun oneTypoIsAWrongMissingExtraOrSwappedLetter() {
        listOf("routor", "routr", "routter", "router", "rotuer").forEach {
            assertTrue(matches("router", it, typos = TypoTolerance.LOW), it)
        }
        assertFalse(matches("router", "rooterr", typos = TypoTolerance.LOW))
    }

    @Test
    fun longerAnswersAllowMoreTypos() {
        assertEquals(listOf(0, 1, 1, 1), listOf(4, 5, 8, 20).map(TypoTolerance.LOW::allowed))
        assertEquals(listOf(0, 1, 1, 2), listOf(3, 4, 7, 8).map(TypoTolerance.MEDIUM::allowed))
        assertEquals(listOf(0, 1, 2, 3), listOf(3, 4, 6, 10).map(TypoTolerance.HIGH::allowed))
        assertTrue(matches("photosynthesis", "fotosyntesis", typos = TypoTolerance.HIGH))
        assertFalse(matches("photosynthesis", "fotosyntesis", typos = TypoTolerance.MEDIUM))
        assertFalse(matches("DNS", "DHS", typos = TypoTolerance.HIGH))
    }

    @Test
    fun numbersMustAlwaysMatch() {
        assertFalse(matches("port 8080", "port 8081", typos = TypoTolerance.HIGH))
        assertFalse(matches("August 1945", "August 1946", typos = TypoTolerance.HIGH))
        assertTrue(matches("August 1945", "Agust 1945", typos = TypoTolerance.LOW))
    }

    @Test
    fun aWrongCapitalLetterIsNotATypo() {
        assertFalse(matches("Paris", "paris", ignoreCase = false, typos = TypoTolerance.HIGH))
        assertTrue(matches("Paris", "Pariss", ignoreCase = false, typos = TypoTolerance.LOW))
    }

    @Test
    fun optionsApplyToAlternatives() {
        val question = Question.Identification(
            id = QuestionId("q"),
            prompt = "?",
            answer = "DNS",
            acceptedAlternatives = listOf("Domain Name System"),
            matching = AnswerMatching(typos = TypoTolerance.MEDIUM),
        )

        assertTrue(Grading.isCorrect(question, Answer.Text("domian name systm")))
        assertFalse(Grading.isCorrect(question, Answer.Text("DHCP")))
    }

    @Test
    fun questionsSavedBeforeTheOptionsUseTheDefaults() {
        val json = """{"type":"identification","id":"q","prompt":"?","answer":"DNS"}"""

        val question = HiveJson.decodeFromString(Question.serializer(), json) as Question.Identification

        assertEquals(AnswerMatching(), question.matching)
    }
}
