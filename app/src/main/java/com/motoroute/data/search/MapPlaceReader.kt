package com.motoroute.data.search

import kotlinx.coroutines.ensureActive
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Reads searchable names straight out of a Mapsforge map.
 *
 * A `.map` file is a tile pyramid: the low zoom levels hold the few things that
 * matter from far away (cities, towns), the high ones hold everything
 * (villages, streets). That structure is what makes an offline search practical
 * on a phone - the town index comes from a dozen tile reads, and street names,
 * of which a federal state has hundreds of thousands, are only ever read for
 * the handful of tiles around where the rider is looking.
 *
 * Only Mapsforge's reader is used here, no Android types, so the whole thing
 * runs in the JVM test build.
 */
class MapPlaceReader(private val file: File) : AutoCloseable {

    private val mapFile: MapFile? = runCatching { MapFile(file) }.getOrNull()

    val isUsable: Boolean get() = mapFile != null

    val bounds: org.mapsforge.core.model.BoundingBox? get() = mapFile?.boundingBox()

    /** Where the map wants to open: its own start position, else its centre. */
    fun startPosition(): LatLong? =
        mapFile?.let { it.startPosition() ?: it.boundingBox()?.getCenterPoint() }

    /**
     * Every named place node in the map, coarse pass first.
     *
     * [zoom] decides how much of the pyramid is walked: 8 finds cities and
     * towns in seconds, 12 also finds villages and hamlets but has to visit two
     * orders of magnitude more tiles. The caller runs the cheap pass first so
     * search works immediately, then the thorough one in the background.
     */
    suspend fun scanPlaces(
        zoom: Byte,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        emit: (Place) -> Unit,
    ) {
        val map = mapFile ?: return
        val box = map.boundingBox() ?: return

        val minX = MercatorProjection.longitudeToTileX(box.minLongitude, zoom)
        val maxX = MercatorProjection.longitudeToTileX(box.maxLongitude, zoom)
        val minY = MercatorProjection.latitudeToTileY(box.maxLatitude, zoom)
        val maxY = MercatorProjection.latitudeToTileY(box.minLatitude, zoom)

        val total = ((maxX - minX + 1).toLong() * (maxY - minY + 1)).toInt().coerceAtLeast(1)
        var done = 0

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                coroutineContext.ensureActive()
                val tile = Tile(x, y, zoom, TILE_SIZE)
                readPois(map, tile)?.pois?.forEach { poi ->
                    val place = poi.tags.toPlace(poi.position) ?: return@forEach
                    emit(place)
                }
                done++
                if (done % PROGRESS_EVERY == 0 || done == total) onProgress(done, total)
            }
        }
        onProgress(total, total)
    }

    /**
     * Named things near [centre]: streets, and points of interest with a name.
     *
     * Street names live only in the most detailed part of the map, so this is a
     * bounded local scan rather than an index - which is the right trade for a
     * navigator, because "Hauptstraße" is only a useful destination when it is
     * the one nearby.
     */
    suspend fun searchNearby(
        normalisedQuery: String,
        centreLat: Double,
        centreLon: Double,
        radiusMeters: Double,
        limit: Int,
        emit: (Place) -> Unit,
    ) {
        val map = mapFile ?: return
        if (normalisedQuery.length < MIN_NEARBY_QUERY) return

        val latSpan = radiusMeters / 111_320.0
        val lonSpan = latSpan / Math.cos(Math.toRadians(centreLat)).coerceAtLeast(0.1)
        val zoom = NEARBY_ZOOM

        val minX = MercatorProjection.longitudeToTileX(centreLon - lonSpan, zoom)
        val maxX = MercatorProjection.longitudeToTileX(centreLon + lonSpan, zoom)
        val minY = MercatorProjection.latitudeToTileY(centreLat + latSpan, zoom)
        val maxY = MercatorProjection.latitudeToTileY(centreLat - latSpan, zoom)

        val centreX = MercatorProjection.longitudeToTileX(centreLon, zoom)
        val centreY = MercatorProjection.latitudeToTileY(centreLat, zoom)

        // Nearest tiles first: the rider is far more likely to mean the street
        // under their wheels than one at the edge of the search radius, and a
        // scan that is cut short has then already found the best answers.
        val tiles = ArrayList<Tile>()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                tiles += Tile(x, y, zoom, TILE_SIZE)
            }
        }
        tiles.sortBy { tile ->
            val dx = (tile.tileX - centreX).toLong()
            val dy = (tile.tileY - centreY).toLong()
            dx * dx + dy * dy
        }

        var found = 0
        val seen = HashSet<String>()
        for (tile in tiles) {
            coroutineContext.ensureActive()
            val result = readNamed(map, tile) ?: continue

            result.pois.forEach { poi ->
                val place = poi.tags.toNamedPoi(poi.position) ?: return@forEach
                if (PlaceQuery.score(place.name, normalisedQuery) == 0) return@forEach
                if (seen.add(place.dedupeKey)) {
                    emit(place)
                    found++
                }
            }

            result.ways.forEach { way ->
                val position = way.labelPosition
                    ?: way.latLongs.firstOrNull()?.firstOrNull()
                    ?: return@forEach
                val place = way.tags.toStreet(position) ?: return@forEach
                if (PlaceQuery.score(place.name, normalisedQuery) == 0) return@forEach
                if (seen.add(place.dedupeKey)) {
                    emit(place)
                    found++
                }
            }

            if (found >= limit) return
        }
    }

    override fun close() {
        runCatching { mapFile?.close() }
    }

    private fun readPois(map: MapFile, tile: Tile): MapReadResult? =
        runCatching { if (map.supportsTile(tile)) map.readPoiData(tile) else null }.getOrNull()

    private fun readNamed(map: MapFile, tile: Tile): MapReadResult? =
        runCatching { if (map.supportsTile(tile)) map.readNamedItems(tile) else null }.getOrNull()

    private fun List<org.mapsforge.core.model.Tag>.value(key: String): String? =
        firstOrNull { it.key == key }?.value?.takeIf { it.isNotBlank() }

    private fun List<org.mapsforge.core.model.Tag>.toPlace(position: LatLong): Place? {
        val name = value("name") ?: return null
        val kind = value("place")?.let { PlaceKind.ofPlaceTag(it) } ?: return null
        return Place(name, kind, position.latitude, position.longitude)
    }

    private fun List<org.mapsforge.core.model.Tag>.toNamedPoi(position: LatLong): Place? {
        val name = value("name") ?: return null
        value("place")?.let { tag ->
            PlaceKind.ofPlaceTag(tag)?.let {
                return Place(name, it, position.latitude, position.longitude)
            }
        }
        val amenity = value("amenity")
        val kind = if (amenity == "fuel") PlaceKind.FUEL else PlaceKind.POI
        val detail = amenity ?: value("tourism") ?: value("shop")
        return Place(name, kind, position.latitude, position.longitude, detail)
    }

    private fun List<org.mapsforge.core.model.Tag>.toStreet(position: LatLong): Place? {
        val name = value("name") ?: return null
        val highway = value("highway") ?: return null
        if (highway in IGNORED_HIGHWAYS) return null
        return Place(name, PlaceKind.STREET, position.latitude, position.longitude, value("ref"))
    }

    private companion object {
        const val TILE_SIZE = 256

        /** Streets and POIs only exist in the most detailed sub-file. */
        const val NEARBY_ZOOM: Byte = 14

        const val MIN_NEARBY_QUERY = 3

        val IGNORED_HIGHWAYS = setOf("footway", "path", "steps", "cycleway", "bridleway")

        const val PROGRESS_EVERY = 8
    }
}
