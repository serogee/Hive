package io.github.serogee.hive.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.serogee.hive.data.AppPreferences.Companion.onboardingCompleted
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPreferencesTest {
    private val completed = booleanPreferencesKey("onboarding_completed")
    private val name = stringPreferencesKey("preferred_name")
    private val session = stringPreferencesKey("session")

    @Test
    fun `a fresh install shows Welcome`() {
        assertFalse(mutablePreferencesOf().onboardingCompleted())
    }

    @Test
    fun `finishing or skipping Welcome opens Home`() {
        assertTrue(mutablePreferencesOf(completed to true).onboardingCompleted())
    }

    @Test
    fun `people with a name or a session from an earlier version open Home`() {
        assertTrue(mutablePreferencesOf(name to "Ana").onboardingCompleted())
        assertTrue(mutablePreferencesOf(session to "{}").onboardingCompleted())
    }
}
