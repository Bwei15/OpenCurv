package com.opencurv.testarena

import com.opencurv.testarena.geometry.LatLon
import com.opencurv.testarena.geometry.Projection
import com.opencurv.testarena.harness.ArenaGraph
import com.opencurv.testarena.harness.Evaluator
import com.opencurv.testarena.harness.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the Mess-Harness actually catches a bad route, not just that it can print a report.
 * "Bad" here means: a route that drives the arena's motorway (the fastest, most direct, but
 * curve-free alternative) instead of one of the genuinely curvy roads. This is the same trap
 * a broken "shortest/fastest path" router would fall into if pointed at OpenCurv's job.
 */
class HarnessViolationTest {

    private fun buildArenaFiles(dir: File): Pair<File, File> {
        val (doc, truth) = ArenaDefinition.build()
        val osmFile = File(dir, "arena.osm")
        OsmXmlWriter.write(doc, osmFile)
        val truthFile = File(dir, "arena_truth.json")
        com.opencurv.testarena.truth.JsonIo.writeArenaTruth(truth, truthFile)
        return osmFile to truthFile
    }

    /** Turns one arena route's own sampled vertices into GPS-style [RoutePoint]s - a stand-in
     *  for "some routing engine computed exactly this path", without depending on any engine. */
    private fun routePointsFor(routeId: String): List<RoutePoint> {
        val path = ArenaDefinition.lastBuildPaths().getValue(routeId)
        return path.vertices.map {
            val ll = Projection.toLatLon(it.point)
            RoutePoint(ll.lat, ll.lon)
        }
    }

    @Test
    fun `a route that drives the motorway violates the curve-over-motorway expectation`() {
        val tmp = createTempDir(prefix = "testarena-harness")
        try {
            ArenaDefinition.build() // populates lastBuildPaths()
            val motorwayRoute = routePointsFor("R7_MOTORWAY")

            val (osmFile, truthFile) = buildArenaFiles(tmp)
            val arena = ArenaGraph.load(osmFile)
            val truth = com.opencurv.testarena.truth.JsonIo.readArenaTruth(truthFile)

            val report = Evaluator.evaluate("motorway-only route", motorwayRoute, arena, truth)

            assertFalse("expected the motorway-only route to fail at least one hard expectation", report.ok)
            assertTrue(report.hardViolations >= 1)

            val motorwayVsCurves = report.expectationResults.first { it.id == "E2_CURVES_OVER_MOTORWAY" }
            assertEquals("violated", motorwayVsCurves.status)
            assertEquals(1.0, motorwayVsCurves.overShare, 1e-3)
            assertEquals(0.0, motorwayVsCurves.preferShare, 1e-6)

            // And the harness correctly identified *which* arena element the route drove on,
            // purely from coordinates - this is the "engine-neutral" contract in action.
            assertTrue(report.motorwaySharePct > 95.0)
            val dominant = report.routeIdShares.maxByOrNull { it.shareOfTotal }
            assertEquals("R7_MOTORWAY", dominant?.routeId)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a route that drives the residential grid violates both grid expectations`() {
        val tmp = createTempDir(prefix = "testarena-harness")
        try {
            ArenaDefinition.build()
            val gridRoute = routePointsFor("R5_GRID")

            val (osmFile, truthFile) = buildArenaFiles(tmp)
            val arena = ArenaGraph.load(osmFile)
            val truth = com.opencurv.testarena.truth.JsonIo.readArenaTruth(truthFile)

            val report = Evaluator.evaluate("grid-only route", gridRoute, arena, truth)

            assertFalse(report.ok)
            val violatedIds = report.expectationResults.filter { it.status == "violated" }.map { it.id }
            assertTrue(violatedIds.contains("E3_CURVES_OVER_GRID"))
            assertTrue(violatedIds.contains("E7_HIGHWAY_OVER_GRID"))
            assertTrue("a route through nothing but the grid should read as 100% town driving", report.townSharePct > 95.0)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a route that drives a genuinely curvy alternative passes every hard expectation`() {
        val tmp = createTempDir(prefix = "testarena-harness")
        try {
            ArenaDefinition.build()
            val flowingRoute = routePointsFor("R3_FLOWING")

            val (osmFile, truthFile) = buildArenaFiles(tmp)
            val arena = ArenaGraph.load(osmFile)
            val truth = com.opencurv.testarena.truth.JsonIo.readArenaTruth(truthFile)

            val report = Evaluator.evaluate("flowing route", flowingRoute, arena, truth)

            assertTrue("expected no hard violations, got: " + report.expectationResults.filter { it.status == "violated" }, report.ok)
            assertEquals(0, report.hardViolations)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `gpx and json readers agree on the same route`() {
        val points = listOf(RoutePoint(0.30, -1.00), RoutePoint(0.301, -1.0005), RoutePoint(0.302, -1.001))
        val tmp = createTempDir(prefix = "testarena-routeio")
        try {
            val gpxFile = File(tmp, "route.gpx")
            gpxFile.writeText(
                "<gpx><trk><trkseg>" +
                    points.joinToString("") { "<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>" } +
                    "</trkseg></trk></gpx>",
            )
            val jsonFile = File(tmp, "route.json")
            jsonFile.writeText(com.google.gson.Gson().toJson(points.map { mapOf("lat" to it.lat, "lon" to it.lon) }))

            val fromGpx = com.opencurv.testarena.harness.RouteInput.read(gpxFile)
            val fromJson = com.opencurv.testarena.harness.RouteInput.read(jsonFile)

            assertEquals(points.size, fromGpx.size)
            assertEquals(points.size, fromJson.size)
            for (i in points.indices) {
                assertEquals(points[i].lat, fromGpx[i].lat, 1e-9)
                assertEquals(points[i].lon, fromGpx[i].lon, 1e-9)
                assertEquals(points[i].lat, fromJson[i].lat, 1e-9)
                assertEquals(points[i].lon, fromJson[i].lon, 1e-9)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
