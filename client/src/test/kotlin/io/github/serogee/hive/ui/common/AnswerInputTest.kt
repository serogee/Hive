package io.github.serogee.hive.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerInputTest {
    @Test
    fun `Confirm answer is the default`() {
        assertEquals(AnswerInput.CONFIRM, AnswerInput.of(null))
        assertEquals(AnswerInput.CONFIRM, AnswerInput.of("NO_LONGER_OFFERED"))
        AnswerInput.entries.forEach { assertEquals(it, AnswerInput.of(it.name)) }
    }

    @Test
    fun `a tap never locks in with Confirm answer`() {
        val taps = AnswerTaps(AnswerInput.CONFIRM)
        assertFalse(taps.locksIn("a", 0))
        assertFalse(taps.locksIn("a", 100))
    }

    @Test
    fun `the first tap locks in with Lock in on tap`() {
        assertTrue(AnswerTaps(AnswerInput.LOCK_ON_TAP).locksIn("a", 0))
    }

    @Test
    fun `only a quick second tap on the same choice locks in with both`() {
        val taps = AnswerTaps(AnswerInput.BOTH)
        assertFalse(taps.locksIn("a", 0))
        assertFalse(taps.locksIn("b", 100), "another choice")
        assertFalse(taps.locksIn("b", 2_000), "too slow")
        assertTrue(taps.locksIn("b", 2_200))
        assertFalse(taps.locksIn("b", 2_300), "a third tap starts over")
    }
}
