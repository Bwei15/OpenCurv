package com.opencurv.testarena

import com.opencurv.testarena.geometry.Projection
import com.opencurv.testarena.model.OsmDocument
import com.slimjars.dist.gnu.trove.list.array.TLongArrayList
import de.topobyte.osm4j.core.model.impl.Node
import de.topobyte.osm4j.core.model.impl.Tag
import de.topobyte.osm4j.core.model.impl.Way
import de.topobyte.osm4j.pbf.seq.PbfWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes the arena as .osm.pbf, using the `osm4j-pbf` library (LGPL-3, see
 * README.md "PBF-Unterstützung") instead of hand-rolling a protobuf/zlib
 * encoder. Node and way order is the same ascending-id order used by
 * [OsmXmlWriter], and no metadata (user/timestamp/version/changeset) is
 * written, so the output is deterministic byte-for-byte across runs.
 */
object OsmPbfWriter {
    fun write(doc: OsmDocument, file: File) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            val writer = PbfWriter(out, /* writeMetadata = */ false)
            for (node in doc.nodes.sortedBy { it.id }) {
                val ll = Projection.toLatLon(node.point)
                val tags = node.tags.keys.sorted().map { Tag(it, node.tags.getValue(it)) }
                writer.write(Node(node.id, ll.lon, ll.lat, tags))
            }
            for (way in doc.ways.sortedBy { it.id }) {
                val refs = TLongArrayList(way.nodeIds.size)
                way.nodeIds.forEach { refs.add(it) }
                val tags = way.tags.keys.sorted().map { Tag(it, way.tags.getValue(it)) }
                writer.write(Way(way.id, refs, tags))
            }
            writer.complete()
        }
    }
}
