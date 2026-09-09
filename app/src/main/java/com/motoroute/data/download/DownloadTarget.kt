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
) {
    val fileName: String get() = path.substringAfterLast('/') + ".map"

    /** The BRouter tiles a route inside this region needs. */
    val segmentTiles: List<String> get() = SegmentTiles.covering(bounds)
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
         * Both hosts are fixed at compile time and mirrored in the network
         * security config, so the app cannot be talked into fetching from
         * anywhere else - not by a corrupted catalog, and not by a redirect.
         */
        const val MAP_HOST = "download.mapsforge.org"
        const val SEGMENT_HOST = "brouter.de"

        const val MAP_BASE = "https://$MAP_HOST/maps/v5/"
        const val SEGMENT_BASE = "https://$SEGMENT_HOST/brouter/segments4/"

        val ALLOWED_HOSTS = setOf(MAP_HOST, SEGMENT_HOST)

        fun map(region: MapRegion): DownloadTarget = DownloadTarget(
            url = MAP_BASE + region.path + ".map",
            fileName = region.fileName,
            kind = OfflineFileKind.MAP,
            label = region.name,
            regionPath = region.path,
            regionName = region.name,
        )

        fun segment(tile: String, region: MapRegion? = null): DownloadTarget = DownloadTarget(
            url = SEGMENT_BASE + tile,
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
