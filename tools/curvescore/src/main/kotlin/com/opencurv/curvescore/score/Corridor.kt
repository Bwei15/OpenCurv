package com.opencurv.curvescore.score

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.geom.bearing
import com.opencurv.curvescore.geom.wrapPi
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmWay
import kotlin.math.abs

/**
 * One point of the corridor: geometry, elevation, and the tags of the OSM node
 * it came from (only for nodes that carry any - traffic lights and the like).
 */
class CorridorPoint(
    val pt: Pt,
    val eleM: Double?,
    val nodeTags: Map<String, String>?,
)

/**
 * The way, plus as much of the road it continues into as the window needs.
 *
 * @param points        the whole corridor
 * @param wayFromIndex  index of the way's own first point inside [points]
 * @param wayToIndex    index of the way's own last point inside [points]
 */
class Corridor(
    val points: List<CorridorPoint>,
    val wayFromIndex: Int,
    val wayToIndex: Int,
)

/**
 * Builds scoring corridors: a way extended along its natural continuation.
 *
 * Why: a score computed on the way alone would depend on where a mapper split
 * the data. A 60 m way in the middle of a serpentine contains almost no
 * geometry of its own, and would come out as "straight". Following the road
 * across the split for one window length in each direction removes that
 * artefact entirely - the 60 m way inherits the serpentine.
 *
 * The continuation rule is deliberately conservative, because inventing a turn
 * would be worse than clipping a window:
 *  - through a node where exactly two routable way-ends meet, always continue
 *    (that is a plain data split of one road);
 *  - at a junction, continue only if exactly one candidate carries on within
 *    40 deg and every other candidate branches off by at least 70 deg;
 *  - otherwise stop.
 *
 * The consequence is worth stating plainly: because the corridor only ever
 * follows the *straight* continuation, it never manufactures a 90 deg corner at
 * a junction. Corners in the score are only ever corners that are really in
 * the geometry. The cost of actually turning off at a junction is the routing
 * engine's turn-cost model, not this score's job.
 */
