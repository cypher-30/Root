package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedCatalogTest {
    @Test fun everyLanguageHasFiveFreeTopicsAndTwoPremiumSets() {
        for (language in SeedCatalog.languages) {
            val packs = SeedCatalog.packsFor(language.key)
            val free = packs.filter { it.isFree }
            assertTrue("${language.name} free topics", free.size >= 5)
            val freeCount = free.sumOf { it.phrases.size } + if (language == SeedCatalog.dholuo) SeedCatalog.legacyDholuoGreetings.size else 0
            assertTrue("${language.name} has $freeCount free phrases", freeCount in 30..40)
            val premium = packs.filterNot { it.isFree }
            assertEquals("${language.name} premium sets", 2, premium.size)
            premium.forEach { assertTrue("${it.id} has at least six phrases", it.phrases.size >= 6) }
            // Premium sets come after every free set in Explore.
            assertTrue(premium.minOf { it.sortOrder } > free.maxOf { it.sortOrder })
            assertTrue("${language.name} packs all have content", packs.all { it.phrases.isNotEmpty() })
        }
    }

    @Test fun idsAreUniqueAndTextIsFilledIn() {
        val phrases = SeedCatalog.languages.flatMap { SeedCatalog.phrasesFor(it.key) }
        assertEquals(phrases.size, phrases.map { it.id }.toSet().size)
        assertEquals(SeedCatalog.packs.size, SeedCatalog.packIds.size)
        assertTrue(phrases.all { it.prompt.isNotBlank() && it.answer.isNotBlank() })
        assertTrue(SeedCatalog.packs.all { it.source.url.startsWith("https://") })
    }

    @Test fun theOriginalStarterSetsStayFree() {
        val starters = listOf("greetings", "family", "market", "numbers", "food", "directions", "people")
        SeedCatalog.packs.filter { pack -> starters.any { pack.id.endsWith("-$it") } }
            .forEach { assertTrue(it.id, it.isFree) }
        assertTrue(SeedCatalog.packs.first { it.id == ReferralPrefs.REWARD_PACK_ID }.isFree)
    }

    @Test fun premiumSetsAreTheNewOnes() {
        assertEquals(
            setOf("pack-dholuo-body", "pack-dholuo-days", "pack-shona-heart", "pack-shona-talking",
                "pack-swahili-heart", "pack-swahili-talking", "pack-amharic-heart", "pack-amharic-talking"),
            SeedCatalog.packs.filterNot { it.isFree }.map { it.id }.toSet(),
        )
    }

    @Test fun existingIdsAndRewardPackAreKept() {
        val ids = SeedCatalog.packIds
        listOf("pack-dholuo-greetings", "pack-dholuo-family", "pack-dholuo-numbers", "pack-dholuo-food",
            "pack-dholuo-directions", "pack-shona-greetings", "pack-swahili-greetings",
            "pack-amharic-greetings", "pack-amharic-directions", ReferralPrefs.REWARD_PACK_ID)
            .forEach { assertTrue(it, it in ids) }
        assertEquals("Mhoro", SeedCatalog.packsFor("shona").first().phrases.first().answer)
        assertEquals("phrase-shona-greetings-01", SeedCatalog.packsFor("shona").first().phrases.first().id)
    }
}
