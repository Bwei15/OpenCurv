package com.motoroute.data.download

import com.motoroute.data.model.BoundingBox
import kotlin.math.floor

/**
 * Works out which BRouter routing tiles cover a region.
 *
 * BRouter cuts the world into 5x5 degree tiles named after their south-west
 * corner: `E5_N45.rd5` covers longitude 5..10 and latitude 45..50, `W5_N50`
 * covers -5..0 and 50..55. Deriving the tile list from a map region's bounding
 * box is what removes the worst part of setting this app up by hand - working
 * out that Bavaria needs exactly four files, and which four.
 */
object SegmentTiles {

    const val TILE_DEGREES = 5

    /** Every tile touching [box], south-west to north-east. */
    fun covering(box: BoundingBox): List<String> {
        val latFrom = snap(box.minLat)
        val latTo = snap(box.maxLat)
        val lonFrom = snap(box.minLon)
        val lonTo = snap(box.maxLon)

        val tiles = ArrayList<String>()
        var lat = latFrom
        while (lat <= latTo) {
            var lon = lonFrom
            while (lon <= lonTo) {
                tiles += name(lon, lat)
                lon += TILE_DEGREES
            }
            lat += TILE_DEGREES
        }
        return tiles
    }

    /** File name of the tile whose south-west corner is [lon], [lat]. */
    fun name(lon: Int, lat: Int): String {
        val ew = if (lon < 0) "W${-lon}" else "E$lon"
        val ns = if (lat < 0) "S${-lat}" else "N$lat"
        return "${ew}_$ns.rd5"
    }

    /** The tile containing a single position. */
    fun containing(latitude: Double, longitude: Double): String =
        name(snap(longitude), snap(latitude))

    /** Rounds a coordinate down to the tile grid. */
    private fun snap(degrees: Double): Int =
        (floor(degrees / TILE_DEGREES) * TILE_DEGREES).toInt()
}
