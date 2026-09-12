package com.motoroute.data.download

import java.io.File

/**
 * One region the rider installed as a package: the Mapsforge map plus every
 * BRouter tile that covers it.
 *
 * The rider picked "Niedersachsen", not "niedersachsen.map plus E5_N50.rd5 plus
 * E10_N50.rd5"; the record is what lets the app talk back to them in the same
 * words - one row to install, one row to delete.
 */
data class RegionRecord(
    val path: String,
    val name: String,
    val country: String,
    val mapFile: String,
    val segmentFiles: List<String>,
    /** The region's `<region-id>.places.sqlite` file, or null if none was catalogued. */
    val placesFile: String? = null,
) {
    val fileCount: Int get() = 1 + segmentFiles.size + (if (placesFile != null) 1 else 0)
}

/** A record plus what of it is actually on disk right now. */
data class RegionStatus(
    val record: RegionRecord,
    val mapPresent: Boolean,
    val presentSegments: List<String>,
    val sizeBytes: Long,
    val placesPresent: Boolean = false,
) {
    val name: String get() = record.name
    val path: String get() = record.path
    val filesPresent: Int get() = (if (mapPresent) 1 else 0) + presentSegments.size + (if (placesPresent) 1 else 0)
    val filesTotal: Int get() = record.fileCount
    val isComplete: Boolean get() = filesPresent == filesTotal
    val canRoute: Boolean get() = presentSegments.isNotEmpty()
}

/**
 * Remembers which files belong to which region.
 *
 * Deliberately a plain text index rather than a database or a JSON library:
 * five fields per region, written when a download starts and read when the data
 * screen opens. Being free of Android types also means the whole thing - the
 * encoding, and the arithmetic that decides which routing tiles a delete may
 * actually remove - runs in the JVM test build.
 *
 * The subtle part is [filesToRemove]. Routing tiles are 5x5 degree squares and
 * regions share them: Niedersachsen and Schleswig-Holstein both need `E5_N50`.
 * Deleting Niedersachsen must not take a tile Schleswig-Holstein is still
 * using, so a tile is only removed when no other installed region claims it.
 */
