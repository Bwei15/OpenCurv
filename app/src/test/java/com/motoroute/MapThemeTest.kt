package com.motoroute

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the bundled mapsforge render themes and checks the road stroke widths.
 *
 * Line widths are multiplied by the screen's density before they are drawn, so
 * a number that looks modest in the XML is what decides whether the map reads
 * as a map or as a plate of spaghetti on a real phone. These assertions are the
 * regression net for the day the numbers creep back up.
 */
class MapThemeTest {

    private val themeDir: File = findThemeDir()

    private fun findThemeDir(): File {
        val candidates = listOfNotNull(
            System.getProperty("opencurv.repo")?.let { File(it, "app/src/main/assets/themes") },
            File("src/main/assets/themes"),
            File("app/src/main/assets/themes"),
            File("../app/src/main/assets/themes"),
            File("../../app/src/main/assets/themes"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate assets/themes; tried $candidates")
    }

    private val themes = listOf(
        "opencurv_colour_day.xml",
        "opencurv_colour_night.xml",
        "opencurv_contrast_day.xml",
        "opencurv_contrast_night.xml",
    )

    /** Every `<line>` of every `highway` rule in one theme, rule by rule. */
    private fun roadRules(theme: String): List<Pair<String, List<Double>>> {
        val file = File(themeDir, theme)
        assertTrue("missing theme $theme", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        val rules = document.getElementsByTagName("rule")
        val result = ArrayList<Pair<String, List<Double>>>()
        for (i in 0 until rules.length) {
            val rule = rules.item(i) as Element
            if (rule.getAttribute("k") != "highway") continue

            val children = rule.childNodes
            val widths = ArrayList<Double>()
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                if (child.tagName != "line") continue
                child.getAttribute("stroke-width").toDoubleOrNull()?.let { widths += it }
            }
            if (widths.isEmpty()) continue
            val label = "$theme ${rule.getAttribute("v")}@z${rule.getAttribute("zoom-min")}"
            result += label to widths
        }
        return result
    }

    @Test
    fun `every theme declares road widths`() {
        themes.forEach { theme ->
            assertTrue("no road rules in $theme", roadRules(theme).size > 8)
        }
    }

    /**
     * The cap that keeps the map legible. A road drawn at 6 units is roughly
     * 18 physical pixels on a 3x phone, and at that width a village becomes one
     * white blob. Raising this is a design decision, not a tweak.
     */
    @Test
    fun `no road is drawn thicker than the cap`() {
        themes.forEach { theme ->
            roadRules(theme).forEach { (label, widths) ->
                val widest = widths.max()
                assertTrue("$label is $widest units wide", widest <= MAX_ROAD_WIDTH)
            }
        }
    }

    /**
     * A road is a casing plus a narrower fill on top. If the two ever meet, the
     * casing disappears and parallel roads stop being separable at a glance.
     */
    @Test
    fun `the casing stays visible under the fill`() {
        themes.forEach { theme ->
            roadRules(theme).filter { it.second.size >= 2 }.forEach { (label, widths) ->
                val casing = widths[0]
                val fill = widths[1]
                assertTrue(
                    "$label draws its fill ($fill) at least as wide as its casing ($casing)",
                    casing > fill,
                )
                assertTrue(
                    "$label leaves only ${casing - fill} units of casing",
                    casing - fill >= MIN_CASING,
                )
            }
        }
    }

    private companion object {
        const val MAX_ROAD_WIDTH = 5.5
        const val MIN_CASING = 0.6
    }
}
