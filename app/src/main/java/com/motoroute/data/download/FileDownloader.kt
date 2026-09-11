package com.motoroute.data.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Thrown when a download is refused before a byte is transferred. */
class DownloadRejected(message: String) : IOException(message)

/**
 * Downloads one file over HTTPS, resumably.
 *
 * Four properties matter more than speed here:
 *
 *  - **Only the known hosts.** Every hop of a redirect chain is re-checked
 *    against [DownloadTarget.isAllowedHost], so a redirect cannot walk the app
 *    onto another server. The Android network security config enforces the same
 *    rule at the platform level; this is the second lock on the same door.
 *  - **Resumable.** Map files run to hundreds of megabytes. A download writes
 *    to `<name>.part` and asks for a byte range when that file already exists,
 *    so a dropped connection costs seconds, not the whole file.
 *  - **Atomic.** The `.part` file is only renamed into place once the transfer
 *    is complete, so a half-written map can never be handed to the renderer.
 *  - **Checked.** When the catalog names a SHA-256 for the file, it is hashed
 *    while it is written (and, on a resume, the bytes already on disk are fed
 *    through the digest first) so a corrupted or tampered download is caught
 *    before it is ever handed to the renderer or the router.
 */
class FileDownloader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

    /**
     * Asks the server how big the file is, without downloading it.
     * Returns null when the server does not say.
     */
    suspend fun sizeOf(target: DownloadTarget): Long? = withContext(dispatcher) {
        val connection = connect(validate(target.url), method = "HEAD", rangeFrom = null)
        try {
            if (connection.responseCode !in 200..299) {
                throw DownloadRejected("server returned ${connection.responseCode}")
            }
            connection.contentLengthLong.takeIf { it > 0 }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches [target] into [directory].
     *
     * @param onProgress called with (bytesDone, bytesTotal); bytesTotal is 0
     *   while unknown. Called often - keep it cheap.
     * @return the finished file.
     */
    suspend fun download(
        target: DownloadTarget,
        directory: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(dispatcher) {
        directory.mkdirs()
        val destination = File(directory, target.fileName)
        val partial = File(directory, target.fileName + PART_SUFFIX)

        val alreadyHave = if (partial.isFile) partial.length() else 0L
        val connection = connect(
            validate(target.url),
            method = "GET",
            rangeFrom = alreadyHave.takeIf { it > 0 },
        )

        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw DownloadRejected(
                    "not found on the server: ${target.fileName}. " +
                        "The region may have been renamed - import the file manually instead.",
                )
            }
            if (code !in 200..299) {
                throw DownloadRejected("server returned $code for ${target.fileName}")
            }

            // A server that ignores our Range header answers 200 and starts
            // from zero; honour that rather than corrupting the part file.
            val resuming = code == HttpURLConnection.HTTP_PARTIAL && alreadyHave > 0
            val startAt = if (resuming) alreadyHave else 0L
            val total = connection.contentLengthLong.let {
                if (it > 0) it + startAt else 0L
            }

            var done = startAt
            onProgress(done, total)

            // Hashed while written, not re-read afterwards: on a plain 400 MB
            // file that is the difference between one pass over the bytes and
            // two. On a resume, the bytes already on disk have to go through
            // the digest too, or the final hash would not match the catalog's.
            val digest = target.sha256?.let { MessageDigest.getInstance("SHA-256") }
            if (digest != null && resuming) {
                partial.inputStream().use { existing ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = existing.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        done += read
                        // Reporting every chunk would spam the UI thread on a
                        // 400 MB file; a megabyte of granularity is plenty.
                        if (done - lastReport >= PROGRESS_STEP_BYTES) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(done, total)

            if (target.expectedBytes != null && target.expectedBytes != done) {
                partial.delete()
                throw DownloadRejected(
                    "size mismatch for ${target.fileName}: expected ${target.expectedBytes} bytes, got $done",
                )
            }
            if (digest != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(target.sha256, ignoreCase = true)) {
                    partial.delete()
                    throw DownloadRejected(
                        "checksum mismatch for ${target.fileName}: the download is corrupt or was tampered with",
                    )
                }
            }

            if (destination.exists() && !destination.delete()) {
                throw IOException("could not replace ${destination.name}")
            }
            if (!partial.renameTo(destination)) {
                throw IOException("could not finish ${destination.name}")
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    /** Drops a partially downloaded file. */
    fun discardPartial(target: DownloadTarget, directory: File) {
        File(directory, target.fileName + PART_SUFFIX).delete()
    }

    /**
     * Checks a URL is HTTPS and points at a host we ship.
     * Public so the same rule can be unit tested directly.
     */
    fun validate(url: String): URL {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw DownloadRejected("not a usable address: $url")
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw DownloadRejected("refusing a non-HTTPS download: $url")
        }
        if (!DownloadTarget.isAllowedHost(parsed.host)) {
            throw DownloadRejected("refusing to download from ${parsed.host}")
        }
        return parsed
    }

    /**
     * Opens a connection, following redirects by hand so every hop can be
     * checked against the host allowlist.
     */
    private fun connect(url: URL, method: String, rangeFrom: Long?): HttpURLConnection {
        var current = url
        var hops = 0

        while (true) {
            val connection = openConnection(current).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept-Encoding", "identity")
                rangeFrom?.let { setRequestProperty("Range", "bytes=$it-") }
            }

            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw DownloadRejected("redirect without a target")
            }
            if (++hops > MAX_REDIRECTS) {
                throw DownloadRejected("too many redirects")
            }
            // Resolving against the current URL handles relative Locations;
            // validate() then re-applies the host and scheme rules.
            current = validate(URL(current, location).toString())
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 1024L * 1024L
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex, the form catalog.json and `sha256sum` both use. */
private fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val v = byte.toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}
