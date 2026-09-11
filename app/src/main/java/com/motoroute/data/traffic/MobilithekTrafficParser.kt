package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for traffic incident datasets provided by Mobilithek (BMDV Open Data),
 * MDM (Mobilitäts Daten Marktplatz / DATEX II GeoJSON export) and regional traffic centers.
 *
 * GeoJSON schema written by [toGeoJson] / [toDisplayGeoJson] (consumed by the
 * map renderer, Welle 7): a `FeatureCollection` whose root carries an
 * optional `fetchedAt` (epoch millis of the last successful refresh, for a
 * "Stand HH:MM" label) alongside `features`. Each feature's `properties`
 * always has `id`, `title`, `type` ([IncidentType] name), `severity`
 * ([IncidentSeverity] name), `impassable` (bool - true means BRouter treats
 * it as a nogo), and `road` (the motorway code, when known). A point
 * incident is one `Point` feature. A line incident (a closed stretch) is a
 * `LineString` feature carrying the full geometry, **plus** a companion
 * `Point` feature at the line's midpoint (`properties.role == "icon"`) so
 * the renderer has a single spot to anchor a barrier icon on, in addition to
 * drawing the line itself in red. [toGeoJson] omits that companion feature -
 * it is the round-trippable form used for the on-disk cache, and adding a
 * second feature per line incident would double-parse into two incidents on
 * the next [parseGeoJson] (the reload path in [TrafficRepository]).
 */
object MobilithekTrafficParser {

    /**
     * Parses a GeoJSON FeatureCollection string into a list of [TrafficIncident] items.
     */
    fun parseGeoJson(jsonString: String): List<TrafficIncident> {
        if (jsonString.isBlank()) return emptyList()

        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return emptyList()
        }

