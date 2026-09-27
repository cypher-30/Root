package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumAccessTest {
    @Test
    fun languageKeysIgnoreCaseAccentsAndSpacing() {
        assertEquals("dholuo", PremiumAccess.languageKey("Dholuo"))
        assertEquals("dholuo", PremiumAccess.languageKey("  DHOLUO "))
        assertEquals("kiswahili", PremiumAccess.languageKey("Kiswahíli"))
        assertEquals("chi_shona", PremiumAccess.languageKey("Chi-Shona"))
        assertEquals("premium_amharic", PremiumAccess.entitlementFor("Amharic"))
    }

    @Test
    fun theBundleCoversEveryLanguage() {
        val access = PremiumAccess.fromEntitlements(listOf("premium"))
        assertTrue(access.allLanguages)
        assertTrue(access.covers("Shona"))
        assertTrue(access.covers(null as String?))
        assertTrue(access.ownsAnything)
    }

    @Test
    fun aSingleLanguageCoversOnlyItself() {
        val access = PremiumAccess.fromEntitlements(listOf("premium_dholuo", "premium_", "other"))
        assertFalse(access.allLanguages)
        assertEquals(setOf("dholuo"), access.languageKeys)
        assertTrue(access.covers("Dholuo"))
        assertFalse(access.covers("Swahili"))
        assertFalse(access.covers(null as String?))
        assertTrue(access.ownsAnything)
    }

    @Test
    fun nothingOwnedCoversNothing() {
        assertFalse(PremiumAccess.NONE.covers("Dholuo"))
        assertFalse(PremiumAccess.NONE.ownsAnything)
        assertFalse(PremiumAccess.isRootEntitlement("premium_"))
        assertFalse(PremiumAccess.isRootEntitlement("pro"))
        assertTrue(PremiumAccess.isRootEntitlement("premium"))
        assertTrue(PremiumAccess.isRootEntitlement("premium_shona"))
    }
}
