package com.motoroute

import com.motoroute.domain.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerTest {

    @Test
    fun `zoom follows the speed bands from the cockpit spec`() {
        val camera = CameraController()
        assertTrue("town zoom", camera.zoomFor(15.0) >= 17)
        camera.reset()
        assertTrue("village zoom", camera.zoomFor(30.0) in 17..18)
        camera.reset()
        assertTrue("country road zoom", camera.zoomFor(70.0) in 15..16)
        camera.reset()
        assertTrue("fast road zoom", camera.zoomFor(110.0) in 13..14)
    }

    @Test
    fun `hysteresis stops the map flapping at a band edge`() {
        val camera = CameraController()
        camera.reset(16)
        // Hovering either side of the 40 km/h boundary must not change zoom.
        val first = camera.zoomFor(39.0)
        val second = camera.zoomFor(41.0)
        val third = camera.zoomFor(38.5)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `a decisive speed change does move the zoom`() {
        val camera = CameraController()
        camera.reset(16)
        assertEquals(18, camera.zoomFor(10.0))
        assertEquals(13, camera.zoomFor(140.0))
    }

    @Test
    fun `tilt ramps in from standstill and caps at fifty degrees`() {
        assertEquals(0f, CameraController.tiltFor(0.0))
        assertEquals(50f, CameraController.tiltFor(90.0))
        val mid = CameraController.tiltFor(12.0)
        assertTrue("mid tilt was $mid", mid > 0f && mid < 50f)
    }
}
