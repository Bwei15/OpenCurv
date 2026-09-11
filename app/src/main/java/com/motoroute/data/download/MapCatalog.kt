package com.motoroute.data.download

import android.content.Context
import com.motoroute.data.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The list of regions the rider can download.
 *
 * It is a plain JSON asset rather than something fetched from the server, for
 * two reasons: the app works the first time it is opened with no network, and
 * a rider who wants a region we did not list can add it to the file themselves
 * without touching any code. If a path ever goes stale, the download fails with
 * a clear message and manual import still works.
 */
class MapCatalog(private val context: Context) {

    private var cached: List<MapRegion>? = null
    private val localCatalogFile = java.io.File(context.filesDir, "catalog.json")

    suspend fun regions(): List<MapRegion> = withContext(Dispatchers.IO) {
        cached ?: load().also { cached = it }
    }

    /** Regions grouped by country, in catalog order. */
    suspend fun grouped(): Map<String, List<MapRegion>> =
        regions().groupBy { it.country }

    /**
     * Checks GitHub Releases for the latest catalog.json and persists it locally.
     * Returns true if a newer catalog was successfully loaded.
     *
     * `.../releases/latest/download/...` is deliberately not used here: "latest"
     * means the most recently *published* release, which is the APK release
     * (currently `v1.0.0`) and carries no `catalog.json` at all - a 404, every
     * time. Map/routing data ships as separate `data-YYYYMMDD` releases, so this
     * lists releases via the API and picks the newest tag that starts with
     * `data-` itself. Never blocks app start: [MapViewModel.loadRegions] already
     * shows the bundled or previously-cached catalog first and calls this
     * afterwards, on the same background scope.
     */
    suspend fun refreshFromNetwork(repo: String = "Bwei15/OpenCurv"): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val assetUrl = latestDataCatalogUrl(repo) ?: return@withContext false
            val conn = java.net.URI(assetUrl).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(text)
                if (parsed.isNotEmpty()) {
                    localCatalogFile.writeText(text, Charsets.UTF_8)
                    cached = parsed
                    return@withContext true
                }
            }
            false
        }.getOrElse { false }
    }

    /**
     * Fetches `catalog.json`'s `browser_download_url` from the newest
     * `data-*` release, or null when the API is unreachable, rate-limited, or
     * no such release/asset exists - any of which just falls back to the
     * bundled or previously-cached catalog in [load].
     */
    private fun latestDataCatalogUrl(repo: String): String? = runCatching {
        val url = java.net.URI(
            "https://${DownloadTarget.GITHUB_API_HOST}/repos/$repo/releases?per_page=20",
        ).toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode !in 200..299) return@runCatching null
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        selectLatestDataCatalogUrl(text)
    }.getOrNull()

    private fun load(): List<MapRegion> = runCatching {
        if (localCatalogFile.isFile) {
            val localText = localCatalogFile.readText(Charsets.UTF_8)
            val localParsed = parse(localText)
            if (localParsed.isNotEmpty()) return@runCatching localParsed
        }
        val text = try {
            context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            context.assets.open(REGIONS_ASSET).bufferedReader().use { it.readText() }
        }
        parse(text)
    }.getOrElse { emptyList() }

    companion object {
        const val CATALOG_ASSET = "catalog/catalog.json"
        const val REGIONS_ASSET = "catalog/regions.json"

        /**
         * Picks `catalog.json`'s `browser_download_url` out of a GitHub
         * `GET /repos/.../releases` response: releases with a `tag_name` that
         * does not start with `data-` are ignored (that includes the APK
         * release), and among the rest the lexicographically newest tag wins -
         * `data-YYYYMMDD` sorts chronologically, so string comparison is enough.
         * A pure function of the response body so the tag-selection logic is
         * testable without a network call.
         */
        fun selectLatestDataCatalogUrl(releasesJson: String): String? = runCatching {
            val releases = JSONArray(releasesJson)
            var bestTag: String? = null
            var bestUrl: String? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val tag = release.optString("tag_name")
                if (!tag.startsWith(DATA_TAG_PREFIX)) continue
                if (bestTag != null && tag <= bestTag) continue

                val assets = release.optJSONArray("assets") ?: continue
                var catalogUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name") == "catalog.json") {
                        catalogUrl = asset.optString("browser_download_url").ifEmpty { null }
                        break
                    }
                }
                if (catalogUrl != null) {
                    bestTag = tag
                    bestUrl = catalogUrl
                }
            }
            bestUrl
        }.getOrNull()

        private const val DATA_TAG_PREFIX = "data-"

        fun parse(text: String): List<MapRegion> = runCatching {
            val root = JSONObject(text)
            val releaseObj = root.optJSONObject("release")
            val releaseBaseUrl = releaseObj?.optString("baseUrl")?.trimEnd('/')

            val array = root.getJSONArray("regions")
            buildList {
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    add(parseRegion(entry, releaseBaseUrl))
                }
            }
        }.getOrElse { emptyList() }

        private fun parseRegion(entry: JSONObject, releaseBaseUrl: String?): MapRegion {
            val hasFiles = entry.has("files")
            val hasBbox = entry.has("bbox")
            val hasId = entry.has("id")

            return if (hasFiles || hasBbox || hasId) {
                val id = entry.optString("id", entry.optString("path"))
                val name = entry.getString("name")
                val sourcePath = entry.optJSONObject("source")?.optString("path")
                    ?: entry.optString("path", id)
                val country = entry.optString("country").ifEmpty {
                    inferCountry(sourcePath, id)
                }
                val bounds = if (hasBbox) {
                    val bbox = entry.getJSONArray("bbox")
                    BoundingBox(
                        minLon = bbox.getDouble(0),
                        minLat = bbox.getDouble(1),
                        maxLon = bbox.getDouble(2),
                        maxLat = bbox.getDouble(3),
                    )
                } else {
                    BoundingBox(
                        minLat = entry.optDouble("minLat", 0.0),
                        minLon = entry.optDouble("minLon", 0.0),
                        maxLat = entry.optDouble("maxLat", 0.0),
                        maxLon = entry.optDouble("maxLon", 0.0),
                    )
                }
                val totalBytes = entry.optLong("totalBytes", 0L)
                val approxSizeMb = if (entry.has("approxSizeMb")) {
                    entry.getInt("approxSizeMb")
                } else {
                    (totalBytes / (1024 * 1024)).toInt().coerceAtLeast(1)
                }

                var mapFileName: String? = null
                var mapUrl: String? = null
                val segmentFiles = mutableListOf<String>()
                val segmentUrls = mutableMapOf<String, String>()
                val checksums = mutableMapOf<String, FileChecksum>()

                val files = entry.optJSONArray("files")
                if (files != null) {
                    for (j in 0 until files.length()) {
                        val fileObj = files.getJSONObject(j)
                        val fileName = fileObj.getString("name")
                        val kind = fileObj.optString("kind")
                        val fileUrl = fileObj.optString("url").ifEmpty {
                            if (!releaseBaseUrl.isNullOrBlank()) "$releaseBaseUrl/$fileName" else ""
                        }
                        val sha256 = fileObj.optString("sha256").ifEmpty { null }
                        val bytes = fileObj.optLong("bytes", -1L).takeIf { it >= 0 }
                        if (sha256 != null || bytes != null) {
                            checksums[fileName] = FileChecksum(sha256, bytes)
                        }

                        if (kind == "maptiles" || fileName.endsWith(".pmtiles", ignoreCase = true) ||
                            kind == "map" || fileName.endsWith(".map", ignoreCase = true)
                        ) {
                            mapFileName = fileName
                            if (fileUrl.isNotEmpty()) mapUrl = fileUrl
                        } else if (kind == "routing" || fileName.endsWith(".rd5", ignoreCase = true)) {
                            segmentFiles.add(fileName)
                            if (fileUrl.isNotEmpty()) segmentUrls[fileName] = fileUrl
                        }
                    }
                }

                if (mapFileName == null) {
                    mapFileName = "$id.pmtiles"
                }

                MapRegion(
                    path = sourcePath.ifEmpty { id },
                    name = name,
                    country = country,
                    bounds = bounds,
                    approxSizeMb = approxSizeMb,
                    mapFile = mapFileName,
                    mapUrl = mapUrl,
                    customSegmentTiles = segmentFiles.takeIf { it.isNotEmpty() },
                    segmentUrls = segmentUrls,
                    checksums = checksums,
                )
            } else {
                MapRegion(
                    path = entry.getString("path"),
                    name = entry.getString("name"),
                    country = entry.optString("country", "Other"),
                    bounds = BoundingBox(
                        minLat = entry.getDouble("minLat"),
                        minLon = entry.getDouble("minLon"),
                        maxLat = entry.getDouble("maxLat"),
                        maxLon = entry.getDouble("maxLon"),
                    ),
                    approxSizeMb = entry.optInt("approxSizeMb", 0),
                )
            }
        }

        private fun inferCountry(sourcePath: String, id: String): String {
            val lower = "$sourcePath $id".lowercase()
            return when {
                "germany" in lower || lower.startsWith("de") -> "Germany"
                "austria" in lower || "switzerland" in lower || "slovenia" in lower -> "Alps"
                "italy" in lower || "spain" in lower || "portugal" in lower || "croatia" in lower -> "Southern Europe"
                "france" in lower || "belgium" in lower || "netherlands" in lower || "luxembourg" in lower -> "Western Europe"
                "denmark" in lower -> "Northern Europe"
                "czech" in lower || "poland" in lower -> "Central Europe"
                else -> "Other"
            }
        }
    }
}
