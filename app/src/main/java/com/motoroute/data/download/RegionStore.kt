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
) {
    val fileCount: Int get() = 1 + segmentFiles.size
}

/** A record plus what of it is actually on disk right now. */
data class RegionStatus(
    val record: RegionRecord,
    val mapPresent: Boolean,
    val presentSegments: List<String>,
    val sizeBytes: Long,
) {
    val name: String get() = record.name
    val path: String get() = record.path
    val filesPresent: Int get() = (if (mapPresent) 1 else 0) + presentSegments.size
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
        val present = record.segmentFiles.filter { File(segmentDir, it).isFile }
        val size = (if (map.isFile) map.length() else 0L) +
            present.sumOf { File(segmentDir, it).length() }
        return RegionStatus(record, map.isFile, present, size)
    }

    /**
     * The files a delete of [path] may remove: its map, plus the routing tiles
     * no other installed region needs.
     */
    fun filesToRemove(path: String): List<File> {
        val target = record(path) ?: return emptyList()
        val claimedElsewhere = records()
            .filterNot { it.path == path }
            .flatMap { it.segmentFiles }
            .toSet()
        return buildList {
            add(File(mapDir, target.mapFile))
            target.segmentFiles.filterNot { it in claimedElsewhere }
                .forEach { add(File(segmentDir, it)) }
        }.filter { it.isFile }
    }

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
        val claimedSegments = records().flatMap { it.segmentFiles }.toSet()
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
                        File(segmentDir, it).isFile
                    },
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

        /** One tab-separated line per region; tiles comma separated. */
        fun encode(records: List<RegionRecord>): String =
            records.joinToString("\n") { record ->
                listOf(
                    record.path,
                    record.name,
                    record.country,
                    record.mapFile,
                    record.segmentFiles.joinToString(","),
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
                )
            }
            .toList()

        fun of(region: MapRegion): RegionRecord = RegionRecord(
            path = region.path,
            name = region.name,
            country = region.country,
            mapFile = region.fileName,
            segmentFiles = region.segmentTiles,
        )
    }
}
