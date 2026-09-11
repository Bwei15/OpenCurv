package com.motoroute

import com.motoroute.data.traffic.AutobahnTrafficSource
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below are trimmed, real shapes captured from
 * https://verkehr.autobahn.de/o/autobahn/ on 2026-09-11 (road list,
 * A1 closure, A7 roadworks, A7/A9 warning) - only the fields
 * [AutobahnTrafficSource] reads are kept.
 */
class AutobahnTrafficSourceTest {

    private val roadsJson = """{"roads":["A1","A7"]}"""

    private fun emptyService(key: String) = """{"$key":[]}"""

    // A real full closure: isBlocked "true".
    private val a1FullClosureJson = """
        {
          "closure": [
            {
              "identifier": "2026-full-block-01",
              "isBlocked": "true",
              "future": false,
              "extent": "49.0,7.0,49.0,7.0",
              "point": "49.0,7.0",
              "coordinate": {"lat": 49.0, "long": 7.0},
              "impact": {"symbols": ["CLOSED", "CLOSED"]},
              "display_type": "CLOSURE",
              "subtitle": "Saarbrücken -> Trier",
              "title": "A1 | Nonnweiler-Bierfeld - Hermeskeil",
              "description": ["Vollsperrung wegen Unfall"]
            }
          ]
        }
    """.trimIndent()

    // A lane restriction: display_type CLOSURE but isBlocked "false" and an
    // ARROW symbol (traffic merged onto a remaining lane) - not a full block.
    private val a1LaneRestrictionJson = """
        {
          "closure": [
            {
              "identifier": "2026-lane-restrict-01",
              "isBlocked": "false",
              "future": true,
              "startTimestamp": "2099-01-01T22:00:00+02:00",
              "extent": "49.607,6.960,49.645,6.926",
              "point": "49.607,6.960",
              "coordinate": {"lat": 49.607, "long": 6.960},
              "impact": {"symbols": ["SEPARATE", "ARROW_UP", "CLOSED"]},
              "display_type": "CLOSURE",
              "title": "A1 | Spurverengung",
              "description": ["Fahrstreifenreduzierung"]
            }
          ]
        }
    """.trimIndent()

    private val a7RoadworksJson = """
        {
          "roadworks": [
            {
              "identifier": "2026-roadworks-01",
              "isBlocked": "false",
              "future": true,
              "startTimestamp": "2026-09-22T06:00:00+02:00",
              "extent": "47.597,10.656,47.606,10.655",
              "point": "47.597,10.656",
              "coordinate": {"lat": 47.597, "long": 10.656},
              "impact": {"symbols": ["ARROW_DOWN", "SEPARATE", "ARROW_UP"]},
              "display_type": "SHORT_TERM_ROADWORKS",
              "title": "A7 | Füssen - Nesselwang",
              "description": ["Baustelle mit Verengung"],
              "geometry": {
                "type": "LineString",
                "coordinates": [[10.6565, 47.5970], [10.6566, 47.5982], [10.6567, 47.5990]]
              }
            }
          ]
        }
    """.trimIndent()

    private val a7WarningJson = """
        {
          "warning": [
            {
              "identifier": "INRIX-warning-01",
              "isBlocked": "false",
              "future": false,
              "startTimestamp": "2026-09-11T08:00:00Z",
              "point": "48.230,10.124",
              "coordinate": {"lat": 48.230, "long": 10.124},
              "display_type": "WARNING",
              "title": "A7 | Tannengarten - Badhauser Wald-Ost",
              "delayTimeValue": "2",
              "description": ["Stockender Verkehr"]
            }
          ]
        }
    """.trimIndent()

    private fun fakeSource(
        baseUrl: String = "https://fake.test/autobahn",
        overrides: Map<String, String> = emptyMap(),
        failing: Set<String> = emptySet(),
    ): AutobahnTrafficSource {
        val responses = mapOf(
            "$baseUrl/" to roadsJson,
            "$baseUrl/A1/services/closure" to a1FullClosureJson,
            "$baseUrl/A1/services/roadworks" to emptyService("roadworks"),
            "$baseUrl/A1/services/warning" to emptyService("warning"),
            "$baseUrl/A7/services/closure" to emptyService("closure"),
            "$baseUrl/A7/services/roadworks" to a7RoadworksJson,
            "$baseUrl/A7/services/warning" to a7WarningJson,
        ) + overrides

        return AutobahnTrafficSource(
            baseUrl = baseUrl,
            httpGet = { url ->
                if (url in failing) throw java.io.IOException("simulated failure for $url")
                responses[url] ?: throw java.io.IOException("no fixture for $url")
            },
        )
    }

    @Test
    fun fullClosureBecomesCriticalRoadClosure() = runBlocking {
        val incidents = fakeSource().fetch()
        val closure = incidents.single { it.id == "autobahn:2026-full-block-01" }

        assertEquals(IncidentType.ROAD_CLOSURE, closure.type)
        assertEquals(IncidentSeverity.CRITICAL, closure.severity)
        assertTrue(closure.isImpassable)
        assertEquals("A1", closure.roadName)
        assertEquals(49.0, closure.location.latitude, 0.0001)
        assertEquals(7.0, closure.location.longitude, 0.0001)
    }

