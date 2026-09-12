package com.motoroute.data.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A destination the rider has routed to before. */
data class HistoryDestination(
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
)

/** One stop of a remembered tour, start and destination included, in ride order. */
data class HistoryStop(
    val name: String?,
    val latitude: Double,
    val longitude: Double,
)

/** A tour the rider planned or actually rode, stops in order plus the settings it used. */
data class HistoryTrip(
    val stops: List<HistoryStop>,
    val profileId: String,
    val curviness: Float,
    val roundTrip: Boolean,
    val timestampMillis: Long,
)

/**
 * Where the rider has been and what they rode there with, kept as one small JSON file.
 *
 * Android-free like [com.motoroute.data.traffic.TrafficRepository]'s cache - plain `java.io.File`
 * and `org.json`, nothing Android needs to be faked for a unit test to save, load and check the
 * caps. Both lists are capped at [MAX_ENTRIES]: this backs the search box's "last destinations"
 * and the plan sheet's "last tours", a convenience, not a ride log, so it must never grow without
 * bound.
 */
class RouteHistory(private val file: File) {

    private var destinations: List<HistoryDestination> = emptyList()
    private var trips: List<HistoryTrip> = emptyList()

    init {
        load()
    }

    fun recentDestinations(): List<HistoryDestination> = destinations

    fun recentTrips(): List<HistoryTrip> = trips

    /**
     * Records a destination that was just routed to.
     *
     * Merges with any existing entry at (roughly) the same coordinate instead of piling up a new
     * row every time the rider recalculates the same ride, and always moves the result to the
     * front - newest first.
     */
    fun recordDestination(name: String?, latitude: Double, longitude: Double) {
        val key = coordKey(latitude, longitude)
        val fresh = HistoryDestination(name, latitude, longitude, System.currentTimeMillis())
        destinations = (listOf(fresh) + destinations.filterNot { coordKey(it.latitude, it.longitude) == key })
            .take(MAX_ENTRIES)
        save()
    }

    /** Records a tour at the moment navigation - or a demo ride - actually starts. */
    fun recordTrip(stops: List<HistoryStop>, profileId: String, curviness: Float, roundTrip: Boolean) {
        if (stops.size < 2) return
        val fresh = HistoryTrip(stops, profileId, curviness, roundTrip, System.currentTimeMillis())
        trips = (listOf(fresh) + trips).take(MAX_ENTRIES)
        save()
    }

    /** Rounded to four decimal degrees (~11 m) - close enough to call two picks "the same place". */
    private fun coordKey(latitude: Double, longitude: Double): Long =
        Math.round(latitude * 10_000.0) * 2_000_000L + Math.round(longitude * 10_000.0)

    private fun load() {
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            destinations = root.optJSONArray("destinations")?.let(::parseDestinations).orEmpty()
            trips = root.optJSONArray("trips")?.let(::parseTrips).orEmpty()
        } catch (_: Exception) {
            // Corrupt or missing cache is non-fatal; starts empty, same as TrafficRepository.
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            root.put("destinations", destinationsToJson(destinations))
            root.put("trips", tripsToJson(trips))
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Best-effort persistence; the in-memory lists stay correct either way.
        }
    }

    private fun parseDestinations(array: JSONArray): List<HistoryDestination> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryDestination(
                name = o.optString("name", null).takeUnless { it.isNullOrEmpty() },
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
                timestampMillis = o.optLong("ts"),
            )
        }

    private fun parseTrips(array: JSONArray): List<HistoryTrip> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryTrip(
                stops = parseStops(o.getJSONArray("stops")),
                profileId = o.optString("profile", ""),
                curviness = o.optDouble("curviness", 1.0).toFloat(),
                roundTrip = o.optBoolean("roundTrip", false),
                timestampMillis = o.optLong("ts"),
            )
        }

    private fun parseStops(array: JSONArray): List<HistoryStop> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryStop(
                name = o.optString("name", null).takeUnless { it.isNullOrEmpty() },
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
            )
        }

    private fun destinationsToJson(list: List<HistoryDestination>): JSONArray = JSONArray().apply {
        list.forEach { d ->
            put(
                JSONObject().apply {
                    put("name", d.name ?: JSONObject.NULL)
                    put("lat", d.latitude)
                    put("lon", d.longitude)
                    put("ts", d.timestampMillis)
                },
            )
        }
    }

    private fun tripsToJson(list: List<HistoryTrip>): JSONArray = JSONArray().apply {
        list.forEach { t ->
            put(
                JSONObject().apply {
                    put("stops", stopsToJson(t.stops))
                    put("profile", t.profileId)
                    put("curviness", t.curviness.toDouble())
                    put("roundTrip", t.roundTrip)
                    put("ts", t.timestampMillis)
                },
            )
        }
    }

    private fun stopsToJson(list: List<HistoryStop>): JSONArray = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject().apply {
                    put("name", s.name ?: JSONObject.NULL)
                    put("lat", s.latitude)
                    put("lon", s.longitude)
                },
            )
        }
    }

    companion object {
        const val MAX_ENTRIES = 30
    }
}
