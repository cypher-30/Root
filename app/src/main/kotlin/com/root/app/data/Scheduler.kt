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
    fun nextDueAt(confidence: ConfidenceLevel, now: Long = System.currentTimeMillis()): Long =
        now + when (confidence) {
            ConfidenceLevel.MISSED -> TimeUnit.HOURS.toMillis(4)
            ConfidenceLevel.CLOSE -> TimeUnit.DAYS.toMillis(1)
            ConfidenceLevel.GOT_IT -> TimeUnit.DAYS.toMillis(4)
        }
}
