package com.root.app.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Instrumented, real-filesystem fault-injection coverage for
 * [RootRepository.deletePersonalPhrase]'s recovery ledger: a delete that the
 * OS refuses (simulated here by making the containing directory read-only)
 * must be recorded as [MediaFileStatus.PENDING_CLEANUP] rather than silently
 * reported as done, and [RootRepository.retryPendingMediaCleanup] must be
 * able to finish the job once the file becomes removable again. Runs against
 * the real [AppDatabase] singleton/[RootRepository] (not an in-memory
 * database) because the behavior under test is specifically about real
 * on-disk delete failures, and uses a language name unique to this test so it
 * cannot collide with fixtures used elsewhere in the same instrumentation run.
 */
class RootRepositoryMediaCleanupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = RootRepository(context)
    private val languageName = "Media Cleanup Fault Test"

    @Test fun failedFileDeleteIsRecordedAndLaterResolvedByRetry() = runBlocking {
        val recordingsDir = File(context.filesDir, "media-cleanup-fault-test").apply { mkdirs() }
        val recording = File(recordingsDir, "take.3gp").apply { writeText("not real audio, just needs to exist") }

        val phrase = repository.contribute(
            languageName = languageName,
            prompt = "prompt",
            answer = "answer",
            audioPath = recording.path,
            speakerLabel = "Tester",
            consentConfirmed = true,
        )

        // Simulate a delete the OS refuses (e.g. another process/holder, a
        // permissions quirk) by making the containing directory read-only.
        assertTrue(recordingsDir.setWritable(false))
        try {
            repository.deletePersonalPhrase(languageId = languageIdFor(phrase), phraseId = phrase.id)

            // The phrase row and its consent are still gone (row delete is
            // never blocked by a file-delete failure)...
            assertNull(repository.db.phraseDao().getById(phrase.id))
            assertNull(repository.db.consentDao().getForPhrase(phrase.id))
            // ...but the leftover file is durably tracked as pending cleanup,
            // not silently reported as erased.
            assertTrue(recording.exists())
            val pending = repository.db.mediaFileFactDao()
                .getFor(MediaFileSubject.PHRASE_REFERENCE_AUDIO, phrase.id)
            assertEquals(1, pending.size)
            assertEquals(MediaFileStatus.PENDING_CLEANUP, pending.single().status)
        } finally {
            // Restore writability so the retry below (and cleanup) can succeed.
            recordingsDir.setWritable(true)
        }

        val resolved = repository.retryPendingMediaCleanup()
        assertTrue(resolved >= 1)
        assertFalse(recording.exists())
        assertTrue(
            repository.db.mediaFileFactDao().getFor(MediaFileSubject.PHRASE_REFERENCE_AUDIO, phrase.id).isEmpty(),
        )

        recordingsDir.deleteRecursively()
        Unit
    }

    private suspend fun languageIdFor(phrase: PhraseEntity): String =
        requireNotNull(repository.db.packDao().getById(phrase.packId)).languageId
}
