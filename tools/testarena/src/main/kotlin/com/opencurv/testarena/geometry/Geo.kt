package com.opencurv.testarena.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Testarena lives in the open Atlantic south-west of the West African
 * coast, a few dozen kilometres off Null Island (0°N 0°E) so it can never be
 * confused with - or accidentally merged into - real map data, and so it
 * does not collide with the many test fixtures that deliberately sit exactly
 * on 0/0. See tools/testarena/README.md ("Warum dieser Ort?").
 */
object ArenaOrigin {
    const val LAT_DEG: Double = 0.30
    const val LON_DEG: Double = -1.00
}

/** WGS84-ish spherical mean radius, good enough for a synthetic flat map. */
private const val EARTH_RADIUS_M = 6378137.0
private const val METERS_PER_DEGREE_LAT = (Math.PI / 180.0) * EARTH_RADIUS_M

/**
 * A local, flat, metres-based ENU (east/north) plane anchored at
 * [ArenaOrigin]. All arena geometry is built in this plane and only
 * projected to lat/lon at the very end (in [OsmXmlWriter] / [OsmPbfWriter]).
 *
 * The projection is a simple equirectangular one, scaled by the cosine of
 * the origin latitude (not recomputed per point). Over the few kilometres
 * the arena spans this introduces sub-millimetre distortion - irrelevant for
 * a synthetic test map - and keeps the mapping a fixed, deterministic affine
 * transform.
 */
object Projection {
    private val metersPerDegreeLon =
        METERS_PER_DEGREE_LAT * cos(Math.toRadians(ArenaOrigin.LAT_DEG))

    fun toLatLon(p: Point): LatLon {
        val lat = ArenaOrigin.LAT_DEG + p.y / METERS_PER_DEGREE_LAT
        val lon = ArenaOrigin.LON_DEG + p.x / metersPerDegreeLon
        return LatLon(lat, lon)
    }

    /** Inverse of [toLatLon] - used by the harness to place an externally-supplied route
     *  (read from GPX/JSON, given in real lat/lon) onto the same local metre plane the
     *  arena geometry was built in, so the two can be compared with plain Euclidean maths. */
    fun toLocal(ll: LatLon): Point {
        val y = (ll.lat - ArenaOrigin.LAT_DEG) * METERS_PER_DEGREE_LAT
        val x = (ll.lon - ArenaOrigin.LON_DEG) * metersPerDegreeLon
        return Point(x, y)
    }
}

/** A point in the local metre plane. x = east, y = north, both from the arena origin. */
data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double {
        val dx = other.x - x
        val dy = other.y - y
        return sqrt(dx * dx + dy * dy)
    }
}

data class LatLon(val lat: Double, val lon: Double)

/**
 * A point plus the compass bearing (degrees, 0 = north, clockwise positive)
 * of travel at that point, and the cumulative distance from the start of the
 * way being built. Optionally carries an elevation in metres (`ele` tag).
 */
data class Vertex(
    val point: Point,
    val headingDeg: Double,
    val distanceFromStartM: Double,
    val elevationM: Double? = null,
)

fun headingToUnitVector(headingDeg: Double): Point {
    val rad = Math.toRadians(headingDeg)
    return Point(sin(rad), cos(rad))
}
