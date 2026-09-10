package com.opencurv.curvescore

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmNode
import com.opencurv.curvescore.model.OsmWay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A tiny road builder for tests: straights and circular arcs with a commanded
 * radius, so every expected value can be worked out on paper.
 *
 * It is intentionally *not* the testarena's builder - the arena is read-only
 * for this module, and a unit test that shares its fixture generator with the
 * thing it measures is not an independent check.
 */
class SyntheticRoad(startHeadingDeg: Double = 0.0) {
    private val pts = ArrayList<Pt>()
    private val ele = ArrayList<Double?>()
    private var x = 0.0
    private var y = 0.0
    private var heading = Math.toRadians(startHeadingDeg)
    private var height: Double? = null

    init { pts.add(Pt(0.0, 0.0)); ele.add(null) }

    fun startElevation(m: Double): SyntheticRoad { height = m; ele[0] = m; return this }

    fun straight(lengthM: Double, gradient: Double = 0.0, stepM: Double = 20.0): SyntheticRoad {
        val n = maxOf(1, Math.round(lengthM / stepM).toInt())
        val step = lengthM / n
        repeat(n) {
            x += sin(heading) * step
            y += cos(heading) * step
            height = height?.plus(step * gradient)
            pts.add(Pt(x, y)); ele.add(height)
        }
        return this
    }

    /** Positive [turnDeg] turns right (clockwise), negative left. */
    fun arc(radiusM: Double, turnDeg: Double, gradient: Double = 0.0): SyntheticRoad {
        val total = Math.toRadians(Math.abs(turnDeg))
        val sign = if (turnDeg >= 0) 1.0 else -1.0
        // Node spacing follows the arena's rule: never more than half the radius,
        // never more than 20 m, so a tight arc is always sampled as an arc.
        val step = min(20.0, radiusM / 2.0)
        val n = maxOf(2, Math.ceil(total * radiusM / step).toInt())
        val dTheta = total / n
        repeat(n) {
            heading += sign * dTheta
            val chord = 2 * radiusM * sin(dTheta / 2)
            x += sin(heading - sign * dTheta / 2) * chord
            y += cos(heading - sign * dTheta / 2) * chord
            height = height?.plus(chord * gradient)
            pts.add(Pt(x, y)); ele.add(height)
        }
        return this
    }

    /** A single instantaneous corner - what an OSM junction kink looks like. */
    fun corner(turnDeg: Double): SyntheticRoad {
        heading += Math.toRadians(turnDeg)
        return this
    }

    fun points(): List<Pt> = pts.toList()

    /** Wraps the road as an OsmData with one tagged way, plus optional landuse polygon. */
    fun toOsm(
        tags: Map<String, String>,
        plane: LocalPlane = LocalPlane(48.0, 11.0),
        landuse: String? = null,
        wayId: Long = 1L,
    ): OsmData {
        val nodes = LinkedHashMap<Long, OsmNode>()
        val ids = LongArray(pts.size)
        for (i in pts.indices) {
            val ll = plane.toLatLon(pts[i])
            val id = 1000L + i
            nodes[id] = OsmNode(id, ll[0], ll[1], ele[i], emptyMap())
            ids[i] = id
        }
        val ways = ArrayList<OsmWay>()
        ways.add(OsmWay(wayId, ids, tags))
        if (landuse != null) {
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in pts) {
                minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
            }
            val pad = 500.0
            val corners = listOf(
                Pt(minX - pad, minY - pad), Pt(maxX + pad, minY - pad),
                Pt(maxX + pad, maxY + pad), Pt(minX - pad, maxY + pad),
            )
            val polyIds = LongArray(5)
            for ((i, c) in corners.withIndex()) {
                val ll = plane.toLatLon(c)
                val id = 900000L + i
                nodes[id] = OsmNode(id, ll[0], ll[1], null, emptyMap())
                polyIds[i] = id
            }
            polyIds[4] = polyIds[0]
            ways.add(OsmWay(990000L, polyIds, mapOf("landuse" to landuse)))
        }
        return OsmData(nodes, ways)
    }

    companion object {
        /** Repeats a curve/straight pattern to build a road of a given character. */
        fun alternating(
            curves: Int,
            radiusM: Double,
            turnDeg: Double,
            straightM: Double,
            gradient: Double = 0.0,
            startElevationM: Double? = null,
        ): SyntheticRoad {
            val r = SyntheticRoad()
            if (startElevationM != null) r.startElevation(startElevationM)
            r.straight(straightM / 2, gradient)
            for (i in 0 until curves) {
                r.arc(radiusM, if (i % 2 == 0) turnDeg else -turnDeg, gradient)
                r.straight(straightM, gradient)
            }
            return r
        }
    }
}
