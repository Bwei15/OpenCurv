package com.motoroute

import com.motoroute.data.download.MapRegion
import com.motoroute.data.download.RegionRecord
import com.motoroute.data.download.RegionStore
import com.motoroute.data.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The region index is what lets the app say "Niedersachsen" instead of listing
 * six files, so the two things it must never get wrong are covered here: the
 * round trip through the index file, and which routing tiles a delete is
 * allowed to take with it.
 */
class RegionStoreTest {

    private lateinit var root: File
    private lateinit var maps: File
    private lateinit var segments: File
    private lateinit var store: RegionStore

    private val lowerSaxony = RegionRecord(
        path = "europe/germany/niedersachsen",
        name = "Niedersachsen",
        country = "Deutschland",
        mapFile = "niedersachsen.map",
        segmentFiles = listOf("E5_N50.rd5", "E10_N50.rd5"),
    )

    private val schleswig = RegionRecord(
        path = "europe/germany/schleswig-holstein",
        name = "Schleswig-Holstein",
        country = "Deutschland",
        mapFile = "schleswig-holstein.map",
        segmentFiles = listOf("E5_N50.rd5"),
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("opencurv-regions").toFile()
        maps = File(root, "maps").apply { mkdirs() }
        segments = File(root, "segments").apply { mkdirs() }
        store = RegionStore(File(root, "regions.index"), maps, segments)
    }

    @Test
    fun `index survives a round trip`() {
        val encoded = RegionStore.encode(listOf(lowerSaxony, schleswig))
        assertEquals(listOf(lowerSaxony, schleswig), RegionStore.decode(encoded))
    }

    @Test
    fun `a garbled line is skipped rather than losing the whole index`() {
        val text = RegionStore.encode(listOf(lowerSaxony)) + "\nnonsense\n"
        assertEquals(listOf(lowerSaxony), RegionStore.decode(text))
    }

    @Test
    fun `status counts what is actually on disk`() {
        store.install(lowerSaxony)
        write(maps, "niedersachsen.map", 300)
        write(segments, "E5_N50.rd5", 100)

        val status = store.statuses().single()
        assertFalse(status.isComplete)
        assertEquals(2, status.filesPresent)
        assertEquals(3, status.filesTotal)
        assertEquals(400L, status.sizeBytes)

        write(segments, "E10_N50.rd5", 100)
        assertTrue(store.statuses().single().isComplete)
    }

    @Test
    fun `deleting a region keeps tiles another region still needs`() {
        store.install(lowerSaxony)
        store.install(schleswig)
        write(maps, "niedersachsen.map", 10)
        write(maps, "schleswig-holstein.map", 10)
        write(segments, "E5_N50.rd5", 10)
        write(segments, "E10_N50.rd5", 10)

        store.delete(lowerSaxony.path)

        assertFalse(File(maps, "niedersachsen.map").exists())
        assertFalse("a tile no one else needs goes", File(segments, "E10_N50.rd5").exists())
        assertTrue("a shared tile stays", File(segments, "E5_N50.rd5").exists())
        assertTrue(File(maps, "schleswig-holstein.map").exists())
        assertEquals(listOf(schleswig.path), store.records().map { it.path })
    }

    @Test
    fun `deleting the last region takes its tiles`() {
        store.install(lowerSaxony)
        write(maps, "niedersachsen.map", 10)
        write(segments, "E5_N50.rd5", 10)
        write(segments, "E10_N50.rd5", 10)

        val freed = store.delete(lowerSaxony.path)

        assertEquals(30L, freed)
        assertEquals(0, segments.listFiles()!!.size)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `a record with a prefixed catalog tile name still finds its canonical file`() {
        // Our own pipeline's catalog calls the tile "de-ni_E5_N50.rd5" (see
        // SegmentTiles.canonicalName), but the download only ever writes the
        // bare grid name to segmentDir - status() has to bridge that gap.
        val niedersachsen = RegionRecord(
            path = "europe/germany/niedersachsen",
            name = "Niedersachsen",
            country = "Deutschland",
            mapFile = "niedersachsen.map",
            segmentFiles = listOf("de-ni_E5_N50.rd5", "de-ni_E10_N50.rd5"),
        )
        store.install(niedersachsen)
        write(maps, "niedersachsen.map", 10)
        write(segments, "E5_N50.rd5", 10)
        write(segments, "E10_N50.rd5", 10)

        val status = store.statuses().single()
        assertTrue(status.isComplete)
        assertEquals(30L, status.sizeBytes)
    }

    @Test
    fun `deleting a region keeps a tile another region claims under a different prefix`() {
        // Niedersachsen's catalog entry names the tile "de-ni_E5_N50.rd5",
        // Schleswig-Holstein's names the very same grid cell plainly - both
        // resolve to the one "E5_N50.rd5" file actually on disk, so deleting
        // one must not take it.
        val niedersachsen = RegionRecord(
            path = "europe/germany/niedersachsen",
            name = "Niedersachsen",
            country = "Deutschland",
            mapFile = "niedersachsen.map",
            segmentFiles = listOf("de-ni_E5_N50.rd5", "de-ni_E10_N50.rd5"),
        )
        store.install(niedersachsen)
        store.install(schleswig)
        write(maps, "niedersachsen.map", 10)
        write(maps, "schleswig-holstein.map", 10)
        write(segments, "E5_N50.rd5", 10)
        write(segments, "E10_N50.rd5", 10)

        store.delete(niedersachsen.path)

        assertTrue("a tile claimed under another prefix stays", File(segments, "E5_N50.rd5").exists())
        assertFalse("a tile no one else needs goes", File(segments, "E10_N50.rd5").exists())
        assertTrue(File(maps, "schleswig-holstein.map").exists())
    }

    @Test
    fun `files from an older install are adopted as their region`() {
        write(maps, "niedersachsen.map", 10)
        write(segments, "E5_N50.rd5", 10)

        store.adopt(listOf(catalogEntry()))

        val status = store.statuses().single()
        assertEquals("Niedersachsen", status.name)
        assertTrue(status.mapPresent)
        // Only the tile that is here is claimed; the region is honest about
        // being incomplete rather than pretending a missing tile is present.
        assertEquals(listOf("E5_N50.rd5"), status.record.segmentFiles)
        assertTrue(status.isComplete)
    }

    @Test
    fun `loose files are the ones no region claims`() {
        store.install(lowerSaxony)
        write(maps, "niedersachsen.map", 10)
        write(maps, "tirol.map", 10)
        write(segments, "E5_N50.rd5", 10)

        assertEquals(listOf("tirol.map"), store.looseFiles().map { it.name })
    }

    private fun catalogEntry() = MapRegion(
        path = "europe/germany/niedersachsen",
        name = "Niedersachsen",
        country = "Deutschland",
        bounds = BoundingBox(51.3, 6.6, 53.9, 11.6),
        approxSizeMb = 300,
    )

    private fun write(dir: File, name: String, bytes: Int) {
        File(dir, name).writeBytes(ByteArray(bytes))
    }
}
