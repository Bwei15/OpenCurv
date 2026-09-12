package com.motoroute

import com.motoroute.data.download.DownloadRepository
import com.motoroute.data.download.DownloadState
import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.download.FileDownloader
import com.motoroute.data.map.OfflineFileKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bayern = DownloadTarget(
        url = "https://download.mapsforge.org/maps/v5/europe/germany/bayern.map",
        fileName = "bayern.map",
        kind = OfflineFileKind.MAP,
        label = "Bayern (map)",
    )
    private val tile = DownloadTarget.segment("E5_N45.rd5")

    private fun directories(): (OfflineFileKind) -> File = { kind ->
        File(folder.root, kind.directory).apply { mkdirs() }
    }

    @Test
    fun `a file already on disk is not queued again`() = runTest {
        val dirs = directories()
        File(dirs(OfflineFileKind.MAP), "bayern.map").writeText("already here")

        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = FileDownloader(dispatcher = UnconfinedTestDispatcher(testScheduler)),
            directoryFor = dirs,
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        assertTrue(repository.isAlreadyInstalled(bayern))
        repository.enqueue(listOf(bayern))
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test
    fun `queueing the same target twice adds it once`() = runTest {
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = directories(),
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        repository.enqueue(listOf(bayern, tile))
        repository.enqueue(listOf(bayern))

        assertEquals(2, repository.state.value.items.size)
    }

    @Test
    fun `a download refuses to start without free storage`() = runTest {
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = directories(),
            freeSpaceBytes = { 10L * 1024 * 1024 },
        )

        repository.enqueue(listOf(bayern))

        val item = repository.state.value.items.single()
        assertEquals(DownloadState.FAILED, item.state)
        assertTrue(item.error!!.contains("storage"))
    }

    @Test
    fun `a failure is recorded and can be retried`() = runTest {
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = directories(),
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        repository.enqueue(listOf(bayern))
        assertEquals(DownloadState.FAILED, repository.state.value.items.single().state)
        assertEquals(1, repository.state.value.failed.size)

        repository.retryFailed()
        // The fake keeps failing, but the entry went back through the queue.
        assertEquals(DownloadState.FAILED, repository.state.value.items.single().state)
    }

    @Test
    fun `cancelling clears everything still queued`() = runTest {
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = directories(),
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        repository.enqueue(listOf(bayern, tile))
        repository.cancelAll()

        assertFalse(repository.state.value.isRunning)
        assertTrue(
            repository.state.value.items.none {
                it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
            },
        )
    }

    @Test
    fun `clearing finished entries leaves the queue itself alone`() = runTest {
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = directories(),
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        repository.enqueue(listOf(bayern, tile))
        repository.clearFinished()
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test
    fun `downloadRegion registers region in RegionStore and enqueues pmtiles and segments`() = runTest {
        val dirs = directories()
        val store = com.motoroute.data.download.RegionStore(
            File(folder.root, "regions.index"),
            dirs(OfflineFileKind.MAP),
            dirs(OfflineFileKind.SEGMENT),
        )
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = failingDownloader(),
            directoryFor = dirs,
            freeSpaceBytes = { Long.MAX_VALUE },
            regionStore = store,
        )

        val pmtilesRegion = com.motoroute.data.download.MapRegion(
            path = "de-by",
            name = "Bayern",
            country = "Germany",
            bounds = com.motoroute.data.model.BoundingBox(47.2, 8.9, 50.6, 13.9),
            approxSizeMb = 100,
            mapFile = "de-by.pmtiles",
            mapUrl = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by.pmtiles",
            customSegmentTiles = listOf("de-by_E10_N45.rd5", "de-by_E10_N50.rd5"),
            segmentUrls = mapOf(
                "de-by_E10_N45.rd5" to "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N45.rd5",
                "de-by_E10_N50.rd5" to "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N50.rd5",
            ),
        )

        repository.downloadRegion(pmtilesRegion)

        // Verify RegionStore registration
        val record = store.record("de-by")
        org.junit.Assert.assertNotNull(record)
        assertEquals("de-by.pmtiles", record!!.mapFile)
        assertEquals(listOf("de-by_E10_N45.rd5", "de-by_E10_N50.rd5"), record.segmentFiles)

        // Verify queue items
        val items = repository.state.value.items
        assertEquals(3, items.size)
        val mapTarget = items[0].target
        assertEquals("de-by.pmtiles", mapTarget.fileName)
        assertEquals(OfflineFileKind.MAP, mapTarget.kind)
        assertEquals("https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by.pmtiles", mapTarget.url)

        // The file written to segmentDir loses the per-region prefix - BRouter's
        // own segment lookup (NodesCache.fileForSegment) only ever asks for the
        // bare grid name, see SegmentTiles.canonicalName - while the URL it is
        // fetched from still points at the catalog's prefixed asset name.
        val seg1 = items[1].target
        assertEquals("E10_N45.rd5", seg1.fileName)
        assertEquals(OfflineFileKind.SEGMENT, seg1.kind)
        assertEquals(
            "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N45.rd5",
            seg1.url,
        )

        val seg2 = items[2].target
        assertEquals("E10_N50.rd5", seg2.fileName)
        assertEquals(OfflineFileKind.SEGMENT, seg2.kind)
        assertEquals(
            "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N50.rd5",
            seg2.url,
        )
    }

    @Test
    fun `a checksum mismatch surfaces through the repository as a clear failure`() = runTest {
        val content = "hello world".toByteArray()
        val target = DownloadTarget(
            url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/tiny.rd5",
            fileName = "tiny.rd5",
            kind = OfflineFileKind.SEGMENT,
            label = "tiny",
            // Deliberately wrong, as if the catalog and the server disagreed.
            sha256 = "0000000000000000000000000000000000000000000000000000000000000",
        )
        val repository = DownloadRepository(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            downloader = FileDownloader(
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                openConnection = { url -> ServingConnection(url, content) },
            ),
            directoryFor = directories(),
            freeSpaceBytes = { Long.MAX_VALUE },
        )

        repository.enqueue(listOf(target))

        val item = repository.state.value.items.single()
        assertEquals(DownloadState.FAILED, item.state)
        assertTrue(item.error!!.contains("checksum"))
    }

    /**
     * A downloader whose connections always refuse, so nothing touches a
     * network. It runs on the test dispatcher, otherwise the real IO
     * dispatcher would let the assertions overtake the download.
     */
    private fun kotlinx.coroutines.test.TestScope.failingDownloader() = FileDownloader(
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        openConnection = { url -> RefusingConnection(url) },
    )

    private class RefusingConnection(url: URL) : HttpURLConnection(url) {
        override fun connect() = throw IOException("no network in tests")
        override fun getResponseCode(): Int = throw IOException("no network in tests")
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    /** A fake HTTPS server that always serves the whole of [body] with a 200. */
    private class ServingConnection(url: URL, private val body: ByteArray) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getInputStream() = java.io.ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = body.size.toLong()
    }
}