    @Test
    fun laneRestrictionIsNotImpassable() = runBlocking {
        val incidents = fakeSource(
            overrides = mapOf("https://fake.test/autobahn/A1/services/closure" to a1LaneRestrictionJson),
        ).fetch()
        val restriction = incidents.single { it.id == "autobahn:2026-lane-restrict-01" }

        assertEquals(IncidentType.CONSTRUCTION, restriction.type)
        assertTrue(restriction.severity != IncidentSeverity.CRITICAL)
        assertTrue(!restriction.isImpassable)
    }

    @Test
    fun futureFlagGatesStartEpochMillisButPastStartDoesNot() = runBlocking {
        val incidents = fakeSource(
            overrides = mapOf("https://fake.test/autobahn/A1/services/closure" to a1LaneRestrictionJson),
        ).fetch()
        val futureItem = incidents.single { it.id == "autobahn:2026-lane-restrict-01" }
        assertNotNull(futureItem.startEpochMillis)
        assertTrue(futureItem.startEpochMillis!! > System.currentTimeMillis())

        // The warning fixture is future=false even though it carries a
        // startTimestamp already in the past - must not be set.
        val warning = incidents.filter { it.roadName == "A7" }
        val warningItem = warning.first { it.type == IncidentType.HAZARD }
        assertNull(warningItem.startEpochMillis)
    }

    @Test
    fun roadworksMapToConstructionWithWarningSeverityWhenLanesAffected() = runBlocking {
        val incidents = fakeSource().fetch()
        val roadworks = incidents.single { it.id == "autobahn:2026-roadworks-01" }

        assertEquals(IncidentType.CONSTRUCTION, roadworks.type)
        assertEquals(IncidentSeverity.WARNING, roadworks.severity)
        assertTrue(!roadworks.isImpassable)
    }

    @Test
    fun warningMapsToHazardWarning() = runBlocking {
        val incidents = fakeSource().fetch()
        val warning = incidents.single { it.id == "autobahn:INRIX-warning-01" }

        assertEquals(IncidentType.HAZARD, warning.type)
        assertEquals(IncidentSeverity.WARNING, warning.severity)
    }

    @Test
    fun lineStringGeometryBecomesPolylineInLatLonOrder() = runBlocking {
        val incidents = fakeSource().fetch()
        val roadworks = incidents.single { it.id == "autobahn:2026-roadworks-01" }

        val polyline = roadworks.polyline
        assertNotNull(polyline)
        assertEquals(3, polyline!!.size)
        // geometry.coordinates are [lon, lat]; GeoPoint is (lat, lon).
        assertEquals(47.5970, polyline[0].latitude, 0.0001)
        assertEquals(10.6565, polyline[0].longitude, 0.0001)
    }

    @Test
    fun radiusIsDerivedFromExtentDiagonal() = runBlocking {
        val incidents = fakeSource().fetch()
        val roadworks = incidents.single { it.id == "autobahn:2026-roadworks-01" }
        // extent diagonal here is roughly 1.2 km -> half is a few hundred metres, well above the 50 m default.
        assertTrue(roadworks.radiusMeters > 100)
    }

    @Test
    fun oneRoadFailingDoesNotAbortTheOthers() = runBlocking {
        val incidents = fakeSource(
            failing = setOf(
                "https://fake.test/autobahn/A1/services/closure",
                "https://fake.test/autobahn/A1/services/roadworks",
                "https://fake.test/autobahn/A1/services/warning",
            ),
        ).fetch()

        // A1 contributed nothing (every one of its 3 requests failed), but
        // A7's incidents must still be present.
        assertTrue(incidents.none { it.roadName == "A1" })
        assertTrue(incidents.any { it.roadName == "A7" })
        assertEquals(2, incidents.size) // A7 roadworks + A7 warning
    }

    @Test
    fun emptyRoadListYieldsNoIncidentsAndNoRequests() = runBlocking {
        val incidents = AutobahnTrafficSource(
            baseUrl = "https://fake.test/autobahn",
            httpGet = { url ->
                if (url == "https://fake.test/autobahn/") """{"roads":[]}"""
                else throw AssertionError("should not fetch services when there are no roads: $url")
            },
        ).fetch()

        assertTrue(incidents.isEmpty())
    }

    @Test
    fun trimsTrailingSpaceInRoadCodesBeforeBuildingUrls() = runBlocking {
        val incidents = AutobahnTrafficSource(
            baseUrl = "https://fake.test/autobahn",
            httpGet = { url ->
                when (url) {
                    "https://fake.test/autobahn/" -> """{"roads":["A60 "]}"""
                    "https://fake.test/autobahn/A60/services/closure" -> emptyService("closure")
                    "https://fake.test/autobahn/A60/services/roadworks" -> emptyService("roadworks")
                    "https://fake.test/autobahn/A60/services/warning" -> emptyService("warning")
                    else -> throw AssertionError("unexpected url with untrimmed road code: $url")
                }
            },
        ).fetch()

        assertTrue(incidents.isEmpty())
    }
}
