package com.opencurv.testarena.harness

import com.opencurv.testarena.geometry.LatLon
import com.opencurv.testarena.geometry.Point
import com.opencurv.testarena.geometry.Projection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One straight sub-segment of an arena way, in the local metre plane, carrying that way's tags. */
data class ArenaSegment(val a: Point, val b: Point, val wayId: Long, val tags: Map<String, String>)

/**
 * The arena's road network as loaded straight from arena.osm - the harness's only source of
 * truth about "where is what". It never reads arena_truth.json's geometry (there isn't any:
 * that file holds analytic numbers and expectations, not coordinates) and never talks to a
 * routing engine.
 */
class ArenaGraph private constructor(val segments: List<ArenaSegment>) {
    /** Nearest segment to [p], or null if the arena has no ways at all (segments only - area
     *  polygons like landuse are not routes and are excluded by [load]). */
    fun nearestSegment(p: Point): Pair<ArenaSegment, Double>? {
        var best: ArenaSegment? = null
        var bestDist = Double.MAX_VALUE
        for (seg in segments) {
            val d = distancePointToSegment(p, seg.a, seg.b)
            if (d < bestDist) {
                bestDist = d
                best = seg
            }
        }
        return best?.let { it to bestDist }
    }

    companion object {
        fun load(osmFile: File): ArenaGraph {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val doc = factory.newDocumentBuilder().parse(osmFile)

            val nodePoints = HashMap<Long, Point>()
            val nodeEls = doc.getElementsByTagName("node")
            for (i in 0 until nodeEls.length) {
                val el = nodeEls.item(i) as org.w3c.dom.Element
                val id = el.getAttribute("id").toLong()
                val lat = el.getAttribute("lat").toDouble()
                val lon = el.getAttribute("lon").toDouble()
                nodePoints[id] = Projection.toLocal(LatLon(lat, lon))
            }

            val segments = mutableListOf<ArenaSegment>()
            val wayEls = doc.getElementsByTagName("way")
            for (i in 0 until wayEls.length) {
                val wayEl = wayEls.item(i) as org.w3c.dom.Element
                val wayId = wayEl.getAttribute("id").toLong()
                val tags = mutableMapOf<String, String>()
                val refs = mutableListOf<Long>()
                val children = wayEl.childNodes
                for (c in 0 until children.length) {
                    val child = children.item(c)
                    if (child !is org.w3c.dom.Element) continue
                    when (child.tagName) {
                        "nd" -> refs += child.getAttribute("ref").toLong()
                        "tag" -> tags[child.getAttribute("k")] = child.getAttribute("v")
                    }
                }
                // Only real ways/tracks are routable segments; landuse/area polygons (which
                // OpenStreetMap represents as closed ways too) are context, not road network.
                if (!tags.containsKey("highway")) continue
                for (n in 0 until refs.size - 1) {
                    val a = nodePoints[refs[n]] ?: continue
                    val b = nodePoints[refs[n + 1]] ?: continue
                    segments += ArenaSegment(a, b, wayId, tags)
                }
            }
            return ArenaGraph(segments)
        }
    }
}

/** Shortest distance from point [p] to the line segment [a]-[b]. */
fun distancePointToSegment(p: Point, a: Point, b: Point): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq < 1e-9) return p.distanceTo(a)
    var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
    t = t.coerceIn(0.0, 1.0)
    val proj = Point(a.x + t * abx, a.y + t * aby)
    return p.distanceTo(proj)
}
