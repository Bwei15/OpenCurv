package com.motoroute

import com.motoroute.data.download.DownloadRejected
import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.download.FileDownloader
import com.motoroute.data.download.MapRegion
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.BoundingBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The downloader is the only part of OpenCurv that touches the network, so the
 * rules about *where* it may go are worth testing without a network at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val downloader = FileDownloader()

    @Test
    fun `the two shipped hosts are accepted`() {
        downloader.validate("https://download.mapsforge.org/maps/v5/europe/germany/bayern.map")
        downloader.validate("https://brouter.de/brouter/segments4/E5_N45.rd5")
    }

    @Test
    fun `github release hosts are accepted`() {
        downloader.validate("https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/catalog.json")
        downloader.validate("https://objects.githubusercontent.com/github-production-release-asset-2e65be/de-by.pmtiles")
    }

    @Test
    fun `the release-assets redirect target is accepted`() {
        // GitHub's actual 302 for a release asset lands here, not on
        // objects.githubusercontent.com - this is the host that was missing
        // and made every catalog download fail with "refusing to download from
        // release-assets.githubusercontent.com".
        downloader.validate(
            "https://release-assets.githubusercontent.com/github-production-release-asset-2e65be/de-ni.pmtiles",
        )
    }

    @Test
    fun `the github api host is accepted`() {
        downloader.validate("https://api.github.com/repos/Bwei15/OpenCurv/releases?per_page=20")
    }

    @Test
    fun `any other host is refused`() {
        val error = assertThrows(DownloadRejected::class.java) {
            downloader.validate("https://example.com/evil.map")
        }
        assertTrue(error.message!!.contains("example.com"))
    }

    @Test
    fun `plain http is refused even on an allowed host`() {
        assertThrows(DownloadRejected::class.java) {
            downloader.validate("http://brouter.de/brouter/segments4/E5_N45.rd5")
        }
    }

    @Test
    fun `a lookalike host is refused`() {
        assertThrows(DownloadRejected::class.java) {
            downloader.validate("https://brouter.de.attacker.example/x.rd5")
        }
        assertThrows(DownloadRejected::class.java) {
            downloader.validate("https://evil.download.mapsforge.org/x.map")
        }
        // Not a subdomain of githubusercontent.com - no dot before "github".
        assertThrows(DownloadRejected::class.java) {
            downloader.validate("https://notgithubusercontent.com/evil.pmtiles")
        }
    }

    @Test
    fun `nonsense addresses are refused rather than crashing`() {
        assertThrows(DownloadRejected::class.java) { downloader.validate("not a url") }
        assertThrows(DownloadRejected::class.java) { downloader.validate("") }
    }

    @Test
    fun `targets are built against the shipped bases`() {
        val region = MapRegion(
            path = "europe/germany/bayern",
            name = "Bayern",
            country = "Germany",
            bounds = BoundingBox(47.2, 8.9, 50.6, 13.9),
            approxSizeMb = 470,
        )

        val map = DownloadTarget.map(region)
        assertEquals("bayern.map", map.fileName)
        assertEquals(
            "https://download.mapsforge.org/maps/v5/europe/germany/bayern.map",
            map.url,
        )
        downloader.validate(map.url)

        val segment = DownloadTarget.segment("E5_N45.rd5")
        assertEquals("E5_N45.rd5", segment.fileName)
        assertEquals("https://brouter.de/brouter/segments4/E5_N45.rd5", segment.url)
        downloader.validate(segment.url)
    }

    @Test
    fun `a region knows the routing tiles it needs`() {
        val region = MapRegion(
            path = "europe/germany/bayern",
            name = "Bayern",
            country = "Germany",
            bounds = BoundingBox(47.2, 8.9, 50.6, 13.9),
            approxSizeMb = 470,
        )
        assertEquals(4, region.segmentTiles.size)
        region.segmentTiles.forEach { downloader.validate(DownloadTarget.segment(it).url) }
    }

    @Test
    fun `a download matching its sha256 is accepted and finishes normally`() = runTest {
        val content = "hello world".toByteArray()
        val dir = folder.newFolder("checksum-ok")
        val target = DownloadTarget(
            url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/tiny.rd5",
            fileName = "tiny.rd5",
            kind = OfflineFileKind.SEGMENT,
            label = "tiny",
            sha256 = SHA256_HELLO_WORLD,
        )
        val testDownloader = FileDownloader(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            openConnection = { url -> ResumableFakeConnection(url, content) },
        )

        val file = testDownloader.download(target, dir)
        assertEquals("hello world", file.readText())
        assertFalse(File(dir, "tiny.rd5.part").exists())
    }

    @Test
    fun `a checksum mismatch fails the download and discards the partial file`() = runTest {
        val content = "hello world".toByteArray()
        val dir = folder.newFolder("checksum-bad")
        val target = DownloadTarget(
            url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/tiny.rd5",
            fileName = "tiny.rd5",
            kind = OfflineFileKind.SEGMENT,
            label = "tiny",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000",
        )
        val testDownloader = FileDownloader(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            openConnection = { url -> ResumableFakeConnection(url, content) },
        )

        val error = assertThrows(DownloadRejected::class.java) {
            runBlocking { testDownloader.download(target, dir) }
        }
        assertTrue(error.message!!.contains("checksum"))
        assertFalse(File(dir, "tiny.rd5").exists())
        assertFalse(File(dir, "tiny.rd5.part").exists())
    }

    @Test
    fun `a size mismatch against the catalog fails the download`() = runTest {
        val content = "hello world".toByteArray()
        val dir = folder.newFolder("size-bad")
        val target = DownloadTarget(
            url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/tiny.rd5",
            fileName = "tiny.rd5",
            kind = OfflineFileKind.SEGMENT,
            label = "tiny",
            expectedBytes = content.size.toLong() + 1,
        )
        val testDownloader = FileDownloader(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            openConnection = { url -> ResumableFakeConnection(url, content) },
        )

        val error = assertThrows(DownloadRejected::class.java) {
            runBlocking { testDownloader.download(target, dir) }
        }
        assertTrue(error.message!!.contains("size mismatch"))
    }

    @Test
    fun `resuming a download hashes the bytes already on disk before verifying`() = runTest {
        // "hello " is already on disk from a previous, interrupted attempt;
        // the fake server honours the Range header and serves only "world".
        // The checksum is of the full "hello world" - it only passes if the
        // already-downloaded bytes were fed through the digest too.
        val full = "hello world".toByteArray()
        val dir = folder.newFolder("resume-ok")
        File(dir, "tiny.rd5.part").writeBytes("hello ".toByteArray())

        val target = DownloadTarget(
            url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/tiny.rd5",
            fileName = "tiny.rd5",
            kind = OfflineFileKind.SEGMENT,
            label = "tiny",
            sha256 = SHA256_HELLO_WORLD,
        )
        val testDownloader = FileDownloader(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            openConnection = { url -> ResumableFakeConnection(url, full) },
        )

        val file = testDownloader.download(target, dir)
        assertEquals("hello world", file.readText())
    }

    private companion object {
        /** sha256("hello world"), computed with `shasum -a 256`. */
        const val SHA256_HELLO_WORLD = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    }
}

/**
 * A fake HTTPS server that serves [full] and honours a Range header by
 * returning only the requested suffix with a 206 - the same shape FileDownloader's
 * resume logic expects from a real server.
 */
private class ResumableFakeConnection(
    url: URL,
    private val full: ByteArray,
) : HttpURLConnection(url) {
    private var rangeFrom: Long? = null

    override fun setRequestProperty(field: String?, newValue: String?) {
        if (field == "Range" && newValue != null) {
            rangeFrom = newValue.removePrefix("bytes=").removeSuffix("-").toLongOrNull()
        }
    }

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = if (rangeFrom != null) HTTP_PARTIAL else 200

    override fun getInputStream(): InputStream {
        val from = (rangeFrom ?: 0L).toInt()
        return ByteArrayInputStream(full.copyOfRange(from, full.size))
    }

    override fun getContentLengthLong(): Long {
        val from = (rangeFrom ?: 0L).toInt()
        return (full.size - from).toLong()
    }
}
