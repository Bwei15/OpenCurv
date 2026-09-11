package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import com.motoroute.data.traffic.MobilithekTrafficParser
import com.motoroute.data.traffic.TrafficIncident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilithekTrafficParserTest {

    @Test
    fun parsesEmptyOrMalformedJsonSafely() {
        assertTrue(MobilithekTrafficParser.parseGeoJson("").isEmpty())
        assertTrue(MobilithekTrafficParser.parseGeoJson("{").isEmpty())
        assertTrue(MobilithekTrafficParser.parseGeoJson("{}").isEmpty())
        assertTrue(MobilithekTrafficParser.parseGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}").isEmpty())
    }

    @Test
    fun parsesPointFeatureClosure() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "closure-101",
                  "geometry": {
                    "type": "Point",
                    "coordinates": [9.12345, 50.12345]
                  },
                  "properties": {
                    "title": "B45 Vollsperrung",
                    "description": "Fahrbahnerneuerung",
                    "type": "road_closure",
                    "severity": "critical",
                    "radius": 100,
                    "road": "B45",
                    "startEpochMillis": 1700000000000,
                    "endEpochMillis": 1750000000000
                  }
                }
              ]
            }
        """.trimIndent()

        val incidents = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertEquals(1, incidents.size)

        val inc = incidents[0]
        assertEquals("closure-101", inc.id)
        assertEquals("B45 Vollsperrung", inc.title)
        assertEquals("Fahrbahnerneuerung", inc.description)
        assertEquals(IncidentType.ROAD_CLOSURE, inc.type)
        assertEquals(IncidentSeverity.CRITICAL, inc.severity)
        assertEquals(50.12345, inc.location.latitude, 0.00001)
        assertEquals(9.12345, inc.location.longitude, 0.00001)
        assertEquals(100, inc.radiusMeters)
        assertEquals("B45", inc.roadName)
        assertEquals(1700000000000L, inc.startEpochMillis)
        assertEquals(1750000000000L, inc.endEpochMillis)
        assertTrue(inc.isImpassable)
    }

    @Test
    fun parsesLineStringFeaturePassClosure() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "pass-01",
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [11.0, 47.0],
                      [11.05, 47.05],
                      [11.1, 47.1]
                    ]
                  },
                  "properties": {
                    "title": "Hahntennjoch Wintersperre",
                    "incidentType": "pass_closure",
                    "impact": "warning"
                  }
                }
              ]
            }
        """.trimIndent()

        val incidents = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertEquals(1, incidents.size)

        val inc = incidents[0]
        assertEquals("pass-01", inc.id)
        assertEquals(IncidentType.PASS_CLOSURE, inc.type)
        assertTrue(inc.isImpassable)
        assertNotNull(inc.polyline)
        assertEquals(3, inc.polyline?.size)
        assertEquals(47.0, inc.location.latitude, 0.0001)
        assertEquals(11.0, inc.location.longitude, 0.0001)
    }

    @Test
    fun roundTripSerializationToGeoJson() {
        val incident = TrafficIncident(
            id = "test-export-1",
            title = "A7 Baustelle",
            description = "Fahrbahnverengung",
            type = IncidentType.CONSTRUCTION,
            severity = IncidentSeverity.WARNING,
            location = GeoPoint(53.5, 9.9),
            radiusMeters = 75,
            roadName = "A7",
        )

        val geoJson = MobilithekTrafficParser.toGeoJson(listOf(incident))
        assertTrue(geoJson.contains("FeatureCollection"))
        assertTrue(geoJson.contains("test-export-1"))
        assertTrue(geoJson.contains("A7 Baustelle"))
        assertTrue(geoJson.contains("53.5"))
        assertTrue(geoJson.contains("9.9"))

        // Re-parse
        val reParsed = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertEquals(1, reParsed.size)
        assertEquals("test-export-1", reParsed[0].id)
        assertEquals(53.5, reParsed[0].location.latitude, 0.0001)
        assertEquals(9.9, reParsed[0].location.longitude, 0.0001)
    }

    @Test
    fun geoJsonUsesImpassablePropertyKeyForTheMapRenderer() {
        val incident = TrafficIncident(
            id = "impassable-check",
            title = "A1 Vollsperrung",
            description = "",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(50.0, 10.0),
        )

        val geoJson = MobilithekTrafficParser.toGeoJson(listOf(incident))
        assertTrue(geoJson.contains("\"impassable\":true"))
        assertFalse(geoJson.contains("isImpassable"))
    }

    @Test
    fun toGeoJsonWritesFetchedAtAndStaysRoundTrippable() {
        val incident = TrafficIncident(
            id = "cache-item",
            title = "A1 Vollsperrung",
            description = "",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(50.0, 10.0),
            polyline = listOf(GeoPoint(50.0, 10.0), GeoPoint(50.01, 10.0)),
        )

        val geoJson = MobilithekTrafficParser.toGeoJson(listOf(incident), fetchedAtMillis = 1_726_000_000_000L)
        assertEquals(1_726_000_000_000L, MobilithekTrafficParser.extractFetchedAt(geoJson))

        // Exactly one feature per incident, even for a line - the cache
        // format must round-trip 1:1 (see class doc), unlike toDisplayGeoJson.
        val reParsed = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertEquals(1, reParsed.size)
        assertEquals(2, reParsed[0].polyline?.size)
    }

    @Test
    fun toDisplayGeoJsonAddsAnIconPointForLineIncidents() {
        val lineIncident = TrafficIncident(
            id = "line-item",
            title = "A1 Vollsperrung",
            description = "",
            type = IncidentType.ROAD_CLOSURE,
            severity = IncidentSeverity.CRITICAL,
            location = GeoPoint(50.0, 10.0),
            polyline = listOf(GeoPoint(50.0, 10.0), GeoPoint(50.02, 10.0)),
        )
        val pointIncident = TrafficIncident(
            id = "point-item",
            title = "A9 Warnung",
            description = "",
            type = IncidentType.HAZARD,
            severity = IncidentSeverity.WARNING,
            location = GeoPoint(48.0, 11.0),
        )

        val geoJson = MobilithekTrafficParser.toDisplayGeoJson(listOf(lineIncident, pointIncident))

        // The line incident contributes 2 features (line + icon anchor), the point incident 1.
        val featureCount = Regex("\"type\":\\s*\"Feature\"").findAll(geoJson).count()
        assertEquals(3, featureCount)
        assertTrue(geoJson.contains("\"role\":\"icon\""))
        assertTrue(geoJson.contains("LineString"))

        // A display feed is not meant to be reloaded as the source of truth,
        // but if it ever is, the icon-anchor duplicate must not resurrect as
        // a second incident.
        val reParsed = MobilithekTrafficParser.parseGeoJson(geoJson)
        assertEquals(2, reParsed.size)
    }
}
