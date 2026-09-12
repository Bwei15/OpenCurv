package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.MobilithekTrafficParser
import com.motoroute.data.traffic.TrafficRepository
import com.motoroute.domain.NoGoFilter
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the bug that produced 2 281 nogo circles for a ~1 km Hannover
 * route on the emulator (see BRouterEngine's "doRun start ... nogos=" log)
 * instead of the ~57 a plain bounding box gives for a sane pair of
 * waypoints, or the corridor filter's typically much smaller count.
 *
 * The fixture is 194 real impassable (closure/critical) features from a
 * live Mobilithek/Autobahn feed cache pulled during that measurement,
 * trimmed to Niedersachsen and rounded to 5 decimals to stay well under the
 * repo's asset-size comfort zone (~180 KB here) while remaining
 * representative: motorway closures sampled into avoidance circles every
 * 150 m, some running many kilometres long.
 */
class NoGoFilterIntegrationTest {

    private val hannoverFrom = GeoPoint(52.37, 9.74)
    private val hannoverTo = GeoPoint(52.36, 9.72)

    private fun loadFixtureGeoJson(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("traffic/niedersachsen_impassable_fixture.json")) {
            "missing test fixture traffic/niedersachsen_impassable_fixture.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `a short Hannover route keeps well under 150 nogos from a real feed`() {
        val geoJson = loadFixtureGeoJson()
        val incidents = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertTrue("fixture should have parsed some incidents", incidents.isNotEmpty())

        val repo = TrafficRepository()
        kotlinx.coroutines.runBlocking { repo.updateFromGeoJson(geoJson) }

        val activeNoGos = repo.activeNoGoAreas()
        println(
            "[NoGoFilterIntegrationTest] parsed=${incidents.size} " +
                "impassable=${incidents.count { it.isImpassable }} activeNoGoCircles=${activeNoGos.size}",
        )

        val near = NoGoFilter.near(activeNoGos, listOf(hannoverFrom, hannoverTo))
        println("[NoGoFilterIntegrationTest] near(hannover 1km) size=${near.size}")

        assertTrue(
            "a ~1 km route should keep well under 150 nogos, kept ${near.size} " +
                "out of ${activeNoGos.size} active circles",
            near.size < 150,
        )
    }

    @Test
    fun `a bogus far-away waypoint no longer balloons the kept nogos`() {
        // Regression for the actual root cause: the "from" waypoint that
        // produced the 2 281 measurement was effectively a far-away
        // placeholder, not a point near Hannover. The old bounding-box
        // filter stretched to cover the whole gap and kept over half the
        // country's circles; the corridor filter (capped at
        // NoGoFilter.MAX_CORRIDOR_KM) must not reproduce that blow-up.
        val geoJson = loadFixtureGeoJson()
        val repo = TrafficRepository()
        kotlinx.coroutines.runBlocking { repo.updateFromGeoJson(geoJson) }
        val activeNoGos = repo.activeNoGoAreas()

        val nullIsland = GeoPoint(0.0, 0.0)
        val near = NoGoFilter.near(activeNoGos, listOf(nullIsland, hannoverFrom))
        println("[NoGoFilterIntegrationTest] near(null-island -> hannover) size=${near.size} of ${activeNoGos.size}")

        assertTrue(
            "a bogus far-away waypoint must not drag in most of the country's " +
                "nogos, kept ${near.size} out of ${activeNoGos.size}",
            near.size < activeNoGos.size / 2,
        )
    }
}
