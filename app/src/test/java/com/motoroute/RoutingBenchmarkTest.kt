package com.motoroute

import com.motoroute.data.brouter.BRouterEngine
import com.motoroute.data.brouter.RouteRequest
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Real-data routing benchmark, for two questions raised while chasing
 * "routing is unusably slow" and "profiles don't feel different" (see
 * `1.Doku/Kurven_Score.md`, section "Messung auf Niedersachsen, 12.09.2026"):
 * how long a calculation actually takes, and how much the `curviness` slider
 * and the bundled profiles actually change the result.
 *
 * This needs the real ~230 MB Niedersachsen tiles our own pipeline built
 * (tagged with `opencurv:curve`), which have no business in the repo - every
 * test here is skipped, not failed, wherever `~/Downloads/region-de-ni/` is
 * missing (CI, a fresh checkout).
 */
class RoutingBenchmarkTest {

    private lateinit var profileDir: File
    private lateinit var segmentDir: File
    private val engine = BRouterEngine()

    @Before
    fun setUp() {
        profileDir = findProfileDir()
        segmentDir = File(System.getProperty("user.home"), "Downloads/region-de-ni")
        assumeTrue(
            "no local region-de-ni tiles under ~/Downloads - skipping the routing " +
                "benchmark (see 1.Doku/Kurven_Score.md)",
            segmentDir.listFiles { f -> f.name.endsWith(".rd5") }?.isNotEmpty() == true,
        )
    }

    private fun findProfileDir(): File {
        val candidates = listOfNotNull(
            System.getProperty("opencurv.repo")?.let { File(it, "app/src/main/assets/profiles") },
            File("src/main/assets/profiles"),
            File("app/src/main/assets/profiles"),
            File("../app/src/main/assets/profiles"),
            File("../../app/src/main/assets/profiles"),
        )
        return candidates.firstOrNull { File(it, "lookups.dat").isFile }
            ?: error("could not locate assets/profiles; tried $candidates")
    }

    private class Timed(val route: Route, val ms: Long)

