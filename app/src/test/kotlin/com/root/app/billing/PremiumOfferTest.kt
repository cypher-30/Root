package com.root.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumOfferTest {
    @Test fun describesRealSetsNotAccessOrFrequency() {
        val rows = PremiumOffer.included("Swahili")
        assertEquals(listOf("Swahili premium sets", "Practise them like any set", "New sets included",
            "Pay once, for one language or all"), rows.map { it.first })
        assertEquals("Heart words (8 phrases) and Keep talking (8 phrases).", rows.first().second)
        val all = rows.joinToString(" ") { it.first + " " + it.second }.lowercase()
        listOf("unlimited", "streak", "hearts", "ad-free", "daily limit").forEach { assertFalse(it, it in all) }
        assertTrue("not a subscription" in all)
    }

    @Test fun withoutALanguageEveryLanguagesSetsAreNamed() {
        val row = PremiumOffer.included(null).first()
        assertEquals("Premium sets in every language", row.first)
        listOf("Dholuo: Body & health and Days of the week.", "Shona: Heart words and Keep talking.",
            "Amharic: Heart words and Keep talking.").forEach { assertTrue(it, it in row.second) }
        assertEquals(8, PremiumOffer.premiumSets().size)
    }

    @Test fun aLanguageWithoutPremiumSetsSaysSo() {
        assertEquals("Kikuyu has no premium sets yet.", PremiumOffer.included("Kikuyu").first().second)
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
        listOf("planned", "not a subscription", "exact amount").forEach { assertTrue(it, it in note) }
        assertFalse("kenya" in note)
    }

    @Test fun redeemCodeIgnoresCaseSpacesAndDashes() {
        assertTrue(RedeemCode.isValid("SHIPATON2026"))
        assertTrue(RedeemCode.isValid(" shipaton-2026 "))
        assertFalse(RedeemCode.isValid("SHIPATON2025"))
        assertFalse(RedeemCode.isValid(""))
    }
}
