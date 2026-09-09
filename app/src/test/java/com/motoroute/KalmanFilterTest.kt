package com.motoroute

import com.motoroute.data.location.KalmanFilter
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KalmanFilterTest {

    /**
     * A rider heading due north at 90 km/h, with the kind of noise a handlebar
     * mount produces. The filter has to beat the raw signal, not merely track it.
     */
    @Test
    fun `filtering a noisy straight run beats the raw fixes`() {
        val random = Random(1234)
        val filter = KalmanFilter()
        val speedMps = 25.0
        val start = GeoPoint(47.5, 11.5)

        var rawError = 0.0
        var filteredError = 0.0
        var samples = 0

        for (step in 0 until 120) {
            val truth = Geo.offset(start, 0.0, speedMps * step)
            val noisyLat = truth.latitude + (random.nextDouble() - 0.5) * 0.00018
            val noisyLon = truth.longitude + (random.nextDouble() - 0.5) * 0.00018

            val fix = filter.update(
                latitude = noisyLat,
                longitude = noisyLon,
                accuracyMeters = 8f,
                timestampMillis = 1_000L * step,
                speedMps = speedMps,
                bearingDegrees = 0.0,
            )

            if (step > 20) {
                rawError += Geo.distanceMeters(truth, GeoPoint(noisyLat, noisyLon))
                filteredError += Geo.distanceMeters(truth, fix.point)
                samples++
            }
        }

        val raw = rawError / samples
        val filtered = filteredError / samples
        assertTrue("raw=$raw filtered=$filtered", filtered < raw)
    }

    @Test
    fun `speed converges on the true speed`() {
        val filter = KalmanFilter()
        val start = GeoPoint(47.5, 11.5)
        var fix = filter.update(start.latitude, start.longitude, 8f, 0L, 0.0, 0.0)

        for (step in 1..60) {
            val truth = Geo.offset(start, 0.0, 25.0 * step)
            fix = filter.update(
                truth.latitude,
                truth.longitude,
                8f,
                1_000L * step,
                speedMps = 25.0,
                bearingDegrees = 0.0,
            )
        }
        assertEquals(25.0, fix.speedMps, 1.5)
    }

    @Test
    fun `heading follows the direction of travel`() {
        val filter = KalmanFilter()
        val start = GeoPoint(47.5, 11.5)
        filter.update(start.latitude, start.longitude, 8f, 0L, 0.0, 90.0)

        var fix = filter.update(start.latitude, start.longitude, 8f, 0L)
        for (step in 1..40) {
            val truth = Geo.offset(start, 90.0, 20.0 * step)
            fix = filter.update(
                truth.latitude,
                truth.longitude,
                8f,
                1_000L * step,
                speedMps = 20.0,
                bearingDegrees = 90.0,
            )
        }
        assertEquals(90.0, fix.headingDegrees, 5.0)
    }

    @Test
    fun `standing still does not spin the heading`() {
        val filter = KalmanFilter()
        val random = Random(7)
        val point = GeoPoint(47.5, 11.5)

        var last = 0.0
        var maxJump = 0.0
        for (step in 0..40) {
            val fix = filter.update(
                point.latitude + (random.nextDouble() - 0.5) * 0.00008,
                point.longitude + (random.nextDouble() - 0.5) * 0.00008,
                12f,
                1_000L * step,
                speedMps = 0.1,
            )
            if (step > 2) {
                maxJump = maxOf(maxJump, Geo.bearingDifference(last, fix.headingDegrees))
            }
            last = fix.headingDegrees
        }
        assertEquals("heading jumped $maxJump degrees while parked", 0.0, maxJump, 1e-6)
    }

    @Test
    fun `a single wild outlier does not throw the position away`() {
        val filter = KalmanFilter()
        val start = GeoPoint(47.5, 11.5)
        var truth = start
        for (step in 0..30) {
            truth = Geo.offset(start, 0.0, 20.0 * step)
            filter.update(truth.latitude, truth.longitude, 6f, 1_000L * step, 20.0, 0.0)
        }

        // 300 m sideways glitch, reported with poor accuracy.
        val glitch = Geo.offset(truth, 90.0, 300.0)
        val afterGlitch = filter.update(
            glitch.latitude,
            glitch.longitude,
            60f,
            31_000L,
            speedMps = 20.0,
            bearingDegrees = 0.0,
        )
        val pulled = Geo.distanceMeters(truth, afterGlitch.point)
        assertTrue("filter followed the glitch by $pulled m", pulled < 150.0)
    }

    @Test
    fun `reset forgets the previous track`() {
        val filter = KalmanFilter()
        filter.update(47.5, 11.5, 5f, 0L, 20.0, 0.0)
        assertTrue(filter.isInitialised)
        filter.reset()
        org.junit.Assert.assertFalse(filter.isInitialised)
    }
}
