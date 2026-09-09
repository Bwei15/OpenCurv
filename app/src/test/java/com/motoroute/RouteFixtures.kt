package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo

/** Shared synthetic routes so the navigation tests read like a ride, not like maths. */
object RouteFixtures {

    val ORIGIN = GeoPoint(47.5000, 11.5000)

    /**
     * 2 km north, left turn, 2 km west. Points every 20 m, which is about what
     * BRouter emits on a country road.
     */
    fun lShapedRoute(): Route {
        val points = ArrayList<GeoPoint>()
        for (i in 0..100) points += Geo.offset(ORIGIN, 0.0, i * 20.0)
        val corner = points.last()
        for (i in 1..100) points += Geo.offset(corner, 270.0, i * 20.0)

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
        }

        val instructions = listOf(
            NavigationInstruction(
                pointIndex = 100,
                location = points[100],
                maneuver = Maneuver.TURN_LEFT,
                distanceFromStart = cumulative[100],
                turnAngleDegrees = -90.0,
            ),
            NavigationInstruction(
                pointIndex = points.lastIndex,
                location = points.last(),
                maneuver = Maneuver.DESTINATION,
                distanceFromStart = cumulative.last(),
                turnAngleDegrees = 0.0,
            ),
        )

        return Route(
            points = points,
            instructions = instructions,
            profileName = "test",
            estimatedSeconds = 240,
        )
    }

    /** Two maneuvers 80 m apart: the "left, then immediately right" case. */
    fun quickSuccessionRoute(): Route {
        val points = ArrayList<GeoPoint>()
        for (i in 0..50) points += Geo.offset(ORIGIN, 0.0, i * 20.0)
        val first = points.last()
        for (i in 1..4) points += Geo.offset(first, 270.0, i * 20.0)
        val second = points.last()
        for (i in 1..50) points += Geo.offset(second, 0.0, i * 20.0)

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
        }

        val instructions = listOf(
            NavigationInstruction(50, points[50], Maneuver.TURN_LEFT, cumulative[50], -90.0),
            NavigationInstruction(54, points[54], Maneuver.TURN_RIGHT, cumulative[54], 90.0),
            NavigationInstruction(
                points.lastIndex,
                points.last(),
                Maneuver.DESTINATION,
                cumulative.last(),
                0.0,
            ),
        )
        return Route(points, instructions, "test", 200)
    }
}
