package com.root.app.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented, real-database coverage for the `archive` package's private
 * notes ([RootRepository.noteFor]/[setNote]) and durable contribution-draft
 * lifecycle ([RootRepository.createDraft]/[saveDraftText]/[discardDraft]/
 * [contribute] with a `draftId`). Runs against the real [AppDatabase]
 * singleton (not in-memory) because the durability claim under test is
 * specifically "survives process recreation, not just a Compose recomposition" —
 * closing and reopening the repository here stands in for that recreation.
 * Uses a language name unique to this test to avoid colliding with other
 * fixtures sharing the same instrumentation run.
 */
class RootRepositoryArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = RootRepository(context)
    private val languageName = "Archive Draft Test"

    @Test fun noteIsSetReadAndClearedWithoutTouchingPhraseText() = runBlocking {
        val phrase = repository.contribute(languageName, "prompt", "answer", audioPath = null)

        assertNull(repository.noteFor(phrase.id))

        repository.setNote(phrase.id, "  Grandma, recorded at Sunday dinner  ")
        assertEquals("Grandma, recorded at Sunday dinner", repository.noteFor(phrase.id)?.noteText)

        // Deleting the person's label (the note) must not touch their words.
        repository.setNote(phrase.id, "   ")
        assertNull(repository.noteFor(phrase.id))
        val stillThere = requireNotNull(repository.db.phraseDao().getById(phrase.id))
        assertEquals("prompt", stillThere.prompt)
        assertEquals("answer", stillThere.answer)
    }

    @Test fun draftSurvivesProcessRecreationAndCommitsExactlyOnce() = runBlocking {
        val languageId = repository.languages().firstOrNull { it.name == languageName }?.id
            ?: repository.contribute(languageName, "seed", "seed", audioPath = null).let {
                requireNotNull(repository.db.packDao().getById(it.packId)).languageId
            }

        val draft = repository.createDraft(languageId)
        repository.saveDraftText(draft.id, "how are you", "kase ang'o", "Uncle")

        // A brand-new RootRepository instance (new Room connection, same
        // underlying file) stands in for the process being recreated after
        // the draft was saved but before it was committed.
        val recreated = RootRepository(context)
        val resumed = requireNotNull(recreated.draft(draft.id))
        assertEquals("how are you", resumed.promptDraft)
        assertEquals("kase ang'o", resumed.answerDraft)
        assertEquals("Uncle", resumed.speakerLabelDraft)
        assertEquals(ContributionAudioState.NONE, resumed.audioState)
        assertTrue(recreated.openDrafts().any { it.id == draft.id })

        val phrase = recreated.contribute(
            languageName = languageName,
            prompt = resumed.promptDraft,
            answer = resumed.answerDraft,
            audioPath = null,
            draftId = draft.id,
        )

        val committed = requireNotNull(recreated.draft(draft.id))
        assertEquals(ContributionAudioState.COMMITTED, committed.audioState)
        assertEquals(phrase.id, committed.committedPhraseId)
        assertTrue(recreated.openDrafts().none { it.id == draft.id })

        // A committed draft is terminal: it cannot be silently reused for a
        // second phrase, and it cannot be discarded after the fact.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                recreated.contribute(languageName, "again", "again", audioPath = null, draftId = draft.id)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { recreated.discardDraft(draft.id) }
        }
        Unit
    }

    @Test fun discardedDraftIsNoLongerOpenAndRejectsFurtherEdits() = runBlocking {
        val languageId = repository.languages().firstOrNull { it.name == languageName }?.id
            ?: repository.contribute(languageName, "seed2", "seed2", audioPath = null).let {
                requireNotNull(repository.db.packDao().getById(it.packId)).languageId
            }

        val draft = repository.createDraft(languageId)
        repository.saveDraftText(draft.id, "typed text", "typed answer", null)
        repository.discardDraft(draft.id)

        assertEquals(ContributionAudioState.DISCARDED, requireNotNull(repository.draft(draft.id)).audioState)
        assertTrue(repository.openDrafts().none { it.id == draft.id })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.saveDraftText(draft.id, "x", "y", null) }
        }
        Unit
    }

    @Test fun unknownDraftIdIsRejectedRatherThanSilentlyIgnored() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.saveDraftText("does-not-exist", "x", "y", null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.contribute(languageName, "p", "a", audioPath = null, draftId = "does-not-exist")
            }
        }
        Unit
    }
}
