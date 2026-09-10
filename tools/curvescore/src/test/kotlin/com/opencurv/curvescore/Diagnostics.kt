package com.opencurv.curvescore

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.analyseTurns
import com.opencurv.curvescore.io.OsmReader
import com.opencurv.curvescore.score.ScoreConfig
import com.opencurv.curvescore.score.Terms
import org.junit.Test
import java.io.File

/**
 * Not an assertion test - a printout used while calibrating the constants.
 * Kept in the tree because the next person to touch a weight will want it.
 */
class Diagnostics {
    @Test
    fun dumpPerRouteGeometry() {
        val repo = File(System.getProperty("curvescore.repoDir") ?: ".")
        val data = OsmReader.read(File(repo, "tools/testarena/data/arena.osm"))
        val c = data.centre()
        val plane = LocalPlane(c.first, c.second)
        val cfg = ScoreConfig()
        println("route            L     sumTurn  sumTurnQ    rho     C   events corners")
        for (w in data.ways) {
            val rid = w.tags["opencurv:route"] ?: continue
            val pts = w.nodeIds.map { data.nodes[it] }.filterNotNull().map { plane.toLocal(it.lat, it.lon) }
            val a = analyseTurns(pts, cfg.noiseDeg, cfg.minEventDeg, cfg.cornerDeg)
            var turn = 0.0
            var turnQ = 0.0
            for (v in a.vertices) {
                if (v.isCorner) continue
                turn += Math.abs(v.deflectionRad)
                turnQ += Math.abs(v.deflectionRad) * Terms.radiusQuality(v.radiusM, cfg)
            }
            val rho = turnQ / a.lengthM
            println(
                String.format(
                    java.util.Locale.ROOT,
                    "%-16s %6.0f %8.2f %9.2f %8.5f %6.3f %6d %6d",
                    rid, a.lengthM, turn, turnQ, rho, Math.min(1.0, Math.sqrt(rho / cfg.densityRefRadPerM)),
                    a.events.count { !it.isCorner }, a.events.count { it.isCorner },
                )
            )
            if (rid == "R8_JOG90" || rid == "R4_S_CURVES") {
                val (_, ev) = com.opencurv.curvescore.score.analysePolyline(pts, cfg)
                println("    Pipeline-Events: " + ev.joinToString(" ") {
                    (if (it.isCorner) "E" else "K") + String.format(java.util.Locale.ROOT, "%.0f/%.0f", Math.toDegrees(it.turnRad), it.radiusM)
                })
            }
            if (rid == "R2_SERPENTINE" || rid == "R4_S_CURVES") {
                val radii = a.vertices.filter { !it.isCorner && Math.abs(it.deflectionRad) > 0.05 }
                    .map { Math.round(it.radiusM) }
                println("    Vertex-Radien: " + radii.groupingBy { it }.eachCount().entries
                    .sortedBy { it.key }.joinToString(" ") { "${it.key}m x${it.value}" })
            }
        }
    }
}
