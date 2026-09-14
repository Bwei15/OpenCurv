package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.RideItinerary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideItineraryTest {

    /** A straight run north, one point every ~111 m, about 5.5 km long. */
    private fun straightRoute(points: Int = 50, seconds: Int = 300): Route {
        val list = (0 until points).map { GeoPoint(52.0 + it * 0.001, 9.7) }
        return Route(
            points = list,
            instructions = emptyList(),
            profileName = "test",
            estimatedSeconds = seconds,
        )
    }

    @Test
    fun `a stop is projected onto the nearest route point`() {
        val route = straightRoute()
        // 30 m east of route point 20 - a waypoint never sits exactly on the
        // node BRouter snapped it to.
        val offset = GeoPoint(route.points[20].latitude, route.points[20].longitude + 0.0004)
        assertEquals(20, RideItinerary.nearestPointIndex(route, offset))
    }

    @Test
    fun `distance ahead counts from where the rider is, not from the start`() {
        val route = straightRoute()
        val stop = route.points[30]
        val travelled = route.distanceAt(10)
        val ahead = RideItinerary.stopsAhead(
            route = route,
            viaPoints = listOf(stop),
            travelledMeters = travelled,
            nowMillis = 1_000_000L,
            speedMps = 25.0,
        ).single()
        assertEquals(route.distanceAt(30) - travelled, ahead.distanceAheadMeters, 1.0)
        assertFalse(ahead.isPassed)
    }

    @Test
    fun `a stop already behind the rider is marked passed and gets no arrival time`() {
        val route = straightRoute()
        val passed = RideItinerary.stopsAhead(
            route = route,
            viaPoints = listOf(route.points[5]),
            travelledMeters = route.distanceAt(20),
            nowMillis = 1_000_000L,
            speedMps = 25.0,
        ).single()
        assertTrue(passed.isPassed)
        assertEquals(0L, passed.etaEpochMillis)
    }

    @Test
    fun `arrival is now plus distance over speed`() {
        val route = straightRoute()
        val now = 1_700_000_000_000L
        val stop = RideItinerary.stopsAhead(
            route = route,
            viaPoints = listOf(route.points[20]),
            travelledMeters = 0.0,
            nowMillis = now,
            speedMps = 20.0,
        ).single()
        val expectedSeconds = stop.distanceAheadMeters / 20.0
        assertEquals(
            (now + (expectedSeconds * 1000).toLong()).toDouble(),
            stop.etaEpochMillis.toDouble(),
            1500.0,
        )
    }

    @Test
    fun `a bike at a standstill still gets a plausible arrival`() {
        // Without a speed floor this would divide by zero and promise an
        // arrival in the year 2400.
        val route = straightRoute()
        val now = 1_700_000_000_000L
        val stop = RideItinerary.stopsAhead(
            route = route,
            viaPoints = listOf(route.points[40]),
            travelledMeters = 0.0,
            nowMillis = now,
            speedMps = 0.0,
        ).single()
        val hoursOut = (stop.etaEpochMillis - now) / 3_600_000.0
        assertTrue("arrival was $hoursOut h out", hoursOut > 0 && hoursOut < 2)
    }

    @Test
    fun `stops keep the order they were given, not the order along the route`() {
        // The sheet numbers stops by the plan, and the plan is the rider's
        // order - reordering here would silently disagree with the map.
        val route = straightRoute()
        val stops = RideItinerary.stopsAhead(
            route = route,
            viaPoints = listOf(route.points[30], route.points[10]),
            travelledMeters = 0.0,
            nowMillis = 1L,
            speedMps = 25.0,
        )
        assertEquals(30, stops[0].pointIndex)
        assertEquals(10, stops[1].pointIndex)
    }

    @Test
    fun `an empty route yields no stops rather than throwing`() {
        val stops = RideItinerary.stopsAhead(
            route = Route.EMPTY,
            viaPoints = listOf(GeoPoint(52.0, 9.7)),
            travelledMeters = 0.0,
            nowMillis = 1L,
            speedMps = 25.0,
        )
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `the fallback speed comes from the route's own estimate`() {
        // 5.5 km in 300 s is about 18 m/s; that is what an arrival estimate
        // uses while the bike is stopped.
        val route = straightRoute()
        val average = RideItinerary.averageSpeedMps(route)
        assertEquals(route.distanceMeters / 300.0, average, 0.01)
    }
}
