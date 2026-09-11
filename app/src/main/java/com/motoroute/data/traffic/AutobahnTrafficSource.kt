package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Traffic feed from the BMDV/Autobahn GmbH open API (verkehr.autobahn.de).
 *
 * Free, keyless, motorway-only (see [TrafficSource]). The API has no single
 * "all incidents" endpoint: it lists ~110 roads, and each road has three
 * services (`closure`, `roadworks`, `warning`) that must be fetched
 * separately - roughly 330 requests per refresh. Those run with bounded
 * concurrency ([maxConcurrency] at a time) so the server and the phone's
 * radio are not hit with everything at once, and one road's failure (a
 * timeout, a transient 5xx, a 404 for a retired road code) only drops that
 * road's incidents - it never aborts the refresh for the other ~109.
 *
 * Deliberately Android-free: it only uses `java.net` and `org.json`, so it
 * compiles and is testable in `tools/verifier` like the rest of the routing
 * core. [TrafficUpdater] is the Android-side piece that decides *when* to
 * call [fetch] (connectivity, cache age, app lifecycle).
 */
class AutobahnTrafficSource(
    private val baseUrl: String = "https://verkehr.autobahn.de/o/autobahn",
    private val maxConcurrency: Int = 6,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests: `(url) -> raw response body`, throws on failure. */
    private val httpGet: (String) -> String = { url -> defaultHttpGet(url, connectTimeoutMs, readTimeoutMs) },
) : TrafficSource {

    override suspend fun fetch(): List<TrafficIncident> = withContext(dispatcher) {
        val roads = fetchRoads()
        if (roads.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(maxConcurrency)
        coroutineScope {
            roads.flatMap { road -> SERVICES.map { service -> road to service } }
                .map { (road, service) ->
                    async { semaphore.withPermit { fetchService(road, service) } }
                }
                .awaitAll()
                .flatten()
        }
    }

    private fun fetchRoads(): List<String> {
        val root = JSONObject(httpGet("$baseUrl/"))
        val arr = root.optJSONArray("roads") ?: return emptyList()
        val roads = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            // The API occasionally lists a road with a trailing space
            // (seen for "A60 "); trimming here also doubles as the URL
            // encoding, since every road code is otherwise plain ASCII.
            val road = arr.optString(i, "").trim()
            if (road.isNotEmpty()) roads.add(road)
        }
        return roads
    }

    private fun fetchService(road: String, service: String): List<TrafficIncident> {
        return try {
            val body = httpGet("$baseUrl/$road/services/$service")
            parseService(road, service, body)
        } catch (e: Exception) {
            // Network hiccup, 404 for a road with nothing to report this
            // instant, malformed body - none of it may take the other
            // 329 requests down with it.
            emptyList()
        }
    }

    private fun parseService(road: String, service: String, body: String): List<TrafficIncident> {
        val root = JSONObject(body)
        val arr = root.optJSONArray(service) ?: return emptyList()
        val result = ArrayList<TrafficIncident>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            try {
                parseItem(road, service, obj)?.let(result::add)
            } catch (e: Exception) {
                // One malformed entry must not drop the rest of this road's list.
            }
        }
        return result
    }

    private fun parseItem(road: String, service: String, obj: JSONObject): TrafficIncident? {
        val location = parseLocation(obj) ?: return null
        val polyline = parsePolyline(obj)

        val symbols = obj.optJSONObject("impact")?.optJSONArray("symbols")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx, null) }
        }.orEmpty()
        val isBlocked = obj.optString("isBlocked", "false") == "true"
        val (type, severity) = classify(service, isBlocked, symbols)

        // Only "future" entries carry a start time the incident has not
        // reached yet; an entry that is already active should not be
        // artificially delayed by a startTimestamp that is already in the past.
        val future = obj.optBoolean("future", false)
        val startEpochMillis = if (future) parseTimestamp(obj.optString("startTimestamp", "")) else null

        val description = obj.optJSONArray("description")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx, null) }
        }.orEmpty().joinToString("\n")

        val title = obj.optString("title").ifBlank {
            obj.optString("subtitle").ifBlank { "Verkehrsmeldung $road" }
        }

        val identifier = obj.optString("identifier").ifBlank {
            "$road-$service-${location.latitude}-${location.longitude}"
        }

        return TrafficIncident(
            id = "autobahn:$identifier",
            title = title,
            description = description,
            type = type,
            severity = severity,
            location = location,
            radiusMeters = radiusFromExtent(obj.optString("extent", "")),
            polyline = polyline,
            startEpochMillis = startEpochMillis,
            // The API never gives a structured end time (only free-text in
            // "description"), so the incident stays active until the next
            // successful refresh replaces the whole list - see TrafficRepository.
            endEpochMillis = null,
            roadName = road,
        )
    }

    /**
     * Decides whether an item is a full closure (impassable) or a lesser
     * restriction (lane closure, construction, slow traffic).
     *
     * `isBlocked == "true"` is the API's own explicit signal and always
     * wins. Otherwise, a `CLOSURE`/`CLOSURE_ENTRY_EXIT` entry whose lane
     * symbols are all "CLOSED"/"BORDER_*" with no `ARROW_*` (which means
     * traffic is merged onto a remaining lane, i.e. still passable) is
     * treated as a full block too - that is the "Sperr-Symbol" case.
     */
    private fun classify(service: String, isBlocked: Boolean, symbols: List<String>): Pair<IncidentType, IncidentSeverity> {
        val fullyBlocked = isBlocked ||
            (symbols.isNotEmpty() && symbols.any { it == "CLOSED" } && symbols.none { it.startsWith("ARROW") })
        if (fullyBlocked) return IncidentType.ROAD_CLOSURE to IncidentSeverity.CRITICAL

        return when (service) {
            "warning" -> IncidentType.HAZARD to IncidentSeverity.WARNING
            "roadworks" -> IncidentType.CONSTRUCTION to
                (if (symbols.isNotEmpty()) IncidentSeverity.WARNING else IncidentSeverity.INFO)
            // "closure" entries that are not a full block are lane
            // restrictions around construction, not a road closure.
            else -> IncidentType.CONSTRUCTION to IncidentSeverity.WARNING
        }
    }

    private fun parseLocation(obj: JSONObject): GeoPoint? {
        obj.optJSONObject("coordinate")?.let { c ->
            val lat = c.optDouble("lat", Double.NaN)
            val lon = c.optDouble("long", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) return GeoPoint(latitude = lat, longitude = lon)
        }
        val pointStr = obj.optString("point", "")
        if (pointStr.isNotBlank()) {
            val parts = pointStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size == 2) return GeoPoint(latitude = parts[0], longitude = parts[1])
        }
        return null
    }

    private fun parsePolyline(obj: JSONObject): List<GeoPoint>? {
        val geometry = obj.optJSONObject("geometry") ?: return null
        if (!geometry.optString("type").equals("LineString", ignoreCase = true)) return null
        val coords = geometry.optJSONArray("coordinates") ?: return null
        val points = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val pair = coords.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (lon.isNaN() || lat.isNaN()) continue
            points.add(GeoPoint(latitude = lat, longitude = lon))
        }
        return points.takeIf { it.size >= 2 }
    }

    /** Half the diagonal of `extent` ("lat1,lon1,lat2,lon2"), clamped - a 3 km motorway closure needs a bigger circle than the 50 m default. */
    private fun radiusFromExtent(extent: String): Int {
        if (extent.isBlank()) return DEFAULT_RADIUS_M
        val parts = extent.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 4) return DEFAULT_RADIUS_M
        val meters = Geo.distanceMeters(parts[0], parts[1], parts[2], parts[3])
        if (meters <= 0.0 || meters.isNaN()) return DEFAULT_RADIUS_M
        return (meters / 2.0).toInt().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    }

    private fun parseTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        return try {
            // The API mixes "...Z" (warnings) and "...+02:00" (closures/roadworks);
            // OffsetDateTime parses both.
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val SERVICES = listOf("closure", "roadworks", "warning")
        const val DEFAULT_RADIUS_M = 50
        const val MIN_RADIUS_M = 60
        const val MAX_RADIUS_M = 4000
    }
}

private fun defaultHttpGet(urlString: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
    val connection = URL(urlString).openConnection() as HttpURLConnection
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = readTimeoutMs
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "OpenCurv/1.0 (+https://github.com/Bwei15/OpenCurv)")
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IOException("HTTP $code for $urlString")
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
