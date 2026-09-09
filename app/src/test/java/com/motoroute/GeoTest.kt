package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTest {

    private val munich = GeoPoint(48.1372, 11.5756)
    private val salzburg = GeoPoint(47.8095, 13.0550)

    @Test
    fun `flat distance matches haversine over short legs`() {
        val a = GeoPoint(47.5000, 11.5000)
        val b = GeoPoint(47.5050, 11.5060)
        val flat = Geo.distanceMeters(a, b)
        val exact = Geo.haversineMeters(a, b)
        assertTrue("flat=$flat exact=$exact", abs(flat - exact) < 0.5)
    }

    @Test
    fun `distance over a long leg stays within a percent of haversine`() {
        val flat = Geo.distanceMeters(munich, salzburg)
        val exact = Geo.haversineMeters(munich, salzburg)
        assertTrue("flat=$flat exact=$exact", abs(flat - exact) / exact < 0.01)
    }

    @Test
    fun `bearing north is zero and east is ninety`() {
        val origin = GeoPoint(48.0, 11.0)
        assertEquals(0.0, Geo.bearingDegrees(origin, GeoPoint(48.01, 11.0)), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(origin, GeoPoint(48.0, 11.01)), 0.5)
        assertEquals(180.0, Geo.bearingDegrees(origin, GeoPoint(47.99, 11.0)), 0.5)
        assertEquals(270.0, Geo.bearingDegrees(origin, GeoPoint(48.0, 10.99)), 0.5)
    }

    @Test
    fun `normalizeDelta folds into the signed half circle`() {
        assertEquals(-10.0, Geo.normalizeDelta(350.0), 1e-9)
        assertEquals(10.0, Geo.normalizeDelta(-350.0), 1e-9)
        assertEquals(180.0, Geo.normalizeDelta(180.0), 1e-9)
        assertEquals(180.0, Geo.normalizeDelta(-180.0), 1e-9)
    }

    @Test
    fun `turn angle is negative for a left turn`() {
        val a = GeoPoint(48.0000, 11.0000)
        val b = GeoPoint(48.0010, 11.0000) // heading north
        val left = GeoPoint(48.0010, 10.9990) // then west
        val right = GeoPoint(48.0010, 11.0010) // then east
        assertTrue(Geo.turnAngleDegrees(a, b, left) < -80)
        assertTrue(Geo.turnAngleDegrees(a, b, right) > 80)
    }

    @Test
    fun `projection onto a segment clamps to the ends`() {
        val a = GeoPoint(48.0, 11.0)
        val b = GeoPoint(48.0, 11.001)

        val middle = Geo.projectOnSegment(GeoPoint(48.0001, 11.0005), a, b)
        assertEquals(0.5, middle.t, 0.02)
        assertEquals(11.1, middle.distanceMeters, 1.0)

        val beforeStart = Geo.projectOnSegment(GeoPoint(48.0, 10.999), a, b)
        assertEquals(0.0, beforeStart.t, 1e-6)

        val pastEnd = Geo.projectOnSegment(GeoPoint(48.0, 11.002), a, b)
        assertEquals(1.0, pastEnd.t, 1e-6)
    }

    @Test
    fun `offset moves the expected distance in the expected direction`() {
        val start = GeoPoint(48.0, 11.0)
        val north = Geo.offset(start, 0.0, 100.0)
        assertEquals(100.0, Geo.distanceMeters(start, north), 0.5)
        assertTrue(north.latitude > start.latitude)

        val east = Geo.offset(start, 90.0, 100.0)
        assertEquals(100.0, Geo.distanceMeters(start, east), 0.5)
        assertTrue(east.longitude > start.longitude)
    }
}
