package io.github.serogee.hive.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TabOriginTest {
    private val tabs = listOf("Home", "Stacks", "Sessions", "Account")

    private fun origin(vararg below: String) = originTab(tabs) { it in below.toSet() + "Home" }

    @Test
    fun `a stack opened from Home names Home`() {
        assertEquals("Home", origin())
    }

    @Test
    fun `a stack opened from another tab names that tab`() {
        assertEquals("Stacks", origin("Stacks"))
        assertEquals("Sessions", origin("Sessions"))
    }

    @Test
    fun `Home is the answer when the back stack has no tab at all`() {
        assertEquals("Home", originTab(tabs) { false })
    }
}
