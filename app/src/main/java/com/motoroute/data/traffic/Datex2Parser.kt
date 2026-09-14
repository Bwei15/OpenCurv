package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads DATEX II traffic messages into [TrafficIncident]s.
 *
 * DATEX II is the EU-wide exchange format for road traffic data and what the
 * German national access point (Mobilithek, formerly MDM) publishes for
 * Bundes- and Landesstraßen - the roads the keyless Autobahn API
 * ([AutobahnTrafficSource]) knows nothing about.
 *
 * ## Why this is deliberately lenient
 *
 * DATEX II is a very large schema with several versions (2.0 through 3.x) and
 * every publisher uses a different subset: a Land's roadworks feed and a
 * closure feed from the same portal do not agree on where the coordinates sit
 * or what the record is called. Validating against the schema, or binding to
 * one version's element paths, would mean a feed that renders fine in a
 * browser silently produces zero incidents here.
 *
 * So this parser looks for what every profile does have, by **local element
 * name, ignoring namespaces**:
 *
 *  * every `situationRecord` (or, for feeds that skip it, every `situation`)
 *    is one incident;
 *  * its kind comes from the `xsi:type` attribute, which is how DATEX II
 *    expresses the record subclass (`RoadClosure`, `ConstructionWorks`,
 *    `Accident`, ...), with the management-type and impact elements as a
 *    fallback;
 *  * every `latitude`/`longitude` pair anywhere beneath the record is one
 *    coordinate, in document order - one pair is a point incident, several are
 *    a closed stretch, which is exactly the distinction
 *    [TrafficIncident.toNoGoAreas] needs;
 *  * validity times come from `overallStartTime`/`overallEndTime` when
 *    present, so [TrafficRepository.activeNoGoAreas] can drop a closure that
 *    has not started yet.
 *
 * Anything it cannot understand is skipped rather than thrown, for the same
 * reason one failing road does not abort the Autobahn refresh: a partial
 * result is worth much more to a rider than an exception.
 *
 * Android-free (`javax.xml` DOM is on both Android and the JVM), so it runs in
 * `tools/verifier` with the rest of the traffic layer.
 */
object Datex2Parser {

    /** True when [body] looks like XML rather than the GeoJSON some Mobilithek offers serve. */
    fun looksLikeXml(body: String): Boolean {
        val head = body.trimStart()
        return head.startsWith("<")
    }

    fun parse(xml: String, idPrefix: String = "datex2:"): List<TrafficIncident> {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // A traffic feed has no business pulling in external entities.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return emptyList()

        val records = document.documentElement?.let { collectByLocalName(it, "situationRecord") }.orEmpty()
        val elements = records.ifEmpty {
            document.documentElement?.let { collectByLocalName(it, "situation") }.orEmpty()
        }

        val incidents = ArrayList<TrafficIncident>(elements.size)
        for ((index, element) in elements.withIndex()) {
            runCatching { toIncident(element, idPrefix, index) }.getOrNull()?.let { incidents += it }
        }
        return incidents
    }

    // ---- one record -------------------------------------------------------

    private fun toIncident(record: Element, idPrefix: String, index: Int): TrafficIncident? {
        val points = coordinatesIn(record)
        if (points.isEmpty()) return null

        val rawType = record.getAttribute("xsi:type").ifBlank {
            record.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type")
        }
        val managementType = firstText(record, "roadOrCarriagewayOrLaneManagementType")
            ?: firstText(record, "networkManagementType")
        val comment = firstComment(record)
        val road = firstText(record, "roadNumber")
            ?: firstText(record, "roadName")
            ?: firstText(record, "administrativeAreaName")

        val classification = classify(rawType, managementType, comment, record)

        val id = record.getAttribute("id").ifBlank { record.getAttribute("version") }
            .ifBlank { "index$index" }

        return TrafficIncident(
            id = idPrefix + id,
            title = comment?.takeIf { it.isNotBlank() }?.let { shorten(it) }
                ?: humanise(rawType.ifBlank { managementType ?: "Verkehrsmeldung" }),
            description = comment.orEmpty(),
            type = classification.type,
            severity = classification.severity,
            location = points.midpoint(),
            radiusMeters = DEFAULT_RADIUS_M,
            polyline = points.takeIf { it.size >= 2 },
            startEpochMillis = epochMillis(firstText(record, "overallStartTime")),
            endEpochMillis = epochMillis(firstText(record, "overallEndTime")),
            roadName = road,
        )
    }

    private data class Classification(val type: IncidentType, val severity: IncidentSeverity)

