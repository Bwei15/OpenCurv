package com.motoroute.data.model

import com.motoroute.domain.geo.Geo
import kotlin.math.max
import kotlin.math.min

/**
 * A WGS84 position. Elevation is optional because not every source has it.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
) {
    /** BRouter's fixed-point longitude: (lon + 180) * 1e6. */
    fun iLon(): Int = ((longitude + 180.0) * 1_000_000.0 + 0.5).toInt()

    /** BRouter's fixed-point latitude: (lat + 90) * 1e6. */
    fun iLat(): Int = ((latitude + 90.0) * 1_000_000.0 + 0.5).toInt()

    companion object {
        fun fromBRouter(iLon: Int, iLat: Int, elevation: Double? = null): GeoPoint =
            GeoPoint(iLat / 1_000_000.0 - 90.0, iLon / 1_000_000.0 - 180.0, elevation)
    }
}

data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
}

/**
 * A computed route.
 *
 * [cumulativeDistances] holds the distance from the start to every point and is
 * pre-computed once, so remaining distance during navigation is an O(1) lookup
 * instead of a walk over the whole polyline on every GPS fix.
 */
class Route(
    val points: List<GeoPoint>,
    val instructions: List<NavigationInstruction>,
    val profileName: String,
    val estimatedSeconds: Int,
    val ascendMeters: Int = 0,
    /**
     * Legal speed limit in km/h at every route point, 0 where OSM does not say.
     * Read straight out of the routing tiles, so it needs no network and no
     * separate speed database.
     */
    val speedLimitsKmh: IntArray? = null,
) {
    val cumulativeDistances: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) {
            acc[i] = acc[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
        }
    }

    val distanceMeters: Double
        get() = if (cumulativeDistances.isEmpty()) 0.0 else cumulativeDistances.last()

    val isEmpty: Boolean get() = points.size < 2

    val bounds: BoundingBox by lazy {
        var minLat = 90.0
        var minLon = 180.0
        var maxLat = -90.0
        var maxLon = -180.0
        for (p in points) {
            minLat = min(minLat, p.latitude)
            maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude)
            maxLon = max(maxLon, p.longitude)
        }
        if (points.isEmpty()) BoundingBox(0.0, 0.0, 0.0, 0.0)
        else BoundingBox(minLat, minLon, maxLat, maxLon)
    }

    /**
     * How twisty this route is: heading change in degrees per kilometre, with
     * the corners at junctions taken out. A dead straight motorway is around 5,
     * an alpine pass is well past 300. This is what the "curviness" badge in
     * the HUD shows.
     */
    val curvinessScore: Double by lazy { Curviness.score(this) }

    /** Posted speed limit at [index] in km/h, or null when OSM has none. */
    fun speedLimitAt(index: Int): Int? {
        val limits = speedLimitsKmh ?: return null
        if (index < 0 || index >= limits.size) return null
        return limits[index].takeIf { it > 0 }
    }

    /** Distance from the route start to [index], in metres. */
    fun distanceAt(index: Int): Double =
        if (index <= 0) 0.0
        else if (index >= cumulativeDistances.size) distanceMeters
        else cumulativeDistances[index]

    /** Metres still to ride when standing [index] with [alongSegment] metres into it. */
    fun remainingFrom(index: Int, alongSegment: Double): Double =
        (distanceMeters - distanceAt(index) - alongSegment).coerceAtLeast(0.0)

    companion object {
        val EMPTY = Route(emptyList(), emptyList(), "", 0)
    }
}
