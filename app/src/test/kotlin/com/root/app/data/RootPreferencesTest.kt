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
}