        val features = root.optJSONArray("features") ?: return emptyList()
        val incidents = ArrayList<TrafficIncident>(features.length())

        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val incident = parseFeature(feat)
            if (incident != null) {
                incidents.add(incident)
            }
        }

        return incidents
    }

    private fun parseFeature(feature: JSONObject): TrafficIncident? {
        val geometry = feature.optJSONObject("geometry") ?: return null
        val properties = feature.optJSONObject("properties") ?: JSONObject()

        // Defensive: toDisplayGeoJson()'s companion icon-anchor point (see
        // class doc) is never meant to round-trip back into an incident.
        if (properties.optString("role") == "icon") return null

        val geomType = geometry.optString("type", "")
        val coordinates = geometry.optJSONArray("coordinates") ?: return null

        val (location, polyline) = when (geomType.lowercase()) {
            "point" -> {
                val pt = parseCoordPoint(coordinates) ?: return null
                pt to null
            }
            "linestring" -> {
                val pts = parseCoordList(coordinates)
                if (pts.isEmpty()) return null
                pts.first() to pts
            }
            "polygon" -> {
                // First linear ring
                val ring = coordinates.optJSONArray(0) ?: return null
                val pts = parseCoordList(ring)
                if (pts.isEmpty()) return null
                pts.first() to pts
            }
            else -> return null
        }

        val id = properties.optString("id", feature.optString("id", "incident_${location.latitude}_${location.longitude}"))
        val title = properties.optString("title", properties.optString("name", "Verkehrsmeldung"))
        val description = properties.optString("description", properties.optString("text", ""))
        val roadName = properties.optString("road", "").ifBlank {
            properties.optString("roadName", "")
        }.takeIf { it.isNotBlank() }

        val typeStr = properties.optString("type", properties.optString("incidentType", "")).lowercase()
        val severityStr = properties.optString("severity", properties.optString("impact", "")).lowercase()

        val type = when {
            typeStr.contains("pass") -> IncidentType.PASS_CLOSURE
            typeStr.contains("closure") || typeStr.contains("sperrung") || typeStr.contains("closed") -> IncidentType.ROAD_CLOSURE
            typeStr.contains("work") || typeStr.contains("baustelle") || typeStr.contains("construction") -> IncidentType.CONSTRUCTION
            typeStr.contains("accident") || typeStr.contains("unfall") -> IncidentType.ACCIDENT
            typeStr.contains("weather") || typeStr.contains("wetter") || typeStr.contains("snow") -> IncidentType.WEATHER_WARNING
            else -> IncidentType.HAZARD
        }

        val severity = when {
            severityStr.contains("critical") || severityStr.contains("block") || severityStr.contains("voll") || type == IncidentType.ROAD_CLOSURE -> IncidentSeverity.CRITICAL
            severityStr.contains("warning") || severityStr.contains("major") || severityStr.contains("delay") -> IncidentSeverity.WARNING
            else -> IncidentSeverity.INFO
        }

        val radius = properties.optInt("radiusMeters", properties.optInt("radius", 50))
        val startMillis = properties.optLong("startEpochMillis", 0L).takeIf { it > 0 }
        val endMillis = properties.optLong("endEpochMillis", 0L).takeIf { it > 0 }

        return TrafficIncident(
            id = id,
            title = title,
            description = description,
            type = type,
            severity = severity,
            location = location,
            radiusMeters = radius,
            polyline = polyline,
            startEpochMillis = startMillis,
            endEpochMillis = endMillis,
            roadName = roadName,
        )
    }

    private fun parseCoordPoint(coords: JSONArray): GeoPoint? {
        if (coords.length() < 2) return null
        val lon = coords.optDouble(0, Double.NaN)
        val lat = coords.optDouble(1, Double.NaN)
        if (lon.isNaN() || lat.isNaN()) return null
        return GeoPoint(latitude = lat, longitude = lon)
    }

    private fun parseCoordList(array: JSONArray): List<GeoPoint> {
        val list = ArrayList<GeoPoint>(array.length())
        for (i in 0 until array.length()) {
            val ptArray = array.optJSONArray(i) ?: continue
            val pt = parseCoordPoint(ptArray) ?: continue
            list.add(pt)
        }
        return list
    }

    /**
     * Serializes a list of [TrafficIncident] items into a GeoJSON
     * FeatureCollection: one feature per incident, round-trippable through
     * [parseGeoJson]. Used to persist the on-disk cache - see the class doc
     * for why this must stay 1:1 with the incident list, unlike
     * [toDisplayGeoJson].
     */
    fun toGeoJson(incidents: List<TrafficIncident>, fetchedAtMillis: Long? = null): String {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        fetchedAtMillis?.let { root.put("fetchedAt", it) }

        val features = JSONArray()
        for (inc in incidents) {
            features.put(incidentFeature(inc))
        }
        root.put("features", features)
        return root.toString()
    }

    /**
     * Serializes incidents for the map renderer: same as [toGeoJson], but a
     * line incident additionally gets a `Point` feature at its midpoint
     * (`properties.role == "icon"`) to anchor a barrier icon on, per the
     * class doc's schema. Not meant to be fed back into [parseGeoJson] as
     * the source of truth (it is defensively filtered out if it is).
     */
    fun toDisplayGeoJson(incidents: List<TrafficIncident>, fetchedAtMillis: Long? = null): String {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        fetchedAtMillis?.let { root.put("fetchedAt", it) }

        val features = JSONArray()
        for (inc in incidents) {
            features.put(incidentFeature(inc))
            val line = inc.polyline
            if (line != null && line.size > 1) {
                features.put(incidentFeature(inc, geometryOverride = midpoint(line), idSuffix = ":icon", role = "icon"))
            }
        }
        root.put("features", features)
        return root.toString()
    }

    /** Reads back the `fetchedAt` a [toGeoJson]/[toDisplayGeoJson] root carried, or null if absent/unparsable. */
    fun extractFetchedAt(jsonString: String): Long? {
        if (jsonString.isBlank()) return null
        return try {
            JSONObject(jsonString).optLong("fetchedAt", -1L).takeIf { it >= 0L }
        } catch (e: Exception) {
            null
        }
    }

    private fun incidentFeature(
        inc: TrafficIncident,
        geometryOverride: GeoPoint? = null,
        idSuffix: String = "",
        role: String? = null,
    ): JSONObject {
        val feat = JSONObject()
        feat.put("type", "Feature")
        feat.put("id", inc.id + idSuffix)

        val geom = JSONObject()
        val line = inc.polyline
        if (geometryOverride == null && line != null && line.size > 1) {
            geom.put("type", "LineString")
            val coords = JSONArray()
            for (pt in line) {
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
            }
            geom.put("coordinates", coords)
        } else {
            val point = geometryOverride ?: inc.location
            geom.put("type", "Point")
            geom.put("coordinates", JSONArray().put(point.longitude).put(point.latitude))
        }
        feat.put("geometry", geom)

        val props = JSONObject()
        props.put("id", inc.id)
        props.put("title", inc.title)
        props.put("description", inc.description)
        props.put("type", inc.type.name)
        props.put("severity", inc.severity.name)
        props.put("impassable", inc.isImpassable)
        props.put("radiusMeters", inc.radiusMeters)
        inc.roadName?.let { props.put("road", it) }
        role?.let { props.put("role", it) }
        feat.put("properties", props)

        return feat
    }

    /** Point at (approximately) half the polyline's length, for anchoring an icon on a line incident. */
    private fun midpoint(points: List<GeoPoint>): GeoPoint {
        var total = 0.0
        val segments = DoubleArray(points.size - 1) { i ->
            Geo.distanceMeters(points[i], points[i + 1]).also { total += it }
        }
        if (total <= 0.0) return points[points.size / 2]

        var remaining = total / 2.0
        for (i in segments.indices) {
            if (remaining <= segments[i]) {
                val t = if (segments[i] > 0) remaining / segments[i] else 0.0
                val a = points[i]
                val b = points[i + 1]
                return GeoPoint(
                    latitude = a.latitude + (b.latitude - a.latitude) * t,
                    longitude = a.longitude + (b.longitude - a.longitude) * t,
                )
            }
            remaining -= segments[i]
        }
        return points.last()
    }
}
