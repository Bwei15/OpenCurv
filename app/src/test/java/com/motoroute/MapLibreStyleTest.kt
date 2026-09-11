package com.motoroute

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the bundled MapLibre vector map styles (`style_day.json` and `style_night.json`).
 *
 * Ensures:
 * 1. Both styles are valid MapLibre Style Specification (v8) JSON.
 * 2. Required core layers (background, water, transportation / roads, place / labels) exist.
 * 3. Correct glyphs (`asset://maplibre/glyphs/...`) and tile URL placeholders (`__PMTILES_URL__` / `pmtiles://file://`).
 * 4. Road casing rules (casing width > core width across all zoom stops, casing drawn before core, distinct colors).
 */
class MapLibreStyleTest {

    private val maplibreDir: File = findMapLibreDir()

    private fun findMapLibreDir(): File {
        val candidates = listOfNotNull(
            System.getProperty("opencurv.repo")?.let { File(it, "app/src/main/assets/maplibre") },
            File("src/main/assets/maplibre"),
            File("app/src/main/assets/maplibre"),
            File("../app/src/main/assets/maplibre"),
            File("../../app/src/main/assets/maplibre"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate assets/maplibre; tried $candidates")
    }

    private val styles = listOf("style_day.json", "style_night.json")

    private fun loadStyle(name: String): JSONObject {
        val file = File(maplibreDir, name)
        assertTrue("Style file does not exist: $name", file.isFile)
        return JSONObject(file.readText())
    }

    @Test
    fun `styles are valid MapLibre v8 JSON`() {
        for (styleName in styles) {
            val root = loadStyle(styleName)
            assertEquals("$styleName must have version 8", 8, root.optInt("version"))
            assertTrue("$styleName must have a non-empty name", root.optString("name").isNotEmpty())
            assertTrue("$styleName must contain a layers array", root.has("layers"))
            assertTrue("$styleName must contain a sources object", root.has("sources"))
        }
    }

    @Test
    fun `glyphs and PMTiles tile URL placeholders are configured for offline use`() {
        for (styleName in styles) {
            val root = loadStyle(styleName)

            // Glyphs must point to local assets, never to a remote HTTP endpoint
            val glyphs = root.getString("glyphs")
            assertTrue(
                "$styleName glyphs must use asset:// scheme, got $glyphs",
                glyphs.startsWith("asset://maplibre/glyphs/"),
            )
            assertTrue(
                "$styleName glyphs must include fontstack and range templates",
                glyphs.contains("{fontstack}") && glyphs.contains("{range}"),
            )

            // OpenMapTiles source must use the PMTiles URL placeholder
            val sources = root.getJSONObject("sources")
            assertTrue("$styleName must define openmaptiles source", sources.has("openmaptiles"))
            val omtSource = sources.getJSONObject("openmaptiles")
            assertEquals("vector", omtSource.getString("type"))
            val tileUrl = omtSource.getString("url")
            assertEquals(
                "$styleName source url must be __PMTILES_URL__ placeholder",
                "__PMTILES_URL__",
                tileUrl,
            )

            // Simulate runtime substitution
            val runtimePath = "/data/user/0/com.motoroute/files/offline/tiles/germany.pmtiles"
            val substituted = tileUrl.replace("__PMTILES_URL__", "pmtiles://file://$runtimePath")
            assertTrue(
                "Substituted URL must use pmtiles://file:// scheme",
                substituted.startsWith("pmtiles://file://"),
            )
            assertTrue(
                "Substituted URL must end with .pmtiles",
                substituted.endsWith(".pmtiles"),
            )
        }
    }

    @Test
    fun `mandatory layers are present in both styles`() {
        val requiredSourceLayers = setOf(
            "water",
            "transportation",
            "place",
        )

        for (styleName in styles) {
            val root = loadStyle(styleName)
            val layers = root.getJSONArray("layers")
            assertTrue("$styleName layers array is empty", layers.length() > 0)

            var hasBackground = false
            val foundSourceLayers = mutableSetOf<String>()
            val layerIds = mutableListOf<String>()

            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val id = layer.getString("id")
                val type = layer.optString("type")
                layerIds.add(id)

                if (type == "background" || id == "background") {
                    hasBackground = true
                }
                if (layer.has("source-layer")) {
                    foundSourceLayers.add(layer.getString("source-layer"))
                }
            }

            assertTrue("$styleName is missing background layer", hasBackground)
            for (req in requiredSourceLayers) {
                assertTrue(
                    "$styleName is missing required source-layer '$req' (found: $foundSourceLayers)",
                    foundSourceLayers.contains(req),
                )
            }
        }
    }

    @Test
    fun `road casings are wider than cores and rendered underneath`() {
        val roadPairs = listOf(
            "road_minor" to ("road_minor_casing" to "road_minor_core"),
            "road_major" to ("road_major_casing" to "road_major_core"),
        )

        for (styleName in styles) {
            val root = loadStyle(styleName)
            val layers = root.getJSONArray("layers")
            val layerMap = mutableMapOf<String, JSONObject>()
            val layerOrder = mutableMapOf<String, Int>()

            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val id = layer.getString("id")
                layerMap[id] = layer
                layerOrder[id] = i
            }

            for ((roadType, pair) in roadPairs) {
                val (casingId, coreId) = pair
                val casingLayer = layerMap[casingId]
                val coreLayer = layerMap[coreId]

                assertNotNull("$styleName missing casing layer $casingId", casingLayer)
                assertNotNull("$styleName missing core layer $coreId", coreLayer)

                // Casing must be drawn BEFORE core (lower index) so core is layered on top
                val casingIdx = layerOrder[casingId]!!
                val coreIdx = layerOrder[coreId]!!
                assertTrue(
                    "$styleName: $casingId (index $casingIdx) must precede $coreId (index $coreIdx)",
                    casingIdx < coreIdx,
                )

                // Color contrast check: casing and core must have different colors
                val casingPaint = casingLayer!!.getJSONObject("paint")
                val corePaint = coreLayer!!.getJSONObject("paint")
                val casingColor = casingPaint.getString("line-color")
                val coreColor = corePaint.getString("line-color")
                assertNotEquals(
                    "$styleName: $roadType casing and core must have different colors for contrast",
                    casingColor,
                    coreColor,
                )

                // Width check: casing stops width must be strictly greater than core stops width
                val casingStops = casingPaint.getJSONObject("line-width").getJSONArray("stops")
                val coreStops = corePaint.getJSONObject("line-width").getJSONArray("stops")

                assertEquals(
                    "$styleName: $roadType casing and core should have matching stop count",
                    casingStops.length(),
                    coreStops.length(),
                )

                for (s in 0 until casingStops.length()) {
                    val casingStop = casingStops.getJSONArray(s)
                    val coreStop = coreStops.getJSONArray(s)
                    val zoom = casingStop.getDouble(0)
                    val casingWidth = casingStop.getDouble(1)
                    val coreWidth = coreStop.getDouble(1)

                    assertEquals(
                        "$styleName: $roadType stops should match zoom levels",
                        zoom,
                        coreStop.getDouble(0),
                        0.001,
                    )
                    assertTrue(
                        "$styleName: $roadType at zoom $zoom, casing width ($casingWidth) must be > core width ($coreWidth)",
                        casingWidth > coreWidth,
                    )
                }
            }
        }
    }

