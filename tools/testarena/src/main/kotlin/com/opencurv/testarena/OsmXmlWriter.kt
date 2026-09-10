package com.opencurv.testarena

import com.opencurv.testarena.geometry.Projection
import com.opencurv.testarena.model.OsmDocument
import java.io.File
import java.util.Locale

/**
 * Writes the arena as plain OSM XML (.osm). Deterministic: nodes and ways are
 * emitted in ascending id order, coordinates are formatted with a fixed
 * number of decimal digits using [Locale.ROOT], and there is no timestamp,
 * random uid or other non-reproducible field anywhere in the file - the same
 * [OsmDocument] always serialises to the exact same bytes.
 */
object OsmXmlWriter {
    private const val COORD_DECIMALS = 7

    fun write(doc: OsmDocument, file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("<?xml version='1.0' encoding='UTF-8'?>\n")
            w.write("<osm version=\"0.6\" generator=\"opencurv-testarena\">\n")

            val latLons = doc.nodes.map { Projection.toLatLon(it.point) }
            if (latLons.isNotEmpty()) {
                val minLat = latLons.minOf { it.lat }
                val maxLat = latLons.maxOf { it.lat }
                val minLon = latLons.minOf { it.lon }
                val maxLon = latLons.maxOf { it.lon }
                w.write(
                    "  <bounds minlat=\"${fmt(minLat)}\" minlon=\"${fmt(minLon)}\" " +
                        "maxlat=\"${fmt(maxLat)}\" maxlon=\"${fmt(maxLon)}\"/>\n",
                )
            }

            for (node in doc.nodes.sortedBy { it.id }) {
                val ll = Projection.toLatLon(node.point)
                if (node.tags.isEmpty()) {
                    w.write("  <node id=\"${node.id}\" lat=\"${fmt(ll.lat)}\" lon=\"${fmt(ll.lon)}\" version=\"1\"/>\n")
                } else {
                    w.write("  <node id=\"${node.id}\" lat=\"${fmt(ll.lat)}\" lon=\"${fmt(ll.lon)}\" version=\"1\">\n")
                    writeTags(w, node.tags, "    ")
                    w.write("  </node>\n")
                }
            }

            for (way in doc.ways.sortedBy { it.id }) {
                w.write("  <way id=\"${way.id}\" version=\"1\">\n")
                for (ref in way.nodeIds) {
                    w.write("    <nd ref=\"$ref\"/>\n")
                }
                writeTags(w, way.tags, "    ")
                w.write("  </way>\n")
            }

            w.write("</osm>\n")
        }
    }

    private fun writeTags(w: Appendable, tags: Map<String, String>, indent: String) {
        // Deterministic order: sorted by key, not insertion order.
        for (key in tags.keys.sorted()) {
            w.append(indent).append("<tag k=\"").append(escape(key)).append("\" v=\"")
                .append(escape(tags.getValue(key))).append("\"/>\n")
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.${COORD_DECIMALS}f", v)

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
