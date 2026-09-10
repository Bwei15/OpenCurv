package com.opencurv.curvescore.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One interior vertex of a polyline, with everything the score needs to know
 * about the bend that happens there.
 *
 * @param index         index into the (decimated) point list
 * @param sM            arc length from the start of the polyline, in metres
 * @param deflectionRad signed direction change at this vertex (positive = left/CCW)
 * @param baseM         the measurement base: the mean of the two adjacent
 *                      chords. See [analyseTurns] for why the mean.
 * @param radiusM       inferred local radius; [Double.POSITIVE_INFINITY] for a
 *                      straight vertex, 0.0 for a vertex classified as a corner
 * @param isCorner      true if this is a sharp, geometrically radius-less corner
 *                      (a junction-style kink), not a drivable curve
 */
data class TurnVertex(
    val index: Int,
    val sM: Double,
    val deflectionRad: Double,
    val baseM: Double,
    val radiusM: Double,
    val isCorner: Boolean,
)

/**
 * A run of consecutive vertices that bend the same way - one "curve" or one
 * "corner" as a rider would name it.
 *
 * @param startS   arc length where the bend starts
 * @param endS     arc length where the bend ends
 * @param turnRad  signed total direction change of the whole event
 * @param radiusM  representative radius (0.0 for a corner)
 * @param isCorner true if this event is a corner rather than a curve
 */
data class TurnEvent(
    val startS: Double,
    val endS: Double,
    val turnRad: Double,
    val radiusM: Double,
    val isCorner: Boolean,
    val vertexCount: Int,
) {
    val sign: Int get() = if (turnRad >= 0) 1 else -1
    val absTurnRad: Double get() = abs(turnRad)
    val midS: Double get() = (startS + endS) / 2.0
}

/** Result of the pure-geometry stage: everything downstream reads only this. */
class TurnAnalysis(
    val points: List<Pt>,
    val cumS: DoubleArray,
    val vertices: List<TurnVertex>,
    val events: List<TurnEvent>,
) {
    val lengthM: Double get() = if (cumS.isEmpty()) 0.0 else cumS[cumS.size - 1]
}

/**
 * Turns a polyline into per-vertex curvature plus grouped turn events.
 *
 * ## The measurement base
 *
 * The local radius at a vertex follows from the chord/deflection relation of a
 * circle: a circle of radius R sampled with chord c turns by
 * `delta = 2*asin(c / 2R)` at every sample, hence `R = c / (2*sin(delta/2))`.
 * That inversion is exact for a circle and needs no three-point circumcircle.
 *
 * When the two chords meeting at a vertex differ in length, the deflection is
 * the sum of their half-angles, `delta = (c_in + c_out) / 2R`, so the base is
 * their mean - but **only for chords that are part of the same bend**. Where a
 * curve joins a long straight, averaging in the straight's chord smears a real
 * 33 m curve into a 147 m one; where Douglas-Peucker leaves uneven spacing
 * inside a curve, taking the shorter chord instead reports a 400 m curve as
 * 267 m. Neither the mean nor the minimum is right on its own.
 *
 * So the base is decided *after* the vertices have been grouped into events:
 * a chord counts towards the base only if the vertex at its far end belongs to
 * the same bend. Both sides in the bend -> their mean; one side -> that side;
 * an isolated single-vertex bend -> the shorter of the two, conservatively.
 *
 * ## Why a corner is not a curve - the R5_GRID problem
 *
 * A 90 deg junction kink and a 15 m hairpin apex produce the *same* single-vertex
 * geometry when they happen to be sampled at the same node spacing. No local
 * three-point measure can separate them, which is exactly why a naive
 * circumcircle scorer walks into the testarena's `R5_GRID` trap: 717 deg of total
 * direction change through a housing estate looks like the twistiest road on
 * the map.
 *
 * What *does* separate them is **persistence**: a real curve bends over several
 * consecutive vertices, because a road built to a radius has to be mapped with
 * more than one node to stay on that radius. A junction corner is a single
 * isolated jump between two straights. So a vertex is a **corner** when it
 * turns by at least [cornerDeg] *and* neither neighbour carries at least
 * [cornerPersistenceShare] of that turn onwards - radius 0, no curve value,
 * and it feeds the corner penalty instead.
 *
 * The honest limitation, stated up front: a genuine hairpin that some mapper
 * drew with a single node is geometrically indistinguishable from a corner and
 * will be misread as one. That is a data-quality limit, not an algorithmic one -
 * and OSM mappers do map real hairpins with several nodes.
 *
 * @param noiseDeg  deflections below this are treated as digitising noise and
 *                  break a run without starting one
 * @param minEventDeg an event whose total turn stays below this is not a bend
 * @param cornerDeg   a turn of at least this much at one vertex may be a corner
 * @param cornerPersistenceShare how much of that turn a neighbouring vertex must
 *                  carry on for the bend to count as a curve rather than a corner
 */
