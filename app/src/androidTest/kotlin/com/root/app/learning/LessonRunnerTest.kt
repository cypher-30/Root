package com.root.app.learning

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.content.Activity
import com.root.app.content.Choice
import com.root.app.content.ChoiceTask
import com.root.app.content.ContentJson
import com.root.app.content.ContentLanguage
import com.root.app.content.Lesson
import com.root.app.content.LessonFormat
import com.root.app.content.OrderedTokenTask
import com.root.app.content.PackManifest
import com.root.app.content.PublicationStatus
import com.root.app.content.TokenOccurrence
import com.root.app.data.AppDatabase
import com.root.app.data.PackVersionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumented tests for the durable [LessonRunner] against a real Room
 * database: idempotent commands, pinned revisions, assistance persisted
 * before reveal, retries not counted as independent evidence, pause/restart,
 * and that no automatic [com.root.app.data.AttemptEntity] is ever created.
 */
class LessonRunnerTest {
    private lateinit var db: AppDatabase
    private lateinit var runner: LessonRunner
    private val packId = "pack-shona-greetings"
    private val lessonId = "lesson-1"

    private fun manifest(revisionSuffix: Int = 1) = PackManifest(
        schemaVersion = 1, minReaderVersion = 1, id = packId, version = 1,
        language = ContentLanguage(id = "lang-sn", code = "sn", name = "Shona"),
        title = "Greetings", objective = "Greet and reply",
        publication = PublicationStatus.DEVELOPMENT,
        phrases = emptyList(),
        assets = emptyList(),
        lessons = listOf(
            Lesson(
                id = lessonId, revision = revisionSuffix, title = "Greeting exchange$revisionSuffix",
                objective = "Greet and reply", format = LessonFormat.GUIDED_CONVERSATION,
                activities = listOf(
                    Activity.DialogueTurn(id = "act-1", speaker = "A", text = "Mhoro"),
                    Activity.ChoiceActivity(
                        id = "act-2",
                        task = ChoiceTask(
                            id = "task-2", prompt = "Reply?",
                            choices = listOf(Choice("c1", "Ndiripo"), Choice("c2", "Kwaziwai")),
                            acceptedChoiceIds = listOf("c1"),
                        ),
                    ),
                    Activity.OrderedTokenActivity(
                        id = "act-3",
                        task = OrderedTokenTask(
                            id = "task-3", prompt = "Order them",
                            tokens = listOf(TokenOccurrence("t1", "Mangwanani"), TokenOccurrence("t2", "akanaka")),
                            acceptedSequences = listOf(listOf("t1", "t2")),
                        ),
                    ),
                ),
                requiredActivityIds = listOf("act-1", "act-2", "act-3"),
            ),
        ),
    )

