package com.opencurv.curvescore.io

import com.opencurv.curvescore.model.OsmNode

/**
 * A read-only `Map<Long, OsmNode>` backed by parallel primitive arrays
 * instead of one `OsmNode` object (plus a boxed-Long HashMap entry) per node.
 *
 * Nodes are built lazily on lookup or iteration. Nothing above `io/` -
 * `Corridor`, `Environment`, `CurveScorer`, `OsmData.centre()` - can tell the
 * difference from a plain `HashMap<Long, OsmNode>`; they only ever go through
 * the `Map` interface. That is the point: the memory rewrite documented in
 * 1.Doku/Kurven_Score.md ("Laufzeit und Speicher") stays confined to `io/`.
 *
 * @param ids sorted ascending, distinct node ids
 * @param lat1e7 latitude * 1e7, rounded to the nearest int (matches the
 *   ~1.1 cm resolution `1e7`-fixed-point gives at German latitudes - far
 *   below OSM's own positional accuracy, so this is not a precision loss the
 *   scorer's geometry stage (Douglas-Peucker at 0.5 m) could ever notice)
 * @param lon1e7 longitude * 1e7, rounded
 * @param ele elevation in metres, `Float.NaN` if the node carries no `ele` tag
 * @param tagsById sparse: only nodes that carry at least one tag appear here
 *   (traffic signals, barriers, crossings - the minority the "Unterbrechungen"
 *   penalty term reads via `OsmNode.tags`). Absent = no tags, exactly like an
 *   empty map would have meant before.
 */
class PrimitiveNodeStore(
    private val ids: LongArray,
    private val lat1e7: IntArray,
    private val lon1e7: IntArray,
    private val ele: FloatArray,
    private val tagsById: Map<Long, Map<String, String>>,
) : Map<Long, OsmNode> {

    init {
        require(ids.size == lat1e7.size && ids.size == lon1e7.size && ids.size == ele.size) {
            "PrimitiveNodeStore arrays must be the same length"
        }
    }

    override val size: Int get() = ids.size
    override fun isEmpty(): Boolean = ids.isEmpty()

    private fun indexOf(id: Long): Int = java.util.Arrays.binarySearch(ids, id)

    override fun containsKey(key: Long): Boolean = indexOf(key) >= 0
    override fun containsValue(value: OsmNode): Boolean = indexOf(value.id) >= 0

    override fun get(key: Long): OsmNode? {
        val idx = indexOf(key)
        return if (idx >= 0) buildAt(idx) else null
    }

    private fun buildAt(idx: Int): OsmNode {
        val id = ids[idx]
        val e = ele[idx]
        return OsmNode(
            id = id,
            lat = lat1e7[idx] / 1e7,
            lon = lon1e7[idx] / 1e7,
            eleM = if (e.isNaN()) null else e.toDouble(),
            tags = tagsById[id] ?: emptyMap(),
        )
    }

    override val keys: Set<Long>
        get() = object : AbstractSet<Long>() {
            override val size: Int get() = ids.size
            override fun iterator(): Iterator<Long> = ids.iterator()
            override fun contains(element: Long): Boolean = indexOf(element) >= 0
        }

    override val values: Collection<OsmNode>
        get() = object : AbstractCollection<OsmNode>() {
            override val size: Int get() = ids.size
            override fun iterator(): Iterator<OsmNode> = object : Iterator<OsmNode> {
                var i = 0
                override fun hasNext() = i < ids.size
                override fun next(): OsmNode = buildAt(i++)
            }
        }

    override val entries: Set<Map.Entry<Long, OsmNode>>
        get() = object : AbstractSet<Map.Entry<Long, OsmNode>>() {
            override val size: Int get() = ids.size
            override fun iterator(): Iterator<Map.Entry<Long, OsmNode>> = object : Iterator<Map.Entry<Long, OsmNode>> {
                var i = 0
                override fun hasNext() = i < ids.size
                override fun next(): Map.Entry<Long, OsmNode> {
                    val node = buildAt(i)
                    i++
                    return java.util.AbstractMap.SimpleImmutableEntry(node.id, node)
                }
            }
        }
}
