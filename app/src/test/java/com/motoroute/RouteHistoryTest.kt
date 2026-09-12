package com.motoroute

import com.motoroute.data.history.HistoryStop
import com.motoroute.data.history.RouteHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteHistoryTest {

    private fun tempFile(): File = File.createTempFile("history", ".json").apply { deleteOnExit() }

    @Test
    fun `records a destination and reads it back`() {
        val history = RouteHistory(tempFile())
        history.recordDestination("Hameln", 52.1041, 9.3565)

        val result = history.recentDestinations()
        assertEquals(1, result.size)
        assertEquals("Hameln", result[0].name)
        assertEquals(52.1041, result[0].latitude, 1e-6)
        assertEquals(9.3565, result[0].longitude, 1e-6)
    }

    @Test
    fun `merges a destination at the same coordinate and moves it to the front`() {
        val history = RouteHistory(tempFile())
        history.recordDestination("Hameln", 52.1041, 9.3565)
        history.recordDestination("Goslar", 51.9061, 10.4292)
        // Same spot as the first entry (well within the ~11 m dedupe grid) - must replace it,
        // not add a second row, and the merged row moves back to the front.
        history.recordDestination("Hameln Zentrum", 52.10411, 9.35651)

        val result = history.recentDestinations()
        assertEquals(2, result.size)
        assertEquals("Hameln Zentrum", result[0].name)
        assertEquals("Goslar", result[1].name)
    }

    @Test
    fun `caps destinations at MAX_ENTRIES, newest first`() {
        val history = RouteHistory(tempFile())
        repeat(RouteHistory.MAX_ENTRIES + 5) { i ->
            history.recordDestination("stop-$i", 50.0 + i * 0.1, 10.0)
        }

        val result = history.recentDestinations()
        assertEquals(RouteHistory.MAX_ENTRIES, result.size)
        assertEquals("stop-${RouteHistory.MAX_ENTRIES + 4}", result[0].name)
    }

    @Test
    fun `records and reloads a trip from disk`() {
        val file = tempFile()
        val stops = listOf(
            HistoryStop("Hannover", 52.3759, 9.7320),
            HistoryStop(null, 52.20, 9.80),
            HistoryStop("Hameln", 52.1041, 9.3565),
        )
        RouteHistory(file).recordTrip(stops, profileId = "motorcycle_curvy", curviness = 1.5f, roundTrip = false)

        // A fresh instance over the same file proves it actually persisted, not just cached in memory.
        val reloaded = RouteHistory(file)
        val trips = reloaded.recentTrips()
        assertEquals(1, trips.size)
        val trip = trips[0]
        assertEquals(3, trip.stops.size)
        assertEquals("Hannover", trip.stops[0].name)
        assertNull(trip.stops[1].name)
        assertEquals("Hameln", trip.stops[2].name)
        assertEquals("motorcycle_curvy", trip.profileId)
        assertEquals(1.5f, trip.curviness, 1e-6f)
        assertTrue(!trip.roundTrip)
    }

    @Test
    fun `a trip with fewer than two stops is not recorded`() {
        val history = RouteHistory(tempFile())
        history.recordTrip(listOf(HistoryStop("only one", 52.0, 9.0)), "motorcycle_curvy", 1.0f, false)
        assertEquals(0, history.recentTrips().size)
    }

    @Test
    fun `caps trips at MAX_ENTRIES, newest first`() {
        val history = RouteHistory(tempFile())
        repeat(RouteHistory.MAX_ENTRIES + 3) { i ->
            history.recordTrip(
                listOf(HistoryStop("a-$i", 50.0, 9.0), HistoryStop("b-$i", 51.0, 9.0)),
                "motorcycle_curvy",
                1.0f,
                roundTrip = i % 2 == 0,
            )
        }

        val trips = history.recentTrips()
        assertEquals(RouteHistory.MAX_ENTRIES, trips.size)
        assertEquals("a-${RouteHistory.MAX_ENTRIES + 2}", trips[0].stops[0].name)
    }

    @Test
    fun `a missing file starts empty instead of throwing`() {
        val file = File.createTempFile("history", ".json").apply { delete() }
        val history = RouteHistory(file)
        assertEquals(0, history.recentDestinations().size)
        assertEquals(0, history.recentTrips().size)
    }

    @Test
    fun `a corrupt file starts empty instead of throwing`() {
        val file = tempFile().apply { writeText("not json at all {{{") }
        val history = RouteHistory(file)
        assertEquals(0, history.recentDestinations().size)
        assertEquals(0, history.recentTrips().size)
    }
}
