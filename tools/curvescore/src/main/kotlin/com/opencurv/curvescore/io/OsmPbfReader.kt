package com.opencurv.curvescore.io

import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmNode
import com.opencurv.curvescore.model.OsmWay
import de.topobyte.osm4j.core.model.iface.EntityContainer
import de.topobyte.osm4j.core.model.iface.EntityType
import de.topobyte.osm4j.core.model.iface.OsmNode as O4Node
import de.topobyte.osm4j.core.model.iface.OsmWay as O4Way
import de.topobyte.osm4j.pbf.seq.PbfIterator
import java.io.BufferedInputStream
import java.io.File

/**
 * Reads .osm.pbf via the same `osm4j` library `tools/testarena` uses to write
 * it - one dependency, both directions, no second protobuf implementation to
 * keep in sync.
 *
 * Single pass, everything in memory. That is fine for the testarena and for
 * city-sized extracts; the production Bavaria-scale path is described in
 * 1.Doku/Kurven_Score.md ("Laufzeit und Speicher") and uses the same iterator
 * with a two-pass, primitive-array node store instead of a HashMap.
 */
object OsmPbfReader {
    fun read(file: File): OsmData {
        val nodes = HashMap<Long, OsmNode>()
        val ways = ArrayList<OsmWay>()
        BufferedInputStream(file.inputStream(), 1 shl 20).use { input ->
            val it: Iterator<EntityContainer> = PbfIterator(input, true)
            while (it.hasNext()) {
                val c = it.next()
                when (c.type) {
                    EntityType.Node -> {
                        val n = c.entity as O4Node
                        val tags = tagsOf(n.numberOfTags) { i -> n.getTag(i).key to n.getTag(i).value }
                        nodes[n.id] = OsmNode(n.id, n.latitude, n.longitude, tags["ele"]?.toDoubleOrNull(), tags)
                    }
                    EntityType.Way -> {
                        val w = c.entity as O4Way
                        val refs = LongArray(w.numberOfNodes) { i -> w.getNodeId(i) }
                        val tags = tagsOf(w.numberOfTags) { i -> w.getTag(i).key to w.getTag(i).value }
                        ways.add(OsmWay(w.id, refs, tags))
                    }
                    else -> {}
                }
            }
        }
        return OsmData(nodes, ways)
    }

    private inline fun tagsOf(count: Int, get: (Int) -> Pair<String, String>): Map<String, String> {
        if (count == 0) return emptyMap()
        val m = LinkedHashMap<String, String>(count * 2)
        for (i in 0 until count) { val (k, v) = get(i); m[k] = v }
        return m
    }
}
