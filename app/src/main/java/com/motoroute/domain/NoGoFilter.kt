package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.NoGoArea
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps only the closures that can matter for one route.
 *
 * The live feed carries every motorway closure in Germany - several hundred,
 * expanded to thousands of avoidance circles once line closures are sampled.
 * BRouter checks every nogo against every link it expands, so handing it the
 * whole country turns a ten-second route into minutes. A closure 300 km from
 * both ends of the trip cannot change the result, so it is dropped here.
 */
object NoGoFilter {
    /** Margin around the waypoint box; a detour rarely swings out further. */
    const val MARGIN_KM = 30.0

    fun near(noGos: List<NoGoArea>, waypoints: List<GeoPoint>, marginKm: Double = MARGIN_KM): List<NoGoArea> {
        if (noGos.isEmpty() || waypoints.isEmpty()) return emptyList()
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in waypoints) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        val latMargin = marginKm / 111.0
        val lonMargin = marginKm / (111.0 * max(0.2, cos(Math.toRadians((minLat + maxLat) / 2.0))))
        minLat -= latMargin; maxLat += latMargin
        minLon -= lonMargin; maxLon += lonMargin
        return noGos.filter { it.point.latitude in minLat..maxLat && it.point.longitude in minLon..maxLon }
    }
}
