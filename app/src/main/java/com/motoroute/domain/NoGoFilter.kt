package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.NoGoArea
import com.motoroute.domain.geo.Geo
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps only the closures that can matter for one route.
 *
 * The live feed carries every motorway closure in Germany - several hundred,
 * expanded to thousands of avoidance circles once line closures are sampled.
 * BRouter checks every nogo against every link it expands, so handing it the
 * whole country turns a ten-second route into minutes. A closure far from
 * the actual path between the waypoints cannot change the result, so it is
 * dropped here.
 *
 * A plain bounding box around the waypoints used to do this job, but a box
 * grows to cover its *widest* corner - one outlier waypoint (a stale GPS fix,
 * a "from" point that has not caught up with the map yet) stretches the box
 * across the whole gap to that outlier and drags in everything in between.
 * Measured on a real closure feed (device cache, 3 817 features): a ~1 km
 * Hannover route with a sane pair of waypoints kept 57 circles via the old
 * 30 km-margin box, but swapping in one bogus far-away "from" point (Null
 * Island, a stale default location before GPS settles) kept 2 281 - more
 * than half the country - because the box has no notion of "near the
 * *path*", only "near *a* waypoint". A corridor around the waypoint polyline
 * does not have that failure mode: a closure has to be near some leg of the
 * actual route, and [MAX_CORRIDOR_KM] stops one absurd waypoint from
 * widening that corridor past what any real detour would use.
 */
object NoGoFilter {
    /** Corridor half-width floor - a short trip still needs slack for a detour. */
    const val MIN_CORRIDOR_KM = 5.0

    /** Corridor half-width as a fraction of the trip's straight-line length. */
    const val CORRIDOR_FRACTION = 0.2

    /**
     * Corridor half-width ceiling. Without this, one waypoint far from the
     * rest (a GPS fix that has not settled yet, a "current location"
     * fallback outside the actual trip) inflates the straight-line length
     * the corridor scales from, and 20% of *that* can end up wider than the
     * whole country - worse than the bounding box it replaced. A route is
     * never going to detour more than this to dodge one closure anyway.
     */
    const val MAX_CORRIDOR_KM = 50.0

    /**
     * Keeps the [noGos] whose point lies within [corridorKm] of the polyline
     * through [waypoints] (the perpendicular distance to the nearest leg,
     * clamped to that leg's endpoints - see [Geo.projectOnSegment]).
     *
     * [corridorKm] defaults to the straight-line distance from the first to
     * the last waypoint times [CORRIDOR_FRACTION], clamped to
     * [MIN_CORRIDOR_KM]..[MAX_CORRIDOR_KM]: a short hop still gets a few
     * kilometres of detour slack, a long tour gets a corridor that scales
     * with it, and no single waypoint can widen it past [MAX_CORRIDOR_KM].
     */
    fun near(noGos: List<NoGoArea>, waypoints: List<GeoPoint>, corridorKm: Double? = null): List<NoGoArea> {
        if (noGos.isEmpty() || waypoints.isEmpty()) return emptyList()

        val radiusMeters = (corridorKm ?: defaultCorridorKm(waypoints)) * 1000.0
        if (waypoints.size == 1) {
            val only = waypoints[0]
            return noGos.filter { Geo.distanceMeters(it.point, only) <= radiusMeters }
        }

        return noGos.filter { distanceToPolylineMeters(it.point, waypoints) <= radiusMeters }
    }

    private fun defaultCorridorKm(waypoints: List<GeoPoint>): Double {
        if (waypoints.size < 2) return MIN_CORRIDOR_KM
        val straightLineKm = Geo.distanceMeters(waypoints.first(), waypoints.last()) / 1000.0
        return min(MAX_CORRIDOR_KM, max(MIN_CORRIDOR_KM, straightLineKm * CORRIDOR_FRACTION))
    }

    private fun distanceToPolylineMeters(point: GeoPoint, waypoints: List<GeoPoint>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until waypoints.size - 1) {
            val d = Geo.projectOnSegment(point, waypoints[i], waypoints[i + 1]).distanceMeters
            if (d < best) best = d
        }
        return best
    }
}
