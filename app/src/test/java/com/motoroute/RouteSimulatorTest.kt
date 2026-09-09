package com.motoroute

import com.motoroute.domain.RouteSimulator
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo ride is what makes the app testable without a motorcycle, so it has
 * to walk the route properly: on the line, forwards, with a heading that
 * matches the direction of travel, and it has to end.
 */
class RouteSimulatorTest {

    private val route = RouteFixtures.lShapedRoute()

    @Test
    fun `starts at the start and ends at the end`() {
        val simulator = RouteSimulator(route)

        val first = simulator.fixAtDistance(0.0)
        assertEquals(route.points.first().latitude, first.point.latitude, 1e-6)
        assertEquals(route.points.first().longitude, first.point.longitude, 1e-6)

        val last = simulator.fixAtDistance(simulator.totalMeters)
        assertTrue(Geo.distanceMeters(last.point, route.points.last()) < 1.0)
    }

    @Test
    fun `every simulated position sits on the route`() {
        val simulator = RouteSimulator(route)
        var distance = 0.0
        while (distance < simulator.totalMeters) {
            val fix = simulator.fixAtDistance(distance)
            val nearest = route.points.minOf { Geo.distanceMeters(it, fix.point) }
            assertTrue("off the route at $distance m by $nearest m", nearest < 12.0)
            distance += 37.0
        }
    }

    @Test
    fun `heading follows the route around the corner`() {
        val simulator = RouteSimulator(route)
        // The fixture runs 2 km north, then turns left and runs 2 km west.
        val north = simulator.fixAtDistance(500.0).headingDegrees
        val west = simulator.fixAtDistance(3000.0).headingDegrees

        assertTrue("expected northbound, got $north", Geo.bearingDifference(north, 0.0) < 5.0)
        assertTrue("expected westbound, got $west", Geo.bearingDifference(west, 270.0) < 5.0)
    }

    @Test
    fun `the demo finishes instead of running forever`() {
        val simulator = RouteSimulator(route)
        assertNotNull(simulator.fixAt(0))
        assertNotNull(simulator.fixAt(simulator.durationMillis - 1000))
        assertNull(simulator.fixAt(simulator.durationMillis + 5_000))
    }

    @Test
    fun `speed stays in a plausible motorcycle range`() {
        val slow = RouteSimulator(route, speedFactor = 0.001)
        val fast = RouteSimulator(route, speedFactor = 1000.0)
        assertTrue(slow.speedMps >= 8.0)
        assertTrue(fast.speedMps <= 45.0)
    }
}
