package com.motoroute

import com.motoroute.data.location.FilteredFix
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationManagerTest {

    private val route = RouteFixtures.lShapedRoute()

    private fun fixAt(point: GeoPoint, heading: Double, speed: Double = 20.0, t: Long = 0L) =
        FilteredFix(point, speed, heading, 5.0, t)

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
    fun `announcements fire once per ring, closest ring last`() = runTest {
        val manager = NavigationManager()
        val announcements = mutableListOf<VoiceAnnouncement>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.announcements.toList(announcements)
        }

        manager.start(route)
        // Walk in from 1.6 km out to the corner in 20 m steps.
        for (i in 20..100) {
            manager.onLocation(fixAt(route.points[i], 0.0, t = i * 1000L))
        }

        assertEquals(
            "expected exactly the 1000 / 300 / 50 m rings, got $announcements",
            3,
            announcements.size,
        )
        assertTrue(announcements.all { it.maneuver == Maneuver.TURN_LEFT })
        val distances = announcements.map { it.distanceMeters }
        assertTrue("distances=$distances", distances[0] > distances[1])
        assertTrue("distances=$distances", distances[1] > distances[2])
        assertTrue("last ring should be close, was ${distances[2]}", distances[2] <= 60)
        job.cancel()
    }

    @Test
    fun `an announcement is never spoken with a stale distance`() = runTest {
        val manager = NavigationManager()
        val announcements = mutableListOf<VoiceAnnouncement>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.announcements.toList(announcements)
        }

        manager.start(route)
        // Jump straight from far away to 400 m out, crossing the 1000 m ring
        // without ever being near it - the classic post-reroute case.
        manager.onLocation(fixAt(route.points[80], 0.0))

        assertEquals(1, announcements.size)
        val spoken = announcements.single().distanceMeters
        assertTrue("announced $spoken m while actually 400 m out", spoken in 300..500)
        job.cancel()
    }

    @Test
    fun `two maneuvers in quick succession are flagged as immediate`() = runTest {
        val manager = NavigationManager()
        val announcements = mutableListOf<VoiceAnnouncement>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.announcements.toList(announcements)
        }

        val quick = RouteFixtures.quickSuccessionRoute()
        manager.start(quick)
        for (i in 30..50) manager.onLocation(fixAt(quick.points[i], 0.0, t = i * 1000L))

        val closest = announcements.lastOrNull { it.maneuver == Maneuver.TURN_LEFT }
        assertTrue("no close announcement in $announcements", closest != null)
        assertTrue("should be flagged immediate: $closest", closest!!.isImmediate)
        job.cancel()
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
    fun `replacing the route keeps navigation running and resets the rings`() = runTest {
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
        assertEquals(null, manager.state.value.route)
    }

    @Test
    fun `speeding is only flagged past the tolerance`() {
        val state = com.motoroute.domain.NavigationState(speedMps = 100 / 3.6, speedLimitKmh = 100)
        assertFalse(state.isSpeeding)
        val faster = state.copy(speedMps = 110 / 3.6)
        assertTrue(faster.isSpeeding)
    }
}
