package com.opencurv.curvescore

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.io.OsmReader
import com.opencurv.curvescore.io.OsmXmlTagWriter
import com.opencurv.curvescore.io.WayTagValues
import com.opencurv.curvescore.score.CurveScorer
import com.opencurv.curvescore.score.Environment
import com.opencurv.curvescore.score.ScoreConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The write-back path - the actual interface to the cloud pipeline. What has
 * to hold: the tag arrives on every scored way, nothing else in the file
 * changes, and running the pipeline twice does not double the tag.
 */
class TagWriteBackTest {

    private fun arena() = File(System.getProperty("curvescore.repoDir") ?: ".", "tools/testarena/data/arena.osm")

    private fun tmp(name: String): File {
        val f = File.createTempFile(name, ".osm")
        f.deleteOnExit()
        return f
    }

    private fun scoreArena(cfg: ScoreConfig = ScoreConfig()): Map<Long, WayTagValues> {
        val data = OsmReader.read(arena())
        val c = data.centre()
        val plane = LocalPlane(c.first, c.second)
        return CurveScorer(data, cfg, Environment.build(data, plane), plane)
            .scoreAll(parallel = false)
            .associate { it.wayId to WayTagValues(it.level, it.raw01, it.confidence) }
    }

    @Test
    fun `writes the score tag on every scored way and nothing else`() {
        val scores = scoreArena()
        val out = tmp("tagged")
        OsmXmlTagWriter.write(arena(), out, scores, "opencurv:curve", null, null)

        val before = OsmReader.read(arena())
        val after = OsmReader.read(out)
        assertEquals("node count unchanged", before.nodes.size, after.nodes.size)
        assertEquals("way count unchanged", before.ways.size, after.ways.size)

        for (w in after.ways) {
            val orig = before.ways.first { it.id == w.id }
            assertTrue("geometry unchanged", orig.nodeIds.contentEquals(w.nodeIds))
            val expected = scores[w.id]
            if (expected == null) {
                assertTrue("unscored way ${w.id} must stay untouched", !w.tags.containsKey("opencurv:curve"))
                assertEquals(orig.tags, w.tags)
            } else {
                assertEquals("${w.id}", expected.level.toString(), w.tags["opencurv:curve"])
                // Every original tag survives.
                for ((k, v) in orig.tags) assertEquals("tag $k on way ${w.id}", v, w.tags[k])
                assertEquals("exactly one tag added", orig.tags.size + 1, w.tags.size)
            }
        }
        assertTrue("the arena's roads were actually scored", scores.size >= 12)
    }

    @Test
    fun `re-running the pipeline replaces the tag instead of duplicating it`() {
        val scores = scoreArena()
        val once = tmp("once")
        val twice = tmp("twice")
        OsmXmlTagWriter.write(arena(), once, scores, "opencurv:curve", "opencurv:curve:raw", "opencurv:curve:conf")
        OsmXmlTagWriter.write(once, twice, scores, "opencurv:curve", "opencurv:curve:raw", "opencurv:curve:conf")
        assertEquals(
            "a second pass must be byte-identical",
            once.readText().replace(Regex("\\s+"), " "),
            twice.readText().replace(Regex("\\s+"), " "),
        )
        val after = OsmReader.read(twice)
        for (w in after.ways) {
            if (!w.tags.containsKey("opencurv:curve")) continue
            assertTrue(w.tags.containsKey("opencurv:curve:raw"))
            assertTrue(w.tags.containsKey("opencurv:curve:conf"))
        }
        // And the level really is between 0 and 15.
        for (w in after.ways) {
            val lv = w.tags["opencurv:curve"]?.toIntOrNull() ?: continue
            assertTrue("level $lv out of range on way ${w.id}", lv in 0..15)
        }
    }

    @Test
    fun `the tag name is configurable`() {
        val scores = scoreArena()
        val out = tmp("custom")
        OsmXmlTagWriter.write(arena(), out, scores, "brouter:curviness", null, null)
        val after = OsmReader.read(out)
        val tagged = after.ways.count { it.tags.containsKey("brouter:curviness") }
        assertEquals(scores.size, tagged)
        assertEquals(0, after.ways.count { it.tags.containsKey("opencurv:curve") })
    }

    @Test
    fun `a different level count changes the written values but not the range`() {
        val out = tmp("levels8")
        OsmXmlTagWriter.write(arena(), out, scoreArena(ScoreConfig(levels = 8)), "opencurv:curve", null, null)
        val after = OsmReader.read(out)
        for (w in after.ways) {
            val lv = w.tags["opencurv:curve"]?.toIntOrNull() ?: continue
            assertTrue("level $lv outside 0..7", lv in 0..7)
        }
    }

    @Test
    fun `the pbf reader agrees with the xml reader on the arena`() {
        val pbf = File(System.getProperty("curvescore.repoDir") ?: ".", "tools/testarena/data/arena.osm.pbf")
        org.junit.Assume.assumeTrue("arena.osm.pbf not present", pbf.isFile)
        val fromXml = OsmReader.read(arena())
        val fromPbf = OsmReader.read(pbf)
        assertEquals(fromXml.nodes.size, fromPbf.nodes.size)
        assertEquals(fromXml.ways.size, fromPbf.ways.size)
        val c = fromXml.centre()
        val plane = LocalPlane(c.first, c.second)
        fun scores(d: com.opencurv.curvescore.model.OsmData) =
            CurveScorer(d, ScoreConfig(), Environment.build(d, plane), plane)
                .scoreAll(parallel = false).associate { it.wayId to it.level }
        assertEquals("same scores from both formats", scores(fromXml), scores(fromPbf))
    }
}
