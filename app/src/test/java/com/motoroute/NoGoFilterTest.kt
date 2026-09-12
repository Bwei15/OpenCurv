package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.NoGoArea
import com.motoroute.domain.NoGoFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoGoFilterTest {
    private val hannover = GeoPoint(52.37, 9.73)
    private val hameln = GeoPoint(52.10, 9.36)

    @Test
    fun `keeps closures near the route path and drops the rest of the country`() {
        // ~39 km straight line hannover-hameln -> corridor = max(5, 0.2*39) ~= 7.8 km.
        val onPath = NoGoArea(GeoPoint(52.25, 9.55)) // close to the direct line
        val justOffPath = NoGoArea(GeoPoint(52.25, 9.62)) // a few km off the line, inside the corridor
        val farOffPath = NoGoArea(GeoPoint(52.55, 9.73)) // ~20 km off the line, outside the corridor
        val munich = NoGoArea(GeoPoint(48.14, 11.58))

        val kept = NoGoFilter.near(listOf(onPath, justOffPath, farOffPath, munich), listOf(hannover, hameln))

        assertTrue(onPath in kept)
        assertTrue(justOffPath in kept)
        assertTrue(farOffPath !in kept)
        assertTrue(munich !in kept)
    }

    @Test
    fun `no waypoints means no nogos`() {
        assertEquals(emptyList<NoGoArea>(), NoGoFilter.near(listOf(NoGoArea(hannover)), emptyList()))
    }

    @Test
    fun `a single waypoint falls back to a radius around that point`() {
        val near = NoGoArea(GeoPoint(52.38, 9.74)) // ~1.5 km away
        val far = NoGoArea(GeoPoint(53.0, 10.0)) // well beyond the floor radius

        val kept = NoGoFilter.near(listOf(near, far), listOf(hannover))

        assertEquals(listOf(near), kept)
    }

    @Test
    fun `a corridor scales with trip length but never past the ceiling`() {
        val shortTrip = listOf(hannover, GeoPoint(52.36, 9.72)) // ~1.3 km apart
        val longTrip = listOf(GeoPoint(52.9, 9.0), GeoPoint(51.5, 10.5)) // ~190 km apart

        // 7 km off a ~1.3 km hop is outside even the fraction-scaled corridor,
        // but well inside the MIN_CORRIDOR_KM floor either way - use something
        // clearly past the floor to prove the short trip stays tight.
        val farFromShortTrip = NoGoArea(GeoPoint(52.43, 9.74)) // ~7 km north
        assertTrue(farFromShortTrip !in NoGoFilter.near(listOf(farFromShortTrip), shortTrip))

        // Same absolute offset from the long trip's path should still be kept -
        // a real touring route can detour further than a 1 km hop can.
        val sameOffsetFromLongTrip = NoGoArea(GeoPoint(52.2, 9.75)) // near the long trip's path
        assertTrue(sameOffsetFromLongTrip in NoGoFilter.near(listOf(sameOffsetFromLongTrip), longTrip))
    }

    @Test
    fun `one bogus far-away waypoint no longer drags in the whole country`() {
        // Regression for the bounding-box bug: a "from" point that has not
        // settled yet (Null Island here, stand-in for a GPS default) used to
        // stretch the bounding box across the whole gap to Hannover and keep
        // everything in between. The corridor - capped at MAX_CORRIDOR_KM -
        // must not do that: a closure far from BOTH the real route and the
        // straight line to the bogus point stays dropped.
        val nullIsland = GeoPoint(0.0, 0.0)
        val munich = NoGoArea(GeoPoint(48.14, 11.58)) // nowhere near the Null-Island-Hannover line's German leg
        val nearHannover = NoGoArea(GeoPoint(52.4, 9.8))

        val kept = NoGoFilter.near(listOf(munich, nearHannover), listOf(nullIsland, hannover))

        assertTrue(munich !in kept)
        assertTrue(nearHannover in kept)
    }
}
