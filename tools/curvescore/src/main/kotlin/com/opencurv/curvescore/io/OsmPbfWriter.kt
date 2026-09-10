package com.opencurv.curvescore.io

import com.opencurv.curvescore.model.OsmData
import com.slimjars.dist.gnu.trove.list.array.TLongArrayList
import de.topobyte.osm4j.core.model.impl.Node
import de.topobyte.osm4j.core.model.impl.Tag
import de.topobyte.osm4j.core.model.impl.Way
import de.topobyte.osm4j.pbf.seq.PbfWriter
import java.io.BufferedOutputStream
import java.io.File

/**
 * Writes an [OsmData] back out as .osm.pbf, using the same `osm4j` library the
 * reader uses. Only needed by the I/O benchmark (`bench-io`), which has to
 * produce a realistically sized file before it can measure how fast one is
 * read - the testarena's 3 KB PBF says nothing about Bavaria.
 */
object OsmPbfWriter {
    fun write(data: OsmData, file: File) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(file.outputStream(), 1 shl 20).use { out ->
            val w = PbfWriter(out, false)
            for (n in data.nodes.values.sortedBy { it.id }) {
                w.write(Node(n.id, n.lon, n.lat, n.tags.map { Tag(it.key, it.value) }))
            }
            for (way in data.ways.sortedBy { it.id }) {
                val refs = TLongArrayList(way.nodeIds.size)
                way.nodeIds.forEach { refs.add(it) }
                w.write(Way(way.id, refs, way.tags.map { Tag(it.key, it.value) }))
            }
            w.complete()
        }
    }
}
