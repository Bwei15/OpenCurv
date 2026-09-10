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
}
