package com.motoroute

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import btools.router.RoutingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the bundled .brf profiles with the real BRouter expression engine and
 * checks that they still say what we think they say.
 *
 * A routing profile is executable configuration: a typo in it does not fail the
 * build, it silently sends the rider down the motorway. These assertions are
 * the regression net for that.
 */
class RoutingProfileTest {

    private val profileDir: File = findProfileDir()

    private fun findProfileDir(): File {
        val candidates = listOfNotNull(
            System.getProperty("opencurv.repo")?.let { File(it, "app/src/main/assets/profiles") },
            File("src/main/assets/profiles"),
            File("app/src/main/assets/profiles"),
            File("../app/src/main/assets/profiles"),
            File("../../app/src/main/assets/profiles"),
        )
        return candidates.firstOrNull { File(it, "lookups.dat").isFile }
            ?: error("could not locate assets/profiles; tried $candidates")
    }

    /** Cost factor the profile assigns to a way with these tags. */
    private class Profile(dir: File, name: String) {
        val way: BExpressionContextWay
        val context = RoutingContext()

        init {
            val meta = BExpressionMetaData()
            way = BExpressionContextWay(64 * 512, meta)
            val node = BExpressionContextNode(0, meta)
            node.setForeignContext(way)
            meta.readMetaData(File(dir, "lookups.dat"))
            val file = File(dir, name)
            assertTrue("missing profile $name", file.isFile)
            way.parseFile(file, "global")
            node.parseFile(file, "global")
            context.expctxWay = way
            context.expctxNode = node
            context.readGlobalConfig()
        }

        fun cost(vararg tags: Pair<String, String>): Double {
            val data = way.createNewLookupData()
            tags.forEach { (k, v) -> way.addLookupValue(k, v, data) }
            way.evaluate(false, way.encode(data))
            return way.costfactor.toDouble()
        }

        fun turncost(vararg tags: Pair<String, String>): Double {
            cost(*tags)
            return way.turncost.toDouble()
        }

        fun description(vararg tags: Pair<String, String>): String {
            val data = way.createNewLookupData()
            tags.forEach { (k, v) -> way.addLookupValue(k, v, data) }
            val encoded = way.encode(data)
            way.evaluate(false, encoded)
            return way.getKeyValueDescription(false, encoded)
        }
    }

    private fun profile(name: String) = Profile(profileDir, name)

    @Test
    fun `all bundled profiles parse`() {
        listOf("motorcycle_curvy.brf", "motorcycle_fast.brf", "motorcycle_enduro.brf")
            .forEach { assertNotNull(profile(it)) }
    }

    @Test
    fun `profiles are car-class and obey turn restrictions`() {
        val p = profile("motorcycle_curvy.brf")
        assertTrue("motorcycles must route as motor vehicles", p.context.carMode)
        assertTrue("turn restrictions must apply", p.context.considerTurnRestrictions)
        assertTrue("voice hints must be enabled", p.context.turnInstructionMode > 0)
    }

    @Test
    fun `the curvy profile makes fast roads expensive`() {
        val p = profile("motorcycle_curvy.brf")
        val tertiary = p.cost("highway" to "tertiary")
        assertTrue(p.cost("highway" to "motorway") > 20 * tertiary)
        assertTrue(p.cost("highway" to "trunk") > 8 * tertiary)
        assertTrue(p.cost("highway" to "primary") > 3 * tertiary)
        assertTrue(p.cost("highway" to "secondary") > tertiary)
    }

    @Test
    fun `the curvy profile still prefers a proper road over a residential grid`() {
        val p = profile("motorcycle_curvy.brf")
        val tertiary = p.cost("highway" to "tertiary")
        assertTrue(p.cost("highway" to "residential") > tertiary)
        assertTrue(p.cost("highway" to "service") > tertiary)
    }

    @Test
    fun `unpaved surfaces are blocked unless the rider asks for them`() {
        val curvy = profile("motorcycle_curvy.brf")
        val tertiary = curvy.cost("highway" to "tertiary")
        assertTrue(
            curvy.cost("highway" to "tertiary", "surface" to "gravel") > 50 * tertiary,
        )

        val enduro = profile("motorcycle_enduro.brf")
        val enduroTertiary = enduro.cost("highway" to "tertiary")
        assertTrue(
            "enduro must allow gravel",
            enduro.cost("highway" to "tertiary", "surface" to "gravel") < 10 * enduroTertiary,
        )
        assertTrue("enduro must allow tracks", enduro.cost("highway" to "track") < 10)
    }

    @Test
    fun `the fast profile is the mirror image of the curvy one`() {
        val fast = profile("motorcycle_fast.brf")
        val tertiary = fast.cost("highway" to "tertiary")
        assertTrue(
            "fast must not punish motorways",
            fast.cost("highway" to "motorway") < 5 * tertiary,
        )
    }

    /**
     * Turning must be near free on ordinary roads, otherwise a winding road is
     * charged for every bend and the router quietly straightens the route out.
     */
    @Test
    fun `turn cost is low on the roads we want to ride`() {
        val curvy = profile("motorcycle_curvy.brf")
        val fast = profile("motorcycle_fast.brf")
        assertTrue(curvy.turncost("highway" to "tertiary") < 40)
        assertTrue(
            "curvy must charge less per bend than fast",
            curvy.turncost("highway" to "tertiary") < fast.turncost("highway" to "tertiary"),
        )
        assertEquals(0.0, curvy.turncost("junction" to "roundabout"), 0.001)
    }

    /**
     * The HUD's speed-limit field is fed from the way description BRouter
     * carries in the track, which only happens if the profile references the
     * maxspeed tag. This test is what stops that reference being "cleaned up".
     */
    @Test
    fun `maxspeed is exported so the speed limit display works`() {
        val p = profile("motorcycle_curvy.brf")
        val description = p.description("highway" to "tertiary", "maxspeed" to "70")
        assertTrue("way description was '$description'", description.contains("maxspeed=70"))
    }

    @Test
    fun `access restrictions block the way`() {
        val p = profile("motorcycle_curvy.brf")
        assertTrue(
            p.cost("highway" to "tertiary", "motor_vehicle" to "no") > 1000,
        )
        assertTrue(
            p.cost("highway" to "tertiary", "motorcycle" to "no") > 1000,
        )
        assertTrue(
            "a motorcycle-only exception must be honoured",
            p.cost(
                "highway" to "tertiary",
                "motor_vehicle" to "no",
                "motorcycle" to "yes",
            ) < 10,
        )
    }

    @Test
    fun `oneway streets are blocked against the flow`() {
        val p = profile("motorcycle_curvy.brf")
        val data = p.way.createNewLookupData()
        p.way.addLookupValue("highway", "tertiary", data)
        p.way.addLookupValue("oneway", "yes", data)
        val encoded = p.way.encode(data)

        p.way.evaluate(false, encoded)
        val forward = p.way.costfactor
        p.way.evaluate(true, encoded)
        val backward = p.way.costfactor

        assertTrue("forward=$forward", forward < 10)
        assertTrue("backward=$backward", backward > 1000)
    }
}
