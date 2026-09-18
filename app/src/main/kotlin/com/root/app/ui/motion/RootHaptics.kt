package com.root.app.ui.motion

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.root.app.data.ConfidenceLevel

/** A small, deliberately restrained haptic vocabulary: a light tick on reveal and
 *  one distinct waveform per outcome (see docs/DESIGN.md's motion table). All calls
 *  degrade silently — no vibrator, feedback disabled, or the system accessibility
 *  "haptic feedback" setting off — rather than throwing or retrying. */
object RootHaptics {
    fun reveal(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    @Suppress("DEPRECATION")
    fun rate(view: View, level: ConfidenceLevel) {
        if (!view.isHapticFeedbackEnabled ||
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val (times, amplitudes) = when (level) {
            ConfidenceLevel.MISSED -> longArrayOf(0, 16) to intArrayOf(0, 55)
            ConfidenceLevel.CLOSE -> longArrayOf(0, 12, 45, 12) to intArrayOf(0, 55, 0, 55)
            ConfidenceLevel.GOT_IT -> longArrayOf(0, 28) to intArrayOf(0, 95)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(times, amplitudes, -1))
    }
}
