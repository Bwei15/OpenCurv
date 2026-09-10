package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint

/**
 * A circular avoidance area for routing.
 * When [isClosure] is true, roads within this radius are treated as impassable.
 */
data class NoGoArea(
    val point: GeoPoint,
    val radiusMeters: Int = 50,
    val isClosure: Boolean = true,
    val description: String? = null,
)

/**
 * A polygonal avoidance area for line or boundary closures (e.g. closed mountain pass or road stretch).
 */
data class NoGoPolygon(
    val points: List<GeoPoint>,
    val isClosed: Boolean = true,
    val description: String? = null,
)
