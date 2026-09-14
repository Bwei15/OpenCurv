package com.motoroute.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picks the map zoom from the current speed - as a continuous value, not a
 * band.
 *
 * Slow means junctions and villages, where the rider needs to see which of
 * five exits to take. Fast means open road, where the rider needs to see what
 * is coming in half a kilometre. The first version of this class snapped to
 * whole zoom levels with a hysteresis band around each edge; the ride report
 * was that the map sits too close for most of a tour ("man kann effektiv nicht
 * so viel sehen"), and that the band edges still read as jumps.
 *
 * So two things changed:
 *
 *  * the whole curve moved out by roughly one level - a rider at 70 km/h now
 *    gets the overview a rider at 100 km/h used to get, which is the view that
 *    actually shows the next bend coming;
 *  * zoom is a `Double` interpolated between anchor speeds and then eased
 *    towards the target on every fix, so there is no edge to flap across and
 *    no snap to a whole level. MapLibre takes fractional zoom natively.
 *
 * The anchors ([ZOOM_ANCHORS]) read as: what does the rider need to see at
 * this speed? Below is roughly a 12-second-of-travel sight line, which is what
 * the anchors were tuned against.
 */
class CameraController(
    /**
     * How much of the remaining gap to the target zoom is closed per fix.
     *
     * One GPS fix per second and 0.25 means a full level of zoom change takes
     * about three seconds to settle: fast enough to follow an on-ramp, slow
     * enough that a gust of GPS speed noise never reads as a zoom pump.
     */
    private val easing: Double = 0.25,
) {

    private var currentZoom = DEFAULT_ZOOM

    /**
     * Zoom for [speedKmh], eased from the previous value.
     *
     * Call once per location fix. The returned value is fractional on purpose;
     * pass it to the map as-is.
     */
    fun zoomFor(speedKmh: Double): Double {
        val target = targetZoomFor(speedKmh)
        val gap = target - currentZoom
        currentZoom = if (abs(gap) <= SETTLE_EPSILON) target else currentZoom + gap * easing
        return currentZoom
    }

    fun reset(zoom: Double = DEFAULT_ZOOM) {
        currentZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** The zoom the current speed asks for, before easing. Visible for tests. */
    fun targetZoomFor(speedKmh: Double): Double {
        val speed = speedKmh.coerceAtLeast(0.0)
        val anchors = ZOOM_ANCHORS
        if (speed <= anchors.first().first) return anchors.first().second
        if (speed >= anchors.last().first) return anchors.last().second

        for (i in 0 until anchors.size - 1) {
            val (loSpeed, loZoom) = anchors[i]
            val (hiSpeed, hiZoom) = anchors[i + 1]
            if (speed in loSpeed..hiSpeed) {
                val t = (speed - loSpeed) / (hiSpeed - loSpeed)
                return loZoom + t * (hiZoom - loZoom)
            }
        }
        return anchors.last().second
    }

    companion object {
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 19.0

        /** Where the riding camera starts before the first fix arrives. */
        const val DEFAULT_ZOOM = 15.5

        /** Snap to the target once the remaining gap is below one screen-invisible step. */
        private const val SETTLE_EPSILON = 0.01

        /**
         * (km/h, zoom) pairs, ascending by speed, linearly interpolated in
         * between. One level out from the original bands throughout, because
         * the original sat too close to read the road ahead.
         */
        private val ZOOM_ANCHORS: List<Pair<Double, Double>> = listOf(
            0.0 to 16.6,    // stopped at a junction: still see every exit
            30.0 to 16.0,   // through a village
            50.0 to 15.2,   // town limit / slow country road
            70.0 to 14.5,   // Landstraße, the bread and butter of a tour
            100.0 to 13.8,  // fast country road
            130.0 to 13.2,  // Autobahn
            180.0 to 12.6,  // as far out as the riding camera ever goes
        )

        /**
         * Map tilt in degrees while navigating (0 outside navigation - see `OpenCurvRoot.kt`'s
         * `MapRoot`). The product decision, after seeing the demo mode: a flat map at a standstill
         * read as "broken" rather than "stopped", so navigation now keeps at least [MIN_TILT_DEG]
         * of perspective at any speed, the same look the demo already had, and ramps up to the
         * full 50 degrees by 25 km/h.
         */
        fun tiltFor(speedKmh: Double): Float = when {
            speedKmh >= 25 -> 50f
            else -> (MIN_TILT_DEG + speedKmh / 25.0 * (50.0 - MIN_TILT_DEG)).roundToInt().toFloat()
        }

        /** The floor of [tiltFor] - "always slightly tilted while riding", per the product spec. */
        private const val MIN_TILT_DEG = 45.0
    }
}
