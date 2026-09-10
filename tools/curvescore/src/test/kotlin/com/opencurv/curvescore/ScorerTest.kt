package com.opencurv.curvescore

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmWay
import com.opencurv.curvescore.score.CurveScorer
import com.opencurv.curvescore.score.Environment
import com.opencurv.curvescore.score.RoadTags
import com.opencurv.curvescore.score.ScoreConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The scorer end to end on synthetic roads whose expected verdict is obvious. */
class ScorerTest {

    private val plane = LocalPlane(48.0, 11.0)

    private fun score(
        data: OsmData,
        cfg: ScoreConfig = ScoreConfig(),
        wayId: Long = 1L,
    ) = CurveScorer(data, cfg, Environment.build(data, plane), plane)
        .scoreAll(parallel = false).first { it.wayId == wayId }

    private val tertiary = mapOf("highway" to "tertiary", "surface" to "asphalt")

    // ------------------------------------------------- the way-length question

    /**
     * The single most important structural property: the score must not depend
     * on where a mapper split the data. Splitting one road into three ways has
     * to leave every piece with essentially the road's score.
     */
    @Test
    fun `splitting a way into three does not change its score`() {
        val road = SyntheticRoad.alternating(curves = 14, radiusM = 90.0, turnDeg = 70.0, straightM = 120.0)
        val whole = road.toOsm(tertiary, plane)
        val wholeScore = score(whole).raw01

        // Same geometry, cut into three ways sharing their end nodes.
        val nodes = whole.nodes
        val ids = whole.ways.first { it.tags.containsKey("highway") }.nodeIds
        val a = ids.size / 3
        val b = 2 * ids.size / 3
        val split = OsmData(
            nodes,
            listOf(
                OsmWay(11L, ids.copyOfRange(0, a + 1), tertiary),
                OsmWay(12L, ids.copyOfRange(a, b + 1), tertiary),
                OsmWay(13L, ids.copyOfRange(b, ids.size), tertiary),
            ),
        )
        val scorer = CurveScorer(split, ScoreConfig(), Environment.EMPTY, plane)
        val parts = scorer.scoreAll(parallel = false)
        assertEquals(3, parts.size)
        for (p in parts) {
            assertTrue(
                "piece ${p.wayId} scored ${p.raw01} vs whole $wholeScore",
                abs(p.raw01 - wholeScore) < 0.09,
            )
        }
        val lengthWeighted = parts.sumOf { it.raw01 * it.lengthM } / parts.sumOf { it.lengthM }
        assertTrue(
            "length-weighted mean $lengthWeighted vs whole $wholeScore",
            abs(lengthWeighted - wholeScore) < 0.05,
        )
    }

    /**
     * The other half of the same problem: a 40 m stub in the middle of a
     * serpentine has almost no geometry of its own and must inherit the
     * character of the kilometre around it, not come out as "straight".
     */
    @Test
    fun `a short stub inherits the character of its corridor`() {
        val road = SyntheticRoad.alternating(curves = 16, radiusM = 70.0, turnDeg = 80.0, straightM = 80.0)
        val whole = road.toOsm(tertiary, plane)
        val ids = whole.ways.first().nodeIds
        val mid = ids.size / 2
        val split = OsmData(
            whole.nodes,
            listOf(
                OsmWay(21L, ids.copyOfRange(0, mid), tertiary),
                OsmWay(22L, ids.copyOfRange(mid - 1, mid + 2), tertiary),   // the stub
                OsmWay(23L, ids.copyOfRange(mid + 1, ids.size), tertiary),
            ),
        )
        val scorer = CurveScorer(split, ScoreConfig(), Environment.EMPTY, plane)
        val parts = scorer.scoreAll(parallel = false).associateBy { it.wayId }
        val stub = parts.getValue(22L)
        val neighbour = parts.getValue(21L)
        assertTrue("stub is ${stub.lengthM} m long", stub.lengthM < 60.0)
        assertTrue(
            "stub scored ${stub.raw01}, neighbour ${neighbour.raw01}",
            abs(stub.raw01 - neighbour.raw01) < 0.12,
        )
        assertTrue("and it must not read as a straight", stub.raw01 > 0.3)
    }

    @Test
    fun `score does not depend on the direction the way is drawn in`() {
        val road = SyntheticRoad.alternating(curves = 9, radiusM = 60.0, turnDeg = 90.0, straightM = 150.0)
        val fwd = road.toOsm(tertiary, plane)
        val ids = fwd.ways.first().nodeIds
        val rev = OsmData(fwd.nodes, listOf(OsmWay(1L, ids.reversedArray(), tertiary)))
        assertEquals(score(fwd).raw01, score(rev).raw01, 0.01)
    }

    // ---------------------------------------------------------- missing tagging

