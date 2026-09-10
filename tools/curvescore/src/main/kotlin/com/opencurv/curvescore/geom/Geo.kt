package com.opencurv.curvescore.geom

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A point in a local, flat, metre-based plane. x = east, y = north.
 *
 * All curve analysis happens in this plane. Working in metres instead of
 * degrees is not a convenience - a radius, a gradient and a window length are
 * all lengths, and mixing them with angular coordinates is the classic source
 * of latitude-dependent bugs (a degree of longitude is 111 km at the equator
 * and 71 km in Munich).
 */
data class Pt(val x: Double, val y: Double) {
    fun distTo(o: Pt): Double = hypot(o.x - x, o.y - y)
}

/**
 * Equirectangular projection anchored at a reference latitude.
 *
 * Over the few kilometres that any single scoring window spans, the distortion
 * of this projection against the true ellipsoid is far below the noise in OSM
 * geometry itself. The reference latitude is taken from the data being scored
 * (the centre of its bounding box), so the cos(lat) scale is right for the
 * region; it is *not* recomputed per point, which keeps the mapping a fixed
 * affine transform and therefore exactly invertible and deterministic.
 */
class LocalPlane(val refLatDeg: Double, val refLonDeg: Double) {
    private val mPerDegLat = MET_PER_DEG_LAT
    private val mPerDegLon = MET_PER_DEG_LAT * cos(Math.toRadians(refLatDeg))

    fun toLocal(latDeg: Double, lonDeg: Double): Pt =
        Pt((lonDeg - refLonDeg) * mPerDegLon, (latDeg - refLatDeg) * mPerDegLat)

    fun toLatLon(p: Pt): DoubleArray =
        doubleArrayOf(refLatDeg + p.y / mPerDegLat, refLonDeg + p.x / mPerDegLon)

    companion object {
        /** Spherical mean earth radius; the sub-per-mille difference to WGS84 is irrelevant here. */
        const val EARTH_RADIUS_M = 6371008.8
        val MET_PER_DEG_LAT = Math.PI / 180.0 * EARTH_RADIUS_M
    }
}

/** Compass-style bearing of the vector a->b in radians (0 = north, clockwise positive). */
fun bearing(a: Pt, b: Pt): Double = atan2(b.x - a.x, b.y - a.y)

/** Wraps an angle difference into (-pi, pi]. */
fun wrapPi(a: Double): Double {
    var v = a
    while (v > Math.PI) v -= 2 * Math.PI
    while (v <= -Math.PI) v += 2 * Math.PI
    return v
}

/** Shortest distance from [p] to the segment a-b, in metres. */
fun distPointToSegment(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-12) return p.distTo(a)
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
    t = max(0.0, min(1.0, t))
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Standard even-odd ray casting. [ring] is a closed or open ring; the closing edge is implied. */
fun pointInRing(p: Pt, ring: List<Pt>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val yi = ring[i].y
        val yj = ring[j].y
        if ((yi > p.y) != (yj > p.y)) {
            val xInt = ring[i].x + (p.y - yi) / (yj - yi) * (ring[j].x - ring[i].x)
            if (p.x < xInt) inside = !inside
        }
        j = i
    }
    return inside
}

/** Cumulative arc length along a polyline; result has the same size as [pts], starting at 0. */
fun cumulativeLength(pts: List<Pt>): DoubleArray {
    val s = DoubleArray(pts.size)
    for (i in 1 until pts.size) s[i] = s[i - 1] + pts[i - 1].distTo(pts[i])
    return s
}

/** Total polyline length in metres. */
fun polylineLength(pts: List<Pt>): Double {
    var l = 0.0
    for (i in 1 until pts.size) l += pts[i - 1].distTo(pts[i])
    return l
}

/**
 * Douglas-Peucker simplification, returning the *indices* of the points kept.
 * Indices listed in [forced] always survive and split the recursion, so a
 * caller can pin the endpoints of the way it is actually scoring.
 *
 * ## Why this is the right pre-filter
 *
 * OSM geometry is sampled wildly unevenly and is not noise-free. A road traced
 * from aerial imagery can carry a node every 2 m with half a metre of lateral
 * scatter. Feed that to a per-vertex curvature estimate and it reports a
 * continuous string of 40 m-radius bends: the scorer would rate a dead-straight
 * digitised road as a serpentine, because a random walk really does curve.
 *
 * Douglas-Peucker removes exactly that and nothing else. It keeps a point only
 * if it lies more than [epsilonM] away from the line its neighbours describe -
 * so scatter smaller than the tolerance collapses to a straight line, while
 * every point that carries real shape survives.
 *
 * The tolerance is chosen against the *sagitta* of the curves that must not be
 * lost. A chord of length c on a curve of radius R bulges by about c^2/(8R). At
 * epsilon = 0.5 m a 24 m hairpin keeps a point every 9.8 m, a 180 m curve every
 * 27 m, a 900 m motorway sweeper every 60 m - and the chord/deflection relation
 * that recovers the radius is scale-free, so all three still measure correctly.
 * Half a metre is also roughly the positional accuracy of OSM road geometry, so
 * the filter discards what is below the data's own resolution and no more.
 */
fun douglasPeuckerIndices(pts: List<Pt>, epsilonM: Double, forced: Set<Int> = emptySet()): IntArray {
    if (pts.size <= 2) return IntArray(pts.size) { it }
    val keep = BooleanArray(pts.size)
    keep[0] = true
    keep[pts.size - 1] = true
    for (f in forced) if (f in pts.indices) keep[f] = true

    // Recurse between consecutive already-kept anchors.
    val anchors = pts.indices.filter { keep[it] }
    val stack = ArrayDeque<Pair<Int, Int>>()
    for (i in 0 until anchors.size - 1) stack.addLast(anchors[i] to anchors[i + 1])
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        var best = -1
        var bestD = epsilonM
        for (i in a + 1 until b) {
            val d = distPointToSegment(pts[i], pts[a], pts[b])
            if (d > bestD) { bestD = d; best = i }
        }
        if (best >= 0) {
            keep[best] = true
            stack.addLast(a to best)
            stack.addLast(best to b)
        }
    }
    var n = 0
    for (k in keep) if (k) n++
    val out = IntArray(n)
    var j = 0
    for (i in keep.indices) if (keep[i]) out[j++] = i
    return out
}

/**
 * Drops vertices that sit closer than [minStepM] to the previously kept one,
 * unless they carry a deflection of at least [keepDeflectionDeg].
 *
 * Runs *after* Douglas-Peucker as a floor on the measurement base: DP can leave
 * two points a metre apart where the shape genuinely demands it, and dividing a
 * deflection by a one-metre chord produces a nonsense radius. A vertex with a
 * sharp deflection is never dropped - that is where the information is.
 */
fun minStepIndices(pts: List<Pt>, idx: IntArray, minStepM: Double, keepDeflectionDeg: Double, forced: Set<Int>): IntArray {
    if (idx.size < 3) return idx
    val keepRad = Math.toRadians(keepDeflectionDeg)
    val out = ArrayList<Int>(idx.size)
    out.add(idx[0])
    for (k in 1 until idx.size - 1) {
        val i = idx[k]
        val last = pts[out[out.size - 1]]
        if (i in forced || last.distTo(pts[i]) >= minStepM) { out.add(i); continue }
        val defl = abs(wrapPi(bearing(pts[i], pts[idx[k + 1]]) - bearing(pts[idx[k - 1]], pts[i])))
        if (defl >= keepRad) out.add(i)
    }
    out.add(idx[idx.size - 1])
    return out.toIntArray()
}
