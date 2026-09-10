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

    /**
     * A single hairpin, 2 km in, with nothing else nearby - the "isolated
     * sharp bend on an otherwise ordinary road" case, as opposed to
     * [serpentineRoute]'s tight combination of several.
     */
    fun lonelyHairpinRoute(): Route {
        val points = ArrayList<GeoPoint>()
        for (i in 0..100) points += Geo.offset(ORIGIN, 0.0, i * 20.0)
        val corner = points.last()
        for (i in 1..100) points += Geo.offset(corner, 270.0, i * 20.0)
        val cumulative = cumulativeOf(points)

        val instructions = listOf(
            NavigationInstruction(100, points[100], Maneuver.HAIRPIN_LEFT, cumulative[100], -150.0),
            NavigationInstruction(
                points.lastIndex,
                points.last(),
                Maneuver.DESTINATION,
                cumulative.last(),
                0.0,
            ),
        )
        return Route(points, instructions, "test", 240)
    }

    /**
     * A switchback: three hairpins about 80 m apart, then a long final leg.
     * The core "kommt oft mehrfach" scenario - closely spaced sharp turns are
     * exactly what OpenCurv's curvy routing is meant to find.
     */
    fun serpentineRoute(): Route {
        val points = ArrayList<GeoPoint>()
        for (i in 0..500) points += Geo.offset(ORIGIN, 0.0, i * 20.0)
        val cumulative = cumulativeOf(points)

        val instructions = listOf(
            NavigationInstruction(100, points[100], Maneuver.HAIRPIN_LEFT, cumulative[100], -150.0),
            NavigationInstruction(104, points[104], Maneuver.HAIRPIN_RIGHT, cumulative[104], 150.0),
            NavigationInstruction(108, points[108], Maneuver.HAIRPIN_LEFT, cumulative[108], -150.0),
            NavigationInstruction(
                points.lastIndex,
                points.last(),
                Maneuver.DESTINATION,
                cumulative.last(),
                0.0,
            ),
        )
        return Route(points, instructions, "test", 800)
    }

    /**
     * A single, mostly featureless leg long enough (20 km) to exercise the
     * free-ride reassurance, which only fires after 15 km of silence.
     */
    fun longStraightRoute(totalMeters: Double = 20_000.0): Route {
        val step = 50.0
        val count = (totalMeters / step).toInt()
        val points = ArrayList<GeoPoint>(count + 1)
        for (i in 0..count) points += Geo.offset(ORIGIN, 0.0, i * step)
        val cumulative = cumulativeOf(points)

        val instructions = listOf(
            NavigationInstruction(
                points.lastIndex,
                points.last(),
                Maneuver.DESTINATION,
                cumulative.last(),
                0.0,
            ),
        )
        return Route(points, instructions, "test", (totalMeters / 20.0).toInt())
    }

    /**
     * A full 25 km ride mixing every kind of announcement: a plain roundabout,
     * a three-hairpin serpentine, an ordinary two-turn hand-off, a long quiet
     * stretch, and arrival - built for printing the complete announcement list
     * of a simulated ride end to end.
     */
    fun sampleRideRoute(): Route {
        val step = 20.0
        val totalMeters = 25_000.0
        val count = (totalMeters / step).toInt()
        val points = ArrayList<GeoPoint>(count + 1)
        for (i in 0..count) points += Geo.offset(ORIGIN, 0.0, i * step)
        val cumulative = cumulativeOf(points)

        fun indexAt(meters: Double) = nearestIndex(cumulative, meters)

        fun instructionAt(meters: Double, maneuver: Maneuver, angle: Double, exit: Int = 0) =
            indexAt(meters).let { idx ->
                NavigationInstruction(idx, points[idx], maneuver, cumulative[idx], angle, exit)
            }

        val instructions = listOf(
            instructionAt(2_000.0, Maneuver.ROUNDABOUT, 0.0, exit = 2),
            instructionAt(5_000.0, Maneuver.HAIRPIN_LEFT, -150.0),
            instructionAt(5_080.0, Maneuver.HAIRPIN_RIGHT, 150.0),
            instructionAt(5_160.0, Maneuver.HAIRPIN_LEFT, -150.0),
            instructionAt(6_000.0, Maneuver.TURN_RIGHT, 90.0),
            instructionAt(6_100.0, Maneuver.TURN_LEFT, -90.0),
            instructionAt(totalMeters, Maneuver.DESTINATION, 0.0),
        )
        return Route(points, instructions, "test", (totalMeters / 25.0).toInt())
    }

    private fun cumulativeOf(points: List<GeoPoint>): DoubleArray {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
        }
        return cumulative
    }

    private fun nearestIndex(cumulative: DoubleArray, targetMeters: Double): Int {
        val found = cumulative.binarySearch(targetMeters)
        return (if (found < 0) -found - 2 else found).coerceIn(0, cumulative.size - 1)
    }
}