    @Test
    fun `fuel and food POI layers are icon-image symbol layers, not circles`() {
        // Welle 7: MapController.attachOverlayLayers() bakes bitmaps and registers them under
        // these exact ids via style.addImage() - a style.json/MapController mismatch here would
        // leave the pins invisible (unknown icon-image) with no build-time signal otherwise.
        val expectedIconImage = mapOf(
            "poi_fuel_pin" to "opencurv-poi-fuel-icon",
            "poi_food_pin" to "opencurv-poi-food-icon",
        )

        for (styleName in styles) {
            val root = loadStyle(styleName)
            val layers = root.getJSONArray("layers")
            val layerMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                layerMap[layer.getString("id")] = layer
            }

            for ((layerId, iconImage) in expectedIconImage) {
                val layer = layerMap[layerId]
                assertNotNull("$styleName is missing POI layer $layerId", layer)
                assertEquals(
                    "$styleName: $layerId must be a symbol layer (Welle 7 replaced the circle pins with icons)",
                    "symbol",
                    layer!!.getString("type"),
                )
                val layout = layer.getJSONObject("layout")
                assertEquals(
                    "$styleName: $layerId must reference its baked icon image",
                    iconImage,
                    layout.getString("icon-image"),
                )
                assertTrue(
                    "$styleName: $layerId must have a filter",
                    layer.has("filter"),
                )
            }

            // Tankstelle vor Restaurant: fuel must win the collision priority.
            val fuelSortKey = layerMap.getValue("poi_fuel_pin").getJSONObject("layout").getDouble("symbol-sort-key")
            val foodSortKey = layerMap.getValue("poi_food_pin").getJSONObject("layout").getDouble("symbol-sort-key")
            assertTrue(
                "$styleName: fuel's symbol-sort-key ($fuelSortKey) must be lower than food's ($foodSortKey) so fuel wins on overlap",
                fuelSortKey < foodSortKey,
            )
        }
    }
}
