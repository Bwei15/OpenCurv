package com.motoroute.data.cameras

import android.content.Context
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.cameras.SpeedCameraGrid
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Loads speed cameras from the bundled starter data
 * (`assets/cameras/`, files named `<region-id>.cameras.tsv` - see the
 * Niedersachsen starter file documented in `1.Doku/Blitzer.md`) and from whatever a region download has
 * put under [camerasDir] - the same `<region-id>.cameras.tsv` format,
 * produced by the same pipeline step (`tools/pipeline/bin/build_cameras.py`)
 * for every region.
 *
 * Excluded from `tools/verifier` purely because it touches [Context] (see
 * that build's `kotlin.exclude`, mirroring how `ProfileManager` and
 * `OfflineDataRepository` are excluded there for the same reason); the actual
 * parsing ([parseTsv]) and the spatial index it builds ([SpeedCameraGrid])
 * are plain, Android-free code and are exercised through
 * [com.motoroute.domain.cameras.SpeedCameraWarner]'s own tests instead.
 */
class SpeedCameraRepository(
    private val context: Context,
    private val camerasDir: File,
) {

    @Volatile
    private var cached: SpeedCameraGrid? = null

    /** All known cameras as a spatial index, loaded once and cached. */
    fun grid(): SpeedCameraGrid = cached ?: refresh()

    fun all(): List<SpeedCamera> = grid().all()

    fun nearby(point: GeoPoint, radiusMeters: Double): List<SpeedCamera> = grid().nearby(point, radiusMeters)

    /** Re-reads assets and [camerasDir] from disk. Call after a region download adds a new file. */
    fun refresh(): SpeedCameraGrid = load().also { cached = it }

    /**
     * Point-feature GeoJSON for the map icon layer - Welle 7's job to draw,
     * not this agent's; see `1.Doku/Blitzer.md`. `properties.maxspeed` and
     * `properties.direction` are the fields that layer reads.
     */
    fun toGeoJson(): String {
        val features = all().joinToString(",") { camera ->
            val props = buildString {
                append("\"id\":\"").append(camera.id).append('"')
                camera.maxSpeedKmh?.let { append(",\"maxspeed\":").append(it) }
                camera.directionDeg?.let { append(",\"direction\":").append(it) }
                camera.name?.let { append(",\"name\":\"").append(it.replace("\"", "'")).append('"') }
            }
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":" +
                "[${camera.point.longitude},${camera.point.latitude}]},\"properties\":{$props}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
    }

    private fun load(): SpeedCameraGrid {
        // LinkedHashMap keeps first-seen order stable, which keeps toGeoJson()
        // output deterministic run to run - handy for diffing in review.
        val merged = LinkedHashMap<String, SpeedCamera>()

        for (name in context.assets.list(ASSET_DIR).orEmpty()) {
            if (!name.endsWith(SUFFIX)) continue
            context.assets.open("$ASSET_DIR/$name").use { merge(merged, it) }
        }

        camerasDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.forEach { file -> file.inputStream().use { merge(merged, it) } }

        return SpeedCameraGrid(merged.values.toList())
    }

    private fun merge(into: MutableMap<String, SpeedCamera>, stream: InputStream) {
        for (camera in parseTsv(stream)) {
            val existing = into[camera.id]
            into[camera.id] = if (existing == null) camera else mergeDuplicate(existing, camera)
        }
    }

    companion object {
        const val ASSET_DIR = "cameras"
        const val SUFFIX = ".cameras.tsv"

        /** Fills gaps in [a] from [b]; the first-seen value wins where both have one. */
        fun mergeDuplicate(a: SpeedCamera, b: SpeedCamera): SpeedCamera = a.copy(
            directionDeg = a.directionDeg ?: b.directionDeg,
            maxSpeedKmh = a.maxSpeedKmh ?: b.maxSpeedKmh,
            name = a.name ?: b.name,
        )

        /**
         * Parses one `lat\tlon\tdirection\tmaxspeed\tname` TSV (the format
         * `tools/pipeline/bin/build_cameras.py` writes). Blank lines and `#`
         * comments (the starter file's source/licence header) are skipped;
         * coordinates are rounded to 5 decimals for [SpeedCamera.id], which is
         * also how duplicates across files are found.
         */
        fun parseTsv(stream: InputStream): List<SpeedCamera> {
            val out = ArrayList<SpeedCamera>()
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val cols = line.split('\t')
                    if (cols.size < 2) return@forEachLine
                    val lat = cols[0].toDoubleOrNull() ?: return@forEachLine
                    val lon = cols[1].toDoubleOrNull() ?: return@forEachLine
                    val direction = cols.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                        ?.toDoubleOrNull()?.toInt()
                    val maxSpeed = cols.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                        ?.toDoubleOrNull()?.toInt()
                    val name = cols.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
                    out.add(
                        SpeedCamera(
                            id = SpeedCamera.idFor(lat, lon),
                            point = GeoPoint(lat, lon),
                            directionDeg = direction,
                            maxSpeedKmh = maxSpeed,
                            name = name,
                        ),
                    )
                }
            }
            return out
        }
    }
}
