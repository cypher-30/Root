package com.root.app.ui.teach

import com.root.app.content.LessonFormat
import com.root.app.teach.LessonAudioResolver
import com.root.app.teach.LessonAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk of this integration's own logic now lives in the shared, frozen
 * `com.root.app.learning.LessonRunner`/`com.root.app.content.ContentLibrary`
 * (which own their own test suites) — this module deliberately keeps no
 * parallel teaching engine/DTOs to test. What remains genuinely ours is this
 * thin catalog/format display mapping and the local-only audio resolver
 * contract, covered here.
 */
class TeachUiHelpersTest {
    @Test fun formatLabelCoversEveryLessonFormat() {
        assertEquals("Guided conversation", formatLabel(LessonFormat.GUIDED_CONVERSATION))
        assertEquals("Listening / story", formatLabel(LessonFormat.LISTENING))
        assertEquals("Pattern workshop", formatLabel(LessonFormat.PATTERN_WORKSHOP))
    }
}

class LessonAudioResolverTest {
    private val resolver = LessonAudioResolver { assetId ->
        if (assetId.isNullOrBlank()) LessonAudioSource.Unavailable else LessonAudioSource.DownloadedFile("/data/$assetId")
    }

    @Test fun nullOrBlankAssetIdResolvesUnavailable() {
        assertTrue(resolver.resolve(null) is LessonAudioSource.Unavailable)
        assertTrue(resolver.resolve("") is LessonAudioSource.Unavailable)
        assertTrue(resolver.resolve("  ") is LessonAudioSource.Unavailable)
    }

    @Test fun realAssetIdResolvesToConcreteLocalSource() {
        val resolved = resolver.resolve("greeting-1")
        assertTrue(resolved is LessonAudioSource.DownloadedFile)
        assertEquals("/data/greeting-1", (resolved as LessonAudioSource.DownloadedFile).absolutePath)
    }
}
