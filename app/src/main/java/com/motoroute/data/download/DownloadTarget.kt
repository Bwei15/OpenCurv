package com.motoroute.data.download

import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.BoundingBox

/**
 * A region the rider can download: one Mapsforge map plus the BRouter tiles
 * that cover it.
 *
 * [path] is the server-relative path under the Mapsforge maps root, e.g.
 * "europe/germany/bayern" - the same tree the download server uses.
 */
data class MapRegion(
    val path: String,
    val name: String,
    val country: String,
    val bounds: BoundingBox,
    /** Rough download size in MB. Only for planning; the real size comes from the server. */
    val approxSizeMb: Int,
    val mapFile: String? = null,
    val mapUrl: String? = null,
    val customSegmentTiles: List<String>? = null,
    val segmentUrls: Map<String, String> = emptyMap(),
) {
    val fileName: String get() = mapFile ?: (path.substringAfterLast('/') + ".map")

    /** The BRouter tiles a route inside this region needs. */
    val segmentTiles: List<String> get() = customSegmentTiles ?: SegmentTiles.covering(bounds)
}

/**
 * One file to fetch.
 *
 * [regionPath] and [regionName] are what let the queue talk about "Niedersachsen,
 * file 3 of 5" instead of listing five file names the rider never asked for.
 */
data class DownloadTarget(
    val url: String,
    val fileName: String,
    val kind: OfflineFileKind,
    val label: String,
    val regionPath: String? = null,
    val regionName: String? = null,
) {
    companion object {
        /**
         * Hosts are fixed at compile time and mirrored in the network
         * security config, so the app cannot be talked into fetching from
         * anywhere else - not by a corrupted catalog, and not by a redirect.
         */
        const val MAP_HOST = "download.mapsforge.org"
        const val SEGMENT_HOST = "brouter.de"
        const val GITHUB_HOST = "github.com"
        const val GITHUB_OBJECTS_HOST = "objects.githubusercontent.com"

        const val MAP_BASE = "https://$MAP_HOST/maps/v5/"
        const val SEGMENT_BASE = "https://$SEGMENT_HOST/brouter/segments4/"

        val ALLOWED_HOSTS = setOf(MAP_HOST, SEGMENT_HOST, GITHUB_HOST, GITHUB_OBJECTS_HOST)

        fun map(region: MapRegion): DownloadTarget {
            val isPm = region.fileName.endsWith(".pmtiles", ignoreCase = true)
            val defaultUrl = MAP_BASE + region.path + (if (isPm) ".pmtiles" else ".map")
            return DownloadTarget(
                url = region.mapUrl ?: defaultUrl,
                fileName = region.fileName,
                kind = OfflineFileKind.MAP,
                label = region.name,
                regionPath = region.path,
                regionName = region.name,
            )
        }

        fun pmtiles(
            url: String,
            fileName: String,
            region: MapRegion? = null,
            label: String? = null,
        ): DownloadTarget = DownloadTarget(
            url = url,
            fileName = fileName,
            kind = OfflineFileKind.MAP,
            label = label ?: region?.name ?: fileName,
            regionPath = region?.path,
            regionName = region?.name,
        )

        fun pmtiles(region: MapRegion): DownloadTarget {
            val fileName = if (region.fileName.endsWith(".pmtiles", ignoreCase = true)) {
                region.fileName
            } else {
                region.path.substringAfterLast('/') + ".pmtiles"
            }
            val url = region.mapUrl ?: (MAP_BASE + region.path + ".pmtiles")
            return DownloadTarget(
                url = url,
                fileName = fileName,
                kind = OfflineFileKind.MAP,
                label = region.name,
                regionPath = region.path,
                regionName = region.name,
            )
        }

        fun segment(tile: String, region: MapRegion? = null): DownloadTarget = DownloadTarget(
            url = region?.segmentUrls?.get(tile) ?: (SEGMENT_BASE + tile),
            fileName = tile,
            kind = OfflineFileKind.SEGMENT,
            label = tile,
            regionPath = region?.path,
            regionName = region?.name,
        )
    }
}

/** Progress of a single download. */
data class DownloadProgress(
    val target: DownloadTarget,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: DownloadState,
    val error: String? = null,
) {
    /** 0..1, or null while the server has not told us the total. */
    val fraction: Float?
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }
