package com.motoroute.data.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the offline data on disk: Mapsforge .map files, BRouter .rd5 routing
 * tiles and .brf profiles.
 *
 * Files are imported through the Storage Access Framework and copied into the
 * app's own directory. Copying rather than referencing the picked Uri matters
 * here: BRouter and Mapsforge both need real random-access [File] handles, and
 * a content Uri does not survive a reboot without extra permission plumbing.
 */
class OfflineDataRepository(private val context: Context) {

    fun directoryFor(kind: OfflineFileKind): File =
        File(context.filesDir, kind.directory).apply { mkdirs() }

    val mapDir: File get() = directoryFor(OfflineFileKind.MAP)
    val segmentDir: File get() = directoryFor(OfflineFileKind.SEGMENT)

    /** Where PMTiles archives live - what MapLibre actually renders from. */
    val mapTilesDir: File get() = directoryFor(OfflineFileKind.MAPTILES)

    /**
     * Where downloaded `<region-id>.cameras.tsv` speed-camera files live - see
     * `data/cameras/SpeedCameraRepository.kt`. The download pipeline this
     * directory is meant for is being built separately (Welle 6.1); once a
     * region download recognises `kind == "cameras"` from `catalog.json`, it
     * belongs here, next to [mapDir] and [segmentDir].
     */
    val camerasDir: File get() = directoryFor(OfflineFileKind.CAMERAS)

    /** Where derived data lives - the place-search index, and nothing precious. */
    val indexDir: File get() = File(context.cacheDir, "search").apply { mkdirs() }

    /** The bookkeeping that turns a pile of files back into "Niedersachsen". */
    val regionIndexFile: File get() = File(context.filesDir, "regions.index")

    fun mapFiles(): List<File> =
        (mapDir.listFiles { f -> f.isFile && (f.extension.equals("map", ignoreCase = true) || f.extension.equals("pmtiles", ignoreCase = true)) }.orEmpty().toList() +
         mapTilesDir.listFiles { f -> f.isFile && f.extension.equals("pmtiles", ignoreCase = true) }.orEmpty().toList())
            .distinctBy { it.absolutePath }
            .sortedBy { it.name }

    fun list(kind: OfflineFileKind): List<OfflineFile> =
        directoryFor(kind)
            .listFiles { f -> f.isFile && f.extension.equals(kind.extension, ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .map { OfflineFile(it, it.length(), kind) }

    fun hasAny(kind: OfflineFileKind): Boolean = when (kind) {
        OfflineFileKind.MAP, OfflineFileKind.MAPTILES ->
            list(OfflineFileKind.MAP).isNotEmpty() ||
            list(OfflineFileKind.MAPTILES).isNotEmpty() ||
            mapDir.listFiles { f -> f.isFile && f.extension.equals("pmtiles", ignoreCase = true) }?.isNotEmpty() == true
        else -> list(kind).isNotEmpty()
    }

    /** Free space on the volume holding the offline data, in bytes. */
    fun freeSpaceBytes(): Long = context.filesDir.usableSpace

    /**
     * Copies the document behind [uri] into the right directory.
     *
     * @return the imported file, or null when the file type is not one we use.
     */
    suspend fun import(uri: Uri, onProgress: (Long) -> Unit = {}): OfflineFile? =
        withContext(Dispatchers.IO) {
            val name = displayName(uri) ?: return@withContext null
            val kind = OfflineFileKind.of(name) ?: return@withContext null
            val target = File(directoryFor(kind), sanitise(name))

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                }
            } ?: return@withContext null

            OfflineFile(target, target.length(), kind)
        }

    suspend fun delete(file: OfflineFile): Boolean = withContext(Dispatchers.IO) {
        file.file.delete()
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitise(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
}
