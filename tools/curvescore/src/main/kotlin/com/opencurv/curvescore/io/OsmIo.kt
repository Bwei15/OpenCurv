package com.opencurv.curvescore.io

import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmNode
import com.opencurv.curvescore.model.OsmWay
import java.io.BufferedInputStream
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Reads .osm (XML) and .osm.pbf into the in-memory model. */
object OsmReader {

    fun read(file: File): OsmData =
        if (file.name.endsWith(".pbf")) OsmPbfReader.read(file) else readXml(file)

    fun readXml(file: File): OsmData {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }
        val nodes = HashMap<Long, OsmNode>()
        val ways = ArrayList<OsmWay>()
        BufferedInputStream(file.inputStream(), 1 shl 20).use { input ->
            val r = factory.createXMLStreamReader(input)
            var curId = 0L
            var curLat = 0.0
            var curLon = 0.0
            var inNode = false
            var inWay = false
            var tags = LinkedHashMap<String, String>()
            var refs = ArrayList<Long>()
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "node" -> {
                            inNode = true; tags = LinkedHashMap()
                            curId = r.getAttributeValue(null, "id").toLong()
                            curLat = r.getAttributeValue(null, "lat").toDouble()
                            curLon = r.getAttributeValue(null, "lon").toDouble()
                        }
                        "way" -> {
                            inWay = true; tags = LinkedHashMap(); refs = ArrayList()
                            curId = r.getAttributeValue(null, "id").toLong()
                        }
                        "nd" -> if (inWay) refs.add(r.getAttributeValue(null, "ref").toLong())
                        "tag" -> if (inNode || inWay) {
                            tags[r.getAttributeValue(null, "k")] = r.getAttributeValue(null, "v")
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "node" -> {
                            if (inNode) {
                                nodes[curId] = OsmNode(curId, curLat, curLon, tags["ele"]?.toDoubleOrNull(), tags)
                            }
                            inNode = false
                        }
                        "way" -> {
                            if (inWay) ways.add(OsmWay(curId, refs.toLongArray(), tags))
                            inWay = false
                        }
                    }
                }
            }
            r.close()
        }
        return OsmData(nodes, ways)
    }
}

/**
 * Writes an .osm XML file, echoing the input and injecting the score tag.
 *
 * The writer is deliberately a straight echo rather than a re-serialisation of
 * the parsed model: everything the pipeline downstream might care about
 * (version, timestamps, unknown attributes) survives untouched, and only the
 * requested tags are added. Ways that already carry the score tag get it
 * replaced, so re-running the pipeline is idempotent.
 */
object OsmXmlTagWriter {

    fun write(
        input: File,
        output: File,
        scoreByWayId: Map<Long, WayTagValues>,
        tagName: String,
        rawTagName: String?,
        confTagName: String?,
    ) {
        val text = input.readText(Charsets.UTF_8)
        val out = StringBuilder(text.length + scoreByWayId.size * 64)
        var i = 0
        val stripKeys = listOfNotNull(tagName, rawTagName, confTagName).toSet()
        while (i < text.length) {
            val wayStart = text.indexOf("<way ", i)
            if (wayStart < 0) { out.append(text, i, text.length); break }
            out.append(text, i, wayStart)
            // Self-closing way (no nodes/tags) or a full <way> ... </way> block.
            val selfClose = findSelfClosingEnd(text, wayStart)
            val blockEnd: Int
            val body: String
            if (selfClose >= 0) {
                blockEnd = selfClose
                body = text.substring(wayStart, blockEnd)
            } else {
                val close = text.indexOf("</way>", wayStart)
                blockEnd = if (close < 0) text.length else close + "</way>".length
                body = text.substring(wayStart, blockEnd)
            }
            val id = extractAttr(body, "id")?.toLongOrNull()
            val values = if (id != null) scoreByWayId[id] else null
            out.append(renderWay(body, values, tagName, rawTagName, confTagName, stripKeys))
            i = blockEnd
        }
        output.parentFile?.mkdirs()
        output.writeText(out.toString(), Charsets.UTF_8)
    }

    private fun findSelfClosingEnd(text: String, from: Int): Int {
        val gt = text.indexOf('>', from)
        if (gt < 0) return -1
        return if (text[gt - 1] == '/') gt + 1 else -1
    }

    private fun extractAttr(block: String, name: String): String? {
        val key = "$name=\""
        val at = block.indexOf(key)
        if (at < 0) return null
        val end = block.indexOf('"', at + key.length)
        if (end < 0) return null
        return block.substring(at + key.length, end)
    }

    private fun renderWay(
        body: String,
        values: WayTagValues?,
        tagName: String,
        rawTagName: String?,
        confTagName: String?,
        stripKeys: Set<String>,
    ): String {
        // Remove any pre-existing occurrence of the tags we own, so a second run
        // over an already-tagged file replaces rather than duplicates them.
        var b = body
        for (k in stripKeys) {
            val re = Regex("""\s*<tag k="${Regex.escape(k)}" v="[^"]*"\s*/>""")
            b = re.replace(b, "")
        }
        if (values == null) return b
        val added = StringBuilder()
        added.append("\n    <tag k=\"").append(esc(tagName)).append("\" v=\"").append(values.level).append("\"/>")
        if (rawTagName != null) {
            added.append("\n    <tag k=\"").append(esc(rawTagName)).append("\" v=\"")
                .append(String.format(java.util.Locale.ROOT, "%.4f", values.raw)).append("\"/>")
        }
        if (confTagName != null) {
            added.append("\n    <tag k=\"").append(esc(confTagName)).append("\" v=\"")
                .append(String.format(java.util.Locale.ROOT, "%.2f", values.confidence)).append("\"/>")
        }
        val closeAt = b.lastIndexOf("</way>")
        return if (closeAt >= 0) {
            b.substring(0, closeAt).trimEnd() + added.toString() + "\n  " + b.substring(closeAt)
        } else {
            // Self-closing way: expand it so tags can be attached.
            val gt = b.lastIndexOf("/>")
            b.substring(0, gt) + ">" + added.toString() + "\n  </way>"
        }
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** What gets written back per way. */
data class WayTagValues(val level: Int, val raw: Double, val confidence: Double)
