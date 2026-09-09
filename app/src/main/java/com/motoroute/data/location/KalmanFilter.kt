package com.motoroute.data.location

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Result of one filter step.
 *
 * [headingDegrees] is derived from the filtered velocity, which is exactly why
 * the filter exists: a raw GPS bearing on a vibrating handlebar mount jumps by
 * tens of degrees while standing at a light, and a map rotating to that is
 * unusable.
 */
data class FilteredFix(
    val point: GeoPoint,
    val speedMps: Double,
    val headingDegrees: Double,
    val accuracyMeters: Double,
    val timestampMillis: Long,
)

/**
 * A linear Kalman filter with a constant-velocity model over a local tangent
 * plane (east/north metres relative to an anchor point).
 *
 * State  x = [east, north, vEast, vNorth]
 * Motion x' = F x  with F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]]
 * Sensor z = [east, north]  (plus an optional speed pseudo-measurement)
 *
 * The covariance is kept as a full 4x4 matrix but the update is written out by
 * hand - a 2x2 inversion - because this runs on every fix on a phone that is
 * also rendering a map.
 *
 * The filter is deliberately framework-free so it can be unit tested on a
 * desktop JVM.
 */
class KalmanFilter(
    /**
     * Process noise as acceleration spectral density in m^2/s^3. Higher = trust
     * the new measurement more. 1.2 tracks a motorcycle changing speed briskly
     * without letting vibration through.
     */
    private val processNoise: Double = 1.2,
    /** Floor for the reported accuracy; Android sometimes claims absurd precision. */
    private val minAccuracyMeters: Double = 3.0,
) {

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var metersPerDegLon = Geo.METERS_PER_DEG_LAT

    // state
    private var east = 0.0
    private var north = 0.0
    private var vEast = 0.0
    private var vNorth = 0.0

    // covariance, row-major 4x4
    private val p = DoubleArray(16)

    private var lastTimeMillis = 0L
    var isInitialised = false
        private set

    fun reset() {
        isInitialised = false
        java.util.Arrays.fill(p, 0.0)
    }

    /**
     * Feeds one raw fix in and returns the smoothed estimate.
     *
     * @param speedMps optional GPS speed; when present it is used as an extra
     *   1-D measurement on the velocity magnitude, which pulls the filter onto
     *   the true speed far faster than positions alone can.
     */
    fun update(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        timestampMillis: Long,
        speedMps: Double? = null,
        bearingDegrees: Double? = null,
    ): FilteredFix {
        val acc = max(minAccuracyMeters, accuracyMeters.toDouble())

        if (!isInitialised || timestampMillis <= lastTimeMillis) {
            initialise(latitude, longitude, acc, timestampMillis, speedMps, bearingDegrees)
            return currentFix(acc, timestampMillis)
        }

        // Re-anchor when we have moved far enough that the flat-earth
        // approximation would start to bite (and to keep numbers small).
        if (abs(latitude - anchorLat) > 0.05 || abs(longitude - anchorLon) > 0.05) {
            reanchor(latitude, longitude)
        }

        val dt = ((timestampMillis - lastTimeMillis) / 1000.0).coerceIn(0.001, 5.0)
        lastTimeMillis = timestampMillis

        predict(dt)
        correctPosition(latitude, longitude, acc)
        speedMps?.let { correctSpeed(it, bearingDegrees) }

        return currentFix(acc, timestampMillis)
    }

    private fun initialise(
        latitude: Double,
        longitude: Double,
        acc: Double,
        timestampMillis: Long,
        speedMps: Double?,
        bearingDegrees: Double?,
    ) {
        anchorLat = latitude
        anchorLon = longitude
        metersPerDegLon = Geo.metersPerDegLon(latitude)
        east = 0.0
        north = 0.0
        val speed = speedMps ?: 0.0
        val bearing = bearingDegrees ?: 0.0
        vEast = speed * Math.sin(Math.toRadians(bearing))
        vNorth = speed * Math.cos(Math.toRadians(bearing))

        java.util.Arrays.fill(p, 0.0)
        p[0] = acc * acc
        p[5] = acc * acc
        p[10] = 25.0
        p[15] = 25.0

        lastTimeMillis = timestampMillis
        isInitialised = true
    }

    private fun reanchor(latitude: Double, longitude: Double) {
        val absoluteLon = anchorLon + east / metersPerDegLon
        val absoluteLat = anchorLat + north / Geo.METERS_PER_DEG_LAT
        val newMetersPerDegLon = Geo.metersPerDegLon(latitude)
        anchorLat = latitude
        anchorLon = longitude
        metersPerDegLon = newMetersPerDegLon
        east = (absoluteLon - anchorLon) * metersPerDegLon
        north = (absoluteLat - anchorLat) * Geo.METERS_PER_DEG_LAT
    }

    /** x' = F x, P' = F P F^T + Q */
    private fun predict(dt: Double) {
        east += vEast * dt
        north += vNorth * dt

        // P = F P F^T, written out for F = I + dt on the velocity block.
        val a = p.copyOf()
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                var v = a[r * 4 + c]
                if (r < 2) v += dt * a[(r + 2) * 4 + c]
                if (c < 2) v += dt * a[r * 4 + c + 2]
                if (r < 2 && c < 2) v += dt * dt * a[(r + 2) * 4 + c + 2]
                p[r * 4 + c] = v
            }
        }

        // Q for a constant-velocity model driven by white acceleration noise.
        val dt2 = dt * dt
        val dt3 = dt2 * dt / 2.0
        val dt4 = dt2 * dt2 / 4.0
        p[0] += processNoise * dt4
        p[5] += processNoise * dt4
        p[2] += processNoise * dt3
        p[8] += processNoise * dt3
        p[7] += processNoise * dt3
        p[13] += processNoise * dt3
        p[10] += processNoise * dt2
        p[15] += processNoise * dt2
    }

    /** Standard 2-D position update with R = acc^2 I. */
    private fun correctPosition(latitude: Double, longitude: Double, acc: Double) {
        val zEast = (longitude - anchorLon) * metersPerDegLon
        val zNorth = (latitude - anchorLat) * Geo.METERS_PER_DEG_LAT

        val r = acc * acc
        // S = H P H^T + R, with H selecting the two position rows.
        val s00 = p[0] + r
        val s01 = p[1]
        val s10 = p[4]
        val s11 = p[5] + r
        val det = s00 * s11 - s01 * s10
        if (abs(det) < 1e-9) return
        val i00 = s11 / det
        val i01 = -s01 / det
        val i10 = -s10 / det
        val i11 = s00 / det

        // K = P H^T S^-1  (4x2)
        val k = DoubleArray(8)
        for (row in 0 until 4) {
            val a = p[row * 4]
            val b = p[row * 4 + 1]
            k[row * 2] = a * i00 + b * i10
            k[row * 2 + 1] = a * i01 + b * i11
        }

        val yEast = zEast - east
        val yNorth = zNorth - north
        east += k[0] * yEast + k[1] * yNorth
        north += k[2] * yEast + k[3] * yNorth
        vEast += k[4] * yEast + k[5] * yNorth
        vNorth += k[6] * yEast + k[7] * yNorth

        // P = (I - K H) P
        val a = p.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                p[row * 4 + col] = a[row * 4 + col] -
                    k[row * 2] * a[col] - k[row * 2 + 1] * a[4 + col]
            }
        }
    }

    /**
     * Nudges the velocity state towards the receiver's own Doppler speed.
     *
     * This is a scalar gain rather than a full Kalman update: the GPS speed and
     * the position stream are not independent, so a textbook update would make
     * the filter over-confident. A fixed blend is honest and stable.
     */
    private fun correctSpeed(speedMps: Double, bearingDegrees: Double?) {
        val current = hypot(vEast, vNorth)
        val blend = 0.35

        if (speedMps < 0.6) {
            // Standing still: kill the velocity so heading stops spinning.
            vEast *= 0.2
            vNorth *= 0.2
            return
        }

        val bearing = bearingDegrees ?: if (current > 0.3) {
            Math.toDegrees(atan2(vEast, vNorth))
        } else {
            return
        }
        val rad = Math.toRadians(bearing)
        val targetEast = speedMps * Math.sin(rad)
        val targetNorth = speedMps * Math.cos(rad)
        vEast += blend * (targetEast - vEast)
        vNorth += blend * (targetNorth - vNorth)
    }

    private fun currentFix(acc: Double, timestampMillis: Long): FilteredFix {
        val lat = anchorLat + north / Geo.METERS_PER_DEG_LAT
        val lon = anchorLon + east / metersPerDegLon
        val speed = hypot(vEast, vNorth)
        val heading = if (speed > 0.7) {
            Geo.normalizeBearing(Math.toDegrees(atan2(vEast, vNorth)))
        } else {
            lastHeading
        }
        lastHeading = heading
        return FilteredFix(
            point = GeoPoint(lat, lon),
            speedMps = speed,
            headingDegrees = heading,
            accuracyMeters = min(acc, kotlin.math.sqrt(max(p[0], p[5]))),
            timestampMillis = timestampMillis,
        )
    }

    private var lastHeading = 0.0
}
