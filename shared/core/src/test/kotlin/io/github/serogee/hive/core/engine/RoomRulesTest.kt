package io.github.serogee.hive.core.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRulesTest {
    @Test
    fun displayNamesAreTrimmedAndCollapsed() {
        assertEquals("Alex Rivera", DisplayNames.normalize("  Alex   Rivera "))
        assertNull(DisplayNames.normalize("   "))
        assertNull(DisplayNames.normalize(null))
        assertNull(DisplayNames.normalize("x".repeat(DisplayNames.MAX_LENGTH + 1)))
    }

    @Test
    fun roomCodesUseTheUnambiguousAlphabet() {
        val code = RoomCodes.generate(Random(42))

        assertEquals(RoomCodes.LENGTH, code.length)
        assertTrue(code.all { it in RoomCodes.ALPHABET })
    }

    @Test
    fun typedCodesAreNormalized() {
        assertEquals("ABC234", RoomCodes.normalize(" abc-234 "))
    }
}
