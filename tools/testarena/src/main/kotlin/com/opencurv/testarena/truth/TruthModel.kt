package com.opencurv.testarena.truth

/**
 * The schema of arena_truth.json. Every numeric field here is computed
 * analytically by the generator from the exact [com.opencurv.testarena.geometry.Primitive]
 * list used to build the corresponding element - it is never measured/estimated from the
 * sampled OSM geometry, so it is the ground truth that geometry is tested against
 * (see GeometryTruthTest) and that a scored route is compared to (see the harness).
 */
data class LatLonDto(val lat: Double, val lon: Double)

data class ToleranceNotes(
    val radiusRelativeTolerance: Double,
    val lengthRelativeTolerance: Double,
    val turnDegRelativeTolerance: Double,
    val explanation: String,
)

data class ArenaMeta(
    val originLat: Double,
    val originLon: Double,
    val whyThisLocation: String,
    val pointSpacingMetersMin: Double,
    val pointSpacingMetersMax: Double,
    val alphaOmegaStraightLineDistanceM: Double,
    val tolerances: ToleranceNotes,
)

/**
 * One named alternative between two endpoints (usually ALPHA/OMEGA), or one standalone
 * comparison element (the HILL_* / GREEN_* pairs). [routeId] is also written onto every OSM
 * way that belongs to this element as the tag `opencurv:route=<routeId>`, so the harness can
 * work out which element(s) a scored route actually drove on purely from the arena's own OSM
 * data, without needing any additional lookup table.
 */
data class ElementTruth(
    val routeId: String,
    val name: String,
    val description: String,
    val fromNode: String,
    val toNode: String,
    val highway: String,
    val surface: String?,
    val maxspeedKmh: Int?,
    val landuse: String?,
    val wayIds: List<Long>,
    val lengthM: Double,
    val curveCount: Int,
    val minRadiusM: Double?,
    val meanRadiusM: Double?,
    val sharpCornerCount: Int,
    val totalTurnDeg: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
)

/**
 * "For a request from [from] to [to], a good curve-preferring routing algorithm must prefer
 * a route dominated by one of [preferRouteIds] over one dominated by one of [overRouteIds]."
 * [severity] "hard" = must hold for OpenCurv to be doing its job at all; "soft" = desirable /
 * a hook for a future scoring dimension (see reason), not yet a hard requirement.
 */
data class Expectation(
    val id: String,
    val from: String,
    val to: String,
    val preferRouteIds: List<String>,
    val overRouteIds: List<String>,
    val severity: String,
    val reason: String,
)

data class ArenaTruth(
    val meta: ArenaMeta,
    val namedNodes: Map<String, LatLonDto>,
    val elements: List<ElementTruth>,
    val expectations: List<Expectation>,
)
