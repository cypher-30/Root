package com.lugha.app.data

import java.util.concurrent.TimeUnit

/**
 * The whole scheduler. On purpose: this is a hand-written rule, not a trained model —
 * see the plan's Block 1 note ("No model — and say so in the README rather than
 * implying one") and Kumbuka's own baseline-vs-model split in AI-TRACK.md, which this
 * is a direct rehearsal of.
 *
 * Fixed intervals by self-rated confidence (Kumbuka's Blank/Shaky/OK/Solid scale).
 * A real spaced-repetition formula (SM-2-style ease factors) is the natural upgrade
 * path, but is explicitly out of scope for the hackathon build.
 */
object Scheduler {
    private val intervalsByConfidence: Map<ConfidenceLevel, Long> = mapOf(
        ConfidenceLevel.BLANK to TimeUnit.HOURS.toMillis(4),
        ConfidenceLevel.SHAKY to TimeUnit.HOURS.toMillis(20),
        ConfidenceLevel.OK to TimeUnit.DAYS.toMillis(3),
        ConfidenceLevel.SOLID to TimeUnit.DAYS.toMillis(9),
    )

    fun nextDueAt(confidence: ConfidenceLevel, now: Long = System.currentTimeMillis()): Long =
        now + (intervalsByConfidence[confidence] ?: TimeUnit.HOURS.toMillis(4))
}
