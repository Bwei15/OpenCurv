package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint

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
}
