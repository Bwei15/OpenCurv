package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.MapMatcher
import com.motoroute.domain.geo.Geo
import com.motoroute.domain.isOffRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMatcherTest {

    private val route = RouteFixtures.lShapedRoute()

    @Test
    fun `a position on the line matches with no cross track error`() {
        val matcher = MapMatcher()
        val onRoute = route.points[30]
        val match = matcher.match(route, onRoute, headingDegrees = 0.0)
        assertNotNull(match)
        assertEquals(0.0, match!!.crossTrackMeters, 1.0)
        assertEquals(route.distanceAt(30), match.distanceFromStart, 2.0)
    }

    @Test
    fun `cross track error equals the sideways offset`() {
        val matcher = MapMatcher()
        val beside = Geo.offset(route.points[30], 90.0, 20.0)
        val match = matcher.match(route, beside, headingDegrees = 0.0)!!
        assertEquals(20.0, match.crossTrackMeters, 1.5)
        assertFalse(match.isOffRoute(35.0))
    }

    @Test
    fun `a big detour reads as off route`() {
        val matcher = MapMatcher()
        val away = Geo.offset(route.points[30], 90.0, 90.0)
        val match = matcher.match(route, away, headingDegrees = 0.0)!!
        assertTrue(match.isOffRoute(35.0))
    }

    @Test
    fun `progress along the route is monotonic while riding it`() {
        val matcher = MapMatcher()
        var previous = -1.0
        for (i in route.points.indices step 3) {
            val heading = if (i < 100) 0.0 else 270.0
            val match = matcher.match(route, route.points[i], heading)!!
            assertTrue(
                "went backwards at $i: $previous -> ${match.distanceFromStart}",
                match.distanceFromStart >= previous - 1.0,
            )
            previous = match.distanceFromStart
        }
    }

    /**
     * The classic map-matching failure: a hairpin brings the return leg within
     * a few metres of the outbound one, and a naive nearest-segment match jumps
     * the rider forward by a kilometre.
     */
    @Test
    fun `a hairpin does not teleport the match onto the return leg`() {
        val points = ArrayList<GeoPoint>()
        val origin = GeoPoint(47.5, 11.5)
        for (i in 0..60) points += Geo.offset(origin, 0.0, i * 20.0)
        val top = points.last()
        for (i in 1..60) {
            points += Geo.offset(Geo.offset(top, 180.0, i * 20.0), 90.0, 12.0)
        }
        val route = com.motoroute.data.model.Route(points, emptyList(), "test", 100)

        val matcher = MapMatcher()
        // Ride up the outbound leg first so the matcher has a window.
        for (i in 0..30) matcher.match(route, points[i], 0.0)

        val match = matcher.match(route, points[31], 0.0)!!
        assertTrue("matched segment ${match.segmentIndex}", match.segmentIndex < 60)
    }

    @Test
    fun `heading disagreement pushes the match off the wrong carriageway`() {
        val matcher = MapMatcher()
        // Standing on the northbound leg but travelling south: the match should
        // still land on the geometry, and the cross-track stays small.
        val match = matcher.match(route, route.points[40], headingDegrees = 180.0)
        assertNotNull(match)
        assertTrue(match!!.crossTrackMeters < 50.0)
    }
}
