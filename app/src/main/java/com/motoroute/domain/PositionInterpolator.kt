package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlin.math.abs
import kotlin.math.min

/** Where the puck should be drawn right now, and which way it points. */
data class InterpolatedPose(
    val point: GeoPoint,
    val headingDegrees: Double,
)

/**
 * Makes the puck move like a motorcycle instead of teleporting once a second.
 *
 * A GPS gives one fix per second, and drawing each fix as it lands is what the
 * ride report described: the dot jumps to the next position, sits still, jumps
 * again. The fix rate is not going to change, so the movement between fixes has
 * to be worked out rather than waited for.
 *
 * Two pieces do that:
 *
 *  * **Dead reckoning.** A fix carries speed and heading, so where the bike
 *    *will* be a fraction of a second later is simple arithmetic - keep
 *    advancing the last fix along its own heading until the next one lands.
 *    That alone gives continuous motion at whatever rate the screen redraws.
 *  * **Bounded catch-up.** The prediction is always a little wrong, and the
 *    correction must not show. So the drawn position chases the predicted one at
 *    a limited speed ([CATCH_UP_FACTOR] times the bike's own), which turns a
 *    correction into a slightly-faster-than-real glide rather than a visible
 *    step.
 *
 * A jump is still possible, and has to be: after a tunnel, a reroute, or a fix
 * that lands [snapMeters] away, gliding there would have the puck cross half a
 * town in a straight line through buildings. Past that distance it snaps, which
 * is the "äußerster Notfall" the report allows for.
 *
 * Android-free and stateful: one instance per map, fed from the location
 * stream, asked once per rendered frame.
 */
class PositionInterpolator(
    /** Beyond this error, stop gliding and jump. */
    private val snapMeters: Double = 40.0,
    /** How much faster than the bike the drawn position may travel while catching up. */
    private val catchUpFactor: Double = CATCH_UP_FACTOR,
) {

    private var fixPoint: GeoPoint? = null
    private var fixHeading = 0.0
    private var fixSpeedMps = 0.0
    private var fixTimeMillis = 0L

    private var drawnPoint: GeoPoint? = null
    private var drawnHeading = 0.0
    private var lastDrawMillis = 0L

    /** True once a fix has been seen, i.e. once [poseAt] returns something. */
    val hasFix: Boolean get() = fixPoint != null

    /**
     * Takes a fix. [point] should already be the map-matched position when one
     * exists - snapping to the route and then interpolating is what keeps the
     * puck on the road rather than gliding across a field toward it.
     */
    fun onFix(
        point: GeoPoint,
        headingDegrees: Double,
        speedMps: Double,
        timestampMillis: Long,
    ) {
        fixPoint = point
        fixHeading = headingDegrees
        fixSpeedMps = speedMps.coerceAtLeast(0.0)
        fixTimeMillis = timestampMillis
        if (drawnPoint == null) {
            // Nothing on screen yet: start where the rider actually is.
            drawnPoint = point
            drawnHeading = headingDegrees
            lastDrawMillis = timestampMillis
        }
    }

    /** Forgets everything - call when a ride starts or the map is re-attached. */
    fun reset() {
        fixPoint = null
        drawnPoint = null
        lastDrawMillis = 0L
    }

    /**
     * The pose to draw at [nowMillis]. Call once per frame; returns null until
     * the first fix has arrived.
     */
    fun poseAt(nowMillis: Long): InterpolatedPose? {
        val fix = fixPoint ?: return null
        val drawn = drawnPoint ?: return null

        val predicted = predictedPoint(fix, nowMillis)
        val frameSeconds = ((nowMillis - lastDrawMillis).coerceAtLeast(0L)) / 1000.0
        lastDrawMillis = nowMillis

        val error = Geo.distanceMeters(drawn, predicted)
        val next = when {
            // Too far wrong to hide: a tunnel, a reroute, a GPS that found
            // itself again three streets over.
            error > snapMeters -> predicted
            // First frame after a fix, or a paused screen: no elapsed time to
            // budget a step from, so just take the prediction.
            frameSeconds <= 0.0 -> predicted
            else -> {
                val budget = maxOf(fixSpeedMps * catchUpFactor, MIN_CATCH_UP_MPS) * frameSeconds
                if (error <= budget) predicted else Geo.interpolate(drawn, predicted, budget / error)
            }
        }

        drawnPoint = next
        drawnHeading = approachHeading(drawnHeading, fixHeading, frameSeconds)
        return InterpolatedPose(next, drawnHeading)
    }

    /** Where the last fix says the bike is by now, carried along its own heading. */
    private fun predictedPoint(fix: GeoPoint, nowMillis: Long): GeoPoint {
        val elapsedSeconds = ((nowMillis - fixTimeMillis).coerceAtLeast(0L)) / 1000.0
        // Past this, the fix is stale enough that extrapolating it is guessing -
        // a bike that stopped would otherwise keep sliding forever.
        val usable = min(elapsedSeconds, MAX_EXTRAPOLATION_SECONDS)
        if (fixSpeedMps <= 0.0 || usable <= 0.0) return fix
        return Geo.offset(fix, fixHeading, fixSpeedMps * usable)
    }

    /**
     * Turns the puck toward the fix heading at a bounded rate.
     *
     * Heading from a Kalman-filtered GPS can swing 20 degrees between fixes on a
     * bumpy road; applying that straight to the icon makes the puck twitch.
     */
    private fun approachHeading(from: Double, to: Double, frameSeconds: Double): Double {
        if (frameSeconds <= 0.0) return to
        val delta = Geo.normalizeDelta(to - from)
        val maxStep = MAX_TURN_DEG_PER_SEC * frameSeconds
        if (abs(delta) <= maxStep) return Geo.normalizeBearing(to)
        return Geo.normalizeBearing(from + maxStep * if (delta > 0) 1.0 else -1.0)
    }

    private companion object {
        /**
         * 1.35x the bike's speed. Enough to absorb a second's worth of error in
         * about three seconds; much more and the catch-up itself is visible as
         * the puck surging forward after every fix.
         */
        const val CATCH_UP_FACTOR = 1.35

        /**
         * Floor on the catch-up speed, so a bike stopped at a light still
         * corrects rather than freezing in the wrong place.
         *
         * 5 m/s (18 km/h) clears a typical 10-15 m GPS correction in two to
         * three seconds, which reads as the puck settling. At 1.5 m/s - the
         * first value here - the same correction crept for eight seconds, which
         * reads as the app being confused.
         */
        const val MIN_CATCH_UP_MPS = 5.0

        /** Never dead-reckon further ahead than this without a fresh fix. */
        const val MAX_EXTRAPOLATION_SECONDS = 3.0

        /** A motorcycle's yaw rate in a tight hairpin, roughly. */
        const val MAX_TURN_DEG_PER_SEC = 120.0
    }
}
