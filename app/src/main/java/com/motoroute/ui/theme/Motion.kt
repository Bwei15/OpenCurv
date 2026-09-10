package com.motoroute.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion.
 *
 * The rule that settles every argument: **anything a rider presses in an
 * emergency does not animate.** Stop, recenter, mute, dismiss - those change
 * state on the same frame as the touch. An animation there is not delight, it
 * is a 200 ms delay before the rider knows whether the press landed, at a
 * moment when they cannot look twice.
 *
 * Everything the rider watches while standing still may spring. That is where
 * the app is allowed to be likeable.
 */
object Motion {

    // ---- durations -------------------------------------------------------

    /** No animation. Every riding control. Also every state change under way. */
    const val Instant: Int = 0

    /** A press ripple, a chip toggling, an icon swapping. */
    const val Fast: Int = 120

    /** The default: a sheet moving, a card appearing, a colour changing. */
    const val Standard: Int = 220

    /** A full-screen transition on the resting screens. */
    const val Slow: Int = 320

    /** The curviness ramp filling, the route drawing itself in. */
    const val Playful: Int = 480

    // ---- easings ---------------------------------------------------------

    /** Enters and moves. Fast out of the gate, long settle. */
    val Standard_: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Leaves. No settle - gone is gone. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** Arrives from off-screen. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // ---- ready-made specs ------------------------------------------------

    /** Use for anything in the riding register. */
    fun <T> instant(): FiniteAnimationSpec<T> = snap()

    fun <T> fast(): FiniteAnimationSpec<T> = tween(Fast, easing = Standard_)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = Standard_)

    fun <T> exit(): FiniteAnimationSpec<T> = tween(Fast, easing = Exit)

    fun <T> slow(): FiniteAnimationSpec<T> = tween(Slow, easing = Enter)

    /**
     * The one spring in the system. Resting register only.
     *
     * Damping 0.6 overshoots visibly once and settles - enough to read as
     * playful, not enough to read as broken. Never apply it to a number: a
     * distance that springs past its value and comes back has, for two frames,
     * lied to the rider.
     */
    fun <T> bounce(): AnimationSpec<T> = spring(
        dampingRatio = 0.6f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** A sheet or a drawer following a finger. */
    fun <T> settle(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