class RegionStore(
    private val indexFile: File,
    private val mapDir: File,
    private val segmentDir: File,
    /**
     * Where `<region-id>.places.sqlite` files live - see
     * [com.motoroute.data.map.OfflineDataRepository.placesDir]. Optional and
     * defaulted to null so existing call sites (and their tests) that predate
     * the address index keep compiling; with it null, a region's places file
     * is remembered in the index but never shows as present, and delete
     * leaves nothing behind to remove for it.
     */
    private val placesDir: File? = null,
) {

    private var cache: List<RegionRecord>? = null

    fun records(): List<RegionRecord> = cache ?: read().also { cache = it }

    fun record(path: String): RegionRecord? = records().firstOrNull { it.path == path }

    /** Adds or replaces a region. Called when a download is queued, not when it finishes. */
    fun install(record: RegionRecord) {
        val updated = records().filterNot { it.path == record.path } + record
        write(updated)
    }

    /** Drops the bookkeeping without touching any file. */
    fun forget(path: String) {
        val updated = records().filterNot { it.path == path }
        if (updated.size != records().size) write(updated)
    }

    fun statuses(): List<RegionStatus> = records().map(::status).sortedBy { it.name }

    fun status(record: RegionRecord): RegionStatus {
        val map = File(mapDir, record.mapFile)
        val present = record.segmentFiles.filter { segmentFile(it).isFile }
        val places = record.placesFile?.let { placesFile(it) }
        val placesPresent = places?.isFile == true
        val size = (if (map.isFile) map.length() else 0L) +
            present.sumOf { segmentFile(it).length() } +
            (if (placesPresent) places!!.length() else 0L)
        return RegionStatus(record, map.isFile, present, size, placesPresent)
    }

    /**
     * The files a delete of [path] may remove: its map, plus the routing tiles
     * no other installed region needs.
     *
     * Tiles are compared by [SegmentTiles.canonicalName], the name they
     * actually have in [segmentDir] - a record written before that convention
     * existed can still list a prefixed catalog name (see
     * `1.Doku/Kurven_Score.md`), and without canonicalising here two records
     * for the very same on-disk tile would look unrelated, so a delete could
     * take a tile another installed region still needs.
     */
    fun filesToRemove(path: String): List<File> {
        val target = record(path) ?: return emptyList()
        val claimedElsewhere = records()
            .filterNot { it.path == path }
            .flatMap { it.segmentFiles }
            .map(SegmentTiles::canonicalName)
            .toSet()
        return buildList {
            add(File(mapDir, target.mapFile))
            target.segmentFiles.map(SegmentTiles::canonicalName).distinct()
                .filterNot { it in claimedElsewhere }
                .forEach { add(File(segmentDir, it)) }
            // A places file is named for exactly one region (<region-id>.places.sqlite),
            // never shared like a routing tile can be, so it always goes with its region.
            target.placesFile?.let { placesFile(it) }?.let(::add)
        }.filter { it.isFile }
    }

    /** The file a recorded tile name actually has in [segmentDir]. */
    private fun segmentFile(tile: String): File = File(segmentDir, SegmentTiles.canonicalName(tile))

    /** The file a recorded places file name actually has in [placesDir], if any. */
    private fun placesFile(name: String): File? = placesDir?.let { File(it, name) }

    /** Deletes a region as one package and forgets it. Returns the bytes freed. */
    fun delete(path: String): Long {
        val files = filesToRemove(path)
        var freed = 0L
        files.forEach {
            val size = it.length()
            if (it.delete()) freed += size
        }
        forget(path)
        return freed
    }

    /** Map and tile files on disk that no installed region claims. */
    fun looseFiles(): List<File> {
        val claimedMaps = records().map { it.mapFile }.toSet()
        // Files on disk are already canonical names (see SegmentTiles.canonicalName);
        // a record may still list the prefixed catalog name it was installed under.
        val claimedSegments = records().flatMap { it.segmentFiles }
            .map(SegmentTiles::canonicalName).toSet()
        val maps = mapDir.listFiles().orEmpty().filter { it.isFile && it.name !in claimedMaps }
        val segments = segmentDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in claimedSegments }
        return (maps + segments).sortedBy { it.name }
    }

    /**
     * Adopts files that are on disk but not in the index.
     *
     * Two cases need this: an install that predates region packages, and a map
     * imported by hand that happens to be one the catalog knows. Both should
     * show up as the region they are rather than as a bare file name.
     */
    fun adopt(catalog: List<MapRegion>) {
        val known = records().map { it.path }.toSet()
        val onDisk = mapDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val adopted = catalog
            .filter { it.path !in known && it.fileName in onDisk }
            .map { region ->
                RegionRecord(
                    path = region.path,
                    name = region.name,
                    country = region.country,
                    mapFile = region.fileName,
                    // Only tiles that are actually here: an old install may
                    // have some of them, and claiming the rest would make the
                    // region look broken rather than partially covered.
                    segmentFiles = region.segmentTiles.filter {
                        segmentFile(it).isFile
                    },
                    placesFile = region.placesFile?.takeIf { placesFile(it)?.isFile == true },
                )
            }
        if (adopted.isNotEmpty()) write(records() + adopted)
    }

    private fun read(): List<RegionRecord> = runCatching {
        if (!indexFile.isFile) return@runCatching emptyList()
        decode(indexFile.readText())
    }.getOrElse { emptyList() }

    private fun write(records: List<RegionRecord>) {
        cache = records
        runCatching {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(encode(records))
        }
    }

    companion object {
        const val INDEX_FILE_NAME = "regions.index"

        /**
         * One tab-separated line per region; tiles comma separated. The
         * places file is field 6, added after the format already shipped -
         * [decode] only requires 5 fields so a line written before this
         * field existed still parses, just with a null [RegionRecord.placesFile].
         */
        fun encode(records: List<RegionRecord>): String =
            records.joinToString("\n") { record ->
                listOf(
                    record.path,
                    record.name,
                    record.country,
                    record.mapFile,
                    record.segmentFiles.joinToString(","),
                    record.placesFile.orEmpty(),
                ).joinToString("\t") { it.replace('\t', ' ') }
            }

        fun decode(text: String): List<RegionRecord> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@mapNotNull null
                RegionRecord(
                    path = parts[0],
                    name = parts[1],
                    country = parts[2],
                    mapFile = parts[3],
                    segmentFiles = parts[4].split(',').filter { it.isNotBlank() },
                    placesFile = parts.getOrNull(5)?.takeIf { it.isNotBlank() },
                )
            }
            .toList()

        fun of(region: MapRegion): RegionRecord = RegionRecord(
            path = region.path,
            name = region.name,
            country = region.country,
            mapFile = region.fileName,
            segmentFiles = region.segmentTiles,
            placesFile = region.placesFile,
        )
    }
}
