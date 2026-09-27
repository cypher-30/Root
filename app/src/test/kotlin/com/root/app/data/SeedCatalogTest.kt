package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedCatalogTest {
    @Test fun everyLanguageHasFiveTopicsAndThirtyToFortyPhrases() {
        for (language in SeedCatalog.languages) {
            val packs = SeedCatalog.packsFor(language.key)
            assertTrue("${language.name} topics", packs.size >= 5)
            assertTrue("${language.name} has a free pack", packs.any { it.isFree })
            val count = SeedCatalog.phrasesFor(language.key).size
            assertTrue("${language.name} has $count phrases", count in 30..40)
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

    @Test fun everyStarterSetIsFree() {
        assertTrue(SeedCatalog.packs.filterNot { it.isFree }.map { it.id }.toString(), SeedCatalog.packs.all { it.isFree })
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
