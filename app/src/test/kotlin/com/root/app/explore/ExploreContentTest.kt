package com.root.app.explore

import com.root.app.data.SeedCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreContentTest {
    @Test fun everyLanguageHasTwoStoriesAndTwoNotes() {
        assertEquals(SeedCatalog.languages.map { it.key }.toSet(), ExploreContent.byKey.keys)
        ExploreContent.byKey.forEach { (key, content) ->
            assertEquals("$key stories", 2, content.stories.size)
            assertEquals("$key notes", 2, content.notes.size)
            content.notes.forEach {
                assertTrue("${it.id} url", it.url.startsWith("https://"))
                assertFalse("${it.id} body", it.body.isBlank())
            }
        }
    }

    @Test fun storiesOnlySpeakFreeStarterPhrasesOfTheirOwnLanguage() {
        ExploreContent.byKey.forEach { (key, content) ->
            val free = (
                (if (key == SeedCatalog.dholuo.key) SeedCatalog.legacyDholuoGreetings else emptyList()) +
                    SeedCatalog.packsFor(key).filter { it.isFree }.flatMap { it.phrases }
                ).map { it.id }.toSet()
            content.stories.forEach { story ->
                val spoken = story.lines.filter { it.phraseId != null }
                assertTrue("${story.id} speaks at least three phrases", spoken.size >= 3)
                spoken.forEach { line ->
                    assertTrue("${story.id}: ${line.phraseId} is a free $key phrase", line.phraseId in free)
                    assertNotNull(ExploreContent.phrase(line))
                    assertNotNull("${story.id} spoken line has a speaker", line.speaker)
                    assertNull(line.narration)
                }
                story.lines.filter { it.phraseId == null }.forEach { assertFalse(it.narration.isNullOrBlank()) }
            }
        }
    }

    @Test fun situationBlurbsDescribeEverySeededSet() {
        ExploreContent.byKey.forEach { (key, content) ->
            assertEquals("$key situations", SeedCatalog.packsFor(key).map { it.id }.toSet(), content.situations.keys)
        }
    }

    @Test fun lookupIsByDisplayName() {
        assertNotNull(ExploreContent.forLanguage("Swahili"))
        assertNotNull(ExploreContent.forLanguage(" dholuo "))
        assertNull(ExploreContent.forLanguage("Klingon"))
        assertNull(ExploreContent.forLanguage(null))
    }
}
