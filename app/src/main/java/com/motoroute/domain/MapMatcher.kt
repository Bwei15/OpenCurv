package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo
import kotlin.math.abs

/**
 * Where the rider currently is *on the route*.
 *
 * @param segmentIndex index of the polyline segment (points[i] -> points[i+1])
 * @param alongSegmentMeters how far into that segment the projection sits
 * @param crossTrackMeters perpendicular distance from the route (the classic
 *   cross-track error); this is what off-route detection thresholds on
 * @param distanceFromStart metres from the route start to the snapped position
 * @param snapped the position projected onto the route
 */
data class MatchResult(
    val segmentIndex: Int,
    val alongSegmentMeters: Double,
    val crossTrackMeters: Double,
    val distanceFromStart: Double,
    val snapped: GeoPoint,
    val routeBearing: Double,
)

/**
 * Projects raw positions onto the active route.
 *
 * Two things make this more than "find the nearest segment":
 *
 *  - the search is a sliding window around the last match, so a route that
 *    doubles back on itself (very common on a mountain pass) cannot teleport
 *    the rider onto the return leg;
 *  - a candidate whose direction disagrees with the rider's heading by more
 *    than [HEADING_TOLERANCE] is penalised, which keeps the match on the right
 *    carriageway of a dual carriageway and on the right side of a hairpin.
 */
class MapMatcher(
    private val backwardWindowMeters: Double = 120.0,
    private val forwardWindowMeters: Double = 600.0,
) {

    private var lastSegmentIndex = 0

    fun reset() {
        lastSegmentIndex = 0
    }

    /** Forces the next match to search the whole route (after a reroute). */
    fun resetTo(segmentIndex: Int) {
        lastSegmentIndex = segmentIndex
    }

    fun match(route: Route, position: GeoPoint, headingDegrees: Double? = null): MatchResult? {
        if (route.isEmpty) return null

        val lastSegment = route.points.size - 1
        val (from, to) = searchWindow(route)
        var best = search(route, position, headingDegrees, from, to)

        // If the best candidate sits on the edge of the window, the rider is
        // probably not where we last saw them - a tunnel, a long GPS outage, or
        // a phone that was asleep. Fall back to searching the whole route
        // rather than pinning them to the edge of a stale window.
        if (best != null &&
            (from > 0 || to < lastSegment) &&
            (best.index <= from || best.index >= to - 1) &&
            best.projection.distanceMeters > WINDOW_ESCAPE_METERS
        ) {
            best = search(route, position, headingDegrees, 0, lastSegment)
        }

        val result = best ?: return null
        lastSegmentIndex = result.index

        val a = route.points[result.index]
        val b = route.points[result.index + 1]
        return MatchResult(
            segmentIndex = result.index,
            alongSegmentMeters = result.projection.alongMeters,
            crossTrackMeters = result.projection.distanceMeters,
            distanceFromStart = route.distanceAt(result.index) + result.projection.alongMeters,
            snapped = result.projection.point,
            routeBearing = Geo.bearingDegrees(a, b),
        )
    }

    private class Candidate(val index: Int, val projection: Geo.Projection)

    /** Nearest segment in [from, to), with a penalty for going the wrong way. */
    private fun search(
        route: Route,
        position: GeoPoint,
        headingDegrees: Double?,
        from: Int,
        to: Int,
    ): Candidate? {
        var bestScore = Double.MAX_VALUE
        var best: Candidate? = null

        for (i in from until to) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val projection = Geo.projectOnSegment(position, a, b)
            var score = projection.distanceMeters
            if (headingDegrees != null && projection.distanceMeters > 1.0) {
                val segmentBearing = Geo.bearingDegrees(a, b)
                val delta = Geo.bearingDifference(segmentBearing, headingDegrees)
                if (delta > HEADING_TOLERANCE) {
                    // Wrong way down this segment: make it look further away.
                    score += (delta - HEADING_TOLERANCE) * HEADING_PENALTY_PER_DEG
                }
            }
            if (score < bestScore) {
                bestScore = score
                best = Candidate(i, projection)
            }
        }
        return best
    }

    /**
     * The slice of the polyline to search: a little behind the last match (the
     * rider may have stopped or rolled back) and a good way ahead (a fix can be
     * late, or the phone may have been asleep in a tunnel).
     */
    private fun searchWindow(route: Route): Pair<Int, Int> {
        val lastSegments = route.points.size - 1
        if (lastSegmentIndex <= 0) return 0 to lastSegments

        val anchor = route.distanceAt(lastSegmentIndex)
        var from = lastSegmentIndex
        while (from > 0 && anchor - route.distanceAt(from) < backwardWindowMeters) from--
        var to = lastSegmentIndex
        while (to < lastSegments && route.distanceAt(to) - anchor < forwardWindowMeters) to++
        return from to to
    }

    private companion object {
        const val HEADING_TOLERANCE = 60.0
        const val HEADING_PENALTY_PER_DEG = 1.5

        /**
         * Cross-track distance above which a match at the window edge is
         * treated as "we lost them" rather than "they are right there".
         */
        const val WINDOW_ESCAPE_METERS = 50.0
    }
}

/** True when the rider is far enough off the line to warrant a reroute. */
fun MatchResult.isOffRoute(thresholdMeters: Double): Boolean =
    abs(crossTrackMeters) > thresholdMeters
