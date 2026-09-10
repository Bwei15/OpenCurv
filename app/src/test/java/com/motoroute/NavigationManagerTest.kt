package com.motoroute

import com.motoroute.data.location.FilteredFix
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.domain.AnnouncementKind
import com.motoroute.domain.NavigationManager
import com.motoroute.domain.VoiceAnnouncement
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationManagerTest {

    private val route = RouteFixtures.lShapedRoute()

    private fun fixAt(point: GeoPoint, heading: Double, speed: Double = 20.0, t: Long = 0L) =
        FilteredFix(point, speed, heading, 5.0, t)

    /** Collects every announcement fired while [body] drives fixes into [manager]. */
    private fun collectAnnouncements(
        manager: NavigationManager,
        body: () -> Unit,
    ): List<VoiceAnnouncement> {
        val announcements = mutableListOf<VoiceAnnouncement>()
        runTest {
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.announcements.toList(announcements)
            }
            body()
            job.cancel()
        }
        return announcements
    }

    @Test
    fun `distance to the maneuver counts down as the rider approaches`() {
        val manager = NavigationManager()
        manager.start(route)

        manager.onLocation(fixAt(route.points[10], 0.0))
        val far = manager.state.value.distanceToManeuverMeters
        manager.onLocation(fixAt(route.points[80], 0.0))
        val near = manager.state.value.distanceToManeuverMeters

        assertTrue("far=$far near=$near", near < far)
        assertEquals(400.0, near, 25.0)
    }

    @Test
    fun `the state machine steps on once the maneuver is passed`() {
        val manager = NavigationManager()
        manager.start(route)

        manager.onLocation(fixAt(route.points[99], 0.0))
        assertEquals(Maneuver.TURN_LEFT, manager.state.value.current?.maneuver)

        // 40 m past the corner - well beyond the 15 m threshold.
        manager.onLocation(fixAt(route.points[102], 270.0))
        assertEquals(Maneuver.DESTINATION, manager.state.value.current?.maneuver)
    }

    @Test
    fun `the maneuver does not step on while the rider is still short of it`() {
        val manager = NavigationManager()
        manager.start(route)
        manager.onLocation(fixAt(route.points[100], 0.0))
        assertEquals(Maneuver.TURN_LEFT, manager.state.value.current?.maneuver)
    }

    @Test
    fun `a single manoeuvre gets exactly the early, confirm and final calls`() {
        val manager = NavigationManager()
        val announcements = collectAnnouncements(manager) {
            manager.start(route)
            // Walk in from 1.6 km out to the corner in 20 m steps at 20 m/s
            // (rural speed: early at 300 m, confirm at 140 m, final at 60 m).
            for (i in 20..100) {
                manager.onLocation(fixAt(route.points[i], 0.0, t = i * 1000L))
            }
        }

        assertEquals(
            "expected exactly the early / confirm / final calls, got $announcements",
            3,
            announcements.size,
        )
        assertTrue(announcements.all { it.kind == AnnouncementKind.MANEUVER })
        assertTrue(announcements.all { it.maneuver == Maneuver.TURN_LEFT })
        val distances = announcements.map { it.distanceMeters }
        assertTrue("distances=$distances", distances[0] > distances[1])
        assertTrue("distances=$distances", distances[1] > distances[2])
        assertEquals(listOf(false, false, true), announcements.map { it.isFinal })
    }

    @Test
    fun `landing already close after a jump speaks once, at the final tier`() {
        // The classic post-reroute case: a fix lands well inside every tier at
        // once. The old distance-ring code fired the widest ring immediately
        // and left the narrower ones to fire on the next two fixes a second or
        // two later - three near-identical "turn left" calls back to back.
        // The fix must speak exactly once, using the deepest tier already
        // reached.
        val manager = NavigationManager()
        val announcements = collectAnnouncements(manager) {
            manager.start(route)
            manager.onLocation(fixAt(route.points[98], 0.0)) // 40 m out at 20 m/s
        }

        assertEquals(1, announcements.size)
        val only = announcements.single()
        assertTrue(only.isFinal)
        assertTrue("announced ${only.distanceMeters} m while actually ~40 m out", only.distanceMeters in 20..60)
    }

    @Test
    fun `closely spaced manoeuvres are announced once, not once per instruction`() {
        // Reproduces the reported "kommt oft mehrfach" bug: on quickSuccessionRoute
        // the second turn sits only 80 m after the first, which on this app's own
        // curvy routes is the common case, not an edge case. Before the fix this
        // produced six announcements (three full early/confirm/final cascades,
        // one per instruction); it must now produce exactly the three calls for
        // the first manoeuvre, with the second folded into the final call as a
        // "then immediately" hand-off, and nothing separate for the second one.
        val manager = NavigationManager()
        val quick = RouteFixtures.quickSuccessionRoute()
        val announcements = collectAnnouncements(manager) {
            manager.start(quick)
            for (i in 30..58) manager.onLocation(fixAt(quick.points[i], 0.0, t = i * 1000L))
        }

        assertEquals("expected no duplicate cascade, got $announcements", 3, announcements.size)
        assertTrue(announcements.all { it.maneuver == Maneuver.TURN_LEFT })
        assertTrue(announcements.none { it.kind == AnnouncementKind.CURVE_WARNING })
        val final = announcements.last()
        assertTrue(final.isFinal)
        assertEquals(Maneuver.TURN_RIGHT, final.secondManeuver)
    }

    @Test
    fun `a serpentine of hairpins gets one warning, not one call per apex`() {
        val manager = NavigationManager()
        val serpentine = RouteFixtures.serpentineRoute()
        val announcements = collectAnnouncements(manager) {
            manager.start(serpentine)
            // Walk from well before the first hairpin to well past the last one.
            for (i in 60..115) manager.onLocation(fixAt(serpentine.points[i], 0.0, t = i * 1000L))
        }

        val warnings = announcements.filter { it.kind == AnnouncementKind.CURVE_WARNING }
        assertEquals("expected exactly one combo warning, got $announcements", 1, warnings.size)
        assertEquals(3, warnings.single().comboCount)
        assertTrue(announcements.none { it.kind == AnnouncementKind.MANEUVER })
    }

    @Test
    fun `a lone hairpin is warned about, not narrated with a plain turn call`() {
        val manager = NavigationManager()
        val lonely = RouteFixtures.lonelyHairpinRoute()
        val announcements = collectAnnouncements(manager) {
            manager.start(lonely)
            for (i in 20..100) manager.onLocation(fixAt(lonely.points[i], 0.0, t = i * 1000L))
        }

        assertTrue(announcements.isNotEmpty())
        assertTrue(announcements.all { it.kind == AnnouncementKind.CURVE_WARNING })
        assertEquals(1, announcements.size)
        assertEquals(Maneuver.HAIRPIN_LEFT, announcements.first().maneuver)
    }

    @Test
    fun `a roundabout names its exit`() {
        val manager = NavigationManager()
        val r = RouteFixtures.sampleRideRoute()
        val announcements = collectAnnouncements(manager) {
            manager.start(r)
            for (i in 60..105) manager.onLocation(fixAt(r.points[i], 0.0, t = i * 1000L))
        }

        val roundabout = announcements.firstOrNull { it.maneuver == Maneuver.ROUNDABOUT }
        assertTrue("no roundabout announcement in $announcements", roundabout != null)
        assertEquals(2, roundabout!!.roundaboutExit)
    }

    @Test
    fun `a long quiet stretch earns one reassurance, not silence all the way`() {
        val manager = NavigationManager()
        val long = RouteFixtures.longStraightRoute(totalMeters = 20_000.0)
        val simulator = com.motoroute.domain.RouteSimulator(long, speedFactor = 1.0)
        val announcements = collectAnnouncements(manager) {
            manager.start(long)
            var elapsed = 0L
            while (true) {
                val fix = simulator.fixAt(elapsed, elapsed) ?: break
                manager.onLocation(fix, nowMillis = elapsed)
                elapsed += 5_000L // 5 s per fix - keeps the test fast
            }
        }

        val freeRides = announcements.filter { it.kind == AnnouncementKind.FREE_RIDE }
        assertEquals("expected exactly one free-ride cue, got $announcements", 1, freeRides.size)
        assertTrue(freeRides.single().freeRideKm > 0)
    }

    @Test
    fun `active cornering defers a non-final call but never drops it`() {
        val manager = NavigationManager()
        var t = 0L
        val announcements = collectAnnouncements(manager) {
            manager.start(route)
            // A calm baseline fix well before the corner is due, so the swing
            // below has a real heading to measure a real rate against.
            manager.onLocation(fixAt(route.points[70], 0.0, t = t), nowMillis = t)
            t += 300L
            // Now sit exactly on the early tier's distance (300 m) and whip the
            // heading back and forth hard enough to look like a real lean - the
            // call must wait rather than fire into an unrelated apex.
            for (i in 0..20) {
                val heading = if (i % 2 == 0) 45.0 else -45.0
                manager.onLocation(fixAt(route.points[85], heading, t = t), nowMillis = t)
                t += 300L // 21 steps * 300 ms = 6.3 s, past the 4 s defer cap.
            }
        }
        assertTrue(
            "cornering deferred the call past its 4 s safety cap, or dropped it: $announcements",
            announcements.isNotEmpty(),
        )
        assertFalse("the deferred call is not final and must not skip the queue", announcements.first().isFinal)
    }

    @Test
    fun `leaving the route asks for a reroute only after several fixes`() {
        val manager = NavigationManager()
        manager.start(route)
        manager.onLocation(fixAt(route.points[20], 0.0))

        val away = Geo.offset(route.points[22], 90.0, 120.0)
        assertFalse(manager.onLocation(fixAt(away, 90.0)))
        assertFalse(manager.onLocation(fixAt(away, 90.0)))
        assertTrue("third off-route fix should trigger", manager.onLocation(fixAt(away, 90.0)))
        assertTrue(manager.state.value.isOffRoute)
    }

    @Test
    fun `a brief wobble inside the corridor never triggers a reroute`() {
        val manager = NavigationManager()
        manager.start(route)
        for (i in 20..40) {
            val wobble = Geo.offset(route.points[i], 90.0, 25.0)
            assertFalse(manager.onLocation(fixAt(wobble, 0.0)))
        }
        assertFalse(manager.state.value.isOffRoute)
    }

    @Test
    fun `arrival is reported at the end of the route`() {
        val manager = NavigationManager()
        manager.start(route)
        manager.onLocation(fixAt(route.points.last(), 270.0, speed = 2.0))
        assertTrue(manager.state.value.hasArrived)
    }

    @Test
    fun `remaining distance and eta shrink along the route`() {
        val manager = NavigationManager()
        manager.start(route)

        manager.onLocation(fixAt(route.points[10], 0.0, t = 10_000))
        val early = manager.state.value
        manager.onLocation(fixAt(route.points[150], 270.0, t = 150_000))
        val late = manager.state.value

        assertTrue(late.remainingDistanceMeters < early.remainingDistanceMeters)
        assertTrue(late.remainingSeconds < early.remainingSeconds)
        assertTrue(late.etaEpochMillis > 0)
    }

    @Test
    fun `replacing the route keeps navigation running and resets the tiers`() {
        val manager = NavigationManager()
        manager.start(route)
        manager.onLocation(fixAt(route.points[95], 0.0))
        assertTrue(manager.state.value.isNavigating)

        manager.setRerouting(true)
        assertTrue(manager.state.value.isRerouting)

        manager.replaceRoute(RouteFixtures.quickSuccessionRoute())
        val state = manager.state.value
        assertTrue(state.isNavigating)
        assertFalse(state.isRerouting)
        assertFalse(state.isOffRoute)
        assertEquals(Maneuver.TURN_LEFT, state.current?.maneuver)
    }

    @Test
    fun `stopping clears everything`() {
        val manager = NavigationManager()
        manager.start(route)
        manager.onLocation(fixAt(route.points[10], 0.0))
        manager.stop()
        assertFalse(manager.state.value.isNavigating)
        assertNull(manager.state.value.route)
    }

    @Test
    fun `speeding is only flagged past the tolerance`() {
        val state = com.motoroute.domain.NavigationState(speedMps = 100 / 3.6, speedLimitKmh = 100)
        assertFalse(state.isSpeeding)
        val faster = state.copy(speedMps = 110 / 3.6)
        assertTrue(faster.isSpeeding)
    }
}
