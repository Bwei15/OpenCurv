package com.opencurv.curvescore

import com.opencurv.curvescore.io.OsmReader
import com.opencurv.curvescore.report.ArenaRunner
import com.opencurv.curvescore.report.ArenaSvg
import com.opencurv.curvescore.score.ScoreConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The acceptance test. The testarena's expectations E1-E9 are the contract;
 * a failed `hard` one is a red build, not a warning.
 */
class ArenaExpectationsTest {

    private val repo = File(System.getProperty("curvescore.repoDir") ?: ".")
    private val arenaDir = File(repo, "tools/testarena")
    private val cfg = ScoreConfig()

    @Test
    fun `all hard expectations hold and the ranking is printed`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        println(ArenaRunner.render(outcome))
        val failures = outcome.hardFailures
        assertTrue(
            "harte Erwartungen verletzt: " + failures.joinToString("\n") { "${it.id}: ${it.detail}" },
            failures.isEmpty(),
        )
    }

    @Test
    fun `the soft and info expectations hold too`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        val soft = outcome.expectations.filter { it.severity != "hard" && !it.passed }
        assertTrue("weiche Erwartungen verletzt: " + soft.joinToString { it.id }, soft.isEmpty())
    }

    /**
     * The trap the whole design is built around. R5_GRID carries the largest
     * total direction change in the arena (717 deg, more than the serpentine) and
     * must still come out worst.
     */
    @Test
    fun `R5_GRID is the worst route despite having the most direction change`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        val grid = outcome.byRouteId("R5_GRID")!!
        assertEquals("R5_GRID must be dead last", "R5_GRID", outcome.ranking.last().routeId)
        assertEquals("and land on level 0", 0, grid.level)
        assertTrue("its 717 deg must not become curve density", grid.score.terms["density"]!! < 0.20)
        assertTrue("its corners must be detected", grid.score.stats["cornersPerKm"]!! > 1.0)
        // Of 717 deg of direction change, next to nothing may survive as curve
        // value - the arena's fan-out connector contributes the odd real bend,
        // the grid itself must contribute none.
        assertTrue(
            "only ${grid.score.stats["curvatureDegPerKm"]} deg/km of 160 deg/km may survive as curve",
            grid.score.stats["curvatureDegPerKm"]!! < 15.0,
        )
        assertTrue("the corner penalty must bite", grid.score.penalties["corner"]!! < 0.75)
        assertTrue("the settlement penalty must bite", grid.score.penalties["settlement"]!! < 0.35)
        // The specific arena trap: even the boring straight highway beats it.
        val highway = outcome.byRouteId("R1_HIGHWAY")!!
        assertTrue("R1 ${highway.raw01} must beat R5 ${grid.raw01}", highway.raw01 > grid.raw01 * 3)
    }

    @Test
    fun `the two genuinely curvy routes lead the ranking`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        val top2 = outcome.ranking.take(2).map { it.routeId }.toSet()
        assertEquals(setOf("R2_SERPENTINE", "R4_S_CURVES"), top2)
        for (id in top2) assertTrue(outcome.byRouteId(id)!!.level >= 7)
    }

    @Test
    fun `the detected geometry matches the analytic ground truth`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        for (r in outcome.ranking) {
            val detectedCurves = r.score.stats["curveCount"]!!.toInt()
            val detectedCorners = r.score.stats["cornerCount"]!!.toInt()
            // Tolerances, and why they are not zero: the arena joins every route
            // to OMEGA through a connector that is not part of the ground truth
            // (it adds up to one bend and one join corner), and Douglas-Peucker
            // legitimately merges two same-direction transition arcs that sit
            // closer together than the curve-break distance.
            assertTrue(
                "${r.routeId}: $detectedCurves Kurven erkannt, Ground Truth ${r.truth.curveCount}",
                detectedCurves >= (r.truth.curveCount * 0.75).toInt() && detectedCurves <= r.truth.curveCount + 2,
            )
            assertTrue(
                "${r.routeId}: $detectedCorners Ecken erkannt, Ground Truth ${r.truth.sharpCornerCount}",
                detectedCorners >= r.truth.sharpCornerCount - 1 && detectedCorners <= r.truth.sharpCornerCount + 1,
            )
            val truthR = r.truth.minRadiusM
            if (truthR != null) {
                val detectedR = r.score.stats["minRadiusM"]!!
                assertTrue(
                    "${r.routeId}: kleinster Radius $detectedR m vs. Ground Truth $truthR m",
                    detectedR <= truthR * 1.25,
                )
            }
        }
    }

    @Test
    fun `elevation only moves the hill pair by at most one level`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        val flat = outcome.byRouteId("R_HILL_FLAT")!!
        val climb = outcome.byRouteId("R_HILL_CLIMB")!!
        assertTrue(
            "identische Grundrissgeometrie, aber ${flat.level} vs ${climb.level}",
            Math.abs(flat.level - climb.level) <= 1,
        )
    }

    @Test
    fun `the svg report is written and colours the routes by level`() {
        val outcome = ArenaRunner.run(arenaDir, cfg)
        val out = File(repo, "tools/curvescore/report/arena.svg")
        val data = OsmReader.read(File(arenaDir, "data/arena.osm"))
        ArenaSvg.write(data, outcome, cfg, out)
        ArenaRunner.writeJson(outcome, File(repo, "tools/curvescore/report/arena_scores.json"))
        val svg = out.readText()
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        for (r in outcome.ranking) assertTrue("${r.routeId} missing in svg", svg.contains(r.routeId))
        val serpentineColour = ArenaSvg.levelColour(outcome.byRouteId("R2_SERPENTINE")!!.level, cfg.levels)
        val gridColour = ArenaSvg.levelColour(outcome.byRouteId("R5_GRID")!!.level, cfg.levels)
        assertTrue("serpentine must be drawn green: $serpentineColour", svg.contains(serpentineColour))
        assertTrue("grid must be drawn red: $gridColour", svg.contains(gridColour))
        assertTrue(serpentineColour != gridColour)
    }
}