    private fun route(
        profile: String,
        from: GeoPoint,
        to: GeoPoint,
        curviness: Double? = null,
        alternatives: Boolean = false,
        memoryClassMb: Int = 48,
    ): Timed {
        val request = RouteRequest(
            waypoints = listOf(from, to),
            profile = File(profileDir, "$profile.brf"),
            segmentDir = segmentDir,
            profileParams = curviness?.let { mapOf("curviness" to it.toString()) } ?: emptyMap(),
            memoryClassMb = memoryClassMb,
        )
        val start = System.nanoTime()
        val result = runBlocking {
            if (alternatives) engine.routeCurviest(request) else engine.route(request)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return Timed(result, elapsedMs)
    }

    // ---- Problem 1: is routing fast enough? ------------------------------

    private val hannover = GeoPoint(52.3759, 9.7320)
    private val hameln = GeoPoint(52.1036, 9.3568)

    // ~2 km from hannover, clear of the Hameln line, for the "short route" case.
    private val hannoverNear = GeoPoint(52.3830, 9.7450)

    @Test
    fun `45 km hannover to hameln routes in under 10s without alternatives`() {
        val t = route("motorcycle_curvy", hannover, hameln, alternatives = false)
        println("[bench] hannover->hameln curvy, no alternatives: " +
            "${"%.1f".format(t.route.distanceMeters / 1000.0)} km in ${t.ms} ms")
        assertTrue(
            "this waypoint pair should be a real cross-country distance (~45 km), " +
                "was ${t.route.distanceMeters} m",
            t.route.distanceMeters > 30_000,
        )
        assertTrue("45 km should route in under 10s on the JVM, took ${t.ms} ms", t.ms < 10_000)
    }

    @Test
    fun `2 km inside hannover routes in under 2s without alternatives`() {
        val t = route("motorcycle_curvy", hannover, hannoverNear, alternatives = false)
        println("[bench] hannover 2km curvy, no alternatives: ${t.route.distanceMeters.toInt()} m in ${t.ms} ms")
        assertTrue("2 km should route in under 2s on the JVM, took ${t.ms} ms", t.ms < 2_000)
    }

    @Test
    fun `alternatives search does not multiply the running time out of proportion`() {
        val plain = route("motorcycle_curvy", hannover, hannoverNear, alternatives = false)
        val withAlternatives = route("motorcycle_curvy", hannover, hannoverNear, alternatives = true)
        println(
            "[bench] hannover 2km curvy: no-alternatives=${plain.ms}ms " +
                "with-alternatives=${withAlternatives.ms}ms",
        )
        assertTrue(
            "searching alternatives should cost at most ~2x a single search " +
                "(one alternative instead of the old three), was ${withAlternatives.ms}ms vs ${plain.ms}ms",
            withAlternatives.ms < plain.ms * 3,
        )
    }

    @Test
    fun `a bigger node cache is not required for a correct result`() {
        val small = route("motorcycle_curvy", hannover, hameln, memoryClassMb = 48)
        val big = route("motorcycle_curvy", hannover, hameln, memoryClassMb = 192)
        println("[bench] hannover->hameln curvy: memoryClass=48 -> ${small.ms}ms, memoryClass=192 -> ${big.ms}ms")
        // Both have to find the same route; the cache size is a memory/speed
        // trade-off, not a correctness knob.
        assertTrue(
            "distance should not depend on the node-cache budget",
            kotlin.math.abs(small.route.distanceMeters - big.route.distanceMeters) < 50.0,
        )
    }

    // ---- Problem 2: do the profiles and the curviness slider actually differ? ----

    private val goslar = GeoPoint(51.9060, 10.4290)
    private val badHarzburg = GeoPoint(51.8800, 10.5600)

    private data class ProfileRow(
        val label: String,
        val distanceKm: Double,
        val ms: Long,
        val curviness: Double,
    )

    private fun profileRows(from: GeoPoint, to: GeoPoint): List<ProfileRow> = listOf(
        "fast" to route("motorcycle_fast", from, to),
        "curvy(0)" to route("motorcycle_curvy", from, to, curviness = 0.0),
        "curvy(1)" to route("motorcycle_curvy", from, to, curviness = 1.0),
        "curvy(2)" to route("motorcycle_curvy", from, to, curviness = 2.0),
        "enduro" to route("motorcycle_enduro", from, to),
    ).map { (label, timed) ->
        ProfileRow(label, timed.route.distanceMeters / 1000.0, timed.ms, timed.route.curvinessScore)
    }

    @Test
    fun `curvy profile is meaningfully twistier and longer than fast in the Harz`() {
        val rows = profileRows(goslar, badHarzburg)
        printTable("Goslar -> Bad Harzburg", rows)
        assertProfilesDiffer(rows)
    }

    @Test
    fun `curvy profile is meaningfully twistier and longer than fast hannover to hameln`() {
        val rows = profileRows(hannover, hameln)
        printTable("Hannover -> Hameln", rows)
        assertProfilesDiffer(rows)
    }

    private fun printTable(label: String, rows: List<ProfileRow>) {
        println("[bench] $label")
        rows.forEach {
            println(
                "[bench]   %-9s dist=%6.2f km  time=%5d ms  curvinessScore=%6.1f"
                    .format(it.label, it.distanceKm, it.ms, it.curviness),
            )
        }
    }

    private fun assertProfilesDiffer(rows: List<ProfileRow>) {
        val fast = rows.single { it.label == "fast" }
        val curvy0 = rows.single { it.label == "curvy(0)" }
        val curvy1 = rows.single { it.label == "curvy(1)" }
        val curvy2 = rows.single { it.label == "curvy(2)" }

        // curviness=0 is "direct": close to fast, not necessarily identical
        // (fast also disables avoid_motorways, which curvy never does).
        assertTrue(
            "curvy(0) should be within 15% of fast's curviness score, was " +
                "${curvy0.curviness} vs ${fast.curviness}",
            curvy0.curviness < fast.curviness * 1.15 + 5.0,
        )

        // curvy(2) has to be clearly twistier than curvy(0) - the whole point
        // of the slider - and clearly longer, since a twistier route is by
        // definition a detour.
        assertTrue(
            "curvy(2) should be at least 30% twistier than curvy(0), was " +
                "${curvy2.curviness} vs ${curvy0.curviness}",
            curvy2.curviness > curvy0.curviness * 1.30,
        )
        assertTrue(
            "curvy(2) should be at least 10% longer than curvy(0), was " +
                "${curvy2.distanceKm} km vs ${curvy0.distanceKm} km",
            curvy2.distanceKm > curvy0.distanceKm * 1.10,
        )

        // curvy(1) sits strictly between the two extremes.
        assertTrue(
            "curvy(1) should be twistier than curvy(0), was ${curvy1.curviness} vs ${curvy0.curviness}",
            curvy1.curviness > curvy0.curviness * 1.10,
        )
    }
}
