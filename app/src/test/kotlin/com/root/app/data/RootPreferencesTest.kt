package com.root.app.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootPreferencesTest {
    private val preferences =
        RootPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `onboarding version defaults to unseen`() {
        assertEquals(0, preferences.onboardingCompletedVersion)
    }

    @Test
    fun `recording onboarding version does not disturb language or theme`() {
        preferences.activeLanguageId = "shona"
        preferences.theme = "dark"

        preferences.onboardingCompletedVersion = RootPreferences.ONBOARDING_CURRENT_VERSION

        assertEquals(RootPreferences.ONBOARDING_CURRENT_VERSION, preferences.onboardingCompletedVersion)
        assertEquals("shona", preferences.activeLanguageId)
        assertEquals("dark", preferences.theme)
    }

    @Test
    fun `onboarding version rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            preferences.onboardingCompletedVersion = -1
        }
    }

    @Test
    fun `sound settings default to on at full volume`() {
        assertEquals(true, preferences.soundEffectsEnabled)
        assertEquals(true, preferences.startupSoundEnabled)
        assertEquals(RootPreferences.DEFAULT_SOUND_VOLUME, preferences.soundVolume, 0f)
        assertEquals(1f, RootPreferences.DEFAULT_SOUND_VOLUME, 0f)
    }

    @Test
    fun `sound settings persist without disturbing other preferences`() {
        preferences.activeLanguageId = "shona"
        preferences.theme = "dark"
        preferences.onboardingCompletedVersion = 1

        preferences.soundEffectsEnabled = false
        preferences.startupSoundEnabled = false
        preferences.soundVolume = 0f
        preferences.soundVolume = 0.4f

        val reopened = RootPreferences(ApplicationProvider.getApplicationContext())
        assertEquals(false, reopened.soundEffectsEnabled)
        assertEquals(false, reopened.startupSoundEnabled)
        assertEquals(0.4f, reopened.soundVolume, 0f)
        assertEquals("shona", reopened.activeLanguageId)
        assertEquals("dark", reopened.theme)
        assertEquals(1, reopened.onboardingCompletedVersion)
    }

    @Test
    fun `my languages start unset and persist`() {
        assertEquals(null, preferences.myLanguageIds)
        preferences.myLanguageIds = setOf("shona", "amharic")
        assertEquals(setOf("shona", "amharic"),
            RootPreferences(ApplicationProvider.getApplicationContext()).myLanguageIds)
    }

    @Test
    fun `sound volume rejects values outside zero to one`() {
        assertThrows(IllegalArgumentException::class.java) { preferences.soundVolume = -0.01f }
        assertThrows(IllegalArgumentException::class.java) { preferences.soundVolume = 1.01f }
    }
}
