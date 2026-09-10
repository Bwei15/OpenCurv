package com.opencurv.testarena

import com.opencurv.testarena.truth.JsonIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The whole point of a "testarena" is that everyone (a developer today, CI tomorrow, someone
 * re-running the same check next year) sees the exact same map. This proves the generator has
 * no leftover randomness, wall-clock timestamps, HashMap-iteration-order dependence, etc.
 */
class DeterminismTest {

    @Test
    fun `same run twice produces byte-identical arena osm`() {
        val (doc1, _) = ArenaDefinition.build()
        val (doc2, _) = ArenaDefinition.build()

        val f1 = File.createTempFile("arena1", ".osm")
        val f2 = File.createTempFile("arena2", ".osm")
        try {
            OsmXmlWriter.write(doc1, f1)
            OsmXmlWriter.write(doc2, f2)
            assertEquals(f1.readText(Charsets.UTF_8), f2.readText(Charsets.UTF_8))
        } finally {
            f1.delete()
            f2.delete()
        }
    }

    @Test
    fun `same run twice produces identical arena_truth json`() {
        val (_, truth1) = ArenaDefinition.build()
        val (_, truth2) = ArenaDefinition.build()

        val json1 = JsonIo.gson.toJson(truth1)
        val json2 = JsonIo.gson.toJson(truth2)
        assertEquals(json1, json2)
    }

    @Test
    fun `node and way ids are stable across runs`() {
        val (doc1, _) = ArenaDefinition.build()
        val (doc2, _) = ArenaDefinition.build()

        assertEquals(doc1.nodes.map { it.id }, doc2.nodes.map { it.id })
        assertEquals(doc1.ways.map { it.id }, doc2.ways.map { it.id })
        assertEquals(doc1.nodes.map { it.point }, doc2.nodes.map { it.point })
    }

    @Test
    fun `pbf output is byte-identical across runs`() {
        val (doc1, _) = ArenaDefinition.build()
        val (doc2, _) = ArenaDefinition.build()

        val f1 = File.createTempFile("arena1", ".osm.pbf")
        val f2 = File.createTempFile("arena2", ".osm.pbf")
        try {
            OsmPbfWriter.write(doc1, f1)
            OsmPbfWriter.write(doc2, f2)
            assertTrue(f1.readBytes().contentEquals(f2.readBytes()))
        } finally {
            f1.delete()
            f2.delete()
        }
    }

    @Test
    fun `checked-in fixture matches what the generator produces right now`() {
        // Guards against someone hand-editing data/arena.osm or arena_truth.json, or changing
        // ArenaDefinition without regenerating the checked-in fixture (see README.md).
        val moduleDir = File(System.getProperty("testarena.moduleDir"))
        val checkedInOsm = File(moduleDir, "data/arena.osm")
        val checkedInTruth = File(moduleDir, "arena_truth.json")
        org.junit.Assume.assumeTrue(
            "Checked-in fixture not present - run generateArena first.",
            checkedInOsm.exists() && checkedInTruth.exists(),
        )

        val (doc, truth) = ArenaDefinition.build()
        val freshOsm = File.createTempFile("arena_fresh", ".osm")
        try {
            OsmXmlWriter.write(doc, freshOsm)
            assertEquals(checkedInOsm.readText(Charsets.UTF_8), freshOsm.readText(Charsets.UTF_8))
        } finally {
            freshOsm.delete()
        }
        assertEquals(checkedInTruth.readText(Charsets.UTF_8).trim(), JsonIo.gson.toJson(truth).trim())
    }
}
