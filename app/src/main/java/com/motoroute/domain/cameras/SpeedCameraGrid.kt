package com.motoroute.domain.cameras

import com.motoroute.data.cameras.SpeedCamera
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A bucket grid over speed-camera positions, so a "what's within 1 km"
 * lookup does not scan the whole region on every GPS fix.
 *
 * A fix arrives about once a second (`data/location/LocationProvider.kt`) and
 * every one of them needs an answer; for a region like Bayern that can be a
 * few thousand cameras. 0.01 degrees is roughly 1.1 km north-south (a little
 * less east-west away from the equator, see [Geo.metersPerDegLon]), which
 * keeps the warner's 1 km search to a handful of neighbouring cells instead
 * of the whole list - the same discipline [Geo] itself is built around.
 *
 * Android-free on purpose, like the rest of `domain/`, so it runs in
 * `tools/verifier` and is exercised directly by
 * [SpeedCameraWarner]'s tests.
 */
class SpeedCameraGrid(private val cameras: List<SpeedCamera>) {

    private val cells: Map<Long, List<SpeedCamera>> =
        cameras.groupBy { cellKeyOf(it.point.latitude, it.point.longitude) }

    fun all(): List<SpeedCamera> = cameras

    /** Every camera within [radiusMeters] of [point], by true distance - not just same cell. */
    fun nearby(point: GeoPoint, radiusMeters: Double): List<SpeedCamera> {
        if (cameras.isEmpty()) return emptyList()

        val latSpanDeg = radiusMeters / Geo.METERS_PER_DEG_LAT
        val lonSpanDeg = radiusMeters / Geo.metersPerDegLon(point.latitude).coerceAtLeast(1.0)
        val latCell = floor(point.latitude / CELL_DEG).toInt()
        val lonCell = floor(point.longitude / CELL_DEG).toInt()
        // +1 so a point that sits right at the edge of its own cell still
        // reaches the neighbour on the far side of the boundary.
        val latRange = ceil(latSpanDeg / CELL_DEG).toInt() + 1
        val lonRange = ceil(lonSpanDeg / CELL_DEG).toInt() + 1

        val result = ArrayList<SpeedCamera>()
        for (dLat in -latRange..latRange) {
            for (dLon in -lonRange..lonRange) {
                val bucket = cells[packCell(latCell + dLat, lonCell + dLon)] ?: continue
                for (camera in bucket) {
                    if (Geo.distanceMeters(point, camera.point) <= radiusMeters) result.add(camera)
                }
            }
        }
        return result
    }

    private fun cellKeyOf(lat: Double, lon: Double): Long =
        packCell(floor(lat / CELL_DEG).toInt(), floor(lon / CELL_DEG).toInt())

    companion object {
        const val CELL_DEG = 0.01

        private fun packCell(latCell: Int, lonCell: Int): Long =
            (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xFFFFFFFFL)
    }
}
