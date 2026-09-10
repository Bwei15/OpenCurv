package com.opencurv.testarena.model

import com.opencurv.testarena.geometry.PathBuilder
import com.opencurv.testarena.geometry.Point
import com.opencurv.testarena.geometry.Vertex

data class OsmNode(
    val id: Long,
    val point: Point,
    val elevationM: Double? = null,
    val tags: Map<String, String> = emptyMap(),
)

data class OsmWay(
    val id: Long,
    val nodeIds: List<Long>,
    val tags: Map<String, String>,
)

/**
 * Everything the generator builds: an ordinary, in-memory OSM node/way graph.
 * Node ids start at 1, way ids at [WAY_ID_BASE] - the two are independent
 * namespaces in OSM, exactly like on the real API.
 */
class OsmDocument {
    val nodes = mutableListOf<OsmNode>()
    val ways = mutableListOf<OsmWay>()

    private var nextNodeId = 1L
    private var nextWayId = WAY_ID_BASE

    fun newNodeId(): Long = nextNodeId++
    fun newWayId(): Long = nextWayId++

    fun addNode(node: OsmNode): OsmNode {
        nodes += node
        return node
    }

    fun addNamedEndpoint(name: String, point: Point): OsmNode =
        addNode(OsmNode(newNodeId(), point, tags = mapOf("name" to name)))

    fun addPlainNode(point: Point, tags: Map<String, String> = emptyMap()): OsmNode =
        addNode(OsmNode(newNodeId(), point, tags = tags))

    /** A closed way (first and last node identical) tagged as a landuse/area polygon. */
    fun addAreaPolygon(points: List<Point>, tags: Map<String, String>): OsmWay {
        require(points.size >= 3) { "a polygon needs at least 3 distinct points" }
        val nodeIds = points.map { addPlainNode(it).id }.toMutableList()
        nodeIds += nodeIds.first()
        val way = OsmWay(newWayId(), nodeIds, tags)
        ways += way
        return way
    }

    /**
     * Turns a [PathBuilder]'s sampled vertices into OSM nodes and one OSM way, reusing
     * [startNode] and [endNode] for the first/last vertex (this is what makes the shared
     * ALPHA/OMEGA endpoints an actual routable graph junction: every alternative route's way
     * references the very same node id at both ends) and creating fresh nodes for every
     * vertex in between.
     */
    fun addWayFromPath(
        path: PathBuilder,
        startNode: OsmNode,
        endNode: OsmNode,
        tags: Map<String, String>,
    ): OsmWay {
        val vertices = path.vertices
        require(vertices.size >= 2) { "path must have at least two vertices" }
        val nodeIds = mutableListOf(startNode.id)
        for (i in 1 until vertices.size - 1) {
            nodeIds += addVertexAsNode(vertices[i]).id
        }
        nodeIds += endNode.id
        val way = OsmWay(newWayId(), nodeIds, tags)
        ways += way
        return way
    }

    private fun addVertexAsNode(v: Vertex): OsmNode {
        val tags = if (v.elevationM != null) mapOf("ele" to formatEle(v.elevationM)) else emptyMap()
        return addNode(OsmNode(newNodeId(), v.point, v.elevationM, tags))
    }

    private fun formatEle(m: Double): String {
        // OSM convention: metres, plain decimal, no trailing zeros beyond one decimal.
        val rounded = Math.round(m * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    companion object {
        const val WAY_ID_BASE = 100_000L
    }
}
