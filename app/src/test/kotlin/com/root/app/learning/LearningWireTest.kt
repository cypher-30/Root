package com.root.app.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure tests for the command-hashing/result-encoding contract
 *  [LessonRunner] relies on for idempotent command replay. */
class LearningWireTest {

    @Test fun identicalCommandsHashEqually() {
        val a = LearningCommand.SubmitResponse("cmd-1", "run-1", "act-1", ActivityResponse.Choice("c1"))
        val b = LearningCommand.SubmitResponse("cmd-1", "run-1", "act-1", ActivityResponse.Choice("c1"))
        assertEquals(LearningWire.hashCommand(a), LearningWire.hashCommand(b))
    }

    @Test fun differentPayloadsWithSameCommandIdHashDifferently() {
        val a = LearningCommand.SubmitResponse("cmd-1", "run-1", "act-1", ActivityResponse.Choice("c1"))
        val b = LearningCommand.SubmitResponse("cmd-1", "run-1", "act-1", ActivityResponse.Choice("c2"))
        assertNotEquals(LearningWire.hashCommand(a), LearningWire.hashCommand(b))
    }

    @Test fun resultRoundTripsThroughEncoding() {
        val state = LessonRunState(
            runId = "run-1", lessonId = "lesson-1", packId = "pack-1", packVersion = 1, lessonRevision = 1,
            status = "ACTIVE", currentActivityId = "act-1", currentActivityIndex = 0, totalActivities = 3,
            requiredCompletedCount = 0, requiredTotalCount = 2, assistanceUsedActivityIds = emptyList(), completed = false,
        )
        val result: CommandResult = CommandResult.Applied(state)
        val json = LearningWire.encodeResult(result)
        val decoded = LearningWire.decodeResult(json)
        assertEquals(result, decoded)
    }
}
