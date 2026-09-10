package com.opencurv.testarena.harness

import com.google.gson.Gson
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One point of a scored route, as delivered by *some* routing engine. The harness never
 * learns which engine produced it or how - see README.md "Warum engine-neutral": it only
 * ever sees plain coordinates.
 */
data class RoutePoint(val lat: Double, val lon: Double)

/** A tiny, dependency-free point list: `[{"lat": 0.30, "lon": -1.00}, ...]`. */
private data class JsonPoint(val lat: Double, val lon: Double)

object RouteInput {
    fun read(file: File): List<RoutePoint> = when (file.extension.lowercase()) {
        "gpx" -> readGpx(file)
        "json" -> readJsonPoints(file)
        else -> error(
            "Unbekanntes Routen-Dateiformat '${file.extension}' (erwartet .gpx oder .json): ${file.path}",
        )
    }

    fun readGpx(file: File): List<RoutePoint> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(file)
        val points = mutableListOf<RoutePoint>()
        // Accept both <trkpt> (track) and <rtept> (route) points - either is a normal way to
        // hand a computed route to this harness.
        for (tag in listOf("trkpt", "rtept")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as org.w3c.dom.Element
                val lat = el.getAttribute("lat").toDouble()
                val lon = el.getAttribute("lon").toDouble()
                points += RoutePoint(lat, lon)
            }
        }
        require(points.isNotEmpty()) { "GPX-Datei enthält keine <trkpt>/<rtept>-Punkte: ${file.path}" }
        return points
    }

    fun readJsonPoints(file: File): List<RoutePoint> {
        val gson = Gson()
        val raw = file.reader(Charsets.UTF_8).use { gson.fromJson(it, Array<JsonPoint>::class.java) }
        require(raw != null && raw.isNotEmpty()) { "JSON-Punktliste ist leer: ${file.path}" }
        return raw.map { RoutePoint(it.lat, it.lon) }
    }
}
