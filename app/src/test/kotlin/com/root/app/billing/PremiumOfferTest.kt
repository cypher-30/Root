package com.root.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumOfferTest {
    @Test fun describesDeeperNativeContentNotAccessOrFrequency() {
        val titles = PremiumOffer.included.map { it.first }
        assertEquals(listOf("Native voices", "Deeper sets", "Proverbs and longer stories", "Full Learn courses",
            "Pay once, for one language or all"), titles)
        val all = PremiumOffer.included.joinToString(" ") { it.first + " " + it.second }.lowercase()
        listOf("unlimited", "streak", "hearts", "ad-free", "daily limit").forEach { assertFalse(it, it in all) }
        assertTrue("not a subscription" in all)
    }

    @Test fun deeperSetsListsEveryPlannedTopic() {
        val detail = PremiumOffer.included.first { it.first == "Deeper sets" }.second
        assertEquals("Talking with elders, family and home, food and cooking, ceremonies, health, travel, and work.", detail)
        PremiumOffer.plannedTopics.forEach { assertTrue(it, it.lowercase() in detail.lowercase()) }
    }

    @Test fun starterSetsAndPracticeStayFree() {
        listOf("starter set", "practice", "notebook", "your own words").forEach {
            assertTrue(it, it in PremiumOffer.alwaysFree.lowercase())
        }
    }

    @Test fun plansAreNamedForWhatTheyUnlock() {
        assertEquals("Dholuo only", PremiumOffer.planTitle(PremiumPlan.Language("dholuo"), "Dholuo"))
        assertEquals("Shona only", PremiumOffer.planTitle(PremiumPlan.Language("shona"), null))
        assertEquals("All languages", PremiumOffer.planTitle(PremiumPlan.AllLanguages, "Dholuo"))
        assertTrue("Dholuo" in PremiumOffer.planDetail(PremiumPlan.Language("dholuo"), "Dholuo"))
    }

    @Test fun plannedPriceMatchesTheDocumentedPriceAndSaysItIsPlanned() {
        val doc = listOf(java.io.File("../docs/PREMIUM.md"), java.io.File("docs/PREMIUM.md")).first { it.isFile }.readText()
        assertTrue(doc.contains("**${PremiumOffer.plannedLanguagePrice} for one language, or ${PremiumOffer.plannedAllLanguagesPrice} for all of them**"))
        val note = PremiumOffer.plannedPriceNote.lowercase()
        listOf("planned", "not a subscription", "nothing is for sale", "exact amount").forEach { assertTrue(it, it in note) }
    }
}
