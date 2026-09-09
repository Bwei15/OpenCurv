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

    fun list(kind: OfflineFileKind): List<OfflineFile> =
        directoryFor(kind)
            .listFiles { f -> f.isFile && f.extension.equals(kind.extension, ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .map { OfflineFile(it, it.length(), kind) }

    fun hasAny(kind: OfflineFileKind): Boolean = list(kind).isNotEmpty()

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
