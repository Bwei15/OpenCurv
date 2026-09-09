package com.motoroute.domain

import com.motoroute.data.location.FilteredFix
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo

/**
 * A ride along a calculated route, without the ride.
 *
 * This exists because everything that makes the app worth having - the
 * announcements, the manoeuvre arrow, the distance countdown, the map following
 * a heading - can otherwise only be checked by putting a helmet on. The
 * simulator feeds the ordinary navigation pipeline with positions walked along
 * the route, so what the rider sees on the kitchen table is what they will see
 * on the bike.
 *
 * It is deliberately not a "test mode" inside the navigation code: the state
 * machine, the map matcher and the voice cannot tell the difference between
 * these fixes and the GPS.
 */
class RouteSimulator(
    private val route: Route,
    /** How much faster than the route's own estimate the demo runs. */
    private val speedFactor: Double = 3.0,
) {

    /** Metres per second the demo travels; never absurdly slow or fast. */
    val speedMps: Double = run {
        val seconds = route.estimatedSeconds.coerceAtLeast(1)
        val average = route.distanceMeters / seconds
        (average * speedFactor).coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
    }

    val totalMeters: Double = route.distanceMeters

    val isRunnable: Boolean = !route.isEmpty

    /** How long a full demo run takes, in milliseconds. */
    val durationMillis: Long =
        if (speedMps <= 0) 0L else ((totalMeters / speedMps) * 1000.0).toLong()

    /**
     * The fix the rider would be producing [elapsedMillis] into the demo, or
     * null once the route has been driven to its end.
     */
    fun fixAt(elapsedMillis: Long, nowMillis: Long = elapsedMillis): FilteredFix? {
        if (!isRunnable) return null
        val travelled = speedMps * (elapsedMillis / 1000.0)
        if (travelled > totalMeters) return null
        return fixAtDistance(travelled, nowMillis)
    }

    /** The fix at a distance along the route. Exposed for tests. */
    fun fixAtDistance(meters: Double, nowMillis: Long = 0L): FilteredFix {
        val distances = route.cumulativeDistances
        val clamped = meters.coerceIn(0.0, totalMeters)

        var index = distances.binarySearch(clamped).let { if (it < 0) -it - 2 else it }
        index = index.coerceIn(0, route.points.size - 2)

        val from = route.points[index]
        val to = route.points[index + 1]
        val segment = (distances[index + 1] - distances[index]).coerceAtLeast(0.0001)
        val fraction = ((clamped - distances[index]) / segment).coerceIn(0.0, 1.0)

        val position = GeoPoint(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            longitude = from.longitude + (to.longitude - from.longitude) * fraction,
        )

        return FilteredFix(
            point = position,
            speedMps = speedMps,
            headingDegrees = Geo.bearingDegrees(from, to),
            accuracyMeters = SIMULATED_ACCURACY_METERS,
            timestampMillis = nowMillis,
        )
    }

    private companion object {
        const val MIN_SPEED_MPS = 8.0
        const val MAX_SPEED_MPS = 45.0

        /** Better than any real GPS, which is the point: no filter surprises. */
        const val SIMULATED_ACCURACY_METERS = 4.0
    }
}
