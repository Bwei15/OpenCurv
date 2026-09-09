package com.motoroute.domain.geo

import com.motoroute.data.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, allocation-free geodesy used on every GPS fix.
 *
 * Everything here is the equirectangular ("cheap ruler") approximation: over
 * the few hundred metres a map-matcher ever looks at, its error against the
 * haversine formula is far below GPS noise, and it costs one cosine instead of
 * four trigonometric calls.
 */
object Geo {

    const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /** Metres per degree of latitude. Constant enough for our purposes. */
    const val METERS_PER_DEG_LAT = EARTH_RADIUS_M * DEG_TO_RAD

    /** Metres per degree of longitude at [latitude]. */
    fun metersPerDegLon(latitude: Double): Double =
        METERS_PER_DEG_LAT * cos(latitude * DEG_TO_RAD)

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double =
        distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mPerLon = metersPerDegLon((lat1 + lat2) * 0.5)
        val dx = (lon2 - lon1) * mPerLon
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        return hypot(dx, dy)
    }

    /** Great-circle distance. Used for long legs where the flat approximation drifts. */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.latitude - a.latitude) * DEG_TO_RAD
        val dLon = (b.longitude - a.longitude) * DEG_TO_RAD
        val lat1 = a.latitude * DEG_TO_RAD
        val lat2 = b.latitude * DEG_TO_RAD
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from [a] to [b] in degrees, 0 = north, clockwise. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val mPerLon = metersPerDegLon((a.latitude + b.latitude) * 0.5)
        val dx = (b.longitude - a.longitude) * mPerLon
        val dy = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
        return normalizeBearing(atan2(dx, dy) * RAD_TO_DEG)
    }

    /** Folds any angle into [0, 360). */
    fun normalizeBearing(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Folds any angle into (-180, 180]. Negative = left / counter-clockwise. */
    fun normalizeDelta(degrees: Double): Double {
        var d = degrees % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Smallest absolute angle between two bearings, 0..180. */
    fun bearingDifference(a: Double, b: Double): Double = abs(normalizeDelta(b - a))

    /**
     * Signed heading change at [b] when travelling a -> b -> c.
     * Negative = left turn, positive = right turn.
     */
    fun turnAngleDegrees(a: GeoPoint, b: GeoPoint, c: GeoPoint): Double =
        normalizeDelta(bearingDegrees(b, c) - bearingDegrees(a, b))

    /**
     * Projection of [p] onto the segment [a]..[b].
     *
     * @return [t] the clamped position along the segment (0 = at a, 1 = at b),
     *   [distanceMeters] the perpendicular (cross-track) distance,
     *   [alongMeters] how far along the segment the projection sits.
     */
    fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val mPerLon = metersPerDegLon(a.latitude)
        val ax = 0.0
        val ay = 0.0
        val bx = (b.longitude - a.longitude) * mPerLon
        val by = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
        val px = (p.longitude - a.longitude) * mPerLon
        val py = (p.latitude - a.latitude) * METERS_PER_DEG_LAT

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        val cross = hypot(px - projX, py - projY)
        val point = GeoPoint(
            latitude = a.latitude + projY / METERS_PER_DEG_LAT,
            longitude = a.longitude + projX / mPerLon,
        )
        return Projection(t, cross, t * sqrt(lenSq), point)
    }

    data class Projection(
        val t: Double,
        val distanceMeters: Double,
        val alongMeters: Double,
        val point: GeoPoint,
    )

    /** Moves [from] by [meters] along [bearingDeg]. Used for camera look-ahead. */
    fun offset(from: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
        val rad = bearingDeg * DEG_TO_RAD
        val dLat = meters * cos(rad) / METERS_PER_DEG_LAT
        val dLon = meters * sin(rad) / metersPerDegLon(from.latitude)
        return GeoPoint(from.latitude + dLat, from.longitude + dLon)
    }
}
