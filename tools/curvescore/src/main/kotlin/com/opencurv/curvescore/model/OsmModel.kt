package com.opencurv.curvescore.model

/**
 * The slice of OSM the scorer needs. Deliberately minimal: nodes with an
 * optional elevation, ways with a node-id list and tags. Relations are not
 * read - multipolygon landuse is approximated by its outer closed ways, which
 * is what the scenery term needs (see Environment.kt) and costs a fraction of
 * the memory a full relation model would.
 */
class OsmNode(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val eleM: Double?,
    val tags: Map<String, String>,
)

class OsmWay(
    val id: Long,
    val nodeIds: LongArray,
    val tags: Map<String, String>,
) {
    val isClosed: Boolean get() = nodeIds.size >= 4 && nodeIds[0] == nodeIds[nodeIds.size - 1]
}

class OsmData(
    val nodes: Map<Long, OsmNode>,
    val ways: List<OsmWay>,
) {
    /** Bounding-box centre, used as the anchor of the local metre plane. */
    fun centre(): Pair<Double, Double> {
        if (nodes.isEmpty()) return 0.0 to 0.0
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (n in nodes.values) {
            if (n.lat < minLat) minLat = n.lat
            if (n.lat > maxLat) maxLat = n.lat
            if (n.lon < minLon) minLon = n.lon
            if (n.lon > maxLon) maxLon = n.lon
        }
        return (minLat + maxLat) / 2.0 to (minLon + maxLon) / 2.0
    }
}
