package com.motoroute.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.motoroute.OpenCurvApp
import com.motoroute.data.download.DownloadQueueState
import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.download.MapRegion
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.MapTheme
import com.motoroute.data.settings.Settings
import com.motoroute.domain.NavigationState
import com.motoroute.domain.PlanningState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the rider has picked on the map so far. */
data class PlanSelection(
    val start: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val via: List<GeoPoint> = emptyList(),
) {
    val isComplete: Boolean get() = destination != null
}

/**
 * Drives the map and the route-planning screen.
 *
 * Navigation itself deliberately does *not* live here: it runs in the
 * process-scoped NavigationController so a screen rotation on the handlebar
 * cannot interrupt a ride.
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as OpenCurvApp).container

    val mapController = MapController(container.offlineData)

    val navigationState: StateFlow<NavigationState> = container.navigation.state
    val planningState: StateFlow<PlanningState> = container.navigation.planning
    val settings: StateFlow<Settings> = container.settings.settings
    val recommendedZoom: StateFlow<Int> = container.navigation.recommendedZoom

    private val _selection = MutableStateFlow(PlanSelection())
    val selection: StateFlow<PlanSelection> = _selection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val hasMaps: Boolean get() = container.offlineData.hasAny(OfflineFileKind.MAP)
    val hasSegments: Boolean get() = container.offlineData.hasAny(OfflineFileKind.SEGMENT)

    init {
        container.navigation.startLocationUpdates()
    }

    fun onMapTap(point: GeoPoint) {
        _selection.value = _selection.value.copy(destination = point)
    }

    fun addVia(point: GeoPoint) {
        _selection.value = _selection.value.let { it.copy(via = it.via + point) }
    }

    fun clearSelection() {
        _selection.value = PlanSelection()
        container.navigation.clearPlan()
        mapController.showRoute(null, 0)
    }

    /** Calculates a route from the current position to the picked destination. */
    fun calculateRoute() {
        val destination = _selection.value.destination ?: run {
            _message.value = "Tap the map to set a destination"
            return
        }
        val start = _selection.value.start
            ?: container.navigation.lastFix.value?.point
            ?: run {
                _message.value = "Waiting for a GPS fix"
                return
            }
        if (!hasSegments) {
            _message.value = "Import BRouter .rd5 tiles first"
            return
        }
        container.navigation.plan(start, destination, _selection.value.via)
    }

    fun startNavigation(route: Route) {
        container.navigation.startNavigation(route)
    }

    fun stopNavigation() {
        container.navigation.stopNavigation()
        clearSelection()
    }

    fun forceReroute() = container.navigation.forceReroute()

    fun toggleVoice() {
        container.navigation.setVoiceEnabled(!settings.value.voiceEnabled)
    }

    fun setProfile(id: String) = container.settings.update { it.copy(profileId = id) }

    fun setCurviness(value: Float) = container.settings.update { it.copy(curviness = value) }

    fun setMapTheme(theme: MapTheme) = container.settings.update { it.copy(mapTheme = theme) }

    fun setPerspective(enabled: Boolean) =
        container.settings.update { it.copy(perspectiveEnabled = enabled) }

    fun setHeadingUp(enabled: Boolean) = container.settings.update { it.copy(headingUp = enabled) }

    fun setAlternatives(enabled: Boolean) =
        container.settings.update { it.copy(searchAlternatives = enabled) }

    fun profiles() = container.profileManager.profiles()

    fun recenter() {
        val point = container.navigation.lastFix.value?.point ?: return
        mapController.centerOn(point)
    }

    fun zoomIn() = mapController.zoomIn()

    fun zoomOut() = mapController.zoomOut()

    /**
     * Bumped whenever the set of files on disk changes, so the data screen
     * recomposes without needing a file-system watcher.
     */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    // ---- offline data downloads ------------------------------------------

    val downloadQueue: StateFlow<DownloadQueueState> = container.downloads.state

    private val _regions = MutableStateFlow<Map<String, List<MapRegion>>>(emptyMap())
    val regions: StateFlow<Map<String, List<MapRegion>>> = _regions.asStateFlow()

    fun loadRegions() {
        if (_regions.value.isNotEmpty()) return
        viewModelScope.launch { _regions.value = container.mapCatalog.grouped() }
    }

    /**
     * Regions whose map file is already on disk. Only the map is checked: the
     * routing tiles are shared between regions, so a missing tile is re-queued
     * automatically rather than marking the whole region as absent.
     */
    fun installedRegions(): Set<String> {
        val names = container.offlineData.list(OfflineFileKind.MAP).map { it.name }.toSet()
        return _regions.value.values.flatten()
            .filter { it.fileName in names }
            .map { it.path }
            .toSet()
    }

    /**
     * Why downloading is not possible right now, or null when it is.
     * Downloading mid-ride would fight the navigation for CPU and data, so it
     * is refused rather than merely discouraged.
     */
    fun downloadBlockedReason(): String? = when {
        navigationState.value.isNavigating ->
            "Stop navigation before downloading."
        else -> null
    }

    /** Queues a region's map and every routing tile that covers it. */
    fun downloadRegion(region: MapRegion) {
        if (downloadBlockedReason() != null) return
        val targets = buildList {
            add(DownloadTarget.map(region))
            region.segmentTiles.forEach { add(DownloadTarget.segment(it)) }
        }
        container.downloads.enqueue(targets)
        _dataVersion.value++
    }

    fun cancelDownloads() = container.downloads.cancelAll()

    fun retryDownloads() = container.downloads.retryFailed()

    fun filesOf(kind: OfflineFileKind) = container.offlineData.list(kind)

    fun freeSpace(): Long = container.offlineData.freeSpaceBytes()

    fun deleteFile(file: com.motoroute.data.map.OfflineFile) {
        viewModelScope.launch {
            container.offlineData.delete(file)
            if (file.kind == OfflineFileKind.MAP) refreshMaps()
            _dataVersion.value++
        }
    }

    /** Called when a download finishes so the renderer picks up the new file. */
    fun onDownloadedFilesChanged() {
        refreshMaps()
        _dataVersion.value++
    }

    fun refreshMaps() {
        mapController.rebuildMapLayer(getApplication<Application>())
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun importFile(uri: android.net.Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val imported = runCatching { container.offlineData.import(uri) }.getOrNull()
            if (imported == null) {
                onDone("Not a .map, .rd5 or .brf file")
                return@launch
            }
            if (imported.kind == OfflineFileKind.MAP) refreshMaps()
            _dataVersion.value++
            onDone("Imported ${imported.name}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        mapController.detach()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MapViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!)
            }
        }
    }
}
