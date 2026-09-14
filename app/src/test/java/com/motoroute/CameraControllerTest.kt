package com.motoroute

import com.motoroute.domain.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerTest {

    /** The eased zoom, settled: what the camera converges on if the speed holds. */
    private fun settled(camera: CameraController, speedKmh: Double): Double {
        var zoom = 0.0
        repeat(80) { zoom = camera.zoomFor(speedKmh) }
        return zoom
    }

    @Test
    fun `zoom widens monotonically with speed`() {
        val camera = CameraController()
        val speeds = listOf(0.0, 30.0, 50.0, 70.0, 100.0, 130.0, 180.0, 220.0)
        val zooms = speeds.map { camera.targetZoomFor(it) }
        zooms.zipWithNext { closer, wider ->
            assertTrue("zoom must not tighten as speed rises: $zooms", wider <= closer)
        }
    }

    @Test
    fun `the whole curve stays inside the map's zoom range`() {
        val camera = CameraController()
        for (speed in 0..250 step 5) {
            val zoom = camera.targetZoomFor(speed.toDouble())
            assertTrue(
                "zoom $zoom at $speed km/h out of range",
                zoom in CameraController.MIN_ZOOM..CameraController.MAX_ZOOM,
            )
        }
    }

    @Test
    fun `an overview of the road ahead, not a close-up`() {
        // The ride report on the first version: too close to see anything at
        // touring speed. These ceilings are what "wide enough" means now.
        val camera = CameraController()
        assertTrue("town", camera.targetZoomFor(30.0) <= 16.2)
        assertTrue("Landstraße", camera.targetZoomFor(70.0) <= 14.8)
        assertTrue("fast road", camera.targetZoomFor(110.0) <= 14.0)
        assertTrue("Autobahn", camera.targetZoomFor(130.0) <= 13.4)
    }

    @Test
    fun `zoom is stepless - it eases towards the target instead of jumping`() {
        val camera = CameraController()
        camera.reset(16.5)
        val first = camera.zoomFor(180.0)
        // A single fix must not teleport the camera all the way out.
        assertTrue("first step was $first", first < 16.5 && first > 14.0)
        // ... but holding the speed gets there.
        val end = settled(camera, 180.0)
        assertEquals(camera.targetZoomFor(180.0), end, 0.02)
    }

    @Test
    fun `speed noise around a former band edge no longer flaps the zoom`() {
        val camera = CameraController()
        camera.reset(16.0)
        val a = settled(camera, 40.0)
        val b = camera.zoomFor(41.5)
        val c = camera.zoomFor(38.5)
        // Interpolated, not banded: the changes are sub-perceptual, not a level jump.
        assertTrue("a=$a b=$b", kotlin.math.abs(b - a) < 0.1)
        assertTrue("b=$b c=$c", kotlin.math.abs(c - b) < 0.1)
    }

    @Test
    fun `tilt never drops below the standstill floor and caps at fifty degrees`() {
        // The map stays slightly tilted throughout a ride, even stopped at a light -
        // a flat map used to read as "navigation stopped" rather than "you stopped".
        assertEquals(45f, CameraController.tiltFor(0.0))
        assertEquals(50f, CameraController.tiltFor(90.0))
        val mid = CameraController.tiltFor(12.0)
        assertTrue("mid tilt was $mid", mid > 45f && mid < 50f)
    }
}
