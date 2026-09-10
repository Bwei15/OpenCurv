package com.opencurv.testarena.geometry

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

enum class Turn { LEFT, RIGHT }

/** One analytically-known geometric primitive that made up a path. */
sealed class Primitive {
    abstract val lengthM: Double

    /** A straight run. [isConnector] marks a plain closing segment into a shared endpoint node
     *  (e.g. the final approach into OMEGA) - it is not counted as a "designed" curve feature. */
    data class Straight(override val lengthM: Double, val isConnector: Boolean = false) : Primitive()

    /** A true circular arc of exact, constant radius - this is what all "curves" in the
     *  arena are built from, so the exact radius/sweep are known analytically, not estimated. */
    data class Arc(val radiusM: Double, val sweepDeg: Double, val direction: Turn) : Primitive() {
        override val lengthM: Double get() = radiusM * Math.toRadians(sweepDeg)
    }

    /**
     * An instantaneous, zero-radius direction change at a single node - e.g. a 90° street
     * corner in a residential grid. This is deliberately NOT an [Arc]: it has no navigable
     * radius (effectively radius 0, a full stop-and-turn), so it must never be counted
     * towards [curveCount]/[minRadiusM]/[meanRadiusM] the way a real curve is. It still
     * counts towards total direction change, which is exactly the trap: a grid of corners
     * can rack up a large total turn angle while being the opposite of a fun curve.
     */
    data class Corner(val turnDeg: Double) : Primitive() {
        override val lengthM: Double get() = 0.0
    }
}

/**
 * Builds a road centre-line as a sequence of exact straight segments and circular arcs,
 * sampling it at a configurable point spacing (default matches real-world OSM way node
 * spacing, ~18 m) while recording the exact analytic [Primitive]s used - these primitives
 * are the single source of truth for both the generated geometry AND arena_truth.json, so
 * the two can never drift apart.
 */
