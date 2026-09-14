package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.PositionInterpolator
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionInterpolatorTest {

    private val start = GeoPoint(52.0, 9.7)

    @Test
    fun `nothing to draw before the first fix`() {
        assertNull(PositionInterpolator().poseAt(1_000L))
    }

    @Test
    fun `the first fix is drawn exactly where it is, not eased in from nowhere`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 25.0, timestampMillis = 1_000L)
        val pose = interpolator.poseAt(1_000L)
        assertNotNull(pose)
        assertEquals(0.0, Geo.distanceMeters(start, pose!!.point), 0.5)
    }

    @Test
    fun `the puck keeps moving between fixes instead of standing still`() {
        // The whole point: one fix per second must not mean one movement per
        // second. At 25 m/s, half a second of dead reckoning is ~12.5 m.
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 25.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        val mid = interpolator.poseAt(500L)!!
        val travelled = Geo.distanceMeters(start, mid.point)
        assertTrue("moved $travelled m in 500 ms", travelled > 8.0 && travelled < 16.0)
    }

    @Test
    fun `dead reckoning follows the heading, not an arbitrary direction`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 90.0, speedMps = 20.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        val pose = interpolator.poseAt(1_000L)!!
        // Due east: latitude unchanged, longitude up.
        assertEquals(start.latitude, pose.point.latitude, 1e-5)
        assertTrue(pose.point.longitude > start.longitude)
    }

    @Test
    fun `a stopped bike does not drift`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 0.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        val later = interpolator.poseAt(5_000L)!!
        assertEquals(0.0, Geo.distanceMeters(start, later.point), 1.0)
    }

    @Test
    fun `a stale fix stops being extrapolated rather than sliding forever`() {
        // If the GPS drops out, the puck must come to rest, not keep gliding
        // down the road at the last known speed for a minute.
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 30.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        var pose = interpolator.poseAt(3_000L)!!
        // Catch-up is bounded, so give it frames to settle onto the cap.
        for (t in 4_000L..20_000L step 200L) pose = interpolator.poseAt(t)!!
        val travelled = Geo.distanceMeters(start, pose.point)
        // 30 m/s capped at 3 s of extrapolation is 90 m, whatever the clock says.
        assertTrue("drifted $travelled m", travelled in 80.0..100.0)
    }

    @Test
    fun `a small correction is glided, not jumped`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        val before = interpolator.poseAt(1_000L)!!.point

        // A new fix 15 m off where we had drawn - the everyday case.
        val corrected = Geo.offset(before, 90.0, 15.0)
        interpolator.onFix(corrected, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 1_000L)
        val oneFrameLater = interpolator.poseAt(1_016L)!!.point

        val moved = Geo.distanceMeters(before, oneFrameLater)
        assertTrue(
            "one frame moved $moved m - a 15 m correction must not land in one frame",
            moved < 5.0,
        )
    }

    @Test
    fun `a big correction snaps, because gliding there would cross town`() {
        val interpolator = PositionInterpolator(snapMeters = 40.0)
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 0L)
        interpolator.poseAt(0L)

        // Out of a tunnel, 300 m away.
        val far = Geo.offset(start, 90.0, 300.0)
        interpolator.onFix(far, headingDegrees = 90.0, speedMps = 20.0, timestampMillis = 1_000L)
        val pose = interpolator.poseAt(1_016L)!!
        assertEquals(
            "a 300 m error must snap",
            0.0,
            Geo.distanceMeters(far, pose.point),
            5.0,
        )
    }

    @Test
    fun `a glided correction does arrive, given a few frames`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        val target = Geo.offset(start, 90.0, 12.0)
        interpolator.onFix(target, headingDegrees = 90.0, speedMps = 0.0, timestampMillis = 0L)

        var pose = interpolator.poseAt(16L)!!
        for (t in 32L..3_000L step 16L) pose = interpolator.poseAt(t)!!
        assertEquals(0.0, Geo.distanceMeters(target, pose.point), 1.0)
    }

    @Test
    fun `heading turns at a bounded rate so the puck does not twitch`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 0L)
        interpolator.poseAt(0L)
        // A 90-degree swing between two fixes - GPS heading noise on a bumpy road.
        interpolator.onFix(start, headingDegrees = 90.0, speedMps = 20.0, timestampMillis = 0L)
        val oneFrame = interpolator.poseAt(16L)!!
        assertTrue(
            "turned ${oneFrame.headingDegrees} degrees in one frame",
            oneFrame.headingDegrees < 20.0,
        )
        var pose = oneFrame
        for (t in 32L..2_000L step 16L) pose = interpolator.poseAt(t)!!
        assertEquals(90.0, pose.headingDegrees, 1.0)
    }

    @Test
    fun `reset forgets the ride`() {
        val interpolator = PositionInterpolator()
        interpolator.onFix(start, headingDegrees = 0.0, speedMps = 20.0, timestampMillis = 0L)
        assertTrue(interpolator.hasFix)
        interpolator.reset()
        assertNull(interpolator.poseAt(1_000L))
    }
}
