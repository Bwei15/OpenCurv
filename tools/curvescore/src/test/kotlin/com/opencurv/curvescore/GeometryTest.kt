package com.opencurv.curvescore

import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.geom.analyseTurns
import com.opencurv.curvescore.score.ScoreConfig
import com.opencurv.curvescore.score.Terms
import com.opencurv.curvescore.score.analysePolyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The geometry stage on its own: does it measure what it claims to measure? */
class GeometryTest {

    private val cfg = ScoreConfig()

    @Test
    fun `recovers the commanded radius of a circular arc`() {
        for (r in listOf(15.0, 24.0, 45.0, 90.0, 180.0, 400.0)) {
            val road = SyntheticRoad().straight(200.0).arc(r, 90.0).straight(200.0)
            val (_, events) = analysePolyline(road.points(), cfg)
            val curves = events.filter { !it.isCorner }
            assertEquals("one curve expected for R=$r", 1, curves.size)
            val measured = curves[0].radiusM
            assertTrue(
                "R=$r measured as $measured (>3 % off)",
                abs(measured - r) / r < 0.03,
            )
            assertEquals("turn angle for R=$r", 90.0, Math.toDegrees(curves[0].absTurnRad), 2.0)
        }
    }

    @Test
    fun `a single-vertex 90 degree kink is a corner, not a hairpin`() {
        val road = SyntheticRoad().straight(300.0).corner(90.0).straight(300.0)
        val (vertices, events) = analysePolyline(road.points(), cfg)
        assertEquals(1, events.size)
        assertTrue("must be classified as a corner", events[0].isCorner)
        assertEquals("a corner has no radius", 0.0, events[0].radiusM, 1e-9)
        assertEquals(0.0, Terms.radiusQuality(events[0].radiusM, cfg), 1e-9)
        assertEquals(1, vertices.count { it.isCorner })
    }

    /**
     * The heart of the R5_GRID trap: a hairpin and a right-angle corner can
     * produce the *same* single-vertex geometry, and only persistence over
     * several vertices separates them. A properly mapped hairpin must survive.
     */
    @Test
    fun `a properly mapped hairpin is a curve even though it turns more than a corner`() {
        val road = SyntheticRoad().straight(200.0).arc(15.0, 170.0).straight(200.0)
        val (_, events) = analysePolyline(road.points(), cfg)
        val curves = events.filter { !it.isCorner }
        assertEquals("the hairpin must survive as a curve", 1, curves.size)
        assertTrue("no corner may be reported", events.none { it.isCorner })
        assertTrue("radius ~15 m, got ${curves[0].radiusM}", abs(curves[0].radiusM - 15.0) < 1.5)
        assertTrue("a 15 m hairpin still has real value", Terms.radiusQuality(curves[0].radiusM, cfg) > 0.35)
    }

    @Test
    fun `a grid of right angle corners produces corners and no curve value`() {
        val road = SyntheticRoad()
        road.straight(200.0)
        for (i in 0 until 8) {
            road.corner(if (i % 2 == 0) 90.0 else -90.0)
            road.straight(200.0)
        }
        val (vertices, events) = analysePolyline(road.points(), cfg)
        assertEquals("all eight must be corners", 8, events.count { it.isCorner })
        assertEquals("and none a curve", 0, events.count { !it.isCorner })
        val density = Terms.density(vertices, 1800.0, cfg)
        assertTrue("720 deg of direction change must yield ~no curve density, got $density", density < 0.02)
    }

    @Test
    fun `analysis is invariant under reversing the direction of travel`() {
        val road = SyntheticRoad().straight(150.0).arc(60.0, 80.0).straight(120.0).arc(90.0, -70.0).straight(150.0)
        val fwd = analysePolyline(road.points(), cfg)
        val rev = analysePolyline(road.points().reversed(), cfg)
        assertEquals(fwd.second.size, rev.second.size)
        val fRadii = fwd.second.map { Math.round(it.radiusM) }.sorted()
        val rRadii = rev.second.map { Math.round(it.radiusM) }.sorted()
        assertEquals(fRadii, rRadii)
    }

    @Test
    fun `dense tracing jitter does not become curve value`() {
        // A dead-straight road digitised every 2 m with +-25 cm of lateral noise:
        // the classic "traced from aerial imagery" pattern.
        val rnd = java.util.Random(7)
        val pts = ArrayList<Pt>()
        var s = 0.0
        while (s < 1000.0) {
            pts.add(Pt((rnd.nextDouble() - 0.5) * 0.5, s))
            s += 2.0
        }
        val (vertices, _) = analysePolyline(pts, cfg)
        val density = Terms.density(vertices, 1000.0, cfg)
        assertTrue("jitter must not read as curves, got $density", density < 0.12)
    }

    @Test
    fun `a coarsely mapped curve with only two interior nodes is still a curve`() {
        // Two 45 deg nodes 25 m apart: a real curve mapped sparsely, radius ~32 m.
        val pts = listOf(
            Pt(0.0, -200.0), Pt(0.0, 0.0),
            Pt(17.7, 17.7), Pt(42.7, 17.7), Pt(242.7, 17.7),
        )
        val (_, events) = analysePolyline(pts, cfg)
        assertEquals(1, events.size)
        assertTrue("two nodes is enough persistence to be a curve", !events[0].isCorner)
        assertTrue("radius must be plausible, got ${events[0].radiusM}", events[0].radiusM in 15.0..60.0)
    }

    @Test
    fun `noise threshold suppresses sub-degree wobble in the turn events`() {
        val pts = (0..100).map { Pt(if (it % 2 == 0) 0.0 else 0.05, it * 20.0) }
        val a = analyseTurns(pts, cfg.noiseDeg, cfg.minEventDeg, cfg.cornerDeg)
        assertEquals("no events from 0.14 deg wobble", 0, a.events.size)
    }
}
