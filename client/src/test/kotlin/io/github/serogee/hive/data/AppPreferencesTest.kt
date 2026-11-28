package io.github.serogee.hive.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.serogee.hive.audio.SoundPreferences
import io.github.serogee.hive.data.AppPreferences.Companion.MULTI_MUSIC
import io.github.serogee.hive.data.AppPreferences.Companion.MULTI_SFX
import io.github.serogee.hive.data.AppPreferences.Companion.SOLO_MUSIC
import io.github.serogee.hive.data.AppPreferences.Companion.SOLO_SFX
import io.github.serogee.hive.data.AppPreferences.Companion.onboardingCompleted
import io.github.serogee.hive.data.AppPreferences.Companion.soundPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `sound preferences default to enabled and track solo and multiplayer independently`() {
        assertEquals(SoundPreferences(), mutablePreferencesOf().soundPreferences())
        assertEquals(
            SoundPreferences(soloSfx = false, soloMusic = true, multiSfx = true, multiMusic = false),
            mutablePreferencesOf(
                SOLO_SFX to false,
                SOLO_MUSIC to true,
                MULTI_SFX to true,
                MULTI_MUSIC to false,
            ).soundPreferences(),
        )
    }
}
