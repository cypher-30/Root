package com.root.app.content

import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

/** Reads the exact Python-authored pilot that debug packaging consumes, not a Kotlin lookalike. */
class EditorialContractTest {
    @Test fun sharedPilotUsesAllFormatsAndMissingAudioIsHonest() {
        val source = requireNotNull(javaClass.classLoader!!.getResourceAsStream("shona-pilot.json")) {
            "The editorial Shona pilot fixture is missing"
        }
        val bytes = source.use { it.readBytes() }
        val manifest = ContentJson.decodeFromString<PackManifest>(bytes.toString(Charsets.UTF_8))
        assertEquals(ValidationResult.Valid, ContentValidator.validate(manifest, bytes.size.toLong()))
        assertEquals(PublicationStatus.DEVELOPMENT, manifest.publication)
        assertEquals(setOf(LessonFormat.GUIDED_CONVERSATION, LessonFormat.LISTENING, LessonFormat.PATTERN_WORKSHOP),
            manifest.lessons.map { it.format }.toSet())
        val conversation = manifest.lessons.single { it.format == LessonFormat.GUIDED_CONVERSATION }
        assertTrue(conversation.activities.count { it is Activity.DialogueTurn } >= 6)
        val listening = manifest.lessons.single { it.format == LessonFormat.LISTENING }
            .activities.filterIsInstance<Activity.Listening>()
        assertTrue(listening.isNotEmpty())
        // No authentic recording has been supplied; do not fabricate even its metadata.
        assertTrue(manifest.assets.isEmpty())
        assertTrue(listening.all { it.audioAssetId == null })
    }

    @Test fun everyShowcaseUnitIsValidAndHonestAboutAudio() {
        val ids = listOf(
            "pack.shona.meeting", "pack.swahili.meeting", "pack.swahili.market", "pack.dholuo.family",
            "pack.dholuo.market", "pack.amharic.meeting", "pack.amharic.cafe",
        )
        ids.forEach { id ->
            val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("showcase/$id.json")) {
                "Showcase unit $id is missing"
            }.use { it.readBytes() }
            val manifest = ContentJson.decodeFromString<PackManifest>(bytes.toString(Charsets.UTF_8))
            assertEquals(id, manifest.id)
            assertEquals("$id validates", ValidationResult.Valid, ContentValidator.validate(manifest, bytes.size.toLong()))
            assertEquals(
                listOf(LessonFormat.GUIDED_CONVERSATION, LessonFormat.PATTERN_WORKSHOP, LessonFormat.LISTENING),
                manifest.lessons.map { it.format },
            )
            assertTrue(manifest.assets.isEmpty())
            manifest.lessons.forEach { lesson ->
                lesson.activities.filterIsInstance<Activity.Listening>().forEach { listening ->
                    assertNull(listening.audioAssetId)
                    assertFalse("${listening.id} explains missing audio", listening.unavailableReason.isNullOrBlank())
                    assertFalse("${listening.id} is optional", listening.id in lesson.requiredActivityIds)
                }
            }
            val text = bytes.toString(Charsets.UTF_8).lowercase()
            listOf("prototype", "placeholder", "lorem", "development only").forEach {
                assertFalse("$id mentions $it", text.contains(it))
            }
        }
    }
}
