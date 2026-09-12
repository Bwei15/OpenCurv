package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlin.math.PI
import kotlin.random.Random

/**
 * A first cut at a loop's via points: three points on a circle centred on the start, spaced 90
 * degrees apart, sized so the circle's circumference times a road-tortuosity factor comes out to
 * the rider's wanted length.
 *
 * This says nothing about which roads actually connect the points - only BRouter and the tiles
 * on the phone know that. A suggested point that turns out unreachable ("no route found", or off
 * the imported tiles entirely) is meant to be nudged toward the start with [nudgeTowardStart] and
 * tried again, a few times, by whoever drives this (see MapViewModel.suggestRoundTrip) - not
 * something this pure-geometry object tries to predict.
 *
 * TODO(H3 hotspots): this circle is a placeholder until curve-density hotspots (an H3 grid, see
 * the project plan) can bias the points toward roads actually worth riding.
 *
 * Android-free, same reason [Geo] itself is: a unit test can prove the shape with plain numbers
 * instead of a device and 30-80 s of BRouter per attempt.
 */
object RoundTripPlanner {

    /** How much longer real roads are than the straight-line circle approximating them. */
    private const val ROAD_FACTOR = 1.3

    /**
     * Three via points on a circle around [start]. The radius is picked so
     * `2 * pi * radius * ROAD_FACTOR == desiredLengthMeters`; the first point sits at a random
     * bearing so repeated suggestions for the same start do not always send the rider the same
     * way, and the other two follow at +90 deg and +180 deg from it.
     */
    fun suggestLoop(
        start: GeoPoint,
        desiredLengthMeters: Double,
        random: Random = Random.Default,
    ): List<GeoPoint> {
        require(desiredLengthMeters > 0) { "desiredLengthMeters must be positive" }
        val radius = radiusFor(desiredLengthMeters)
        val startBearing = random.nextDouble(0.0, 360.0)
        return listOf(90.0, 180.0, 270.0).map { offset ->
            Geo.offset(start, Geo.normalizeBearing(startBearing + offset), radius)
        }
    }

    /** The radius [suggestLoop] uses for a given wanted length - exposed so tests can check it directly. */
    fun radiusFor(desiredLengthMeters: Double): Double = desiredLengthMeters / (2.0 * PI * ROAD_FACTOR)

    /**
     * Moves [point] [stepMeters] toward [start] - the retry move when BRouter cannot reach a
     * suggested point. Stops at [start] rather than overshooting past it.
     */
    fun nudgeTowardStart(point: GeoPoint, start: GeoPoint, stepMeters: Double = 2_000.0): GeoPoint {
        val distance = Geo.distanceMeters(point, start)
        if (distance <= stepMeters) return start
        val bearing = Geo.bearingDegrees(point, start)
        return Geo.offset(point, bearing, stepMeters)
    }
}
