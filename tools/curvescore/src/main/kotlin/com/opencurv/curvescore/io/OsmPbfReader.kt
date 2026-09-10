package com.opencurv.curvescore.io

import com.opencurv.curvescore.model.OsmData
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
 * Two passes, primitive-array node store. The single-pass `HashMap<Long,
 * OsmNode>` this replaced held one boxed-key HashMap entry plus one `OsmNode`
 * object per node - fine for the testarena, but a real Bundesland has tens of
 * millions of nodes, and measured against Bremen (real data, not a synthetic
 * benchmark) it needed a JVM heap between 400 and 500 MB just to score a
 * 21 MB extract; scaled to Nordrhein-Westfalen (the largest region, ~870 MB)
 * that no longer fits a shared 16 GB GitHub Actions runner with any comfort.
 * See 1.Doku/Kurven_Score.md, section 9, and 1.Doku/Cloud_Pipeline.md for the
 * measurement this rewrite is a response to.
 *
 * Pass 1 (ways only): every way is read and kept exactly as before - nothing
 * about way storage changes. In the same pass, the node ids referenced by
 * ways the scorer actually resolves node coordinates for are collected into a
 * [LongHashSet]: that is every `highway=*` way (`Corridor.roadWays`) and
 * every way [Environment] reads for the scenery/settlement lookup
 * (`landuse`/`natural`/`leisure`, `boundary=national_park|protected_area`,
 * `waterway=river|stream|canal|riverbank` - see `Environment.kt`). Ways of
 * any other kind (buildings, barriers, POIs-as-ways, ...) are kept in the
 * `ways` list unchanged, but their nodes are not needed by anything
 * downstream and are not stored. Measured on Bremen: this drops the node
 * count that needs storing from 1,663,302 (all nodes in the file) to 527,461
 * (31.7%) - buildings dominate the difference in a well-mapped city extract.
 *
 * Pass 2 (nodes only): for each node whose id is in that set, its coordinates
 * go into parallel primitive arrays sorted by id (`long[] id`, `int[] lat`,
 * `int[] lon` as 1e7-fixed-point, `float[] ele`), and its tags - only if it
 * has any, which is a small minority (traffic signals, barriers, crossings) -
 * go into a sparse `Map<Long, Map<String,String>>`. All other nodes are
 * discarded as they are read; they are never allocated as objects.
 *
 * The result is wrapped in a [PrimitiveNodeStore], a `Map<Long, OsmNode>>`
 * that builds an `OsmNode` lazily on lookup. `CurveScorer`, `Corridor` and
 * `Environment` only ever go through the `Map` interface, so this stays
 * entirely inside `io/` - see 1.Doku/Kurven_Score.md, section 9 ("Der Umbau
 * berührt ausschließlich io/; die Bewertungsstufe sieht ihn nicht").
 */
object OsmPbfReader {
    fun read(file: File): OsmData {
        val ways = ArrayList<OsmWay>()
        val refIds = LongHashSet()

        forEachEntity(file) { c ->
            if (c.type == EntityType.Way) {
                val w = c.entity as O4Way
                val refs = LongArray(w.numberOfNodes) { i -> w.getNodeId(i) }
                val tags = tagsOf(w.numberOfTags) { i -> w.getTag(i).key to w.getTag(i).value }
                ways.add(OsmWay(w.id, refs, tags))
                if (needsNodeCoords(tags)) {
                    for (id in refs) refIds.add(id)
                }
            }
        }

        // Freeing the hash set's backing array before pass 2 allocates the
        // (smaller, exactly-sized) result arrays keeps the two structures
        // from being fully live at the same time.
        val sortedIds = refIds.toSortedArray()
        refIds.clear()

        val lat1e7 = IntArray(sortedIds.size)
        val lon1e7 = IntArray(sortedIds.size)
        val ele = FloatArray(sortedIds.size) { Float.NaN }
        val tagsById = HashMap<Long, Map<String, String>>()

        forEachEntity(file) { c ->
            if (c.type == EntityType.Node) {
                val n = c.entity as O4Node
                val idx = java.util.Arrays.binarySearch(sortedIds, n.id)
                if (idx >= 0) {
                    lat1e7[idx] = Math.round(n.latitude * 1e7).toInt()
                    lon1e7[idx] = Math.round(n.longitude * 1e7).toInt()
                    if (n.numberOfTags > 0) {
                        val tags = tagsOf(n.numberOfTags) { i -> n.getTag(i).key to n.getTag(i).value }
                        tags["ele"]?.toDoubleOrNull()?.let { ele[idx] = it.toFloat() }
                        tagsById[n.id] = tags
                    }
                }
            }
        }

        return OsmData(PrimitiveNodeStore(sortedIds, lat1e7, lon1e7, ele, tagsById), ways)
    }

    /**
     * Whether a way's node coordinates are ever resolved via `OsmData.nodes`
     * downstream. Mirrors exactly `Corridor.roadWays` (any `highway=*`) and
     * `Environment`'s polygon/waterline filter (`Environment.kt:135-138`) -
     * keep these three in sync if either of those changes.
     */
    private fun needsNodeCoords(tags: Map<String, String>): Boolean {
        if (tags.containsKey("highway")) return true
        if (tags.containsKey("landuse") || tags.containsKey("natural") || tags.containsKey("leisure")) return true
        val boundary = tags["boundary"]
        if (boundary == "national_park" || boundary == "protected_area") return true
        return tags["waterway"] in WATERLINE_VALUES
    }

    private val WATERLINE_VALUES = setOf("river", "stream", "canal", "riverbank")

    private inline fun forEachEntity(file: File, block: (EntityContainer) -> Unit) {
        BufferedInputStream(file.inputStream(), 1 shl 20).use { input ->
            val it: Iterator<EntityContainer> = PbfIterator(input, true)
            while (it.hasNext()) block(it.next())
        }
    }

    private inline fun tagsOf(count: Int, get: (Int) -> Pair<String, String>): Map<String, String> {
        if (count == 0) return emptyMap()
        val m = LinkedHashMap<String, String>(count * 2)
        for (i in 0 until count) { val (k, v) = get(i); m[k] = v }
        return m
    }
}
