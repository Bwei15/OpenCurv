package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.RoundTripPlanner
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoundTripPlannerTest {

    private val start = GeoPoint(52.37, 9.74) // Hannover, roughly

    @Test
    fun `suggests exactly three points at the expected radius`() {
        val length = 120_000.0
        val expectedRadius = RoundTripPlanner.radiusFor(length)
        val points = RoundTripPlanner.suggestLoop(start, length, Random(1))

        assertEquals(3, points.size)
        points.forEach { p ->
            val distance = Geo.distanceMeters(start, p)
            assertEquals("point should sit on the circle", expectedRadius, distance, expectedRadius * 0.01)
        }
    }

    @Test
    fun `points are ordered 90 degrees apart starting from a random bearing`() {
        val points = RoundTripPlanner.suggestLoop(start, 150_000.0, Random(42))
        val bearings = points.map { Geo.bearingDegrees(start, it) }

        // Each point is +90 deg from the previous one, wrapping past 360.
        val stepAB = Geo.normalizeDelta(bearings[1] - bearings[0])
        val stepBC = Geo.normalizeDelta(bearings[2] - bearings[1])
        assertEquals(90.0, stepAB, 0.5)
        assertEquals(90.0, stepBC, 0.5)
    }

    @Test
    fun `a different seed gives a different starting bearing`() {
        val pointsA = RoundTripPlanner.suggestLoop(start, 120_000.0, Random(1))
        val pointsB = RoundTripPlanner.suggestLoop(start, 120_000.0, Random(2))

        assertTrue(Geo.distanceMeters(pointsA[0], pointsB[0]) > 100.0)
    }

    @Test
    fun `loop perimeter scales with the wanted length, radius formula included`() {
        // The loop is start -> p1 -> p2 -> p3 -> start: two radial legs (start-p1, p3-start) plus
        // two 90-degree chords (p1-p2, p2-p3), each chord being radius * sqrt(2). That fixed
        // shape - not a smooth circle - is why the total comes out well under the wanted length;
        // it is a first cut (see the class doc), not a claim that the ride is exactly that long.
        val length = 200_000.0
        val points = RoundTripPlanner.suggestLoop(start, length, Random(7))
        val perimeter = Geo.distanceMeters(start, points[0]) +
            Geo.distanceMeters(points[0], points[1]) +
            Geo.distanceMeters(points[1], points[2]) +
            Geo.distanceMeters(points[2], start)

        val radius = RoundTripPlanner.radiusFor(length)
        val expectedPerimeter = 2 * radius * (1 + Math.sqrt(2.0))
        assertEquals(expectedPerimeter, perimeter, expectedPerimeter * 0.02)
        // Still the same order of magnitude as the wanted length, not a wildly different scale.
        assertTrue(perimeter > length * 0.4 && perimeter < length)
    }

    @Test
    fun `nudging toward start halves the gap by the step size`() {
        val far = Geo.offset(start, 45.0, 50_000.0)
        val nudged = RoundTripPlanner.nudgeTowardStart(far, start, stepMeters = 2_000.0)

        val before = Geo.distanceMeters(far, start)
        val after = Geo.distanceMeters(nudged, start)
        assertEquals(before - 2_000.0, after, 5.0)
    }

    @Test
    fun `nudging never overshoots past start`() {
        val near = Geo.offset(start, 200.0, 500.0)
        val nudged = RoundTripPlanner.nudgeTowardStart(near, start, stepMeters = 2_000.0)

        assertEquals(start.latitude, nudged.latitude, 1e-9)
        assertEquals(start.longitude, nudged.longitude, 1e-9)
    }
}
