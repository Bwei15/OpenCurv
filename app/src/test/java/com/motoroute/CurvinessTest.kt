package com.motoroute

import com.motoroute.data.model.Curviness
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CurvinessTest {

    /** A dead straight 5 km road sampled every 50 m. */
    private fun straight(): List<GeoPoint> =
        (0..100).map { Geo.offset(GeoPoint(47.5, 11.5), 0.0, it * 50.0) }

    /** A sine-wave road: the same length, but constantly turning. */
    private fun serpentine(amplitudeMeters: Double): List<GeoPoint> =
        (0..100).map { i ->
            val along = Geo.offset(GeoPoint(47.5, 11.5), 0.0, i * 50.0)
            Geo.offset(along, 90.0, amplitudeMeters * sin(i / 4.0))
        }

    @Test
    fun `a straight road scores near zero`() {
        assertTrue(Curviness.score(straight()) < 5.0)
    }

    @Test
    fun `a serpentine scores far above a straight road`() {
        val straightScore = Curviness.score(straight())
        val curvyScore = Curviness.score(serpentine(120.0))
        assertTrue("straight=$straightScore curvy=$curvyScore", curvyScore > straightScore * 20)
    }

    @Test
    fun `more amplitude means more curviness`() {
        val mild = Curviness.score(serpentine(40.0))
        val wild = Curviness.score(serpentine(160.0))
        assertTrue("mild=$mild wild=$wild", wild > mild)
    }

    @Test
    fun `a single junction cannot fake a twisty road`() {
        // 5 km straight with one 90 degree corner in the middle.
        val first = (0..50).map { Geo.offset(GeoPoint(47.5, 11.5), 0.0, it * 50.0) }
        val corner = first.last()
        val second = (1..50).map { Geo.offset(corner, 90.0, it * 50.0) }
        val score = Curviness.score(first + second)
        assertTrue("score=$score", score < 25.0)
    }

    @Test
    fun `a town hop of nothing but junctions is not a curvy road`() {
        // 1.2 km of straight streets joined by five right-angle corners - the
        // route that used to come back as "extreme" with no curve in it.
        val points = ArrayList<GeoPoint>()
        var here = GeoPoint(47.5, 11.5)
        var bearing = 0.0
        val junctions = ArrayList<Double>()
        var travelled = 0.0
        points += here
        repeat(6) { leg ->
            repeat(20) {
                here = Geo.offset(here, bearing, 10.0)
                points += here
                travelled += 10.0
            }
            if (leg < 5) {
                junctions += travelled
                bearing = Geo.normalizeBearing(bearing + if (leg % 2 == 0) 90.0 else -90.0)
            }
        }
        val naive = Curviness.score(points)
        val informed = Curviness.score(points, junctions)
        assertTrue("naive=$naive", naive > 200.0)
        assertTrue("informed=$informed", informed < 20.0)
    }

    @Test
    fun `a hairpin with no junction on it still counts as curve`() {
        // A switchback on a pass road: the rider stays on the same road, so
        // the router says nothing about it, and it is exactly what we want to
        // score highly.
        val points = ArrayList<GeoPoint>()
        var here = GeoPoint(47.5, 11.5)
        var bearing = 0.0
        points += here
        repeat(60) { step ->
            // 180 degrees over about 100 m of road: a 30 m radius hairpin.
            if (step in 20 until 40) bearing = Geo.normalizeBearing(bearing + 9.0)
            here = Geo.offset(here, bearing, 5.0)
            points += here
        }
        val score = Curviness.score(points, junctionDistances = emptyList())
        assertTrue("score=$score", score > 300.0)
    }

    @Test
    fun `a street corner with no instruction on it is still not a curve`() {
        // The case the instruction list misses: a road that turns ninety
        // degrees with no side road to choose between, so the router says
        // nothing about it. It is a corner all the same.
        val points = ArrayList<GeoPoint>()
        var here = GeoPoint(47.5, 11.5)
        points += here
        repeat(40) { here = Geo.offset(here, 0.0, 10.0); points += here }
        repeat(40) { here = Geo.offset(here, 90.0, 10.0); points += here }
        val score = Curviness.score(points, junctionDistances = emptyList())
        assertTrue("score=$score", score < 15.0)
    }

    @Test
    fun `labels move up with the score`() {
        assertEquals("straight", Curviness.label(10.0))
        assertEquals("flowing", Curviness.label(60.0))
        assertEquals("curvy", Curviness.label(120.0))
        assertEquals("twisty", Curviness.label(200.0))
        assertEquals("extreme", Curviness.label(400.0))
    }
}
