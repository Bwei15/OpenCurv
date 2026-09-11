package com.motoroute.data.download

import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.BoundingBox

/**
 * What the catalog promised about one file, so a download can be checked
 * rather than merely trusted. Either field can be missing - a catalog entry
 * without a checksum simply skips verification for that file, the same as
 * today.
 */
data class FileChecksum(val sha256: String? = null, val bytes: Long? = null)

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
    /** Keyed by file name (the same keys as [segmentUrls] plus the map file). */
    val checksums: Map<String, FileChecksum> = emptyMap(),
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
    /** Expected SHA-256 (hex, lowercase) from the catalog, or null to skip verification. */
    val sha256: String? = null,
    /** Expected size from the catalog, or null when the catalog did not say. */
    val expectedBytes: Long? = null,
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

        /** Lists GitHub releases so [MapCatalog] can find the newest data-* tag. */
        const val GITHUB_API_HOST = "api.github.com"

        /**
         * The domain GitHub serves release assets from, plus every subdomain.
         * GitHub has moved this before without notice - assets served from
         * `objects.githubusercontent.com` now redirect to
         * `release-assets.githubusercontent.com` - so the allowlist matches the
         * whole domain rather than one fixed subdomain, exactly like the
         * `includeSubdomains="true"` rule already in network_security_config.xml.
         */
        const val GITHUB_ASSET_DOMAIN = "githubusercontent.com"

        const val MAP_BASE = "https://$MAP_HOST/maps/v5/"
        const val SEGMENT_BASE = "https://$SEGMENT_HOST/brouter/segments4/"

        /** Hosts allowed by exact match only (no subdomains). */
        val ALLOWED_HOSTS = setOf(MAP_HOST, SEGMENT_HOST, GITHUB_HOST, GITHUB_API_HOST)

        /**
         * True for [ALLOWED_HOSTS] and for [GITHUB_ASSET_DOMAIN] or any of its
         * subdomains. Public so [FileDownloader.validate] and its tests use the
         * exact same rule the network security config enforces at the platform
         * level.
         */
        fun isAllowedHost(host: String): Boolean {
            val lower = host.lowercase()
            return lower in ALLOWED_HOSTS ||
                lower == GITHUB_ASSET_DOMAIN ||
                lower.endsWith(".$GITHUB_ASSET_DOMAIN")
        }

        fun map(region: MapRegion): DownloadTarget {
            val isPm = region.fileName.endsWith(".pmtiles", ignoreCase = true)
            val defaultUrl = MAP_BASE + region.path + (if (isPm) ".pmtiles" else ".map")
            val checksum = region.checksums[region.fileName]
            return DownloadTarget(
                url = region.mapUrl ?: defaultUrl,
                fileName = region.fileName,
                kind = OfflineFileKind.MAP,
                label = region.name,
                regionPath = region.path,
                regionName = region.name,
                sha256 = checksum?.sha256,
                expectedBytes = checksum?.bytes,
            )
        }

        fun pmtiles(
            url: String,
            fileName: String,
            region: MapRegion? = null,
            label: String? = null,
        ): DownloadTarget {
            val checksum = region?.checksums?.get(fileName)
            return DownloadTarget(
                url = url,
                fileName = fileName,
                kind = OfflineFileKind.MAP,
                label = label ?: region?.name ?: fileName,
                regionPath = region?.path,
                regionName = region?.name,
                sha256 = checksum?.sha256,
                expectedBytes = checksum?.bytes,
            )
        }

        fun pmtiles(region: MapRegion): DownloadTarget {
            val fileName = if (region.fileName.endsWith(".pmtiles", ignoreCase = true)) {
                region.fileName
            } else {
                region.path.substringAfterLast('/') + ".pmtiles"
            }
            val url = region.mapUrl ?: (MAP_BASE + region.path + ".pmtiles")
            val checksum = region.checksums[fileName]
            return DownloadTarget(
                url = url,
                fileName = fileName,
                kind = OfflineFileKind.MAP,
                label = region.name,
                regionPath = region.path,
                regionName = region.name,
                sha256 = checksum?.sha256,
                expectedBytes = checksum?.bytes,
            )
        }

        fun segment(tile: String, region: MapRegion? = null): DownloadTarget {
            val checksum = region?.checksums?.get(tile)
            return DownloadTarget(
                url = region?.segmentUrls?.get(tile) ?: (SEGMENT_BASE + tile),
                fileName = tile,
                kind = OfflineFileKind.SEGMENT,
                label = tile,
                regionPath = region?.path,
                regionName = region?.name,
                sha256 = checksum?.sha256,
                expectedBytes = checksum?.bytes,
            )
        }
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
