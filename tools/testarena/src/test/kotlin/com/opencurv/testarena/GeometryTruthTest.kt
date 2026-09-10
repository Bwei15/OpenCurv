package com.opencurv.testarena

import com.opencurv.testarena.geometry.PathBuilder
import com.opencurv.testarena.geometry.Point
import com.opencurv.testarena.geometry.Primitive
import com.opencurv.testarena.geometry.metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Proves that the *sampled* OSM geometry (the points a routing engine would actually load)
 * really has the radius/length/turn arena_truth.json claims for it - not just that the
 * bookkeeping in [com.opencurv.testarena.geometry.metrics] agrees with itself. Every
 * assertion here recomputes a quantity from raw [Point]s with independent geometry (circumradius
 * of three points, chord-length summation) and compares it to the analytic value the generator
 * used to command that arc in the first place.
 *
 * Tolerances (documented in arena_truth.json's `meta.tolerances`, repeated here as constants
 * so a change to one is a deliberate, reviewable edit to both):
 *  - radius: 1% relative. A true circular arc's three sampled points have an *exact*
 *    circumradius (no discretisation error at all, regardless of point spacing) - the only
 *    error source is double-precision trig, which is far below 1%.
 *  - length / total turn: 3% relative. These are measured as a chord-length polyline sum
 *    over a true arc, so they slightly *underestimate* the true arc length; at the arena's
 *    tightest radius (15-30 m) with ~10 m point spacing the chord/arc gap is at most
 *    ~(spacing/radius)²/24 ≈ 1-2%, so 3% leaves comfortable margin.
 */
class GeometryTruthTest {

    private val radiusTolerance = 0.01
    private val lengthTolerance = 0.03

    @Test
    fun `every arc's sampled points reproduce its commanded radius and length`() {
        val (_, truth) = ArenaDefinition.build()
        val paths = ArenaDefinition.lastBuildPaths()
        assertTrue("expected at least one built path", paths.isNotEmpty())

        var arcsChecked = 0
        for ((routeId, path) in paths) {
            val chunks = segmentPointsByPrimitive(path)
            for ((primitive, points) in chunks) {
                if (primitive !is Primitive.Arc) continue
                arcsChecked++

                // Length: chord-sum over the sampled points vs. the exact analytic arc length.
                val chordLength = polylineLength(points)
                val expectedLength = primitive.lengthM
                assertRelativeClose(
                    "length of one ${primitive.radiusM}m/${primitive.sweepDeg}deg arc on $routeId",
                    expectedLength, chordLength, lengthTolerance,
                )

                // Radius: circumradius of three well-separated sampled points vs. the
                // commanded radius. Exact for a true circle, independent of point spacing.
                if (points.size >= 3) {
                    val a = points.first()
                    val b = points[points.size / 2]
                    val c = points.last()
                    val measuredRadius = circumradius(a, b, c)
                    assertRelativeClose(
                        "radius of one ${primitive.radiusM}m/${primitive.sweepDeg}deg arc on $routeId",
                        primitive.radiusM, measuredRadius, radiusTolerance,
                    )
                }
            }
        }
        assertTrue("expected to have checked several arcs across the arena", arcsChecked >= 10)

        // Sanity: arena_truth.json's own numbers must equal what the primitives say (this
        // catches a truth/geometry drift introduced in ArenaDefinition itself).
        for (el in truth.elements) {
            val path = paths[el.routeId] ?: continue
            val m = path.primitives.metrics()
            assertEquals("${el.routeId} curveCount", m.curveCount, el.curveCount)
            assertEquals("${el.routeId} sharpCornerCount", m.sharpCornerCount, el.sharpCornerCount)
            assertRelativeClose("${el.routeId} lengthM", m.lengthM, el.lengthM, 1e-9)
        }
    }

