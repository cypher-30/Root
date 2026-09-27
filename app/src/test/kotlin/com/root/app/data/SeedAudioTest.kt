package com.root.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedAudioTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test fun everyClipMatchesAStarterPhraseExactly() {
        val phrases = SeedCatalog.languages.flatMap { SeedCatalog.phrasesFor(it.key) }.associateBy { it.id }
        assertEquals(SeedAudio.clips.size, SeedAudio.clips.map { it.phraseId }.toSet().size)
        for (clip in SeedAudio.clips) {
            val phrase = phrases[clip.phraseId]
            assertTrue("${clip.phraseId} is a starter phrase", phrase != null)
            assertEquals(clip.phraseId, phrase!!.answer, clip.answer)
        }
    }

    @Test fun onlySwahiliAndAmharicHaveRecordings() {
        assertTrue(SeedAudio.clips.all { it.phraseId.startsWith("phrase-swahili-") || it.phraseId.startsWith("phrase-amharic-") })
    }

    @Test fun everyClipIsBundledAndCredited() {
        val sources = File(assets, "content_sources.txt").readText()
        for (clip in SeedAudio.clips) {
            val file = File(assets, clip.assetPath)
            assertTrue("${clip.assetPath} is bundled", file.isFile && file.length() > 1_000)
            assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4)) })
            assertEquals("CC BY-SA 4.0", clip.license)
            assertTrue("${clip.phraseId} source listed", sources.contains(clip.sourceUrl.removePrefix("https://commons.wikimedia.org/wiki/")))
            assertTrue(SeedAudio.creditFor(clip.assetPath)!!.contains(clip.speaker))
        }
        val bundled = File(assets, "audio/seed").listFiles().orEmpty().map { "audio/seed/${it.name}" }.toSet()
        assertEquals("no uncredited clips", SeedAudio.clips.map { it.assetPath }.toSet(), bundled)
    }

    @Test fun learnerAudioHasNoBundledCredit() {
        assertNull(SeedAudio.creditFor(null))
        assertNull(SeedAudio.creditFor("/data/user/0/com.root.app/files/recordings/x.m4a"))
    }
}
