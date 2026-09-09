package com.motoroute.data.model

import com.motoroute.domain.geo.Geo
import kotlin.math.abs
import kotlin.math.sign

/** How twisty a route reads, once the corners are taken out of it. */
enum class CurvinessRating { STRAIGHT, FLOWING, CURVY, TWISTY, EXTREME }

/**
 * Scores how twisty a road is.
 *
 * The score is heading change in degrees per kilometre - but only the heading
 * change that belongs to the *road*. That distinction is the whole point, and
 * the old version got it wrong: a short hop through a town is nothing but
 * right-angle corners, and summing them said "extreme" about a route with no
 * curve in it at all.
 *
 * Two things separate a corner from a curve, and both are used:
 *
 *  1. **A corner is where you change road.** The routing engine already knows
 *     where those are - it has to, to say "turn left" - so heading change
 *     within [JUNCTION_WINDOW_M] of a turn instruction is thrown away.
 *
 *  2. **A corner stands alone; a curve has company.** Geometry alone cannot
 *     tell the two apart - an alpine hairpin is *tighter* than a street
 *     corner, not wider - so a tight, short turn is judged by what is around
 *     it. A ninety-degree turn between two straight stretches is a corner. The
 *     same turn on a road that is bending the whole way is a switchback, which
 *     is exactly what this app exists to find.
 *
 * The second rule is what catches the corners the first one misses: a street
 * that turns ninety degrees with no side road to choose between gets no
 * instruction, and is still not a curve.
 *
 * The known cost: a single hairpin on an otherwise dead straight road reads as
 * a corner. That is the right way round to be wrong - it costs one bend on a
 * road that was not curvy anyway, where the alternative costs the honesty of
 * every rating in a town.
 */
object Curviness {

    /** Heading is measured over this much road at a time. */
    private const val STEP_M = 20.0

    /** Road either side of a turn instruction that does not count as curve. */
    private const val JUNCTION_WINDOW_M = 30.0

    /** Most a single step may contribute, in degrees. */
    private const val MAX_STEP_TURN = 120.0

    /** Below this a step is noise, and does not join or continue a run. */
    private const val SUSTAINED_DEGREES = 4.0

    /** A run turning at least this much, in this little road, is a candidate corner. */
    private const val CORNER_DEGREES = 65.0
    private const val CORNER_ARC_M = 60.0

    /** How far either side of a candidate corner counts as "around it". */
    private const val CONTEXT_M = 150.0

    /** Turning found around a candidate that makes it a switchback, not a corner. */
    private const val CONTEXT_DEGREES = 40.0

    /**
     * @param points the route geometry
     * @param junctionDistances distance from the start, in metres, of every
     *   point where the rider changes road - i.e. of every turn instruction.
     */
    fun score(points: List<GeoPoint>, junctionDistances: List<Double> = emptyList()): Double {
        if (points.size < 3) return 0.0

        // Resample to fixed steps first. Node spacing turns a straight road
        // into a fake zig-zag and spreads a hairpin over six small kinks;
        // neither survives being measured over a constant length of road.
        val bearings = ArrayList<Double>()
        val corners = ArrayList<Double>()
        var length = 0.0

        var anchor = points.first()
        var anchorDistance = 0.0
        var travelled = 0.0
        for (i in 1 until points.size) {
            travelled += Geo.distanceMeters(points[i - 1], points[i])
            val step = travelled - anchorDistance
            if (step < STEP_M && i != points.lastIndex) continue

            bearings += Geo.bearingDegrees(anchor, points[i])
            // The corner between this step and the previous one sits at the
            // anchor, which is where a junction would be recorded too.
            corners += anchorDistance
            length += step
            anchor = points[i]
            anchorDistance = travelled
        }
        if (length < 1.0 || bearings.size < 2) return 0.0

        val junctions = junctionDistances.sorted()
        val turns = DoubleArray(bearings.size - 1) { i ->
            if (nearJunction(junctions, corners[i + 1])) {
                0.0
            } else {
                Geo.normalizeDelta(bearings[i + 1] - bearings[i])
                    .coerceIn(-MAX_STEP_TURN, MAX_STEP_TURN)
            }
        }

        val corner = cornerSteps(turns)
        var total = 0.0
        for (i in turns.indices) if (!corner[i]) total += abs(turns[i])
        return total / (length / 1000.0)
    }

    /**
     * Marks the steps that belong to a corner rather than to a curve.
     *
     * Turning is first grouped into runs - stretches bending the same way -
     * because a corner smeared across two measurement steps is still one
     * corner. A run that turns hard within very little road is a candidate;
     * it is only forgiven if the road around it is bending too.
     */
    private fun cornerSteps(turns: DoubleArray): BooleanArray {
        val corner = BooleanArray(turns.size)
        val runs = ArrayList<IntRange>()

        var start = 0
        while (start < turns.size) {
            if (abs(turns[start]) < SUSTAINED_DEGREES) {
                start++
                continue
            }
            var end = start
            while (end + 1 < turns.size &&
                abs(turns[end + 1]) >= SUSTAINED_DEGREES &&
                sign(turns[end + 1]) == sign(turns[start])
            ) {
                end++
            }
            runs += start..end
            start = end + 1
        }

        for (run in runs) {
            var swept = 0.0
            for (i in run) swept += abs(turns[i])
            val arc = (run.last - run.first + 1) * STEP_M
            if (swept < CORNER_DEGREES || arc > CORNER_ARC_M) continue

            // Tight and short. Corner, unless the road around it is working
            // too - which is what a pass road looks like.
            val reach = (CONTEXT_M / STEP_M).toInt()
            var around = 0.0
            for (i in (run.first - reach)..(run.last + reach)) {
                if (i in run || i !in turns.indices) continue
                around += abs(turns[i])
            }
            if (around < CONTEXT_DEGREES) for (i in run) corner[i] = true
        }
        return corner
    }

    /** Convenience for a whole route: the instructions say where the junctions are. */
    fun score(route: Route): Double = score(
        route.points,
        route.instructions.filter { it.maneuver.isTurn }.map { it.distanceFromStart },
    )

    private fun nearJunction(sorted: List<Double>, distance: Double): Boolean {
        if (sorted.isEmpty()) return false
        // Sorted, so only the neighbours on either side can be in range.
        var low = 0
        var high = sorted.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val delta = sorted[mid] - distance
            when {
                delta > JUNCTION_WINDOW_M -> high = mid - 1
                delta < -JUNCTION_WINDOW_M -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    fun rating(score: Double): CurvinessRating = when {
        score < 40 -> CurvinessRating.STRAIGHT
        score < 90 -> CurvinessRating.FLOWING
        score < 160 -> CurvinessRating.CURVY
        score < 260 -> CurvinessRating.TWISTY
        else -> CurvinessRating.EXTREME
    }

    /** Untranslated name, for logs and tests. The HUD uses [rating] instead. */
    fun label(score: Double): String = rating(score).name.lowercase()
}