class CorridorBuilder(
    private val data: OsmData,
    private val plane: LocalPlane,
    private val cfg: ScoreConfig,
) {
    /** node id -> indices into [roadWays] whose *end* touches that node */
    private val endpointIndex = HashMap<Long, MutableList<Int>>()
    val roadWays: List<OsmWay> = data.ways.filter { RoadTags.isRoutableRoad(it.tags) && it.nodeIds.size >= 2 }

    init {
        for ((i, w) in roadWays.withIndex()) {
            endpointIndex.getOrPut(w.nodeIds.first()) { ArrayList(2) }.add(i)
            endpointIndex.getOrPut(w.nodeIds.last()) { ArrayList(2) }.add(i)
        }
    }

    fun build(wayIndex: Int): Corridor? {
        val w = roadWays[wayIndex]
        val own = pointsOf(w) ?: return null
        if (own.size < 2) return null

        val before = extend(wayIndex, forward = false)
        val after = extend(wayIndex, forward = true)

        val all = ArrayList<CorridorPoint>(before.size + own.size + after.size)
        all.addAll(before)
        all.addAll(own)
        all.addAll(after)
        return Corridor(all, before.size, before.size + own.size - 1)
    }

    private fun pointsOf(w: OsmWay): List<CorridorPoint>? {
        val out = ArrayList<CorridorPoint>(w.nodeIds.size)
        for (id in w.nodeIds) {
            val n = data.nodes[id] ?: return null
            out.add(CorridorPoint(plane.toLocal(n.lat, n.lon), n.eleM, n.tags.ifEmpty { null }))
        }
        return out
    }

    /**
     * Walks the corridor away from [startWayIndex] until [ScoreConfig.contextM]
     * metres are collected or the continuation becomes ambiguous.
     *
     * @param forward true = past the way's last node; false = before its first
     * @return the extra points, already in corridor order
     */
    private fun extend(startWayIndex: Int, forward: Boolean): List<CorridorPoint> {
        val collected = ArrayList<CorridorPoint>()
        var curWay = startWayIndex
        var joinNode = if (forward) roadWays[curWay].nodeIds.last() else roadWays[curWay].nodeIds.first()
        var incomingBearing = bearingAtEnd(roadWays[curWay], forward) ?: return emptyList()
        var acc = 0.0
        val visited = HashSet<Int>()
        visited.add(startWayIndex)

        while (acc < cfg.contextM) {
            val next = pickContinuation(joinNode, curWay, incomingBearing, visited) ?: break
            val nw = roadWays[next.wayIndex]
            val pts = pointsOf(nw) ?: break
            val ordered = if (next.enterAtStart) pts else pts.asReversed()
            // Drop the shared join node itself, it is already in the corridor.
            val body = ordered.subList(1, ordered.size)
            if (body.isEmpty()) break
            var added = 0
            var prev = ordered[0].pt
            val taken = ArrayList<CorridorPoint>(body.size)
            for (p in body) {
                acc += prev.distTo(p.pt)
                taken.add(p)
                added++
                prev = p.pt
                if (acc >= cfg.contextM) break
            }
            if (forward) collected.addAll(taken) else collected.addAll(0, taken.asReversed())
            visited.add(next.wayIndex)
            if (added < body.size) break
            curWay = next.wayIndex
            joinNode = if (next.enterAtStart) nw.nodeIds.last() else nw.nodeIds.first()
            incomingBearing = bearingAtEnd(nw, next.enterAtStart) ?: break
        }
        return collected
    }

    private class Continuation(val wayIndex: Int, val enterAtStart: Boolean)

    private fun pickContinuation(
        node: Long,
        fromWay: Int,
        incomingBearing: Double,
        visited: Set<Int>,
    ): Continuation? {
        val candidates = endpointIndex[node].orEmpty().filter { it != fromWay && it !in visited }
        if (candidates.isEmpty()) return null
        data class Cand(val idx: Int, val atStart: Boolean, val deflection: Double)
        val scored = ArrayList<Cand>(candidates.size)
        for (idx in candidates) {
            val w = roadWays[idx]
            val atStart = w.nodeIds.first() == node
            val b = outgoingBearing(w, atStart) ?: continue
            scored.add(Cand(idx, atStart, abs(wrapPi(b - incomingBearing))))
        }
        if (scored.isEmpty()) return null
        scored.sortBy { it.deflection }
        val best = scored[0]
        if (scored.size == 1) {
            // Exactly two way-ends meet here: a plain data split of one road.
            // Follow it even round a real bend - that is not a junction.
            return if (best.deflection <= Math.toRadians(120.0)) Continuation(best.idx, best.atStart) else null
        }
        val second = scored[1]
        val straightEnough = best.deflection <= Math.toRadians(40.0)
        val othersBranchOff = second.deflection >= Math.toRadians(70.0)
        return if (straightEnough && othersBranchOff) Continuation(best.idx, best.atStart) else null
    }

    /** Bearing of travel arriving at the way's end (forward = leaving via the last node). */
    private fun bearingAtEnd(w: OsmWay, forward: Boolean): Double? {
        val n = w.nodeIds.size
        if (n < 2) return null
        val (aId, bId) = if (forward) w.nodeIds[n - 2] to w.nodeIds[n - 1] else w.nodeIds[1] to w.nodeIds[0]
        val a = data.nodes[aId] ?: return null
        val b = data.nodes[bId] ?: return null
        return bearing(plane.toLocal(a.lat, a.lon), plane.toLocal(b.lat, b.lon))
    }

    /** Bearing of travel leaving [node] into [w] (atStart = entering via its first node). */
    private fun outgoingBearing(w: OsmWay, atStart: Boolean): Double? {
        val n = w.nodeIds.size
        if (n < 2) return null
        val (aId, bId) = if (atStart) w.nodeIds[0] to w.nodeIds[1] else w.nodeIds[n - 1] to w.nodeIds[n - 2]
        val a = data.nodes[aId] ?: return null
        val b = data.nodes[bId] ?: return null
        return bearing(plane.toLocal(a.lat, a.lon), plane.toLocal(b.lat, b.lon))
    }
}
