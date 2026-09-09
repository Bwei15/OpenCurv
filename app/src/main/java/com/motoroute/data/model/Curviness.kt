package com.motoroute.data.model

import com.motoroute.domain.geo.Geo
import kotlin.math.abs

/**
 * Scores how twisty a polyline is.
 *
 * The score is total absolute heading change in degrees per kilometre. Two
 * details keep it honest:
 *
 *  - points closer together than [MIN_SEGMENT_M] are skipped, because GPS-grade
 *    node spacing turns a straight road into a fake zig-zag;
 *  - a single heading change is capped at [MAX_SEGMENT_TURN], so one junction
 *    cannot make a boring route look like a mountain pass.
 */
object Curviness {

    private const val MIN_SEGMENT_M = 12.0
    private const val MAX_SEGMENT_TURN = 120.0

    fun score(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0

        var totalTurn = 0.0
        var length = 0.0

        var anchor = points.first()
        var previousBearing: Double? = null

        for (i in 1 until points.size) {
            val p = points[i]
            val d = Geo.distanceMeters(anchor, p)
            if (d < MIN_SEGMENT_M && i != points.lastIndex) continue

            val bearing = Geo.bearingDegrees(anchor, p)
            previousBearing?.let { prev ->
                totalTurn += Geo.bearingDifference(prev, bearing).coerceAtMost(MAX_SEGMENT_TURN)
            }
            previousBearing = bearing
            length += d
            anchor = p
        }

        if (length < 1.0) return 0.0
        return totalTurn / (length / 1000.0)
    }

    /** Human label for the HUD badge. */
    fun label(score: Double): String = when {
        score < 40 -> "straight"
        score < 90 -> "flowing"
        score < 160 -> "curvy"
        score < 260 -> "twisty"
        else -> "extreme"
    }
}
