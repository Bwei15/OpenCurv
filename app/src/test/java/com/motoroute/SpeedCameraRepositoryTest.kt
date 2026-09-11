package com.motoroute

import com.motoroute.data.cameras.SpeedCamera
import com.motoroute.data.cameras.SpeedCameraRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Covers the Android-free half of [SpeedCameraRepository] - TSV parsing and
 * duplicate merging - which does not need [android.content.Context] at
 * runtime and so is safe to exercise directly in a plain unit test (unlike
 * [SpeedCameraRepository] as a whole, which is excluded from
 * `tools/verifier` for exactly that dependency).
 */
class SpeedCameraRepositoryTest {

    private fun tsv(vararg lines: String): ByteArrayInputStream =
        ByteArrayInputStream(lines.joinToString("\n").toByteArray(Charsets.UTF_8))

    @Test
    fun `parses a well-formed row`() {
        val cameras = SpeedCameraRepository.parseTsv(
            tsv("52.05000\t9.50000\t180\t100\tA7 Nord"),
        )
        assertEquals(1, cameras.size)
        val cam = cameras.first()
        assertEquals(52.05000, cam.point.latitude, 1e-6)
        assertEquals(9.50000, cam.point.longitude, 1e-6)
        assertEquals(180, cam.directionDeg)
        assertEquals(100, cam.maxSpeedKmh)
        assertEquals("A7 Nord", cam.name)
    }

    @Test
    fun `blank optional fields become null, not zero`() {
        val cameras = SpeedCameraRepository.parseTsv(tsv("52.0\t9.5\t\t\t"))
        val cam = cameras.first()
        assertNull(cam.directionDeg)
        assertNull(cam.maxSpeedKmh)
        assertNull(cam.name)
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val cameras = SpeedCameraRepository.parseTsv(
            tsv(
                "# source: OSM/Overpass, license ODbL",
                "",
                "52.0\t9.5\t\t\t",
                "  ",
            ),
        )
        assertEquals(1, cameras.size)
    }

    @Test
    fun `a malformed row is skipped rather than crashing the whole file`() {
        val cameras = SpeedCameraRepository.parseTsv(
            tsv(
                "not-a-number\t9.5\t\t\t",
                "52.0\t9.5\t\t\t",
            ),
        )
        assertEquals(1, cameras.size)
    }

    @Test
    fun `duplicate ids fill gaps from the later entry, first value wins`() {
        val a = SpeedCamera(id = "x", point = com.motoroute.data.model.GeoPoint(1.0, 2.0), directionDeg = 90, maxSpeedKmh = null, name = null)
        val b = SpeedCamera(id = "x", point = com.motoroute.data.model.GeoPoint(1.0, 2.0), directionDeg = 270, maxSpeedKmh = 80, name = "B7")

        val merged = SpeedCameraRepository.mergeDuplicate(a, b)

        assertEquals(90, merged.directionDeg) // a's own value wins
        assertEquals(80, merged.maxSpeedKmh) // filled in from b
        assertEquals("B7", merged.name) // filled in from b
    }

    @Test
    fun `id is stable for coordinates rounded to five decimals`() {
        assertEquals(SpeedCamera.idFor(52.123456, 9.987654), SpeedCamera.idFor(52.1234561, 9.9876539))
    }
}
