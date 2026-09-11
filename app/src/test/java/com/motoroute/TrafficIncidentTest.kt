package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import com.motoroute.data.traffic.TrafficIncident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficIncidentTest {

    @Test
    fun roadClosureIsAlwaysImpassable() {
        val incident = TrafficIncident(
            id = "inc-1",
            title = "B27 Vollsperrung",
            description = "Erdrutsch",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(51.0, 9.0),
            radiusMeters = 80,
        )

        assertTrue(incident.isImpassable)
        val nogo = incident.toNoGoArea()
        assertTrue(nogo.isClosure)
        assertEquals(80, nogo.radiusMeters)
        assertEquals(51.0, nogo.point.latitude, 0.0001)
        assertEquals(9.0, nogo.point.longitude, 0.0001)
        assertEquals("B27 Vollsperrung", nogo.description)
    }

    @Test
    fun passClosureIsAlwaysImpassable() {
        val incident = TrafficIncident(
            id = "inc-pass",
            title = "Timmelsjoch Wintersperre",
            description = "Pass gesperrt",
            type = IncidentType.PASS_CLOSURE,
            severity = IncidentSeverity.WARNING,
            location = GeoPoint(46.9, 11.1),
        )

        assertTrue(incident.isImpassable)
        val nogo = incident.toNoGoArea()
        assertTrue(nogo.isClosure)
    }

    @Test
    fun nonCriticalConstructionIsNotImpassable() {
        val incident = TrafficIncident(
            id = "inc-works",
            title = "Mäharbeiten",
            description = "Rechter Fahrbahnrand",
            type = IncidentType.CONSTRUCTION,
            severity = IncidentSeverity.INFO,
            location = GeoPoint(52.5, 13.4),
        )

        assertFalse(incident.isImpassable)
        val nogo = incident.toNoGoArea()
        assertFalse(nogo.isClosure)
    }

    @Test
    fun criticalConstructionIsImpassable() {
        val incident = TrafficIncident(
            id = "inc-critical-works",
            title = "Brückeninstandsetzung",
            description = "Fahrbahn komplett gesperrt",
            type = IncidentType.CONSTRUCTION,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(53.0, 8.8),
            radiusMeters = 150,
        )

        assertTrue(incident.isImpassable)
        val nogo = incident.toNoGoArea()
        assertTrue(nogo.isClosure)
        assertEquals(150, nogo.radiusMeters)
    }

    @Test
    fun pointIncidentToNoGoAreasReturnsSingleCircle() {
        val incident = TrafficIncident(
            id = "inc-point",
            title = "B27 Vollsperrung",
            description = "Erdrutsch",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(51.0, 9.0),
            radiusMeters = 80,
        )

        val areas = incident.toNoGoAreas()
        assertEquals(1, areas.size)
        assertEquals(80, areas[0].radiusMeters)
    }

    @Test
    fun lineIncidentToNoGoAreasSamplesAlongThePolyline() {
        // ~1.1 km straight stretch along a meridian (1 deg lat ~= 111 km).
        val line = listOf(
            GeoPoint(50.000, 10.0),
            GeoPoint(50.003, 10.0),
            GeoPoint(50.006, 10.0),
            GeoPoint(50.010, 10.0),
        )
        val incident = TrafficIncident(
            id = "inc-line",
            title = "A1 Vollsperrung",
            description = "Streckensperrung",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = line.first(),
            polyline = line,
        )

        val areas = incident.toNoGoAreas(spacingMeters = 300.0)

        // More than one circle, always starting and ending on the line's endpoints.
        assertTrue(areas.size > 1)
        assertEquals(line.first(), areas.first().point)
        assertEquals(line.last(), areas.last().point)
        areas.forEach { assertTrue(it.isClosure) }

        // Consecutive circles must overlap (radius >= half the sampling spacing)
        // so a route cannot thread through the gap between them.
        assertTrue(areas[0].radiusMeters >= 150)
    }

    @Test
    fun shortLineIncidentFallsBackToSingleNoGoArea() {
        val incident = TrafficIncident(
            id = "inc-short-line",
            title = "A1 Vollsperrung",
            description = "kurzer Abschnitt",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(50.0, 10.0),
            polyline = listOf(GeoPoint(50.0, 10.0)), // single point, not a real line
        )

        assertEquals(1, incident.toNoGoAreas().size)
    }
}
