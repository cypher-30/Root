package com.root.app.learning

import com.root.app.content.ChoiceTask
import com.root.app.content.OrderedTokenTask

/**
 * Pure, deterministic evaluation of the two machine-checkable task shapes.
 * No free-text normalization, no punctuation/diacritic stripping — accepted
 * answers are exactly what the editorial content specifies (see
 * docs/TEACHING_CONTRACTS.md and [com.root.app.content.ContentValidator]).
 * These functions have no Android/Room dependency so they can be exercised by
 * plain JVM unit tests.
 */
object ChoiceEvaluator {
    fun evaluate(task: ChoiceTask, chosenChoiceId: String): Boolean = chosenChoiceId in task.acceptedChoiceIds
}

/**
 * Ordered-token construction: the learner's answer is the exact sequence of
 * token *occurrence* ids (not text), so a sentence that repeats a word is
 * fully supported without any ambiguity about which occurrence was placed
 * where. A sequence must match one of [OrderedTokenTask.acceptedSequences]
 * exactly, element for element.
 */
object OrderedTokenEvaluator {
    fun evaluate(task: OrderedTokenTask, submittedOccurrenceIds: List<String>): Boolean =
        task.acceptedSequences.any { it == submittedOccurrenceIds }
}