    @Test
    fun `hill pair has identical planimetric geometry and only differs in elevation`() {
        val (_, truth) = ArenaDefinition.build()
        val flat = truth.elements.first { it.routeId == "R_HILL_FLAT" }
        val climb = truth.elements.first { it.routeId == "R_HILL_CLIMB" }

        assertRelativeClose("hill pair lengthM", flat.lengthM, climb.lengthM, 1e-9)
        assertEquals(flat.curveCount, climb.curveCount)
        assertRelativeClose("hill pair minRadiusM", flat.minRadiusM!!, climb.minRadiusM!!, 1e-9)
        assertRelativeClose("hill pair meanRadiusM", flat.meanRadiusM!!, climb.meanRadiusM!!, 1e-9)
        assertRelativeClose("hill pair totalTurnDeg", flat.totalTurnDeg, climb.totalTurnDeg, 1e-9)

        assertEquals(0.0, flat.elevationGainM, 1e-9)
        assertEquals(350.0, climb.elevationGainM, 1e-6)
    }

    @Test
    fun `forest vs industrial pair has identical geometry and only differs in landuse`() {
        val (_, truth) = ArenaDefinition.build()
        val forest = truth.elements.first { it.routeId == "R_FOREST" }
        val industrial = truth.elements.first { it.routeId == "R_INDUSTRIAL" }

        assertRelativeClose("forest/industrial lengthM", forest.lengthM, industrial.lengthM, 1e-9)
        assertEquals(forest.curveCount, industrial.curveCount)
        assertRelativeClose("forest/industrial totalTurnDeg", forest.totalTurnDeg, industrial.totalTurnDeg, 1e-9)
        assertEquals("forest", forest.landuse)
        assertEquals("industrial", industrial.landuse)
    }

    @Test
    fun `R5_GRID has zero curveCount but a large totalTurnDeg from sharp corners`() {
        val (_, truth) = ArenaDefinition.build()
        val grid = truth.elements.first { it.routeId == "R5_GRID" }
        assertEquals(0, grid.curveCount)
        assertEquals(null, grid.minRadiusM)
        assertEquals(8, grid.sharpCornerCount)
        assertTrue("grid should have a large total turn despite zero real curves", grid.totalTurnDeg > 600.0)
    }

    // -- helpers -------------------------------------------------------------------------

    /** Splits a path's sampled points back up by which [Primitive] produced them, using each
     *  vertex's recorded cumulative distance - independent of PathBuilder's (private) point
     *  spacing, so this works for any path built with any spacing. */
    private fun segmentPointsByPrimitive(path: PathBuilder): List<Pair<Primitive, List<Point>>> {
        val vertices = path.vertices
        var vIdx = 0
        var cum = 0.0
        val result = mutableListOf<Pair<Primitive, List<Point>>>()
        for (primitive in path.primitives) {
            val points = mutableListOf(vertices[vIdx].point)
            val targetCum = cum + primitive.lengthM
            while (vIdx + 1 < vertices.size && vertices[vIdx + 1].distanceFromStartM <= targetCum + 1e-6) {
                vIdx++
                points += vertices[vIdx].point
            }
            result += primitive to points
            cum = targetCum
        }
        return result
    }

    private fun polylineLength(points: List<Point>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) total += points[i].distanceTo(points[i + 1])
        return total
    }

    /** Circumradius of the triangle a/b/c - exact for three points known to lie on a circle. */
    private fun circumradius(a: Point, b: Point, c: Point): Double {
        val sideA = b.distanceTo(c)
        val sideB = a.distanceTo(c)
        val sideC = a.distanceTo(b)
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0
        require(area > 1e-9) { "points are collinear, cannot compute circumradius" }
        return (sideA * sideB * sideC) / (4.0 * area)
    }

    private fun assertRelativeClose(label: String, expected: Double, actual: Double, relTolerance: Double) {
        val denom = if (abs(expected) > 1e-9) abs(expected) else 1.0
        val relError = abs(actual - expected) / denom
        assertTrue(
            "$label: expected ~$expected but measured $actual (relative error ${"%.4f".format(relError)}, tolerance $relTolerance)",
            relError <= relTolerance,
        )
    }
}
