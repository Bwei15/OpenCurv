package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo

enum class IncidentType {
    ROAD_CLOSURE,
    CONSTRUCTION,
    ACCIDENT,
    WEATHER_WARNING,
    PASS_CLOSURE,
    HAZARD,
}

enum class IncidentSeverity {
    CRITICAL, // impassable / full closure
    WARNING,  // delays, single-lane, speed limit reduction
    INFO,     // general roadworks, non-blocking
}

data class TrafficIncident(
    val id: String,
    val title: String,
    val description: String,
    val type: IncidentType,
    val severity: IncidentSeverity,
    val location: GeoPoint,
    val radiusMeters: Int = 50,
    val polyline: List<GeoPoint>? = null,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val roadName: String? = null,
) {
    val isImpassable: Boolean
        get() = type == IncidentType.ROAD_CLOSURE ||
                type == IncidentType.PASS_CLOSURE ||
                severity == IncidentSeverity.CRITICAL

    fun toNoGoArea(): NoGoArea = NoGoArea(
        point = location,
        radiusMeters = radiusMeters,
        isClosure = isImpassable,
        description = title,
    )

    /**
     * Avoidance points covering this incident for BRouter.
     *
     * A point incident is a single circle, same as [toNoGoArea]. A line
     * incident (a closed motorway stretch, a wintersperre pass) becomes a
     * chain of overlapping circles sampled every [spacingMeters] along the
     * polyline, so BRouter must avoid the whole closed stretch rather than
     * just its single reference point. BRouter also supports an unclosed
     * [NoGoPolygon] as a proper nogo *line* (see BRouterEngine), which would
     * be a tighter fit than a chain of circles - but only [NoGoArea] is
     * currently wired from [TrafficRepository] through to `RouteRequest`, so
     * this stays within that existing plumbing rather than reaching into
     * NavigationController.
     */
    fun toNoGoAreas(spacingMeters: Double = 150.0): List<NoGoArea> {
        val line = polyline
        if (line == null || line.size < 2) return listOf(toNoGoArea())

        val sampled = ArrayList<GeoPoint>()
        sampled.add(line.first())
        var sinceLastSample = 0.0
        for (i in 1 until line.size) {
            sinceLastSample += Geo.distanceMeters(line[i - 1], line[i])
            if (sinceLastSample >= spacingMeters || i == line.size - 1) {
                sampled.add(line[i])
                sinceLastSample = 0.0
            }
        }

        // Overlapping radius so consecutive circles leave no gap a route could thread through.
        val radius = maxOf(radiusMeters, (spacingMeters / 2).toInt() + 25)
        return sampled.map { point ->
            NoGoArea(point = point, radiusMeters = radius, isClosure = isImpassable, description = title)
        }
    }
}