class PathBuilder(
    startPoint: Point,
    startHeadingDeg: Double,
    private val pointSpacingM: Double = 18.0,
    startElevationM: Double? = null,
) {
    private var currentPos = startPoint
    private var currentHeadingDeg = normalizeDeg(startHeadingDeg)
    private var cumulativeDistanceM = 0.0
    // null (the default) means "no ele tag on this route" - only routes that opt in via
    // setElevationProfile() get one, so the flat majority of the arena stays free of noise.
    private var currentElevationM: Double? = startElevationM

    private val _vertices = mutableListOf(Vertex(startPoint, currentHeadingDeg, 0.0, startElevationM))
    private val _primitives = mutableListOf<Primitive>()

    val vertices: List<Vertex> get() = _vertices
    val primitives: List<Primitive> get() = _primitives
    val position: Point get() = currentPos
    val headingDeg: Double get() = currentHeadingDeg

    /** A straight run. If [headingOverrideDeg] is given, the road bends to that heading
     *  instantly at this node (an ordinary sharp corner - no separate geometry needed for
     *  that, exactly how a real OSM way vertex works) before running straight. Pass
     *  [isSharpCorner] = true when that bend is a *deliberate* zero-radius corner (a grid
     *  intersection, a stairstep jog) so it is recorded as a [Primitive.Corner]; leave it
     *  false for ordinary connector bends, which should not be flagged as fake curves. */
    fun straight(
        lengthM: Double,
        headingOverrideDeg: Double? = null,
        isConnector: Boolean = false,
        isSharpCorner: Boolean = false,
    ): PathBuilder {
        require(lengthM > 0.0) { "straight() length must be positive, was $lengthM" }
        if (headingOverrideDeg != null) {
            val newHeading = normalizeDeg(headingOverrideDeg)
            if (isSharpCorner) {
                var turn = newHeading - currentHeadingDeg
                turn = ((turn + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                if (Math.abs(turn) > 1e-9) _primitives += Primitive.Corner(turn)
            }
            currentHeadingDeg = newHeading
        }
        val unit = headingToUnitVector(currentHeadingDeg)
        val n = sampleCount(lengthM)
        for (i in 1..n) {
            val d = lengthM * i / n
            val p = Point(currentPos.x + unit.x * d, currentPos.y + unit.y * d)
            addVertex(p, currentHeadingDeg)
        }
        currentPos = Point(currentPos.x + unit.x * lengthM, currentPos.y + unit.y * lengthM)
        _primitives += Primitive.Straight(lengthM, isConnector)
        return this
    }

    /** A true circular arc: constant radius, exact sweep angle, turning [direction]. */
    fun arc(radiusM: Double, sweepDeg: Double, direction: Turn): PathBuilder {
        require(radiusM > 0.0) { "arc() radius must be positive, was $radiusM" }
        require(sweepDeg > 0.0) { "arc() sweep must be positive, was $sweepDeg" }
        val s = if (direction == Turn.RIGHT) 1.0 else -1.0
        val h0 = currentHeadingDeg
        val center = Point(
            currentPos.x + radiusM * headingToUnitVector(h0 + s * 90.0).x,
            currentPos.y + radiusM * headingToUnitVector(h0 + s * 90.0).y,
        )
        val arcLengthM = radiusM * Math.toRadians(sweepDeg)
        // A tight-radius arc needs denser points than the path's general spacing would give
        // it, or a short/sharp curve could be sampled as a single chord (a straight line!).
        // Real OSM data shows exactly this pattern - hairpins carry closer nodes than the
        // straights between them - so this is realistic, not just a numerical workaround.
        val effectiveSpacing = minOf(pointSpacingM, radiusM * 0.5)
        val n = max(1, (arcLengthM / effectiveSpacing).roundToInt())
        for (i in 1..n) {
            val alphaDeg = sweepDeg * i / n
            val pointHeading = h0 - s * 90.0 + s * alphaDeg
            val unit = headingToUnitVector(pointHeading)
            val p = Point(center.x + radiusM * unit.x, center.y + radiusM * unit.y)
            addVertex(p, normalizeDeg(h0 + s * alphaDeg))
        }
        currentPos = _vertices.last().point
        currentHeadingDeg = normalizeDeg(h0 + s * sweepDeg)
        _primitives += Primitive.Arc(radiusM, sweepDeg, direction)
        return this
    }

    /**
     * Closes the path with one final straight run directly into [target] (e.g. the shared
     * OMEGA node), regardless of current heading - modelling the ordinary straight approach
     * a road makes into a junction. Exact by construction: the run's length and heading are
     * derived from the current position and the target, so the path lands on [target].
     */
    fun connectTo(target: Point): PathBuilder {
        val dx = target.x - currentPos.x
        val dy = target.y - currentPos.y
        val dist = currentPos.distanceTo(target)
        if (dist < 1e-6) return this
        val bearingDeg = Math.toDegrees(atan2(dx, dy))
        straight(dist, headingOverrideDeg = bearingDeg, isConnector = true)
        // Snap out any floating point drift: the connector is defined to land exactly on
        // the shared target node.
        _vertices[_vertices.lastIndex] = _vertices.last().copy(point = target)
        currentPos = target
        return this
    }

    /** Applies a linear elevation ramp (metres of climb per metre travelled) from the
     *  current cumulative distance onward, only to vertices added AFTER this call returns -
     *  call this once before building the climbing section. Simpler: apply to the whole
     *  path so far plus what follows, given explicitly per profile in ArenaDefinition. */
    fun setElevationProfile(eleAt: (distanceFromStartM: Double) -> Double): PathBuilder {
        for (i in _vertices.indices) {
            val v = _vertices[i]
            _vertices[i] = v.copy(elevationM = eleAt(v.distanceFromStartM))
        }
        currentElevationM = _vertices.last().elevationM ?: currentElevationM
        return this
    }

    private fun addVertex(p: Point, headingDeg: Double) {
        cumulativeDistanceM += _vertices.last().point.distanceTo(p)
        _vertices += Vertex(p, normalizeDeg(headingDeg), cumulativeDistanceM, currentElevationM)
    }

    private fun sampleCount(lengthM: Double): Int = max(1, (lengthM / pointSpacingM).roundToInt())

    companion object {
        fun normalizeDeg(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0) d += 360.0
            return d
        }
    }
}

/** Analytic metrics derived straight from a builder's [Primitive] list - see arena_truth.json. */
data class PathMetrics(
    val lengthM: Double,
    val curveCount: Int,
    val minRadiusM: Double?,
    val meanRadiusM: Double?,
    /** Zero-radius direction changes (grid corners, stairstep jogs) - see [Primitive.Corner]. */
    val sharpCornerCount: Int,
    /** Sum of |sweep| over real arcs plus |turn| over sharp corners: how much the bars had
     *  to turn in total, regardless of whether that turning was a rideable curve or not. */
    val totalTurnDeg: Double,
)

fun List<Primitive>.metrics(): PathMetrics {
    val arcs = filterIsInstance<Primitive.Arc>()
    val corners = filterIsInstance<Primitive.Corner>()
    val length = sumOf { it.lengthM }
    val radii = arcs.map { it.radiusM }
    return PathMetrics(
        lengthM = length,
        curveCount = arcs.size,
        minRadiusM = radii.minOrNull(),
        meanRadiusM = if (radii.isEmpty()) null else radii.average(),
        sharpCornerCount = corners.size,
        totalTurnDeg = arcs.sumOf { it.sweepDeg } + corners.sumOf { Math.abs(it.turnDeg) },
    )
}

/** Total climb / descent (metres) implied by the `ele` values on consecutive vertices. */
fun List<Vertex>.elevationGainLoss(): Pair<Double, Double> {
    var gain = 0.0
    var loss = 0.0
    for (i in 1 until size) {
        val a = this[i - 1].elevationM
        val b = this[i].elevationM
        if (a != null && b != null) {
            val d = b - a
            if (d > 0) gain += d else loss += -d
        }
    }
    return gain to loss
}
