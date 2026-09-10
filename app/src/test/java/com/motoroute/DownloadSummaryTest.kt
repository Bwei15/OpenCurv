package com.motoroute

import com.motoroute.data.download.DownloadProgress
import com.motoroute.data.download.DownloadQueueState
import com.motoroute.data.download.DownloadState
import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.download.byRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A region is six files, and the rider wants one progress bar. This is the
 * arithmetic behind that bar.
 */
class DownloadSummaryTest {

    private fun target(
        name: String,
        region: String?,
        regionName: String? = null,
        kind: OfflineFileKind = OfflineFileKind.SEGMENT,
    ) = DownloadTarget(
        url = "https://brouter.de/brouter/segments4/$name",
        fileName = name,
        kind = kind,
        label = name,
        regionPath = region,
        regionName = regionName,
    )

    private fun item(
        target: DownloadTarget,
        state: DownloadState,
        done: Long = 0,
        total: Long = 0,
        error: String? = null,
    ) = DownloadProgress(target, done, total, state, error)

    @Test
    fun `files of a region become one row`() {
        val queue = DownloadQueueState(
            items = listOf(
                item(
                    target("niedersachsen.map", "de/ni", "Niedersachsen", OfflineFileKind.MAP),
                    DownloadState.DONE,
                ),
                item(target("E5_N50.rd5", "de/ni", "Niedersachsen"), DownloadState.RUNNING, 50, 100),
                item(target("E10_N50.rd5", "de/ni", "Niedersachsen"), DownloadState.QUEUED),
            ),
        )

        val region = queue.byRegion().single()
        assertEquals("Niedersachsen", region.regionName)
        assertEquals(3, region.filesTotal)
        assertEquals(1, region.filesDone)
        assertTrue(region.isRunning)
        assertFalse(region.isFinished)
        // One file done plus half of the running one, out of three.
        assertEquals(0.5f, region.fraction, 0.01f)
    }

    @Test
    fun `pmtiles and rd5 files of a region become one row`() {
        val queue = DownloadQueueState(
            items = listOf(
                item(
                    DownloadTarget(
                        url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by.pmtiles",
                        fileName = "de-by.pmtiles",
                        kind = OfflineFileKind.MAP,
                        label = "Bayern",
                        regionPath = "de-by",
                        regionName = "Bayern",
                    ),
                    DownloadState.DONE,
                ),
                item(
                    DownloadTarget(
                        url = "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N45.rd5",
                        fileName = "de-by_E10_N45.rd5",
                        kind = OfflineFileKind.SEGMENT,
                        label = "de-by_E10_N45.rd5",
                        regionPath = "de-by",
                        regionName = "Bayern",
                    ),
                    DownloadState.RUNNING,
                    50,
                    100,
                ),
            ),
        )

        val region = queue.byRegion().single()
        assertEquals("Bayern", region.regionName)
        assertEquals(2, region.filesTotal)
        assertEquals(1, region.filesDone)
        assertTrue(region.isRunning)
        assertFalse(region.isFinished)
        assertEquals(0.75f, region.fraction, 0.01f)
    }

    @Test
    fun `two regions stay two rows, in the order they were queued`() {
        val queue = DownloadQueueState(
            items = listOf(
                item(target("a.map", "de/ni", "Niedersachsen", OfflineFileKind.MAP), DownloadState.DONE),
                item(target("b.map", "de/sh", "Schleswig-Holstein", OfflineFileKind.MAP), DownloadState.QUEUED),
            ),
        )

        assertEquals(
            listOf("Niedersachsen", "Schleswig-Holstein"),
            queue.byRegion().map { it.regionName },
        )
    }

    @Test
    fun `a failure is reported on the region rather than swallowed`() {
        val queue = DownloadQueueState(
            items = listOf(
                item(target("a.map", "de/ni", "Niedersachsen", OfflineFileKind.MAP), DownloadState.DONE),
                item(
                    target("E5_N50.rd5", "de/ni", "Niedersachsen"),
                    DownloadState.FAILED,
                    error = "server returned 404",
                ),
            ),
        )

        val region = queue.byRegion().single()
        assertEquals(1, region.filesFailed)
        assertEquals("server returned 404", region.error)
        assertTrue(region.isFinished)
    }

    @Test
    fun `files imported without a region still get a row`() {
        val queue = DownloadQueueState(
            items = listOf(item(target("E5_N50.rd5", null), DownloadState.QUEUED)),
        )
        assertEquals(1, queue.byRegion().size)
        assertEquals("E5_N50.rd5", queue.byRegion().single().regionName)
    }
}
