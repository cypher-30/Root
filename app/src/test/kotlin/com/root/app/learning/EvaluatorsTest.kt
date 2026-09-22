package com.root.app.learning

import com.root.app.content.Choice
import com.root.app.content.ChoiceTask
import com.root.app.content.OrderedTokenTask
import com.root.app.content.TokenOccurrence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the deterministic evaluators — no Room/Android needed. */
class EvaluatorsTest {

    @Test fun choiceEvaluatorAcceptsExactAcceptedId() {
        val task = ChoiceTask(
            id = "t1", prompt = "Reply?",
            choices = listOf(Choice("c1", "Ndiripo"), Choice("c2", "Kwaziwai")),
            acceptedChoiceIds = listOf("c1"),
        )
        assertTrue(ChoiceEvaluator.evaluate(task, "c1"))
        assertFalse(ChoiceEvaluator.evaluate(task, "c2"))
    }

    @Test fun choiceEvaluatorSupportsMultipleAcceptedSynonyms() {
        val task = ChoiceTask(
            id = "t1", prompt = "Reply?",
            choices = listOf(Choice("c1", "A"), Choice("c2", "B"), Choice("c3", "C")),
            acceptedChoiceIds = listOf("c1", "c2"),
        )
        assertTrue(ChoiceEvaluator.evaluate(task, "c1"))
        assertTrue(ChoiceEvaluator.evaluate(task, "c2"))
        assertFalse(ChoiceEvaluator.evaluate(task, "c3"))
    }

    private fun orderedTask(accepted: List<List<String>>) = OrderedTokenTask(
        id = "ot1", prompt = "Order them",
        tokens = listOf(
            TokenOccurrence("t1", "Mangwanani"),
            TokenOccurrence("t2", "akanaka"),
            TokenOccurrence("t3", "kwazvo"),
        ),
        acceptedSequences = accepted,
    )

    @Test fun orderedTokenEvaluatorMatchesExactOccurrenceOrder() {
        val task = orderedTask(listOf(listOf("t1", "t2", "t3")))
        assertTrue(OrderedTokenEvaluator.evaluate(task, listOf("t1", "t2", "t3")))
        assertFalse(OrderedTokenEvaluator.evaluate(task, listOf("t2", "t1", "t3")))
    }

    @Test fun orderedTokenEvaluatorSupportsMultipleAcceptedOrders() {
        val task = orderedTask(listOf(listOf("t1", "t2", "t3"), listOf("t2", "t1", "t3")))
        assertTrue(OrderedTokenEvaluator.evaluate(task, listOf("t1", "t2", "t3")))
        assertTrue(OrderedTokenEvaluator.evaluate(task, listOf("t2", "t1", "t3")))
        assertFalse(OrderedTokenEvaluator.evaluate(task, listOf("t3", "t2", "t1")))
    }

    /** Repeated words are supported because occurrence identity (not text) is
     *  what's checked — two occurrences of the same word are distinguishable. */
    @Test fun orderedTokenEvaluatorDistinguishesRepeatedWordOccurrences() {
        val task = OrderedTokenTask(
            id = "ot2", prompt = "Order them",
            tokens = listOf(
                TokenOccurrence("t1", "iwe"), TokenOccurrence("t2", "neni"), TokenOccurrence("t3", "iwe"),
            ),
            acceptedSequences = listOf(listOf("t1", "t2", "t3")),
        )
        assertTrue(OrderedTokenEvaluator.evaluate(task, listOf("t1", "t2", "t3")))
        assertFalse(OrderedTokenEvaluator.evaluate(task, listOf("t3", "t2", "t1")))
    }
}
