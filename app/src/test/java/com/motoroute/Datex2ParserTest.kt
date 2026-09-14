package com.motoroute

import com.motoroute.data.traffic.Datex2Parser
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import com.motoroute.data.traffic.MobilithekTrafficSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DATEX II reader behind the Mobilithek feed.
 *
 * The fixtures below are deliberately *two different shapes* of the same
 * information, because that is the actual risk with DATEX II: every publisher
 * uses a different subset of a very large schema, and a parser bound to one
 * publisher's element paths silently returns nothing for the next one.
 */
class Datex2ParserTest {

    /** A closure, DATEX II v2 style: namespaced root, xsi:type on the record, linear location. */
    private val closureFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d2LogicalModel xmlns="http://datex2.eu/schema/2/2_0"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <payloadPublication lang="de">
            <publicationTime>2026-09-14T08:00:00+02:00</publicationTime>
            <situation id="SIT-1" version="3">
              <situationRecord id="REC-1" version="3" xsi:type="RoadOrCarriagewayOrLaneManagement">
                <validity>
                  <validityStatus>active</validityStatus>
                  <validityTimeSpecification>
                    <overallStartTime>2026-09-10T06:00:00+02:00</overallStartTime>
                    <overallEndTime>2026-10-31T18:00:00+02:00</overallEndTime>
                  </validityTimeSpecification>
                </validity>
                <generalPublicComment>
                  <comment>
                    <values>
                      <value lang="de">B3 Vollsperrung zwischen Alfeld und Brunkensen</value>
                    </values>
                  </comment>
                </generalPublicComment>
                <groupOfLocations xsi:type="Linear">
                  <locationContainedInGroup xsi:type="Point">
                    <pointByCoordinates>
                      <pointCoordinates><latitude>51.9820</latitude><longitude>9.8240</longitude></pointCoordinates>
                    </pointByCoordinates>
                  </locationContainedInGroup>
                  <locationContainedInGroup xsi:type="Point">
                    <pointByCoordinates>
                      <pointCoordinates><latitude>51.9910</latitude><longitude>9.8410</longitude></pointCoordinates>
                    </pointByCoordinates>
                  </locationContainedInGroup>
                  <locationContainedInGroup xsi:type="Point">
                    <pointByCoordinates>
                      <pointCoordinates><latitude>52.0005</latitude><longitude>9.8600</longitude></pointCoordinates>
                    </pointByCoordinates>
                  </locationContainedInGroup>
                  <roadNumber>B3</roadNumber>
                </groupOfLocations>
                <roadOrCarriagewayOrLaneManagementType>roadClosed</roadOrCarriagewayOrLaneManagementType>
              </situationRecord>
            </situation>
          </payloadPublication>
        </d2LogicalModel>
    """.trimIndent()

    /** Roadworks from a different publisher: no namespace prefix, plain `type` naming, single point. */
    private val roadworksFeed = """
        <d2LogicalModel xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <payloadPublication>
            <situation id="SIT-2">
              <situationRecord id="REC-2" xsi:type="ConstructionWorks">
                <impact>
                  <numberOfLanesRestricted>1</numberOfLanesRestricted>
                </impact>
                <generalPublicComment><comment><values>
                  <value lang="de">L461 Fahrbahnerneuerung, einspurig</value>
                </values></comment></generalPublicComment>
                <groupOfLocations>
                  <pointByCoordinates>
                    <pointCoordinates><latitude>51.7500</latitude><longitude>9.4000</longitude></pointCoordinates>
                  </pointByCoordinates>
                </groupOfLocations>
              </situationRecord>
            </situation>
          </payloadPublication>
        </d2LogicalModel>
    """.trimIndent()

    @Test
    fun `a linear closure becomes one impassable incident with a polyline`() {
        val incidents = Datex2Parser.parse(closureFeed)
        assertEquals(1, incidents.size)
        val closure = incidents.single()
        assertEquals(IncidentType.ROAD_CLOSURE, closure.type)
        assertEquals(IncidentSeverity.CRITICAL, closure.severity)
        assertTrue("a closure must reach BRouter as a NoGo", closure.isImpassable)
        assertEquals(3, closure.polyline?.size)
        assertEquals("B3", closure.roadName)
        assertTrue(closure.title.contains("Vollsperrung"))
        assertNotNull(closure.startEpochMillis)
        assertNotNull(closure.endEpochMillis)
    }

    @Test
    fun `a closed stretch avoids the whole line, not just its midpoint`() {
        val closure = Datex2Parser.parse(closureFeed).single()
        val areas = closure.toNoGoAreas()
        assertTrue("a 3-point line should sample to several circles, got ${areas.size}", areas.size >= 2)
        assertTrue(areas.all { it.isClosure })
    }

    @Test
    fun `lane-restricted roadworks are a warning, not a closure`() {
        val works = Datex2Parser.parse(roadworksFeed).single()
        assertEquals(IncidentType.CONSTRUCTION, works.type)
        assertEquals(IncidentSeverity.WARNING, works.severity)
        assertFalse("a lane closure must not block the route", works.isImpassable)
        assertEquals(null, works.polyline)
    }

    @Test
    fun `a publisher's different element shapes both parse`() {
        // The real failure mode: one feed works, the next silently yields zero.
        assertEquals(1, Datex2Parser.parse(closureFeed).size)
        assertEquals(1, Datex2Parser.parse(roadworksFeed).size)
    }

    @Test
    fun `garbage in does not throw`() {
        assertTrue(Datex2Parser.parse("<not-xml").isEmpty())
        assertTrue(Datex2Parser.parse("").isEmpty())
        // Well-formed but with no coordinates anywhere: nothing to avoid.
        assertTrue(Datex2Parser.parse("<d2LogicalModel><situationRecord id='x'/></d2LogicalModel>").isEmpty())
    }

    @Test
    fun `incident ids are namespaced so two feeds cannot collide`() {
        val fromMobilithek = MobilithekTrafficSource(apiKey = "token").parse(closureFeed)
        assertTrue(
            "id was ${fromMobilithek.single().id}",
            fromMobilithek.single().id.startsWith(MobilithekTrafficSource.ID_PREFIX),
        )
    }

    @Test
    fun `the source picks the parser from the payload, not from configuration`() {
        val source = MobilithekTrafficSource(apiKey = "token")
        assertEquals(1, source.parse(closureFeed).size)
        // A GeoJSON subscription must work through the same source.
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"x","geometry":{"type":"Point","coordinates":[9.7,52.4]},
               "properties":{"id":"x","title":"L444 gesperrt","type":"ROAD_CLOSURE","severity":"CRITICAL"}}
            ]}
        """.trimIndent()
        assertEquals(1, source.parse(geoJson).size)
    }

    @Test
    fun `without a key the source is inert rather than an error`() {
        val source = MobilithekTrafficSource(apiKey = "  ")
        assertFalse(source.isConfigured)
        assertEquals(emptyList<Any>(), kotlinx.coroutines.runBlocking { source.fetch() })
    }
}
