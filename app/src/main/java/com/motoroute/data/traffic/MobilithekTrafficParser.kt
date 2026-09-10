package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for traffic incident datasets provided by Mobilithek (BMDV Open Data),
 * MDM (Mobilitäts Daten Marktplatz / DATEX II GeoJSON export) and regional traffic centers.
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
     * Serializes a list of [TrafficIncident] items into GeoJSON FeatureCollection
     * for rendering in MapLibre Native.
     */
    fun toGeoJson(incidents: List<TrafficIncident>): String {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        val features = JSONArray()

        for (inc in incidents) {
            val feat = JSONObject()
            feat.put("type", "Feature")
            feat.put("id", inc.id)

            val geom = JSONObject()
            if (inc.polyline != null && inc.polyline.size > 1) {
                geom.put("type", "LineString")
                val coords = JSONArray()
                for (pt in inc.polyline) {
                    val c = JSONArray()
                    c.put(pt.longitude)
                    c.put(pt.latitude)
                    coords.put(c)
                }
                geom.put("coordinates", coords)
            } else {
                geom.put("type", "Point")
                val coords = JSONArray()
                coords.put(inc.location.longitude)
                coords.put(inc.location.latitude)
                geom.put("coordinates", coords)
            }
            feat.put("geometry", geom)

            val props = JSONObject()
            props.put("id", inc.id)
            props.put("title", inc.title)
            props.put("description", inc.description)
            props.put("type", inc.type.name)
            props.put("severity", inc.severity.name)
            props.put("isImpassable", inc.isImpassable)
            props.put("radiusMeters", inc.radiusMeters)
            inc.roadName?.let { props.put("road", it) }

            feat.put("properties", props)
            features.put(feat)
        }

        root.put("features", features)
        return root.toString()
    }
}