    private fun classify(
        rawType: String,
        managementType: String?,
        comment: String?,
        record: Element,
    ): Classification {
        val haystack = buildString {
            append(rawType.lowercase())
            append(' ')
            append(managementType?.lowercase().orEmpty())
        }
        val text = comment?.lowercase().orEmpty()

        // "carriageway closed", "roadClosed", "Vollsperrung": the one case that
        // has to become a NoGo area for the router.
        val closed = haystack.contains("closed") ||
            haystack.contains("closure") ||
            text.contains("vollsperrung") ||
            text.contains("voll gesperrt") ||
            capacityRemaining(record) == 0.0

        val type = when {
            closed && (haystack.contains("winter") || text.contains("wintersperr")) -> IncidentType.PASS_CLOSURE
            closed -> IncidentType.ROAD_CLOSURE
            haystack.contains("construction") || haystack.contains("maintenance") ||
                haystack.contains("roadworks") -> IncidentType.CONSTRUCTION
            haystack.contains("accident") || haystack.contains("obstruction") -> IncidentType.ACCIDENT
            haystack.contains("weather") || haystack.contains("environmental") ||
                haystack.contains("poorenvironment") -> IncidentType.WEATHER_WARNING
            else -> IncidentType.HAZARD
        }

        val severity = when {
            closed -> IncidentSeverity.CRITICAL
            lanesRestricted(record) > 0 -> IncidentSeverity.WARNING
            type == IncidentType.ACCIDENT -> IncidentSeverity.WARNING
            type == IncidentType.CONSTRUCTION -> IncidentSeverity.INFO
            else -> IncidentSeverity.WARNING
        }
        return Classification(type, severity)
    }

    private fun capacityRemaining(record: Element): Double? =
        firstText(record, "capacityRemaining")?.toDoubleOrNull()

    private fun lanesRestricted(record: Element): Int =
        firstText(record, "numberOfLanesRestricted")?.toIntOrNull() ?: 0

    // ---- DOM helpers ------------------------------------------------------

    /**
     * Every coordinate beneath [record], in document order.
     *
     * DATEX II nests coordinates differently per location type (`Point`,
     * `Linear`, `Itinerary`), so rather than walking a fixed path this pairs up
     * `latitude` and `longitude` as they appear - which is what every profile
     * agrees on.
     */
    private fun coordinatesIn(record: Element): List<GeoPoint> {
        val latitudes = ArrayList<Double>()
        val longitudes = ArrayList<Double>()
        walk(record) { element ->
            when (localName(element)) {
                "latitude" -> element.textContent?.trim()?.toDoubleOrNull()?.let { latitudes += it }
                "longitude" -> element.textContent?.trim()?.toDoubleOrNull()?.let { longitudes += it }
            }
        }
        val count = minOf(latitudes.size, longitudes.size)
        val points = ArrayList<GeoPoint>(count)
        for (i in 0 until count) {
            val lat = latitudes[i]
            val lon = longitudes[i]
            // A feed with a swapped or empty coordinate is worse than no incident.
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)) {
                points += GeoPoint(lat, lon)
            }
        }
        return points
    }

    /** The rider-facing text: the first non-blank `<value>` under a comment element. */
    private fun firstComment(record: Element): String? {
        var found: String? = null
        walk(record) { element ->
            if (found == null && localName(element) == "value") {
                element.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { found = it }
            }
        }
        return found
    }

    private fun firstText(root: Element, localName: String): String? {
        var found: String? = null
        walk(root) { element ->
            if (found == null && localName(element) == localName) {
                element.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { found = it }
            }
        }
        return found
    }

    private fun collectByLocalName(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        walk(root) { element -> if (localName(element) == name) out += element }
        return out
    }

    private fun walk(root: Element, visit: (Element) -> Unit) {
        visit(root)
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) walk(child as Element, visit)
        }
    }

    private fun localName(element: Element): String =
        element.localName ?: element.tagName.substringAfterLast(':')

    private fun List<GeoPoint>.midpoint(): GeoPoint = this[size / 2]

    private fun epochMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun shorten(text: String): String =
        if (text.length <= MAX_TITLE_CHARS) text else text.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"

    /** "ConstructionWorks" -> "Construction works", for a record with no comment text. */
    private fun humanise(rawType: String): String {
        val bare = rawType.substringAfterLast(':')
        val spaced = bare.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private const val DEFAULT_RADIUS_M = 80
    private const val MAX_TITLE_CHARS = 80
}
