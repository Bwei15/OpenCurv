package com.opencurv.testarena.harness

import com.opencurv.testarena.geometry.LatLon
import com.opencurv.testarena.geometry.Point
import com.opencurv.testarena.geometry.Projection
import com.opencurv.testarena.truth.ArenaTruth
import kotlin.math.atan2

/**
 * Scores one already-computed route against the arena. Deliberately engine-neutral: the only
 * input is a list of coordinates (see [RoutePoint]) - there is no notion of "BRouter" or
 * "GraphHopper" or a routing profile anywhere in this file. The harness answers exactly one
 * question: given where this route physically went, which arena elements did it drive on,
 * and does that satisfy the ground-truth expectations in arena_truth.json.
 */
object Evaluator {
    /** How far (metres) a route point may be from an arena way and still count as "on" it -
     *  generous enough to absorb real-world GPS/route-simplification noise, tight enough to
     *  not bleed between the arena's distinct alternatives (which are spaced well apart). */
    const val DEFAULT_SNAP_TOLERANCE_M = 30.0

    fun evaluate(
        routeLabel: String,
        route: List<RoutePoint>,
        arena: ArenaGraph,
        truth: ArenaTruth,
        snapToleranceM: Double = DEFAULT_SNAP_TOLERANCE_M,
    ): EvaluationReport {
        require(route.size >= 2) { "Eine Route braucht mindestens 2 Punkte, hatte ${route.size}" }
        val local = route.map { Projection.toLocal(LatLon(it.lat, it.lon)) }

        var totalLength = 0.0
        var unmatched = 0.0
        val matchedLengthByWay = HashMap<Long, Double>()
        val tagsByWay = HashMap<Long, Map<String, String>>()

        for (i in 0 until local.size - 1) {
            val a = local[i]
            val b = local[i + 1]
            val segLen = a.distanceTo(b)
            totalLength += segLen
            if (segLen < 1e-9) continue
            val mid = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
            val nearest = arena.nearestSegment(mid)
            if (nearest != null && nearest.second <= snapToleranceM) {
                val (seg, _) = nearest
                matchedLengthByWay[seg.wayId] = (matchedLengthByWay[seg.wayId] ?: 0.0) + segLen
                tagsByWay[seg.wayId] = seg.tags
            } else {
                unmatched += segLen
            }
        }

        val curvinessDegPerKm = if (totalLength > 0) totalTurnDeg(local) / (totalLength / 1000.0) else 0.0

        fun shareWhere(predicate: (Map<String, String>) -> Boolean): Double {
            val matched = matchedLengthByWay.entries.filter { (wayId, _) -> tagsByWay[wayId]?.let(predicate) == true }
                .sumOf { it.value }
            return if (totalLength > 0) matched / totalLength * 100.0 else 0.0
        }

        val townSharePct = shareWhere { it["landuse"] == "residential" || it["highway"] == "residential" }
        val gravelSharePct = shareWhere { it["surface"] == "gravel" }
        val motorwaySharePct = shareWhere { it["highway"] == "motorway" }

        // Map way id -> routeId using the truth file (each element lists its own way ids).
        val wayIdToRouteId = HashMap<Long, String>()
        val routeIdToName = HashMap<String, String>()
        for (el in truth.elements) {
            routeIdToName[el.routeId] = el.name
            for (w in el.wayIds) wayIdToRouteId[w] = el.routeId
        }
        val matchedLengthByRouteId = HashMap<String, Double>()
        for ((wayId, len) in matchedLengthByWay) {
            val routeId = wayIdToRouteId[wayId] ?: continue
            matchedLengthByRouteId[routeId] = (matchedLengthByRouteId[routeId] ?: 0.0) + len
        }

        val routeIdShares = matchedLengthByRouteId.map { (id, len) ->
            RouteIdShare(id, routeIdToName[id], len, if (totalLength > 0) len / totalLength else 0.0)
        }

        val expectationResults = truth.expectations.map { exp ->
            val preferShare = shareOf(exp.preferRouteIds, matchedLengthByRouteId, totalLength)
            val overShare = shareOf(exp.overRouteIds, matchedLengthByRouteId, totalLength)
            val status = when {
                exp.severity == "info" -> "info"
                exp.preferRouteIds.isEmpty() && exp.overRouteIds.isEmpty() -> "info"
                preferShare == 0.0 && overShare == 0.0 -> "not_applicable"
                preferShare > overShare -> "met"
                else -> "violated"
            }
            ExpectationResult(
                exp.id, exp.from, exp.to, exp.severity, status,
                exp.preferRouteIds, exp.overRouteIds, preferShare, overShare, exp.reason,
            )
        }

        val hardViolations = expectationResults.count { it.severity == "hard" && it.status == "violated" }
        val softViolations = expectationResults.count { it.severity == "soft" && it.status == "violated" }

        return EvaluationReport(
            routeFile = routeLabel,
            pointCount = route.size,
            totalLengthM = totalLength,
            unmatchedLengthM = unmatched,
            unmatchedSharePct = if (totalLength > 0) unmatched / totalLength * 100.0 else 0.0,
            curvinessDegPerKm = curvinessDegPerKm,
            townSharePct = townSharePct,
            gravelSharePct = gravelSharePct,
            motorwaySharePct = motorwaySharePct,
            routeIdShares = routeIdShares,
            expectationResults = expectationResults,
            hardViolations = hardViolations,
            softViolations = softViolations,
        )
    }

    private fun shareOf(ids: List<String>, matched: Map<String, Double>, total: Double): Double {
        if (ids.isEmpty() || total <= 0.0) return 0.0
        val best = ids.maxOf { matched[it] ?: 0.0 }
        return best / total
    }

    /** Sum of absolute heading changes between consecutive segments of the polyline, in
     *  degrees - the same "how much did the bars turn in total" quantity as
     *  [com.opencurv.testarena.geometry.PathMetrics.totalTurnDeg], just measured from a
     *  plain coordinate list instead of known analytic primitives. */
    private fun totalTurnDeg(points: List<Point>): Double {
        if (points.size < 3) return 0.0
        var total = 0.0
        var prevBearing: Double? = null
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a.distanceTo(b) < 1e-6) continue
            val bearing = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
            if (prevBearing != null) {
                var diff = bearing - prevBearing
                diff = ((diff + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                total += Math.abs(diff)
            }
            prevBearing = bearing
        }
        return total
    }
}
