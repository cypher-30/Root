package com.root.app.ui.motion

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Shared timing/easing constants and helpers so launch, reveal, and settle
 * animations stay consistent across screens instead of each hardcoding its own
 * numbers. [enabled] checks the system's "remove animations" accessibility setting;
 * callers should snap state instead of animating when it is false.
 */
object RootMotion {
    val settleEase = CubicBezierEasing(0.18f, 0.72f, 0.2f, 1f)
    val launchEase = CubicBezierEasing(0.3f, 0.05f, 0.25f, 1f)
    const val revealMillis = 520
    const val launchSeedMillis = 250L
    const val launchMillis = 2600
    const val launchHoldMillis = 350L
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
    fun settle() = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 190f)
}
