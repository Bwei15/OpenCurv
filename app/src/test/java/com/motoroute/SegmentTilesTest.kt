package com.motoroute

import com.motoroute.data.download.SegmentTiles
import com.motoroute.data.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Working out which .rd5 tiles a region needs is the part of setting up an
 * offline navigator that people get wrong by hand, so it is worth pinning down.
 */
class SegmentTilesTest {

    @Test
    fun `tiles are named after their south-west corner`() {
        assertEquals("E5_N45.rd5", SegmentTiles.name(5, 45))
        assertEquals("E0_N50.rd5", SegmentTiles.name(0, 50))
        assertEquals("W5_N50.rd5", SegmentTiles.name(-5, 50))
        assertEquals("W10_S15.rd5", SegmentTiles.name(-10, -15))
    }

    @Test
    fun `bavaria needs exactly the four tiles the readme claims`() {
        val bavaria = BoundingBox(minLat = 47.2, minLon = 8.9, maxLat = 50.6, maxLon = 13.9)
        assertEquals(
            listOf("E5_N45.rd5", "E10_N45.rd5", "E5_N50.rd5", "E10_N50.rd5"),
            SegmentTiles.covering(bavaria),
        )
    }

    @Test
    fun `a region inside one tile needs one tile`() {
        val berlin = BoundingBox(minLat = 52.3, minLon = 13.0, maxLat = 52.7, maxLon = 13.8)
        assertEquals(listOf("E10_N50.rd5"), SegmentTiles.covering(berlin))
    }

    @Test
    fun `a region straddling the prime meridian gets both sides`() {
        val channel = BoundingBox(minLat = 50.1, minLon = -2.0, maxLat = 51.5, maxLon = 2.0)
        assertEquals(listOf("W5_N50.rd5", "E0_N50.rd5"), SegmentTiles.covering(channel))
    }

    @Test
    fun `a coordinate on a tile boundary belongs to the tile it opens`() {
        assertEquals("E10_N50.rd5", SegmentTiles.containing(50.0, 10.0))
        assertEquals("E5_N45.rd5", SegmentTiles.containing(49.999, 9.999))
    }

    @Test
    fun `negative coordinates round away from zero, not towards it`() {
        // -0.5 degrees sits in the tile starting at -5, not the one at 0.
        assertEquals("W5_N50.rd5", SegmentTiles.containing(50.5, -0.5))
        assertEquals("W5_S5.rd5", SegmentTiles.containing(-0.5, -0.5))
    }

    @Test
    fun `a whole country still yields a sane number of tiles`() {
        val germany = BoundingBox(minLat = 47.2, minLon = 5.8, maxLat = 55.1, maxLon = 15.1)
        val tiles = SegmentTiles.covering(germany)
        assertEquals(9, tiles.size)
        assertTrue(tiles.contains("E5_N45.rd5"))
        assertTrue(tiles.contains("E15_N55.rd5"))
        assertEquals(tiles.size, tiles.distinct().size)
    }
}
