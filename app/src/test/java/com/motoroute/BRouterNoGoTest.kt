package com.motoroute

import btools.mapaccess.OsmNode
import btools.router.OsmNodeNamed
import btools.router.OsmNogoPolygon
import btools.router.RoutingContext
import com.motoroute.data.brouter.RouteRequest
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.NoGoArea
import com.motoroute.data.traffic.NoGoPolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BRouterNoGoTest {

    @Test
    fun preparesCircularNoGoPointsCorrectly() {
        val noGo = NoGoArea(
            point = GeoPoint(52.5200, 13.4050),
            radiusMeters = 120,
            isClosure = true,
            description = "Baustelle Mitte",
        )

        val node = OsmNodeNamed().apply {
            ilon = noGo.point.iLon()
            ilat = noGo.point.iLat()
            radius = noGo.radiusMeters.toDouble()
            name = "nogo" + noGo.radiusMeters
            isNogo = true
        }

        val list = mutableListOf(node)
        RoutingContext.prepareNogoPoints(list)

        assertEquals(120.0, node.radius, 0.001)
        assertTrue(node.isNogo)
        assertEquals(noGo.point.iLon(), node.ilon)
        assertEquals(noGo.point.iLat(), node.ilat)
    }

    @Test
    fun preparesPolygonNoGoCorrectly() {
        val poly = NoGoPolygon(
            points = listOf(
                GeoPoint(50.0, 10.0),
                GeoPoint(50.1, 10.0),
                GeoPoint(50.1, 10.1),
                GeoPoint(50.0, 10.1),
            ),
            isClosed = true,
            description = "Sperrbereich",
        )

        val osmPoly = OsmNogoPolygon(poly.isClosed).apply {
            for (pt in poly.points) {
                addVertex(pt.iLon(), pt.iLat())
            }
            calcBoundingCircle()
        }

        assertTrue(osmPoly.isNogo)
        assertEquals(4, osmPoly.points.size)
        assertTrue(osmPoly.radius > 0)
    }

    @Test
    fun cleanNogoListRemovesNogosContainingWaypoints() {
        val rc = RoutingContext()

        val nogoNear = OsmNodeNamed().apply {
            ilon = GeoPoint(50.0001, 10.0001).iLon()
            ilat = GeoPoint(50.0001, 10.0001).iLat()
            radius = 100.0
            name = "nogo100"
            isNogo = true
        }

        val nogoFar = OsmNodeNamed().apply {
            ilon = GeoPoint(51.0, 11.0).iLon()
            ilat = GeoPoint(51.0, 11.0).iLat()
            radius = 50.0
            name = "nogo50"
            isNogo = true
        }

        rc.nogopoints = arrayListOf(nogoNear, nogoFar)
        RoutingContext.prepareNogoPoints(rc.nogopoints)

        val waypoints = listOf<OsmNode>(
            OsmNodeNamed().apply {
                ilon = GeoPoint(50.0, 10.0).iLon()
                ilat = GeoPoint(50.0, 10.0).iLat()
            }
        )

        // nogoNear is within ~15m of waypoint, radius is 100m, so it must be cleaned away to prevent routing failure
        rc.cleanNogoList(waypoints)

        assertNotNull(rc.nogopoints)
        assertEquals(1, rc.nogopoints.size)
        assertEquals(nogoFar.ilon, rc.nogopoints[0].ilon)
    }

    @Test
    fun routeRequestDataClassCarriesNoGos() {
        val request = RouteRequest(
            waypoints = listOf(GeoPoint(50.0, 10.0), GeoPoint(50.5, 10.5)),
            profile = File("test.brf"),
            segmentDir = File("segments"),
            noGos = listOf(
                NoGoArea(point = GeoPoint(50.2, 10.2), radiusMeters = 75)
            ),
        )

        assertEquals(1, request.noGos.size)
        assertEquals(75, request.noGos[0].radiusMeters)
    }
}
