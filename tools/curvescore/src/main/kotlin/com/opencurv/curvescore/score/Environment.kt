package com.opencurv.curvescore.score

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.geom.distPointToSegment
import com.opencurv.curvescore.geom.pointInRing
import com.opencurv.curvescore.model.OsmData
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What a point on the map is surrounded by.
 *
 * The brief asks for surrounding polygons - woods and water - to count in
 * favour, and settlements to count against. Both come from the same lookup, so
 * they share one spatial index.
 *
 * @param scenery 0..1, 0.5 = neutral/unknown
 * @param settlement 1.0 if the point is inside a built-up area
 */
data class EnvSample(val scenery: Double, val settlement: Double)

/**
 * Scenery value of an area type, on the 0..1 scale where 0.5 is "no
 * information". These are rider judgements, and they are meant to be arguable -
 * but they are at least explicit and in one table.
 */
private val AREA_SCENERY = mapOf(
    // Positive - the reason to take the long way round.
    "forest" to 1.0, "wood" to 1.0, "nature_reserve" to 1.0, "national_park" to 1.0,
    "water" to 0.95, "wetland" to 0.85, "bay" to 0.95, "beach" to 0.9, "glacier" to 1.0,
    "heath" to 0.85, "scrub" to 0.75, "moor" to 0.85, "grassland" to 0.8,
    "meadow" to 0.8, "vineyard" to 0.85, "orchard" to 0.8, "park" to 0.7,
    "recreation_ground" to 0.6, "village_green" to 0.6, "cemetery" to 0.55,
    // Neutral-ish - open country, but worked land.
    "farmland" to 0.6, "farmyard" to 0.5, "allotments" to 0.5, "greenhouse_horticulture" to 0.4,
    "grass" to 0.6, "plant_nursery" to 0.5,
    // Negative - places you ride through, not to.
    "residential" to 0.20, "garages" to 0.15, "commercial" to 0.15, "retail" to 0.15,
    "industrial" to 0.10, "railway" to 0.15, "port" to 0.15,
    "quarry" to 0.10, "landfill" to 0.05, "brownfield" to 0.10, "greenfield" to 0.35,
    "military" to 0.20, "construction" to 0.10,
)

/** Area types that count as "built up" for the settlement penalty. */
private val BUILT_UP = setOf(
    "residential", "commercial", "retail", "industrial", "garages",
    "railway", "port", "construction", "landfill", "brownfield",
)

private class AreaPolygon(
    val ring: List<Pt>,
    val kind: String,
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
)

