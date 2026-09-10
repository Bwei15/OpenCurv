package com.opencurv.curvescore

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.score.CurveScorer
import com.opencurv.curvescore.score.Environment
import com.opencurv.curvescore.score.ScoreConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Calibration against roads a rider can picture.
 *
 * The testarena proves the *ordering* is right. It cannot prove the *scale* is
 * right, because it contains no road anyone has ever ridden. This test does
 * that half: it rebuilds six archetypes from their real published geometry and
 * asserts each lands in the band the report claims for it. If someone changes a
 * weight and the Autobahn starts scoring 6, this is what goes red.
 */
class ReferenceRoadsTest {

    private val plane = LocalPlane(46.5, 10.4)
    private val cfg = ScoreConfig()

    private fun level(data: OsmData): Pair<Int, Double> {
        val s = CurveScorer(data, cfg, Environment.build(data, plane), plane)
            .scoreAll(parallel = false).maxByOrNull { it.lengthM }!!
        return s.level to s.raw01
    }

    private fun report(name: String, lv: Pair<Int, Double>, lo: Int, hi: Int) {
        println(String.format(Locale.ROOT, "  %-34s Stufe %2d  (roh %.3f)   erwartet %d..%d", name, lv.first, lv.second, lo, hi))
        assertTrue("$name landete auf Stufe ${lv.first} (roh ${lv.second}), erwartet $lo..$hi", lv.first in lo..hi)
    }

    @Test
    fun `the six reference roads land in their documented bands`() {
        println("Kalibrierung an realen Referenzstrecken:")

        // 1. Autobahn: 1500 m sweepers, one every 2 km. A7 through Lower Saxony.
        val autobahn = SyntheticRoad.alternating(
            curves = 4, radiusM = 1500.0, turnDeg = 15.0, straightM = 2000.0,
        ).toOsm(mapOf("highway" to "motorway", "surface" to "asphalt", "maxspeed" to "none", "lanes" to "3"), plane)
        report("Autobahn (R=1500 alle 2 km)", level(autobahn), 0, 1)

        // 2. Village street: 50 km/h, right angles, residential landuse.
        val village = SyntheticRoad().also { r ->
            r.straight(150.0)
            for (i in 0 until 8) { r.corner(if (i % 2 == 0) 90.0 else -90.0); r.straight(180.0) }
        }.toOsm(mapOf("highway" to "residential", "maxspeed" to "50", "surface" to "asphalt"), plane, landuse = "residential")
        report("Ortsstrasse im Wohngebiet", level(village), 0, 0)

        // 3. Ordinary rural Landstrasse: a gentle 25 deg bend of R=300 every 700 m.
        val ordinary = SyntheticRoad.alternating(
            curves = 8, radiusM = 300.0, turnDeg = 25.0, straightM = 700.0,
            gradient = 0.01, startElevationM = 400.0,
        ).toOsm(mapOf("highway" to "secondary", "surface" to "asphalt", "maxspeed" to "100"), plane, landuse = "farmland")
        report("gewoehnliche Landstrasse", level(ordinary), 3, 6)

        // 4. A properly twisty Landstrasse: R=120 every 250 m, rolling, forest.
        val twisty = SyntheticRoad.alternating(
            curves = 20, radiusM = 120.0, turnDeg = 50.0, straightM = 145.0,
            gradient = 0.03, startElevationM = 300.0,
        ).toOsm(mapOf("highway" to "secondary", "surface" to "asphalt", "maxspeed" to "100"), plane, landuse = "forest")
        report("sehr kurvige Landstrasse", level(twisty), 11, 15)

        // 5. Stelvio north ramp: 48 hairpins over 24.3 km, R~15 m, 7.4 % average.
        //    Rebuilt to scale over 6 km (12 hairpins) - the density is the same.
        val stelvio = SyntheticRoad().also { r ->
            r.startElevation(1200.0)
            r.straight(230.0, 0.074)
            for (i in 0 until 12) {
                r.arc(15.0, if (i % 2 == 0) 170.0 else -170.0, 0.074)
                r.straight(460.0, 0.074)
            }
        }.toOsm(mapOf("highway" to "secondary", "surface" to "asphalt", "maxspeed" to "60"), plane, landuse = "meadow")
        report("Stilfser Joch (Nordrampe)", level(stelvio), 9, 13)

        // 6. The theoretical dream road: continuously alternating 75 m arcs,
        //    6 % gradient, through forest. This is what level 15 means.
        val dream = SyntheticRoad().also { r ->
            r.startElevation(500.0)
            for (i in 0 until 40) r.arc(75.0, if (i % 2 == 0) 90.0 else -90.0, 0.06)
        }.toOsm(mapOf("highway" to "tertiary", "surface" to "asphalt", "maxspeed" to "80"), plane, landuse = "forest")
        report("Traumstrecke (Dauerkurven R=75)", level(dream), 14, 15)
    }

    /**
     * The scale has to be *used*: if the six archetypes collapse into three
     * levels, the routing engine has nothing to steer with.
     */
    @Test
    fun `the reference roads spread across the scale`() {
        val roads = listOf(
            SyntheticRoad.alternating(4, 1500.0, 15.0, 2000.0)
                .toOsm(mapOf("highway" to "motorway", "surface" to "asphalt"), plane),
            SyntheticRoad.alternating(8, 300.0, 25.0, 700.0)
                .toOsm(mapOf("highway" to "secondary", "surface" to "asphalt"), plane),
            SyntheticRoad.alternating(14, 180.0, 40.0, 300.0)
                .toOsm(mapOf("highway" to "secondary", "surface" to "asphalt"), plane),
            SyntheticRoad.alternating(20, 120.0, 50.0, 145.0)
                .toOsm(mapOf("highway" to "tertiary", "surface" to "asphalt"), plane),
            SyntheticRoad.alternating(30, 75.0, 90.0, 40.0)
                .toOsm(mapOf("highway" to "tertiary", "surface" to "asphalt"), plane),
        )
        val levels = roads.map { level(it).first }
        println("Stufen der Steigerungsreihe: $levels")
        assertTrue("must be non-decreasing: $levels", levels.zipWithNext().all { (a, b) -> a <= b })
        assertTrue("must span at least 9 levels: $levels", levels.last() - levels.first() >= 9)
    }
}
