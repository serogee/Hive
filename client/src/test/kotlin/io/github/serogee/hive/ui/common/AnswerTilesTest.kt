package io.github.serogee.hive.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class AnswerTilesTest {
    @Test
    fun `a choice shows only the selection until the answer is known`() {
        assertEquals(AnswerTileState.SELECTED, answerTileState(selected = true, correct = null))
        assertEquals(AnswerTileState.IDLE, answerTileState(selected = false, correct = null))
    }

    @Test
    fun `the correct choice is right, even when the Player did not select it`() {
        assertEquals(AnswerTileState.RIGHT, answerTileState(selected = true, correct = true))
        assertEquals(AnswerTileState.RIGHT, answerTileState(selected = false, correct = true))
    }

    @Test
    fun `only the selected choice is wrong`() {
        assertEquals(AnswerTileState.WRONG, answerTileState(selected = true, correct = false))
        assertEquals(AnswerTileState.IDLE, answerTileState(selected = false, correct = false))
    }
}