    private suspend fun installVersion(version: Int, m: PackManifest) {
        db.contentDao().insertPackVersion(
            PackVersionEntity(
                packId = packId, version = version, schemaVersion = 1, minReaderVersion = 1,
                languageId = "lang-1", languageCode = "sn", languageName = "Shona", title = m.title,
                publication = "development", manifestJson = ContentJson.encodeToString(m),
                manifestSha256 = "a".repeat(64), phraseCount = 0, lessonCount = 1, assetCount = 0,
            ),
        )
    }

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        runner = LessonRunner(db)
        runBlocking { installVersion(1, manifest(1)) }
    }

    @After fun tearDown() { db.close() }

    @Test fun beginOrResumeThenFullSequenceCompletesWithoutAutomaticAttempt() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        assertEquals(0, begin.state.currentActivityIndex)

        runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        var advanced = runner.execute(LearningCommand.Advance("cmd-adv-1", runId, "act-1")) as CommandResult.Applied
        assertEquals("act-2", advanced.state.currentActivityId)

        val choiceResult = runner.execute(LearningCommand.SubmitResponse("cmd-2", runId, "act-2", ActivityResponse.Choice("c1")))
        assertTrue(choiceResult is CommandResult.Applied)
        advanced = runner.execute(LearningCommand.Advance("cmd-adv-2", runId, "act-2")) as CommandResult.Applied
        assertEquals("act-3", advanced.state.currentActivityId)

        runner.execute(LearningCommand.SubmitResponse("cmd-3", runId, "act-3", ActivityResponse.OrderedTokens(listOf("t1", "t2"))))
        val completed = runner.execute(LearningCommand.Advance("cmd-adv-3", runId, "act-3")) as CommandResult.Applied
        assertTrue(completed.state.completed)
        assertEquals(3, completed.state.requiredCompletedCount)

        // Teaching evidence never manufactures a recall attempt.
        assertEquals(0, db.attemptDao().capabilityCount("lang-1"))
    }

    @Test fun duplicateCommandIdSamePayloadIsAlreadyApplied() = runBlocking {
        val first = runner.execute(LearningCommand.BeginOrResume("cmd-x", packId, 1, lessonId))
        val replay = runner.execute(LearningCommand.BeginOrResume("cmd-x", packId, 1, lessonId))
        assertTrue(first is CommandResult.Applied)
        assertTrue(replay is CommandResult.AlreadyApplied)
        assertEquals((first as CommandResult.Applied).state, (replay as CommandResult.AlreadyApplied).state)
    }

    @Test fun duplicateCommandIdConflictingPayloadIsRejected() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-dup", runId, "act-1", ActivityResponse.Acknowledged))
        val conflicting = runner.execute(
            LearningCommand.SubmitResponse("cmd-dup", runId, "act-1", ActivityResponse.SelfReport(true)),
        )
        assertTrue(conflicting is CommandResult.Rejected)
        assertEquals(RejectionReason.COMMAND_CONFLICT, (conflicting as CommandResult.Rejected).reason)
    }

    @Test fun assistancePersistsBeforeRevealAndRetryIsNotIndependentEvidence() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        runner.execute(LearningCommand.Advance("cmd-adv-1", runId, "act-1"))

        val revealed = runner.execute(LearningCommand.RevealSupport("cmd-reveal", runId, "act-2")) as CommandResult.Applied
        assertTrue("act-2" in revealed.state.assistanceUsedActivityIds)

        // Even a first submission after a reveal is assisted, not independent evidence.
        runner.execute(LearningCommand.SubmitResponse("cmd-2", runId, "act-2", ActivityResponse.Choice("c2")))
        val eventsAfterFirst = db.learningDao().eventsForRun(runId)
        val firstEvent = eventsAfterFirst.last { it.activityId == "act-2" }
        assertEquals(com.root.app.data.LearningEventKind.ASSISTED, firstEvent.kind)
        assertEquals(false, firstEvent.correct)

        // A retry on the same activity is also assisted, never independent evidence.
        runner.execute(LearningCommand.SubmitResponse("cmd-3", runId, "act-2", ActivityResponse.Choice("c1")))
        val retryEvent = db.learningDao().eventsForRun(runId).last { it.activityId == "act-2" }
        assertEquals(com.root.app.data.LearningEventKind.ASSISTED, retryEvent.kind)
        assertEquals(true, retryEvent.correct)
    }

    @Test fun unassistedFirstAttemptIsCheckedEvidence() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        runner.execute(LearningCommand.Advance("cmd-adv-1", runId, "act-1"))

        runner.execute(LearningCommand.SubmitResponse("cmd-2", runId, "act-2", ActivityResponse.Choice("c1")))
        val event = db.learningDao().eventsForRun(runId).last { it.activityId == "act-2" }
        assertEquals(com.root.app.data.LearningEventKind.CHECKED, event.kind)
        assertEquals(true, event.correct)
    }

    @Test fun pauseLeavesRunResumableAtPinnedRevision() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId

        // Publish a new revision after the run started.
        installVersion(2, manifest(2))

        val paused = runner.execute(LearningCommand.Pause("cmd-pause", runId)) as CommandResult.Applied
        assertEquals("PAUSED", paused.state.status)

        val resumed = runner.execute(LearningCommand.BeginOrResume("cmd-begin-2", packId, 1, lessonId)) as CommandResult.Applied
        assertEquals(runId, resumed.state.runId)
        // Still pinned to the revision it started with, not the newly published one.
        assertEquals(1, resumed.state.packVersion)
        assertEquals(1, resumed.state.lessonRevision)
        // A resumed PAUSED run becomes ACTIVE again, not left PAUSED forever.
        assertEquals("ACTIVE", resumed.state.status)
    }

    @Test fun retiredPackRejectsNewBeginOrResumeAndRestart() = runBlocking {
        db.contentDao().insertInstalledPack(
            com.root.app.data.InstalledPackEntity(packId = packId, currentVersion = 1, status = com.root.app.data.InstalledPackStatus.RETIRED),
        )
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId))
        assertTrue(begin is CommandResult.Rejected)
        assertEquals(RejectionReason.PACK_UNAVAILABLE, (begin as CommandResult.Rejected).reason)

        val restart = runner.execute(LearningCommand.Restart("cmd-restart", packId, 1, lessonId))
        assertTrue(restart is CommandResult.Rejected)
        assertEquals(RejectionReason.PACK_UNAVAILABLE, (restart as CommandResult.Rejected).reason)
    }

    @Test fun retiringAPackMidRunRejectsFurtherMutationsAndResume() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId

        db.contentDao().insertInstalledPack(
            com.root.app.data.InstalledPackEntity(packId = packId, currentVersion = 1, status = com.root.app.data.InstalledPackStatus.RETIRED),
        )

        val submit = runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        assertTrue(submit is CommandResult.Rejected)
        assertEquals(RejectionReason.PACK_UNAVAILABLE, (submit as CommandResult.Rejected).reason)

        val reveal = runner.execute(LearningCommand.RevealSupport("cmd-2", runId, "act-2"))
        assertTrue(reveal is CommandResult.Rejected)
        assertEquals(RejectionReason.PACK_UNAVAILABLE, (reveal as CommandResult.Rejected).reason)

        // Resuming (BeginOrResume) an already-open run whose pack is now retired
        // must also be refused, not silently reopened.
        val resume = runner.execute(LearningCommand.BeginOrResume("cmd-resume", packId, 1, lessonId))
        assertTrue(resume is CommandResult.Rejected)
        assertEquals(RejectionReason.PACK_UNAVAILABLE, (resume as CommandResult.Rejected).reason)
    }

    @Test fun packWithNoInstalledPackRowIsTreatedAsAvailable() = runBlocking {
        // No InstalledPackEntity row exists for packId at all (only PackVersionEntity,
        // as installVersion() sets up) — this must not be mistaken for retired.
        assertNull(db.contentDao().getInstalledPack(packId))
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId))
        assertTrue(begin is CommandResult.Applied)
    }

    @Test fun restartCreatesNewRunWithoutErasingPreviousEvidence() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val originalRunId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-1", originalRunId, "act-1", ActivityResponse.Acknowledged))

        val restarted = runner.execute(LearningCommand.Restart("cmd-restart", packId, 1, lessonId)) as CommandResult.Applied
        assertNotEquals(originalRunId, restarted.state.runId)

        // Prior evidence is preserved, not deleted.
        assertNotNull(db.learningDao().getRun(originalRunId))
        val priorEvents = db.learningDao().eventsForRun(originalRunId)
        assertEquals(1, priorEvents.size)

        // The new run is now the one resumed going forward.
        val resumed = runner.execute(LearningCommand.BeginOrResume("cmd-begin-3", packId, 1, lessonId)) as CommandResult.Applied
        assertEquals(restarted.state.runId, resumed.state.runId)
    }

    @Test fun advanceRejectsAStaleExpectedActivityIdInsteadOfDoubleAdvancing() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        val advanced = runner.execute(LearningCommand.Advance("cmd-adv-1", runId, "act-1")) as CommandResult.Applied
        assertEquals("act-2", advanced.state.currentActivityId)

        // A distinct commandId (e.g. a delayed retry the caller re-issued with a
        // fresh id) still carrying the *old* expected activity must be rejected,
        // not silently advance a second time past a step the caller never saw.
        val stale = runner.execute(LearningCommand.Advance("cmd-adv-1-retry", runId, "act-1"))
        assertTrue(stale is CommandResult.Rejected)
        assertEquals(RejectionReason.STEP_MISMATCH, (stale as CommandResult.Rejected).reason)
        // The run's cursor did not move again.
        assertEquals("act-2", runner.currentState(runId)!!.currentActivityId)
    }

    @Test fun getOpenRunIsScopedByPackIdNotJustLessonId() = runBlocking {
        val otherPackId = "pack-other-with-same-lesson-id"
        installOtherPackVersion(otherPackId)

        val beginA = runner.execute(LearningCommand.BeginOrResume("cmd-a", packId, 1, lessonId)) as CommandResult.Applied
        val beginB = runner.execute(LearningCommand.BeginOrResume("cmd-b", otherPackId, 1, lessonId)) as CommandResult.Applied

        // Same lessonId in two different packs must never be treated as the same
        // open run — each pack gets its own run despite the shared lessonId.
        assertNotEquals(beginA.state.runId, beginB.state.runId)
        assertEquals(packId, beginA.state.packId)
        assertEquals(otherPackId, beginB.state.packId)

        // Resuming pack A again must still return run A, not run B.
        val resumedA = runner.execute(LearningCommand.BeginOrResume("cmd-a-2", packId, 1, lessonId)) as CommandResult.Applied
        assertEquals(beginA.state.runId, resumedA.state.runId)
    }

    @Test fun resumedStateExposesLastFeedbackForTheCurrentActivity() = runBlocking {
        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val runId = begin.state.runId
        runner.execute(LearningCommand.SubmitResponse("cmd-1", runId, "act-1", ActivityResponse.Acknowledged))
        runner.execute(LearningCommand.Advance("cmd-adv-1", runId, "act-1"))

        // A wrong first attempt on act-2, never advanced past — simulates leaving
        // mid-step after seeing feedback but before continuing.
        runner.execute(LearningCommand.SubmitResponse("cmd-2", runId, "act-2", ActivityResponse.Choice("c2")))

        val resumed = runner.currentState(runId)!!
        assertEquals("act-2", resumed.currentActivityId)
        val feedback = resumed.currentActivityFeedback
        assertNotNull(feedback)
        assertEquals(false, feedback!!.correct)
        assertEquals("CHECKED", feedback.kind)
        assertEquals(ActivityResponse.Choice("c2"), feedback.response)
    }

    @Test fun openRunStateIsReadOnlyAndNeverFabricatesARun() = runBlocking {
        // No run exists yet for this lesson: openRunState must return null
        // without creating one (unlike BeginOrResume).
        assertNull(runner.openRunState(packId, lessonId))

        val begin = runner.execute(LearningCommand.BeginOrResume("cmd-begin", packId, 1, lessonId)) as CommandResult.Applied
        val open = runner.openRunState(packId, lessonId)
        assertNotNull(open)
        assertEquals(begin.state.runId, open!!.runId)

        // Pausing ends the "open for resume via BeginOrResume" state but the run
        // itself is still PAUSED, which openRunState still surfaces.
        runner.execute(LearningCommand.Pause("cmd-pause", begin.state.runId))
        val paused = runner.openRunState(packId, lessonId)
        assertNotNull(paused)
        assertEquals("PAUSED", paused!!.status)
    }

    /** A [Lesson] whose sole activity is a [Activity.Listening] with a
     *  permanently null [Activity.Listening.audioAssetId], required for
     *  completion — the exact shape of the real Shona-pilot listening lesson. */
    private fun listeningOnlyManifest(): PackManifest = manifest().let { base ->
        base.copy(
            lessons = listOf(
                Lesson(
                    id = "lesson-listening", revision = 1, title = "Listening", objective = "Listen",
                    format = LessonFormat.LISTENING,
                    activities = listOf(
                        Activity.Listening(
                            id = "listen-1",
                            audioAssetId = null,
                            unavailableReason = "No recording yet.",
                            comprehension = com.root.app.content.EvaluableTask.Choice(
                                ChoiceTask(
                                    id = "listen-1-task", prompt = "Which word means hello?",
                                    choices = listOf(Choice("w1", "Mhoro"), Choice("w2", "Mauya")),
                                    acceptedChoiceIds = listOf("w1"),
                                ),
                            ),
                        ),
                    ),
                    requiredActivityIds = listOf("listen-1"),
                ),
            ),
        )
    }

    @Test fun requiredListeningWithNullAudioAcceptsAcknowledgedAsExposureButStaysIncomplete() = runBlocking {
        installVersion(2, listeningOnlyManifest())
        val begin = runner.execute(
            LearningCommand.BeginOrResume("cmd-l-begin", packId, 2, "lesson-listening"),
        ) as CommandResult.Applied
        val runId = begin.state.runId

        // A real comprehension-answer attempt against unavailable audio is
        // correctly refused, never faked as evidence.
        val rejected = runner.execute(
            LearningCommand.SubmitResponse("cmd-l-bad", runId, "listen-1", ActivityResponse.Choice("w1")),
        )
        assertTrue(rejected is CommandResult.Rejected)
        assertEquals(RejectionReason.AUDIO_UNAVAILABLE, (rejected as CommandResult.Rejected).reason)

        // A plain acknowledgement of the honest unavailable state IS accepted
        // and recorded as EXPOSURE evidence — the learner isn't stuck on this
        // single step — but it never counts toward *required* completion for a
        // Listening activity, so the lesson stays incomplete without real
        // playable audio and a real comprehension attempt.
        val ack = runner.execute(
            LearningCommand.SubmitResponse("cmd-l-ack", runId, "listen-1", ActivityResponse.Acknowledged),
        )
        assertTrue(ack is CommandResult.Applied)

        val advanced = runner.execute(LearningCommand.Advance("cmd-l-adv", runId, "listen-1"))
        assertTrue(advanced is CommandResult.Rejected)
        assertEquals(RejectionReason.STEP_MISMATCH, (advanced as CommandResult.Rejected).reason)

        val stateAfter = runner.execute(LearningCommand.BeginOrResume("cmd-l-resume", packId, 2, "lesson-listening")) as CommandResult.Applied
        assertFalse(stateAfter.state.completed)
        assertEquals(0, stateAfter.state.requiredCompletedCount)
    }

    private suspend fun installOtherPackVersion(otherPackId: String) {
        val m = manifest(1).copy(id = otherPackId)
        db.contentDao().insertPackVersion(
            PackVersionEntity(
                packId = otherPackId, version = 1, schemaVersion = 1, minReaderVersion = 1,
                languageId = "lang-1", languageCode = "sn", languageName = "Shona", title = m.title,
                publication = "development", manifestJson = ContentJson.encodeToString(m),
                manifestSha256 = "b".repeat(64), phraseCount = 0, lessonCount = 1, assetCount = 0,
            ),
        )
    }
}
