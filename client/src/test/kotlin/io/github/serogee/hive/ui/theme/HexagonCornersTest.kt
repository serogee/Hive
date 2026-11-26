package io.github.serogee.hive.ui.theme

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class HexagonCornersTest {
    private val square = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))

    @Test
    fun `each corner starts and ends the radius away from its vertex`() {
        val corner = roundedCorners(square, radius = 2f)[1]
        assertEquals(Offset(8f, 0f), corner.start)
        assertEquals(Offset(10f, 0f), corner.control)
        assertEquals(Offset(10f, 2f), corner.end)
    }

    @Test
    fun `a radius longer than half an edge stops halfway, so corners never cross`() {
        val corner = roundedCorners(square, radius = 8f)[1]
        assertEquals(Offset(5f, 0f), corner.start)
        assertEquals(Offset(10f, 5f), corner.end)
    }

    @Test
    fun `no radius keeps the sharp corners`() {
        roundedCorners(square, radius = 0f).zip(square).forEach { (corner, vertex) ->
            assertEquals(vertex, corner.start)
            assertEquals(vertex, corner.end)
        }
    }
}
