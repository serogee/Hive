package io.github.serogee.hive.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TabOriginTest {
    private val tabs = listOf("Home", "My Stacks", "Quiz Room", "Accounts")

    private fun origin(vararg below: String) = originTab(tabs) { it in below.toSet() + "Home" }

    @Test
    fun `a stack opened from Home names Home`() {
        assertEquals("Home", origin())
    }

    @Test
    fun `a stack opened from another tab names that tab`() {
        assertEquals("My Stacks", origin("My Stacks"))
        assertEquals("Quiz Room", origin("Quiz Room"))
    }

    @Test
    fun `Home is the answer when the back stack has no tab at all`() {
        assertEquals("Home", originTab(tabs) { false })
    }
}