    @Test
    fun `a road without surface and maxspeed still gets a sensible score`() {
        val road = SyntheticRoad.alternating(curves = 12, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val tagged = score(road.toOsm(mapOf("highway" to "tertiary", "surface" to "asphalt", "maxspeed" to "80")))
        val bare = score(road.toOsm(mapOf("highway" to "tertiary")))
        assertTrue("untagged must not collapse", bare.raw01 > 0.8 * tagged.raw01)
        assertTrue("but it must be flagged as less certain", bare.confidence < tagged.confidence)
        assertTrue("confidence stays usable", bare.confidence > 0.6)
    }

    @Test
    fun `an untagged track is treated as probably unpaved but with low confidence`() {
        val road = SyntheticRoad.alternating(curves = 10, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val track = score(road.toOsm(mapOf("highway" to "track")))
        val gravel = score(road.toOsm(mapOf("highway" to "track", "surface" to "gravel")))
        val paved = score(road.toOsm(mapOf("highway" to "track", "surface" to "asphalt")))
        assertTrue("the guess sits between the two certain cases", track.raw01 > gravel.raw01)
        assertTrue(track.raw01 < paved.raw01)
        assertTrue("and says so", track.confidence < 0.5)
        assertTrue("explicit surface is near-certain", gravel.confidence > 0.75)
    }

    @Test
    fun `the enduro profile stops punishing gravel`() {
        val road = SyntheticRoad.alternating(curves = 10, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val data = road.toOsm(mapOf("highway" to "track", "surface" to "gravel"))
        val roadProfile = score(data, ScoreConfig())
        val enduro = score(data, ScoreConfig(enduroProfile = true))
        assertTrue("enduro must rate the gravel higher", enduro.raw01 > roadProfile.raw01 * 1.5)
    }

    // ---------------------------------------------------------------- behaviour

    @Test
    fun `a village street with right angle corners scores below a straight rural road`() {
        val grid = SyntheticRoad()
        grid.straight(150.0)
        for (i in 0 until 8) { grid.corner(if (i % 2 == 0) 90.0 else -90.0); grid.straight(150.0) }
        val gridScore = score(
            grid.toOsm(mapOf("highway" to "residential", "maxspeed" to "50"), plane, landuse = "residential")
        )
        val straight = score(SyntheticRoad().straight(3000.0).toOsm(mapOf("highway" to "secondary")))
        assertTrue(
            "grid ${gridScore.raw01} must lose to a plain straight ${straight.raw01}",
            gridScore.raw01 < straight.raw01,
        )
        assertEquals("and land at the bottom level", 0, gridScore.level)
    }

    @Test
    fun `forest beats industrial at identical geometry`() {
        val road = SyntheticRoad.alternating(curves = 8, radiusM = 100.0, turnDeg = 60.0, straightM = 200.0)
        val forest = score(road.toOsm(tertiary, plane, landuse = "forest"))
        val industrial = score(road.toOsm(tertiary, plane, landuse = "industrial"))
        assertTrue("forest ${forest.raw01} > industrial ${industrial.raw01}", forest.raw01 > industrial.raw01)
    }

    @Test
    fun `traffic lights devalue an otherwise good road`() {
        val road = SyntheticRoad.alternating(curves = 10, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val clean = road.toOsm(tertiary, plane)
        val ids = clean.ways.first().nodeIds
        val withLights = OsmData(
            clean.nodes.mapValues { (id, n) ->
                if (ids.indexOf(id) >= 0 && ids.indexOf(id) % 40 == 20) {
                    com.opencurv.curvescore.model.OsmNode(
                        n.id, n.lat, n.lon, n.eleM, mapOf("highway" to "traffic_signals")
                    )
                } else n
            },
            clean.ways,
        )
        assertTrue(score(withLights).raw01 < score(clean).raw01 * 0.95)
    }

    @Test
    fun `a forbidden road scores zero but is still reported`() {
        val road = SyntheticRoad.alternating(curves = 10, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val s = score(road.toOsm(mapOf("highway" to "tertiary", "motor_vehicle" to "no")))
        assertEquals(0, s.level)
        assertEquals(0.0, s.raw01, 1e-9)
    }

    @Test
    fun `footways are not scored at all`() {
        assertTrue(RoadTags.isRoutableRoad(mapOf("highway" to "tertiary")))
        assertTrue(!RoadTags.isRoutableRoad(mapOf("highway" to "footway")))
        assertTrue(!RoadTags.isRoutableRoad(mapOf("highway" to "path")))
        assertTrue("a path open to motor vehicles is a road", RoadTags.isRoutableRoad(mapOf("highway" to "path", "motor_vehicle" to "yes")))
        assertTrue(!RoadTags.isRoutableRoad(mapOf("waterway" to "river")))
    }

    @Test
    fun `the level count is configurable end to end`() {
        val road = SyntheticRoad.alternating(curves = 12, radiusM = 80.0, turnDeg = 70.0, straightM = 140.0)
        val data = road.toOsm(tertiary, plane)
        val s16 = score(data, ScoreConfig(levels = 16))
        val s8 = score(data, ScoreConfig(levels = 8))
        val s4 = score(data, ScoreConfig(levels = 4))
        assertEquals("the raw value must not change", s16.raw01, s8.raw01, 1e-9)
        assertTrue(s16.level in 0..15)
        assertTrue(s8.level in 0..7)
        assertTrue(s4.level in 0..3)
        assertEquals(s8.level, (s16.level / 2))
    }
}
