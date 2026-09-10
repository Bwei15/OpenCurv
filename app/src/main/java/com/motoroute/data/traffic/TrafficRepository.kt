package com.motoroute.data.traffic

import com.motoroute.data.model.GeoPoint
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
 */
class TrafficRepository(
    private val cacheFile: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _incidents = MutableStateFlow<List<TrafficIncident>>(emptyList())
    val incidents: StateFlow<List<TrafficIncident>> = _incidents.asStateFlow()

    init {
        loadFromCache()
    }

    /**
     * Replaces the currently active incidents in memory and persists them to cache if configured.
     */
    suspend fun updateIncidents(newIncidents: List<TrafficIncident>) = withContext(ioDispatcher) {
        _incidents.value = newIncidents
        saveToCache(newIncidents)
    }

    /**
     * Parses and updates incidents from a GeoJSON string (e.g. from Mobilithek/MDM).
     */
    suspend fun updateFromGeoJson(geoJson: String) {
        val parsed = MobilithekTrafficParser.parseGeoJson(geoJson)
        updateIncidents(parsed)
    }

    /**
     * Returns avoidance points for all currently active impassable closures and construction sites.
     */
    fun activeNoGoAreas(): List<NoGoArea> {
        val now = System.currentTimeMillis()
        return _incidents.value
            .filter { incident ->
                incident.isImpassable &&
                (incident.startEpochMillis == null || incident.startEpochMillis <= now) &&
                (incident.endEpochMillis == null || incident.endEpochMillis >= now)
            }
            .map { it.toNoGoArea() }
    }

    /**
     * Generates a GeoJSON FeatureCollection string of current incidents for MapLibre Native.
     */
    fun getIncidentsGeoJson(): String {
        return MobilithekTrafficParser.toGeoJson(_incidents.value)
    }

    private fun loadFromCache() {
        val file = cacheFile ?: return
        if (!file.isFile) return
        try {
            val content = file.readText(Charsets.UTF_8)
            val parsed = MobilithekTrafficParser.parseGeoJson(content)
            _incidents.value = parsed
        } catch (_: Exception) {
            // Cache read failure is non-fatal; starts empty
        }
    }

    private fun saveToCache(incidentsToSave: List<TrafficIncident>) {
        val file = cacheFile ?: return
        try {
            file.parentFile?.mkdirs()
            val geoJson = MobilithekTrafficParser.toGeoJson(incidentsToSave)
            file.writeText(geoJson, Charsets.UTF_8)
        } catch (_: Exception) {
            // Cache write failure is non-fatal
        }
    }
}
