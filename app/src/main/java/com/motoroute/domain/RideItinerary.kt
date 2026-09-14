package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo

/** Where one stop sits relative to the rider right now. */
data class ItineraryStop(
    /** Index into [Route.points] the stop projects onto. */
    val pointIndex: Int,
    /** Metres still to ride to reach it. Negative once it is behind the rider. */
    val distanceAheadMeters: Double,
    /** Expected arrival, epoch millis, or 0 when it cannot be estimated. */
    val etaEpochMillis: Long,
) {
    val isPassed: Boolean get() = distanceAheadMeters < 0.0
}

/**
 * Turns the ride's waypoints into "in 12 km, 16:02" for the riding stop list.
 *
 * A planned route knows its stops as coordinates; a rider mid-ride wants them as
 * distances and times from where they are. The awkward part is that a waypoint
 * is not a route point: BRouter snaps it to the nearest node on the way it
 * picked, which can be tens of metres off what the rider searched for. So each
 * stop is projected onto the route by nearest point, and everything else falls
 * out of [Route.cumulativeDistances].
 *
 * Android-free so `tools/verifier` can test it: this is arithmetic on a route,
 * and arithmetic on a route is exactly the kind of thing that is wrong by a
 * factor of two for a month before anyone notices on a screen.
 */
object RideItinerary {

    /**
     * Index of the route point closest to [point].
     *
     * A linear scan: a route has a few thousand points and this runs when the
     * rider opens the stop sheet, not per GPS fix.
     */
    fun nearestPointIndex(route: Route, point: GeoPoint): Int {
        if (route.points.isEmpty()) return -1
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in route.points.indices) {
            val d = Geo.distanceMeters(route.points[i], point)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /**
     * One [ItineraryStop] per entry in [viaPoints], in the same order.
     *
     * [travelledMeters] is how far along the route the rider already is (route
     * length minus remaining). [speedMps] is used for the arrival estimate and
     * floored at [MIN_ETA_SPEED_MPS] so a bike stopped at a light does not
     * produce an arrival time next Tuesday; pass the route's own average when
     * there is no live speed worth trusting.
     */
    fun stopsAhead(
        route: Route,
        viaPoints: List<GeoPoint>,
        travelledMeters: Double,
        nowMillis: Long,
        speedMps: Double,
    ): List<ItineraryStop> {
        if (route.isEmpty) return emptyList()
        val effectiveSpeed = speedMps.coerceAtLeast(MIN_ETA_SPEED_MPS)
        return viaPoints.map { point ->
            val index = nearestPointIndex(route, point)
            val ahead = route.distanceAt(index) - travelledMeters
            ItineraryStop(
                pointIndex = index,
                distanceAheadMeters = ahead,
                etaEpochMillis = if (ahead <= 0.0) 0L else nowMillis + (ahead / effectiveSpeed * 1000L).toLong(),
            )
        }
    }

    /**
     * The route's own average speed in m/s - the fallback for an arrival
     * estimate when the bike is standing still.
     */
    fun averageSpeedMps(route: Route): Double {
        if (route.estimatedSeconds <= 0) return MIN_ETA_SPEED_MPS
        return (route.distanceMeters / route.estimatedSeconds).coerceAtLeast(MIN_ETA_SPEED_MPS)
    }

    /** ~11 km/h: slow enough to be honest at a standstill, fast enough to stay a number. */
    const val MIN_ETA_SPEED_MPS = 3.0
}
