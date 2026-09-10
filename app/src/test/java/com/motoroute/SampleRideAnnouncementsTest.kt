package com.motoroute

import com.motoroute.domain.AnnouncementKind
import com.motoroute.domain.NavigationManager
import com.motoroute.domain.RouteSimulator
import com.motoroute.domain.VoiceAnnouncement
import com.motoroute.domain.guidance.GermanPhrasebook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RouteFixtures.sampleRideRoute] through the demo pipeline
 * (`RouteSimulator` -> `NavigationManager`, exactly what `RouteSimulator.kt`
 * feeds the app with when a rider tries the demo ride before ever getting on
 * the bike) and prints the complete, timestamped announcement list.
 *
 * This is the artefact `1.Doku/Sprachausgabe.md` points at for a human to
 * read and judge - a machine can check counts and ordering, but only a person
 * can judge whether the *wording* actually makes sense read end to end. It
 * also asserts the properties that matter mechanically: no announcement
 * repeats itself, the hairpin combo gets exactly one warning, the long
 * stretch gets its reassurance, and the ride ends with arrival.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SampleRideAnnouncementsTest {

    private data class Logged(val elapsedMillis: Long, val text: String, val announcement: VoiceAnnouncement)

    @Test
    fun `sample ride prints and validates its full announcement list`() {
        val route = RouteFixtures.sampleRideRoute()
        val simulator = RouteSimulator(route, speedFactor = 1.0)
        val manager = NavigationManager()

        val log = mutableListOf<Logged>()

        // Collect with elapsed-time stamps: a plain toList() would lose when
        // each announcement fired, which is exactly what a human reviewer
        // needs to judge the cadence.
        var elapsed = 0L
        runTest {
            val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.announcements.collect { announcement ->
                    log += Logged(elapsed, GermanPhrasebook.announce(announcement), announcement)
                }
            }
            manager.start(route)
            while (true) {
                val fix = simulator.fixAt(elapsed, elapsed) ?: break
                manager.onLocation(fix, nowMillis = elapsed)
                elapsed += 1_000L
            }
            collectorJob.cancel()
        }

        println("Sample ride: ${route.distanceMeters.toInt()} m at ${simulator.speedMps} m/s")
        println("t [s]  | kind          | text")
        println("-------+----------------+----------------------------------------------")
        for (entry in log) {
            val seconds = entry.elapsedMillis / 1000
            println("%6d | %-14s | %s".format(seconds, entry.announcement.kind, entry.text))
        }

        // -- Mechanical checks a human reviewer should not have to make by eye --

        // The roundabout is announced with its exit, tiered, never duplicated
        // beyond the early/confirm/final cadence.
        val roundabouts = log.filter {
            it.announcement.kind == AnnouncementKind.MANEUVER &&
                it.announcement.roundaboutExit > 0
        }
        assertTrue("expected the roundabout to be announced: $log", roundabouts.isNotEmpty())
        assertTrue("roundabout announced more than the 3 tiers: $roundabouts", roundabouts.size <= 3)

        // The three-hairpin serpentine collapses into exactly one warning.
        val curveWarnings = log.filter { it.announcement.kind == AnnouncementKind.CURVE_WARNING }
        assertEquals("hairpin combo must be one warning, not one per apex: $log", 1, curveWarnings.size)
        assertEquals(3, curveWarnings.single().announcement.comboCount)

        // The ordinary two-turn hand-off is one manoeuvre call with a "then
        // immediately" tail, not two separate cascades.
        val handOff = log.filter { it.announcement.secondManeuver != null }
        assertEquals("expected exactly one hand-off call: $log", 1, handOff.size)

        // The long final stretch earns its one reassurance.
        val freeRides = log.filter { it.announcement.kind == AnnouncementKind.FREE_RIDE }
        assertEquals("expected exactly one free-ride cue: $log", 1, freeRides.size)

        // The ride ends with exactly one arrival call.
        val arrivals = log.filter { it.announcement.kind == AnnouncementKind.ARRIVAL }
        assertEquals(1, arrivals.size)

        // No two announcements ever say the same thing.
        assertEquals("no announcement should repeat verbatim: $log", log.size, log.map { it.text }.toSet().size)
    }
}
