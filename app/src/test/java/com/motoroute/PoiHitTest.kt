package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.ui.map.PoiKind
import com.motoroute.ui.map.barrierHitFrom
import com.motoroute.ui.map.poiHitFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [poiHitFrom]/[barrierHitFrom] - the naming rules behind Welle 7's map POI card - as plain
 * functions of strings, with no [org.maplibre.geojson.Feature] or `Context` involved.
 */
class PoiHitTest {

    private val point = GeoPoint(52.37, 9.74)

    @Test
    fun `prefers name-latin over the raw name`() {
        val hit = poiHitFrom(PoiKind.FUEL, point, name = "Aral", nameLatin = "Aral Tankstelle")
        assertEquals("Aral Tankstelle", hit.name)
    }

    @Test
    fun `falls back to the raw name when name-latin is blank`() {
        val hit = poiHitFrom(PoiKind.RESTAURANT, point, name = "Gasthaus Krone", nameLatin = "")
        assertEquals("Gasthaus Krone", hit.name)
    }

    @Test
    fun `falls back to an empty name when the POI has neither field`() {
        val hit = poiHitFrom(PoiKind.FUEL, point, name = null, nameLatin = null)
        assertEquals("", hit.name)
        assertEquals(PoiKind.FUEL, hit.kind)
        assertEquals(point, hit.point)
    }

    @Test
    fun `carries the point and kind through untouched`() {
        val hit = poiHitFrom(PoiKind.RESTAURANT, point, name = "Zur Post", nameLatin = null)
        assertEquals(point, hit.point)
        assertEquals(PoiKind.RESTAURANT, hit.kind)
    }

    @Test
    fun `a barrier hit carries the incident title and road, with no via-able point ambiguity`() {
        val hit = barrierHitFrom(point, title = "A7 | Vollsperrung", road = "A7")
        assertEquals("A7 | Vollsperrung", hit.name)
        assertEquals("A7", hit.road)
        assertEquals(PoiKind.BARRIER, hit.kind)
    }

    @Test
    fun `a barrier with no title falls back to an empty name, not a crash`() {
        val hit = barrierHitFrom(point, title = null, road = null)
        assertEquals("", hit.name)
        assertEquals(null, hit.road)
    }
}
