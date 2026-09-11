package com.motoroute.data.traffic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository managing traffic incidents (construction, full road closures, pass closures).
 *
 * Maintains offline-first behavior:
 * - Reads from cached local incident files.
 * - Converts impassable closures into [NoGoArea] avoidance points for BRouter routing.
 * - Exports GeoJSON for MapLibre Native rendering.
 *
 * A refresh (see [refreshFrom]) always replaces the whole incident list on
 * success and leaves it untouched on failure - offline-first means a rider
 * never loses avoidance data because one HTTP request timed out.
 */
class TrafficRepository(
    private val cacheFile: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _incidents = MutableStateFlow<List<TrafficIncident>>(emptyList())
    val incidents: StateFlow<List<TrafficIncident>> = _incidents.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)

    /**
     * When the current incident list was fetched from the network (epoch
     * millis) - or, before any refresh has happened this run, when the
     * on-disk cache was last written. Null if there is no data at all yet.
     * Meant for a UI "Stand 14:32" label (Welle 7); this repository does not
     * render anything itself.
     */
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    /** True while a network fetch is in flight. [incidents] and routing keep using the last known-good data throughout. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadFromCache()
    }

    /**
     * Replaces the currently active incidents in memory and persists them to cache if configured.
     */
    suspend fun updateIncidents(newIncidents: List<TrafficIncident>) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        _incidents.value = newIncidents
        _lastUpdated.value = now
        saveToCache(newIncidents, now)
    }

    /**
     * Parses and updates incidents from a GeoJSON string (e.g. from Mobilithek/MDM).
     */
    suspend fun updateFromGeoJson(geoJson: String) {
        val parsed = MobilithekTrafficParser.parseGeoJson(geoJson)
        updateIncidents(parsed)
    }

    /**
     * Fetches fresh incidents from [source] (e.g. [AutobahnTrafficSource])
     * and replaces the incident list wholesale on success. On failure -
     * offline, timeout, bad response - the existing incidents and cache are
     * left exactly as they were. Never touches an active route: it only
     * updates data that the next route calculation (or a manual reroute)
     * will read via [activeNoGoAreas].
     */
    suspend fun refreshFrom(source: TrafficSource): Result<Int> {
        _isRefreshing.value = true
        return try {
            val fresh = withContext(ioDispatcher) { source.fetch() }
            updateIncidents(fresh)
            Result.success(fresh.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Returns avoidance points for all currently active impassable closures and construction sites.
     */
    fun activeNoGoAreas(): List<NoGoArea> {
        val now = System.currentTimeMillis()
        return _incidents.value
            .filter { incident -> incident.isImpassable && isActive(incident, now) }
            .flatMap { it.toNoGoAreas() }
    }

    /**
     * Generates a GeoJSON FeatureCollection string of current incidents for
     * MapLibre Native. See [MobilithekTrafficParser]'s class doc for the
     * feature/property schema.
     */
    fun getIncidentsGeoJson(): String {
        return MobilithekTrafficParser.toDisplayGeoJson(_incidents.value, _lastUpdated.value)
    }

    private fun isActive(incident: TrafficIncident, now: Long): Boolean =
        (incident.startEpochMillis == null || incident.startEpochMillis <= now) &&
        (incident.endEpochMillis == null || incident.endEpochMillis >= now)

    private fun loadFromCache() {
        val file = cacheFile ?: return
        if (!file.isFile) return
        try {
            val content = file.readText(Charsets.UTF_8)
            _incidents.value = MobilithekTrafficParser.parseGeoJson(content)
            _lastUpdated.value = MobilithekTrafficParser.extractFetchedAt(content)
        } catch (_: Exception) {
            // Cache read failure is non-fatal; starts empty
        }
    }

    private fun saveToCache(incidentsToSave: List<TrafficIncident>, fetchedAtMillis: Long) {
        val file = cacheFile ?: return
        try {
            file.parentFile?.mkdirs()
            val geoJson = MobilithekTrafficParser.toGeoJson(incidentsToSave, fetchedAtMillis)
            file.writeText(geoJson, Charsets.UTF_8)
        } catch (_: Exception) {
            // Cache write failure is non-fatal
        }
    }
}
