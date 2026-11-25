package io.github.serogee.hive.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AnswerColorsTest {
    @Test
    fun `App honey is the default`() {
        assertEquals(AnswerColorChoice.APP_HONEY, AnswerColorChoice.of(null))
        assertEquals(AnswerColorChoice.APP_HONEY, AnswerColorChoice.of("NO_LONGER_OFFERED"))
    }

    @Test
    fun `a saved choice is kept`() {
        AnswerColorChoice.entries.forEach { assertEquals(it, AnswerColorChoice.of(it.name)) }
    }

    @Test
    fun `answers are readable with every choice`() {
        AnswerColorChoice.entries.forEach { choice ->
            listOf("right" to choice.colors.right, "wrong" to choice.colors.wrong).forEach { (name, tone) ->
                assertContrast("$choice $name text", tone.text, tone.background)
                assertContrast("$choice $name icon", tone.onChip, tone.chip, ICON_CONTRAST)
            }
            assertNotEquals(choice.colors.right, choice.colors.wrong)
        }
    }
}
