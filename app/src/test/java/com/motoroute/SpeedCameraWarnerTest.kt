package com.motoroute

import com.motoroute.data.cameras.SpeedCamera
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.cameras.SpeedCameraGrid
import com.motoroute.domain.cameras.SpeedCameraWarner
import com.motoroute.domain.cameras.SpeedCameraWarning
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedCameraWarnerTest {

    private val camera = GeoPoint(52.0500, 9.5000)

    private fun camera(
        directionDeg: Int? = null,
        maxSpeedKmh: Int? = null,
        point: GeoPoint = camera,
    ) = SpeedCamera(
        id = SpeedCamera.idFor(point.latitude, point.longitude),
        point = point,
        directionDeg = directionDeg,
        maxSpeedKmh = maxSpeedKmh,
    )

    /** A point [meters] from [camera], positioned so bearing(point -> camera) == [bearingToCamera]. */
    private fun riderNear(cameraPoint: GeoPoint, bearingToCamera: Double, meters: Double): GeoPoint =
        Geo.offset(cameraPoint, Geo.normalizeBearing(bearingToCamera - 180.0), meters)

    private fun warnerFor(vararg cameras: SpeedCamera) = SpeedCameraWarner(SpeedCameraGrid(cameras.toList()))

    /** Collects every announcement fired while [body] drives fixes into [warner]. */
    private fun collectAnnouncements(
        warner: SpeedCameraWarner,
        body: () -> Unit,
    ): List<SpeedCameraWarning> {
        val announcements = mutableListOf<SpeedCameraWarning>()
        runTest {
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                warner.announcements.toList(announcements)
            }
            body()
            job.cancel()
        }
        return announcements
    }

    @Test
    fun `heading straight at a camera within range warns`() {
        val cam = camera(maxSpeedKmh = 100)
        val warner = warnerFor(cam)
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        val warning = warner.warning.value
        assertNotNull(warning)
        assertEquals(cam.id, warning!!.camera.id)
        assertEquals(100, warning.maxSpeedKmh)
        assertTrue("distance should be close to 500 m", kotlin.math.abs(warning.distanceMeters - 500.0) < 5.0)
    }

    @Test
    fun `driving away from a camera does not warn`() {
        val cam = camera()
        val warner = warnerFor(cam)
        // Camera is due north of the rider (bearing 0), but the rider is heading south.
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 180.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `a camera off to the side does not warn`() {
        val cam = camera()
        val warner = warnerFor(cam)
        // Camera sits 90 degrees off the rider's heading - passing it, not approaching it.
        val rider = riderNear(cam.point, bearingToCamera = 90.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `a direction-tagged camera facing the other carriageway does not warn`() {
        // The camera watches southbound traffic (faces 180); the rider is
        // heading north straight at its position - wrong carriageway.
        val cam = camera(directionDeg = 180)
        val warner = warnerFor(cam)
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `a direction-tagged camera facing the rider's own carriageway warns`() {
        // The camera watches northbound traffic (faces 0), same as the rider.
        val cam = camera(directionDeg = 0)
        val warner = warnerFor(cam)
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        assertNotNull(warner.warning.value)
    }

    @Test
    fun `below walking speed no new warning starts, heading is not trustworthy`() {
        val cam = camera()
        val warner = warnerFor(cam)
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 1.0, enabled = true, nowMillis = 0L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `disabling the feature clears an active warning immediately`() {
        val cam = camera()
        val warner = warnerFor(cam)
        val rider = riderNear(cam.point, bearingToCamera = 0.0, meters = 500.0)
        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)
        assertNotNull(warner.warning.value)

        warner.onFix(rider, headingDegrees = 0.0, speedMps = 20.0, enabled = false, nowMillis = 1000L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `hysteresis - warning survives a bearing drift that would not have started it fresh`() {
        val cam = camera()
        val warner = warnerFor(cam)

        // Activates cleanly, straight ahead.
        warner.onFix(riderNear(cam.point, 0.0, 500.0), headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)
        assertNotNull(warner.warning.value)

        // Bearing to the camera has drifted to 60 degrees off heading - past
        // the 35-degree fresh-trigger threshold, but well under the 100-degree
        // "passed" release. The warning must stay up.
        val drifted = riderNear(cam.point, 60.0, 400.0)
        warner.onFix(drifted, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 1000L)

        assertNotNull("an active warning should not need to re-qualify against the start threshold", warner.warning.value)
    }

    @Test
    fun `hysteresis - warning clears once the camera is passed`() {
        val cam = camera()
        val warner = warnerFor(cam)
        warner.onFix(riderNear(cam.point, 0.0, 500.0), headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)
        assertNotNull(warner.warning.value)

        // Now well past the camera: it sits 150 degrees off the current heading.
        val passed = riderNear(cam.point, 150.0, 200.0)
        warner.onFix(passed, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 1000L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `hysteresis - warning clears once far enough away`() {
        val cam = camera()
        val warner = warnerFor(cam)
        warner.onFix(riderNear(cam.point, 0.0, 500.0), headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)
        assertNotNull(warner.warning.value)

        val far = riderNear(cam.point, 0.0, 1300.0)
        warner.onFix(far, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 1000L)

        assertNull(warner.warning.value)
    }

    @Test
    fun `one announcement per approach`() {
        val cam = camera(maxSpeedKmh = 70)
        val warner = warnerFor(cam)

        val announcements = collectAnnouncements(warner) {
            // Several fixes while still approaching within one continuous pass.
            warner.onFix(riderNear(cam.point, 0.0, 900.0), 0.0, 20.0, true, 0L)
            warner.onFix(riderNear(cam.point, 0.0, 600.0), 0.0, 20.0, true, 5_000L)
            warner.onFix(riderNear(cam.point, 0.0, 300.0), 0.0, 20.0, true, 10_000L)
        }

        assertEquals(1, announcements.size)
        assertEquals(70, announcements.first().maxSpeedKmh)
    }

    @Test
    fun `cooldown suppresses a second announcement within three minutes but the warning still shows`() {
        val cam = camera()
        val warner = warnerFor(cam)

        val announcements = collectAnnouncements(warner) {
            // First pass: triggers and announces.
            warner.onFix(riderNear(cam.point, 0.0, 500.0), 0.0, 20.0, true, 0L)
            // Passed and released.
            warner.onFix(riderNear(cam.point, 150.0, 200.0), 0.0, 20.0, true, 20_000L)
            assertNull(warner.warning.value)

            // Second pass, 90 s later - inside the 3-minute cooldown.
            warner.onFix(riderNear(cam.point, 0.0, 500.0), 0.0, 20.0, true, 90_000L)
        }

        assertNotNull("the visual warning is not gated by the announce cooldown", warner.warning.value)
        assertEquals("only the first approach should have spoken", 1, announcements.size)
    }

    @Test
    fun `cooldown re-arms after three minutes`() {
        val cam = camera()
        val warner = warnerFor(cam)

        val announcements = collectAnnouncements(warner) {
            warner.onFix(riderNear(cam.point, 0.0, 500.0), 0.0, 20.0, true, 0L)
            warner.onFix(riderNear(cam.point, 150.0, 200.0), 0.0, 20.0, true, 20_000L)

            // Third approach, well past the 3-minute (180_000 ms) cooldown.
            warner.onFix(riderNear(cam.point, 0.0, 500.0), 0.0, 20.0, true, 200_000L)
        }

        assertEquals(2, announcements.size)
    }

    @Test
    fun `grid lookup finds a camera across a cell boundary`() {
        // 0.01 degrees is the cell size (SpeedCameraGrid.CELL_DEG). Put the
        // camera just on the far side of a cell edge from the query point so
        // a naive same-cell-only lookup would miss it.
        val queryPoint = GeoPoint(52.0050, 9.5000)
        val camPoint = GeoPoint(52.0100, 9.5000) // ~556 m north, different 0.01-degree cell
        val cam = camera(point = camPoint)
        val grid = SpeedCameraGrid(listOf(cam))

        val found = grid.nearby(queryPoint, radiusMeters = 1000.0)

        assertEquals(1, found.size)
        assertEquals(cam.id, found.first().id)
    }

    @Test
    fun `grid-boundary camera still triggers a warning end to end`() {
        val camPoint = GeoPoint(52.0100, 9.5000)
        val queryPoint = GeoPoint(52.0050, 9.5000) // south of the camera, different grid cell
        val cam = camera(point = camPoint)
        val warner = warnerFor(cam)

        // queryPoint is south of camPoint, so heading north (0) approaches it.
        warner.onFix(queryPoint, headingDegrees = 0.0, speedMps = 20.0, enabled = true, nowMillis = 0L)

        assertNotNull(warner.warning.value)
    }
}
