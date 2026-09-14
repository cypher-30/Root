package com.root.app.data

import java.util.concurrent.TimeUnit

/**
 * The whole scheduler. On purpose: this is a hand-written rule, not a trained model —
 * see the plan's Block 1 note ("No model — and say so in the README rather than
 * implying one") and Kumbuka's own baseline-vs-model split in AI-TRACK.md, which this
 * is a direct rehearsal of.
 *
 * Fixed intervals keyed by outcome (Missed/Close/Got it), not felt confidence — see
 * Entities.kt's ConfidenceLevel doc comment for why that swap happened. A real
 * spaced-repetition formula (SM-2-style ease factors, or eventually something closer
 * to Duolingo's half-life regression) is the natural upgrade path, but is explicitly
 * out of scope for the hackathon build.
 */
object Scheduler {
    private val intervalsByConfidence: Map<ConfidenceLevel, Long> = mapOf(
        ConfidenceLevel.MISSED to TimeUnit.HOURS.toMillis(4),
        ConfidenceLevel.CLOSE to TimeUnit.DAYS.toMillis(1),
        ConfidenceLevel.GOT_IT to TimeUnit.DAYS.toMillis(4),
    )

    fun nextDueAt(confidence: ConfidenceLevel, now: Long = System.currentTimeMillis()): Long =
        now + (intervalsByConfidence[confidence] ?: TimeUnit.HOURS.toMillis(4))
}
