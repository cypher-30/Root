package com.root.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies [ContentAccess.canAccess]'s access matrix: free vs. paid packs, the
 *  reward-unlocked Market pack, paid languages, and personal (user-contributed)
 *  packs, which must stay free even inside an otherwise-paid language. */
class ContentAccessTest {
    private val language = LanguageEntity(id = "lang-dholuo", name = "Dholuo", isPremium = false, updatedAt = 0)
    private val pack = PackEntity(
        id = "pack-dholuo-greetings",
        languageId = language.id,
        theme = "Greetings",
        sortOrder = 0,
        isFree = true,
        updatedAt = 0,
    )

    @Test
    fun freeCuratedPackIsAccessibleWithoutPurchase() {
        assertTrue(ContentAccess.canAccess(language, pack, premium = false, rewardUnlocked = false))
    }

    @Test
    fun paidPackIsLockedWithoutPurchase() {
        assertFalse(ContentAccess.canAccess(language, pack.copy(isFree = false), false, false))
        assertTrue(ContentAccess.canAccess(language, pack.copy(isFree = false), true, false))
    }

    @Test
    fun rewardUnlocksOnlyTheMarketPack() {
        val market = pack.copy(id = ReferralPrefs.REWARD_PACK_ID, isFree = false)
        assertFalse(ContentAccess.canAccess(language, market, false, false))
        assertTrue(ContentAccess.canAccess(language, market, false, true))
        assertFalse(ContentAccess.canAccess(language, pack.copy(id = "pack-dholuo-food", isFree = false), false, true))
    }

    @Test
    fun paidLanguageCannotBeBypassedByFreePackFlagOrReward() {
        val paidLanguage = language.copy(isPremium = true)
        assertFalse(ContentAccess.canAccess(paidLanguage, pack, false, false))
        assertFalse(ContentAccess.canAccess(paidLanguage, pack.copy(id = ReferralPrefs.REWARD_PACK_ID), false, true))
        assertTrue(ContentAccess.canAccess(paidLanguage, pack, true, false))
    }

    @Test
    fun personalPackStaysFreeInAPaidLanguage() {
        val personal = pack.copy(id = ContentAccess.userPackId(language.id))
        assertTrue(ContentAccess.canAccess(language.copy(isPremium = true), personal, false, false))
    }

    @Test
    fun wrongLanguageCannotAccessEvenWithPurchase() {
        assertFalse(ContentAccess.canAccess(language.copy(id = "other-language"), pack, true, true))
    }

    @Test
    fun personalPackNameDoesNotUnlockCuratedContent() {
        assertFalse(ContentAccess.canAccess(language, pack.copy(theme = "Your words", isFree = false), false, false))
    }
}