fun analyseTurns(
    pts: List<Pt>,
    noiseDeg: Double = 1.5,
    minEventDeg: Double = 4.0,
    cornerDeg: Double = 40.0,
    cornerPersistenceShare: Double = 0.35,
    curveBreakM: Double = 70.0,
): TurnAnalysis {
    val cumS = cumulativeLength(pts)
    if (pts.size < 3) return TurnAnalysis(pts, cumS, emptyList(), emptyList())

    val n = pts.size
    val chord = DoubleArray(n - 1)
    val bear = DoubleArray(n - 1)
    for (i in 0 until n - 1) {
        chord[i] = pts[i].distTo(pts[i + 1])
        bear[i] = bearing(pts[i], pts[i + 1])
    }

    // --- 1. per-vertex deflection --------------------------------------------
    val defl = DoubleArray(n - 2)
    for (i in 1 until n - 1) defl[i - 1] = wrapPi(bear[i] - bear[i - 1])

    // --- 2. corner classification --------------------------------------------
    // A vertex is a corner when it turns hard *and* its neighbours do not carry
    // the turn on. The neighbour share is measured against the vertex's own
    // deflection: a uniformly sampled arc gives 100 % in its interior and 50 %
    // where it meets a straight, a junction kink gives ~0 %. The threshold sits
    // between the two with room for uneven sampling.
    //
    // Testing the neighbours directly, rather than asking whether the vertex
    // ended up alone in a grouped run, matters: a single 2 deg digitising kink
    // next to a right-angle junction would otherwise merge with it and turn the
    // junction into a flattering 70 m "curve".
    val cornerRad = Math.toRadians(cornerDeg)
    val isCorner = BooleanArray(defl.size)
    for (k in defl.indices) {
        val d = defl[k]
        if (abs(d) < cornerRad) continue
        val sign = if (d >= 0) 1.0 else -1.0
        fun share(j: Int): Double {
            if (j < 0 || j >= defl.size) return 0.0
            val nd = defl[j]
            if (nd * sign <= 0.0) return 0.0
            return abs(nd) / abs(d)
        }
        if (max(share(k - 1), share(k + 1)) < cornerPersistenceShare) isCorner[k] = true
    }

    // --- 3. group the remaining vertices into curve events --------------------
    val noiseRad = Math.toRadians(noiseDeg)
    val minEventRad = Math.toRadians(minEventDeg)

    class Run(val from: Int, var to: Int, var sum: Double)

    val runs = ArrayList<Run>()
    var cur: Run? = null
    var lastK = -1
    for (k in defl.indices) {
        if (isCorner[k]) { cur = null; continue }   // a corner breaks the run
        val d = defl[k]
        if (abs(d) < noiseRad) { cur = null; continue }
        // A straight between two bends makes them two bends. After
        // Douglas-Peucker the collinear points of that straight are gone, so
        // the only trace it leaves is a long chord between two deflecting
        // vertices - without this check two 28 deg curves 170 m apart would merge
        // into one 56 deg curve. 70 m is about three seconds at country-road pace,
        // and longer than the node spacing DP leaves on any curve up to ~900 m
        // radius, so it never splits a real curve.
        if (cur != null && lastK >= 0 && cumS[k + 1] - cumS[lastK + 1] > curveBreakM) cur = null
        lastK = k
        val s = if (d >= 0) 1 else -1
        val c = cur
        if (c != null && (if (c.sum >= 0) 1 else -1) == s) {
            c.to = k; c.sum += d
        } else {
            val r = Run(k, k, d)
            runs.add(r); cur = r
        }
    }

    // --- 4. measurement base and radius, now that the bends are known ---------
    val runOf = IntArray(defl.size) { -1 }
    for ((ri, r) in runs.withIndex()) for (k in r.from..r.to) runOf[k] = ri
    val raw = ArrayList<TurnVertex>(defl.size)
    for (k in defl.indices) {
        val i = k + 1                       // index into pts
        val cIn = chord[i - 1]
        val cOut = chord[i]
        val ri = runOf[k]
        val prevInRun = ri >= 0 && k > runs[ri].from
        val nextInRun = ri >= 0 && k < runs[ri].to
        val base = when {
            prevInRun && nextInRun -> (cIn + cOut) / 2.0
            prevInRun -> cIn
            nextInRun -> cOut
            else -> min(cIn, cOut)
        }
        val a = abs(defl[k])
        val r = when {
            isCorner[k] -> 0.0
            a < 1e-9 || base < 1e-9 -> Double.POSITIVE_INFINITY
            else -> base / (2.0 * kotlin.math.sin(a / 2.0))
        }
        raw.add(TurnVertex(i, cumS[i], defl[k], base, r, isCorner[k]))
    }

    // --- 5. events: one per curve run, one per corner -------------------------
    val events = ArrayList<TurnEvent>(runs.size + 4)
    for (r in runs) {
        if (abs(r.sum) < minEventRad) continue
        // Representative radius of the event: the radius at its *tightest*
        // vertex.
        //
        // Not the mean, and not arc-length-over-angle. Both are pulled upwards
        // by the vertices where the bend joins the straight: such a vertex
        // carries only half a step of the turn, so it reports about twice the
        // true radius, and averaging it in inflates a 15 m hairpin to 22 m.
        // Because that error is always an *over*estimate, the minimum simply
        // ignores those vertices - no threshold needed.
        //
        // The minimum is also the semantically right answer. What a rider means
        // by "a 40 m corner", and what decides the speed that can be carried
        // through it, is the tightest point - not the average of entry, apex
        // and exit. It is the same quantity the testarena publishes as
        // `minRadiusM`.
        var rad = Double.POSITIVE_INFINITY
        for (k in r.from..r.to) if (raw[k].radiusM < rad) rad = raw[k].radiusM
        events.add(
            TurnEvent(raw[r.from].sM, raw[r.to].sM, r.sum, rad, false, r.to - r.from + 1)
        )
    }
    for (k in defl.indices) {
        if (!isCorner[k]) continue
        events.add(TurnEvent(raw[k].sM, raw[k].sM, defl[k], 0.0, true, 1))
    }
    events.sortBy { it.startS }

    return TurnAnalysis(pts, cumS, raw, events)
}
