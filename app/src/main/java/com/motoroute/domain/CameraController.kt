package com.motoroute.domain

import kotlin.math.roundToInt

/**
 * Picks the map zoom from the current speed.
 *
 * Slow means junctions and villages, where the rider needs to see which of
 * five exits to take. Fast means open road, where the rider needs to see what
 * is coming in half a kilometre. The bands come straight from the spec:
 *
 *   < 40 km/h  -> 17..18   town, junctions
 *   40..90     -> 15..16   country roads and curves
 *   > 90       -> 13..14   long sight lines
 *
 * Within each band the zoom interpolates, and a hysteresis band stops the map
 * from flapping between two levels while the rider hovers at 40 km/h.
 */
class CameraController(
    private val hysteresisKmh: Double = 4.0,
) {

    private var currentZoom = 16

    /** Zoom for [speedKmh], honouring hysteresis around the band edges. */
    fun zoomFor(speedKmh: Double): Int {
        val target = rawZoomFor(speedKmh)
        if (target == currentZoom) return currentZoom

        // Only move if the speed is clearly past the boundary that separates
        // the current zoom from the new one.
        val boundarySpeed = boundaryBetween(currentZoom, target)
        if (boundarySpeed != null && kotlin.math.abs(speedKmh - boundarySpeed) < hysteresisKmh) {
            return currentZoom
        }
        currentZoom = target
        return currentZoom
    }

    fun reset(zoom: Int = 16) {
        currentZoom = zoom
    }

    private fun rawZoomFor(speedKmh: Double): Int = when {
        speedKmh < 20 -> 18
        speedKmh < 40 -> 17
        speedKmh < 65 -> 16
        speedKmh <= 90 -> 15
        speedKmh <= 120 -> 14
        else -> 13
    }

    private fun boundaryBetween(a: Int, b: Int): Double? {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if (hi - lo != 1) return null
        return when (lo) {
            17 -> 20.0
            16 -> 40.0
            15 -> 65.0
            14 -> 90.0
            13 -> 120.0
            else -> null
        }
    }

    companion object {
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 19

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