private class WaterLine(val pts: List<Pt>, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * A flat grid index over landuse/natural/leisure polygons and waterway lines.
 *
 * Grid rather than an R-tree on purpose: the cells are 500 m, the polygons are
 * mostly small, lookups are point-in-cell, and the whole thing is 60 lines
 * instead of 400. At Bavaria scale it is a few hundred thousand polygons and
 * a few million cell entries - well inside what a CI runner does in seconds.
 *
 * Multipolygon relations are not resolved; their outer ways usually carry the
 * landuse tag themselves or are close enough for a scenery term that only ever
 * moves the score by at most 0.08.
 */
class Environment private constructor(
    private val cellM: Double,
    private val polyCells: Map<Long, MutableList<AreaPolygon>>,
    private val waterCells: Map<Long, MutableList<WaterLine>>,
    private val waterBufferM: Double,
) {

    /**
     * Rule: **the least attractive surrounding decides.** If several polygons
     * contain the point, the minimum of their scenery values wins - an
     * industrial estate carved into a forest is an industrial estate. If none
     * does, the value is the neutral 0.5, never a guess.
     *
     * Water is the one thing that can lift a neutral surrounding without
     * containing the point: a road along a river or lakeshore is a scenic road
     * even though it is not "inside" the water. The lift decays linearly to
     * nothing at the buffer distance, and it can never override a built-up
     * area (an industrial harbour is still an industrial harbour).
     */
    fun sample(p: Pt): EnvSample {
        var value = Double.MAX_VALUE
        var settlement = 0.0
        for (poly in polyCells[cellKey(p.x, p.y)].orEmpty()) {
            if (p.x < poly.minX || p.x > poly.maxX || p.y < poly.minY || p.y > poly.maxY) continue
            if (!pointInRing(p, poly.ring)) continue
            val v = AREA_SCENERY[poly.kind] ?: continue
            if (v < value) value = v
            if (poly.kind in BUILT_UP) settlement = 1.0
        }
        var scenery = if (value == Double.MAX_VALUE) 0.5 else value
        if (settlement == 0.0 && scenery < 0.9) {
            for (w in waterCells[cellKey(p.x, p.y)].orEmpty()) {
                if (p.x < w.minX - waterBufferM || p.x > w.maxX + waterBufferM) continue
                if (p.y < w.minY - waterBufferM || p.y > w.maxY + waterBufferM) continue
                var d = Double.MAX_VALUE
                for (i in 0 until w.pts.size - 1) {
                    d = min(d, distPointToSegment(p, w.pts[i], w.pts[i + 1]))
                    if (d < 1.0) break
                }
                if (d <= waterBufferM) {
                    scenery = max(scenery, 0.5 + 0.4 * (1.0 - d / waterBufferM))
                }
            }
        }
        return EnvSample(scenery, settlement)
    }

    private fun cellKey(x: Double, y: Double): Long {
        val cx = floor(x / cellM).toLong()
        val cy = floor(y / cellM).toLong()
        return (cx shl 32) xor (cy and 0xffffffffL)
    }

    companion object {
        private const val CELL_M = 500.0
        private const val WATER_BUFFER_M = 120.0

        val EMPTY = Environment(CELL_M, emptyMap(), emptyMap(), WATER_BUFFER_M)

        fun build(data: OsmData, plane: LocalPlane): Environment {
            val polyCells = HashMap<Long, MutableList<AreaPolygon>>()
            val waterCells = HashMap<Long, MutableList<WaterLine>>()
            for (w in data.ways) {
                val kind = w.tags["landuse"] ?: w.tags["natural"] ?: w.tags["leisure"]
                    ?: w.tags["boundary"]?.takeIf { it == "national_park" || it == "protected_area" }
                        ?.let { "nature_reserve" }
                val isWaterLine = w.tags["waterway"] in setOf("river", "stream", "canal", "riverbank")
                if (kind == null && !isWaterLine) continue
                val pts = w.nodeIds.map { id -> data.nodes[id] }.filterNotNull()
                    .map { plane.toLocal(it.lat, it.lon) }
                if (pts.size < 2) continue
                var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                for (p in pts) {
                    minX = min(minX, p.x); maxX = max(maxX, p.x)
                    minY = min(minY, p.y); maxY = max(maxY, p.y)
                }
                if (kind != null && AREA_SCENERY.containsKey(kind) && pts.size >= 4 && w.isClosed) {
                    val poly = AreaPolygon(pts, kind, minX, minY, maxX, maxY)
                    forEachCell(minX, minY, maxX, maxY, 0.0) { key ->
                        polyCells.getOrPut(key) { ArrayList(2) }.add(poly)
                    }
                } else if (isWaterLine) {
                    val line = WaterLine(pts, minX, minY, maxX, maxY)
                    forEachCell(minX, minY, maxX, maxY, WATER_BUFFER_M) { key ->
                        waterCells.getOrPut(key) { ArrayList(2) }.add(line)
                    }
                }
            }
            return Environment(CELL_M, polyCells, waterCells, WATER_BUFFER_M)
        }

        private inline fun forEachCell(
            minX: Double, minY: Double, maxX: Double, maxY: Double, pad: Double,
            body: (Long) -> Unit,
        ) {
            val x0 = floor((minX - pad) / CELL_M).toLong()
            val x1 = floor((maxX + pad) / CELL_M).toLong()
            val y0 = floor((minY - pad) / CELL_M).toLong()
            val y1 = floor((maxY + pad) / CELL_M).toLong()
            // Guard against a pathological bounding box swallowing the whole grid.
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 2_000_000L) return
            var x = x0
            while (x <= x1) {
                var y = y0
                while (y <= y1) {
                    body((x shl 32) xor (y and 0xffffffffL))
                    y++
                }
                x++
            }
        }
    }
}
