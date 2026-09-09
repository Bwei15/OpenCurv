package com.motoroute

import com.motoroute.data.download.DownloadRejected
import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.download.FileDownloader
import com.motoroute.data.download.MapRegion
import com.motoroute.data.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloader is the only part of OpenCurv that touches the network, so the
 * rules about *where* it may go are worth testing without a network at all.
 */
class FileDownloaderTest {

    private val downloader = FileDownloader()

    @Test
    fun `the two shipped hosts are accepted`() {
        downloader.validate("https://download.mapsforge.org/maps/v5/europe/germany/bayern.map")
        downloader.validate("https://brouter.de/brouter/segments4/E5_N45.rd5")
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
}
