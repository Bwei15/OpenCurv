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
import com.motoroute.data.download.RegionStatus
import com.motoroute.data.download.RegionStore
import com.motoroute.data.map.OfflineFile
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.search.IndexState
import com.motoroute.data.search.Place
import com.motoroute.data.settings.MapStyle
import com.motoroute.data.settings.MapTheme
import com.motoroute.data.settings.Settings
import com.motoroute.domain.NavigationState
import com.motoroute.domain.PlanningState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the rider has picked on the map so far. */
data class PlanSelection(
    val start: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val destinationName: String? = null,
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
    val demoRunning: StateFlow<Boolean> = container.navigation.demoRunning

    private val _selection = MutableStateFlow(PlanSelection())
    val selection: StateFlow<PlanSelection> = _selection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Whether the map follows the rider.
     *
     * The single most annoying thing a navigator can do is drag the map back
     * under your thumb a second after you moved it, so following is a mode the
     * rider owns: any pan or pinch turns it off, and only the recentre button
     * turns it back on.
     */
    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    /**
     * True once the rider has picked a zoom by hand.
     *
     * The speed-driven camera is helpful right up to the moment you zoom out to
     * see what is after the next village and it zooms straight back in. So a
     * manual zoom - volume keys included - hands the zoom over until the next
     * recentre, while the map keeps following.
     */
    private val _manualZoom = MutableStateFlow(false)
    val manualZoom: StateFlow<Boolean> = _manualZoom.asStateFlow()

    val hasMaps: Boolean get() = container.offlineData.hasAny(OfflineFileKind.MAP)
    val hasSegments: Boolean get() = container.offlineData.hasAny(OfflineFileKind.SEGMENT)

    init {
        container.navigation.startLocationUpdates()
    }

    // ---- map interaction --------------------------------------------------

    fun onMapTap(point: GeoPoint) {
        _selection.value = _selection.value.copy(destination = point, destinationName = null)
    }

    /** A press held in place sets where the route starts, GPS or no GPS. */
    fun onMapLongPress(point: GeoPoint) {
        _selection.value = _selection.value.copy(start = point)
        _message.value = getApplication<Application>().getString(com.motoroute.R.string.start_set)
    }

    fun onUserGesture() {
        if (_followMode.value) _followMode.value = false
    }

    fun addVia(point: GeoPoint) {
        _selection.value = _selection.value.let { it.copy(via = it.via + point) }
    }

    fun clearSelection() {
        _selection.value = PlanSelection()
        container.navigation.clearPlan()
        mapController.showRoute(null, 0)
    }

    fun recenter() {
        _followMode.value = true
        _manualZoom.value = false
        val point = container.navigation.lastFix.value?.point ?: return
        mapController.centerOn(point)
    }

    fun zoomIn() {
        _manualZoom.value = true
        mapController.zoomIn()
    }

    fun zoomOut() {
        _manualZoom.value = true
        mapController.zoomOut()
    }

    // ---- planning ---------------------------------------------------------

    /**
     * Calculates a route to the picked destination.
     *
     * With no GPS fix the map centre stands in for the rider - which is what
     * makes planning tomorrow's ride at the kitchen table possible, and is the
     * only way to try the app indoors at all.
     */
    fun calculateRoute() {
        val app = getApplication<Application>()
        val destination = _selection.value.destination ?: run {
            _message.value = app.getString(com.motoroute.R.string.msg_pick_destination)
            return
        }
        if (!hasSegments) {
            _message.value = app.getString(com.motoroute.R.string.msg_no_segments)
            return
        }
        val start = _selection.value.start
            ?: container.navigation.lastFix.value?.point
            ?: mapController.center()?.also {
                _message.value = app.getString(com.motoroute.R.string.msg_start_is_map_centre)
            }
            ?: run {
                _message.value = app.getString(com.motoroute.R.string.msg_waiting_for_gps)
                return
            }
        container.navigation.plan(start, destination, _selection.value.via)
    }

    fun startNavigation(route: Route) {
        _followMode.value = true
        _manualZoom.value = false
        container.navigation.startNavigation(route)
    }

    fun stopNavigation() {
        container.navigation.stopNavigation()
        clearSelection()
    }

    /** Rides the planned route without a motorcycle, for testing everything at home. */
    fun startDemo(route: Route) {
        _followMode.value = true
        _manualZoom.value = false
        container.navigation.startDemo(route)
    }

    fun stopDemo() {
        container.navigation.stopDemo()
        clearSelection()
    }

    fun forceReroute() = container.navigation.forceReroute()

    fun toggleVoice() {
        container.navigation.setVoiceEnabled(!settings.value.voiceEnabled)
    }

    /** Speaks a sample announcement so the rider can check volume and intercom. */
    fun testVoice() {
        val app = getApplication<Application>()
        val spoke = container.voice.speakTest()
        _message.value = app.getString(
            if (spoke) com.motoroute.R.string.msg_voice_test else com.motoroute.R.string.msg_voice_unavailable,
        )
    }

    fun profiles() = container.profileManager.profiles()

    // ---- settings ---------------------------------------------------------

    fun setProfile(id: String) = container.settings.update { it.copy(profileId = id) }

    fun setCurviness(value: Float) = container.settings.update { it.copy(curviness = value) }

    fun setMapTheme(theme: MapTheme) = container.settings.update { it.copy(mapTheme = theme) }

    fun setMapStyle(style: MapStyle) = container.settings.update { it.copy(mapStyle = style) }

    fun setPerspective(enabled: Boolean) =
        container.settings.update { it.copy(perspectiveEnabled = enabled) }

    fun setHeadingUp(enabled: Boolean) = container.settings.update { it.copy(headingUp = enabled) }

    fun setVolumeKeyZoom(enabled: Boolean) =
        container.settings.update { it.copy(volumeKeyZoom = enabled) }

    fun setAlternatives(enabled: Boolean) =
        container.settings.update { it.copy(searchAlternatives = enabled) }

    fun completeOnboarding() = container.settings.update { it.copy(onboardingDone = true) }

    // ---- offline destination search ---------------------------------------

    val indexState: StateFlow<IndexState> = container.placeSearch.state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results: StateFlow<List<Place>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var searchJob: Job? = null

    fun prepareSearch() = container.placeSearch.ensureIndex()

    /** Debounced: a rider types on a phone, not a keyboard. */
    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        if (text.isBlank()) {
            _results.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _searching.value = true
            val near = container.navigation.lastFix.value?.point ?: mapController.center()
            _results.value = runCatching { container.placeSearch.search(text, near) }
                .getOrDefault(emptyList())
            _searching.value = false
        }
    }

    fun chooseSearchResult(place: Place) {
        _selection.value = _selection.value.copy(
            destination = place.point,
            destinationName = place.name,
        )
        _followMode.value = false
        mapController.centerOn(place.point, DESTINATION_ZOOM)
        _query.value = ""
        _results.value = emptyList()
    }

    /** Puts the camera on the downloaded data when there is no fix to follow. */
    fun centerOnDataIfIdle() {
        if (container.navigation.lastFix.value != null) return
        viewModelScope.launch {
            container.placeSearch.mapStartPosition()?.let { mapController.centerOn(it) }
        }
    }

    // ---- offline data -----------------------------------------------------

    /**
     * Bumped whenever the set of files on disk changes, so the data screen
     * recomposes without needing a file-system watcher.
     */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    val downloadQueue: StateFlow<DownloadQueueState> = container.downloads.state

    private val _regions = MutableStateFlow<Map<String, List<MapRegion>>>(emptyMap())
    val regions: StateFlow<Map<String, List<MapRegion>>> = _regions.asStateFlow()

    fun loadRegions() {
        viewModelScope.launch {
            if (_regions.value.isEmpty()) {
                val catalog = container.mapCatalog.regions()
                if (catalog.isNotEmpty()) {
                    container.regions.adopt(catalog)
                    _regions.value = catalog.groupBy { it.country }
                    _dataVersion.value++
                }
            }
            val updated = container.mapCatalog.refreshFromNetwork()
            if (updated) {
                val fresh = container.mapCatalog.regions()
                container.regions.adopt(fresh)
                _regions.value = fresh.groupBy { it.country }
                _dataVersion.value++
            }
        }
    }

    /** Regions the rider has installed, complete or not. */
    fun installedRegions(): List<RegionStatus> = container.regions.statuses()

    fun installedRegionPaths(): Set<String> =
        container.regions.statuses().filter { it.isComplete }.map { it.path }.toSet()

    /**
     * Why downloading is not possible right now, or null when it is.
     * Downloading mid-ride would fight the navigation for CPU and data, so it
     * is refused rather than merely discouraged.
     */
    fun downloadBlockedReason(): String? = when {
        navigationState.value.isNavigating ->
            getApplication<Application>().getString(com.motoroute.R.string.download_blocked_navigating)
        else -> null
    }

    /** Queues a region as one package: its map and every routing tile it needs. */
    fun downloadRegion(region: MapRegion) {
        if (downloadBlockedReason() != null) return
        container.downloads.downloadRegion(region, container.regions)
        _dataVersion.value++
    }

    /**
     * Deletes a region in one go.
     *
     * Routing tiles shared with another installed region stay: the rider asked
     * to remove Niedersachsen, not to break Schleswig-Holstein.
     */
    fun deleteRegion(status: RegionStatus) {
        viewModelScope.launch {
            val freed = container.regions.delete(status.path)
            refreshMaps()
            container.placeSearch.invalidate()
            _dataVersion.value++
            _message.value = getApplication<Application>().getString(
                com.motoroute.R.string.msg_region_deleted,
                status.name,
                formatMegabytes(freed),
            )
        }
    }

    fun cancelDownloads() = container.downloads.cancelAll()

    fun retryDownloads() = container.downloads.retryFailed()

    fun filesOf(kind: OfflineFileKind) = container.offlineData.list(kind)

    /** Map and tile files that no installed region claims. */
    fun looseFiles(): List<OfflineFile> = container.regions.looseFiles().map { file ->
        OfflineFile(file, file.length(), OfflineFileKind.of(file.name) ?: OfflineFileKind.MAP)
    }

    fun freeSpace(): Long = container.offlineData.freeSpaceBytes()

    fun deleteFile(file: OfflineFile) {
        viewModelScope.launch {
            container.offlineData.delete(file)
            if (file.kind == OfflineFileKind.MAP) {
                refreshMaps()
                container.placeSearch.invalidate()
            }
            _dataVersion.value++
        }
    }

    /** Called when a download finishes so the renderer picks up the new file. */
    fun onDownloadedFilesChanged() {
        refreshMaps()
        container.placeSearch.invalidate()
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
            val app = getApplication<Application>()
            val imported = runCatching { container.offlineData.import(uri) }.getOrNull()
            if (imported == null) {
                onDone(app.getString(com.motoroute.R.string.msg_import_wrong_type))
                return@launch
            }
            if (imported.kind == OfflineFileKind.MAP) {
                refreshMaps()
                container.placeSearch.invalidate()
            }
            _dataVersion.value++
            onDone(app.getString(com.motoroute.R.string.msg_imported, imported.name))
        }
    }

    override fun onCleared() {
        super.onCleared()
        mapController.detach()
    }

    private fun formatMegabytes(bytes: Long): String = "${bytes / 1_000_000} MB"

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 220L

        /** Close enough to see the streets around a chosen destination. */
        private const val DESTINATION_ZOOM = 14

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MapViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!)
            }
        }
    }
}
