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
import com.motoroute.data.history.HistoryDestination
import com.motoroute.data.history.HistoryStop
import com.motoroute.data.history.HistoryTrip
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
import com.motoroute.domain.RecalcTrigger
import com.motoroute.domain.RoundTripPlanner
import com.motoroute.ui.search.SearchMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A stop on the way to the destination: where, and what to call it in the stop list. */
data class Stop(val point: GeoPoint, val name: String? = null)

/** What the rider has picked on the map so far. */
data class PlanSelection(
    val start: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val destinationName: String? = null,
    val via: List<Stop> = emptyList(),
    /**
     * Ziel = Start once this is on: the sheet stops asking for a destination and instead offers
     * to suggest a loop through [via] and back. See [MapViewModel.suggestRoundTrip].
     */
    val roundTrip: Boolean = false,
) {
    val isComplete: Boolean get() = destination != null || roundTrip
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

    /** The POI or barrier icon the rider last tapped, shown as a floating card over the sheet. */
    private val _selectedPoi = MutableStateFlow<PoiHit?>(null)
    val selectedPoi: StateFlow<PoiHit?> = _selectedPoi.asStateFlow()

    fun selectPoi(hit: PoiHit) {
        _selectedPoi.value = hit
    }

    fun clearPoi() {
        _selectedPoi.value = null
    }

    /** "Als Ziel" on the POI card - same move as picking a search result. */
    fun choosePoiAsDestination(hit: PoiHit) {
        _selection.value = _selection.value.copy(
            destination = hit.point,
            destinationName = hit.name.ifBlank { null },
        )
        _followMode.value = false
        mapController.centerOn(hit.point, DESTINATION_ZOOM)
        _selectedPoi.value = null
    }

    /** "Zwischenziel" on the POI card. */
    fun choosePoiAsVia(hit: PoiHit) {
        addVia(hit.point, hit.name.ifBlank { null })
        _selectedPoi.value = null
    }

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
        // Every successful calculation - manual or a profile/curviness auto-recalc - remembers
        // its destination, so the search box's "last destinations" stays current without the
        // rest of the app having to know history exists.
        viewModelScope.launch {
            container.navigation.planning.collect { state ->
                if (state !is PlanningState.Ready) return@collect
                val sel = _selection.value
                val destination = if (sel.roundTrip) sel.start else sel.destination
                destination?.let {
                    container.routeHistory.recordDestination(sel.destinationName, it.latitude, it.longitude)
                    refreshHistory()
                }
            }
        }
    }

    // ---- map interaction --------------------------------------------------

    fun onMapTap(point: GeoPoint) {
        _selection.value = _selection.value.copy(destination = point, destinationName = null, roundTrip = false)
    }

    /**
     * A press held in place sets where the route starts, GPS or no GPS - unless a plan already
     * exists, in which case overwriting the start silently would throw away work. Once start and
     * destination are both set, a long press instead asks (see [pendingStopPrompt]) whether the
     * point should become a stop.
     */
    fun onMapLongPress(point: GeoPoint) {
        val sel = _selection.value
        if (sel.start != null && sel.isComplete) {
            _pendingStopPrompt.value = point
            return
        }
        _selection.value = sel.copy(start = point)
        _message.value = getApplication<Application>().getString(com.motoroute.R.string.start_set)
    }

    /** The point from a long press while a plan already exists, awaiting the "add as stop?" answer. */
    private val _pendingStopPrompt = MutableStateFlow<GeoPoint?>(null)
    val pendingStopPrompt: StateFlow<GeoPoint?> = _pendingStopPrompt.asStateFlow()

    fun confirmAddStop() {
        _pendingStopPrompt.value?.let { addVia(it) }
        _pendingStopPrompt.value = null
    }

    fun dismissAddStopPrompt() {
        _pendingStopPrompt.value = null
    }

    fun onUserGesture() {
        if (_followMode.value) _followMode.value = false
    }

    fun addVia(point: GeoPoint, name: String? = null) {
        _selection.value = _selection.value.let { it.copy(via = it.via + Stop(point, name)) }
        scheduleRecalc()
    }

    /** Swaps a stop with the one before it; index 0 has no "before" and is ignored. */
    fun moveStopUp(index: Int) {
        val via = _selection.value.via
        if (index !in 1 until via.size) return
        val mutable = via.toMutableList()
        mutable[index - 1] = via[index].also { mutable[index] = via[index - 1] }
        _selection.value = _selection.value.copy(via = mutable)
        scheduleRecalc()
    }

    /** Swaps a stop with the one after it; the last stop has no "after" and is ignored. */
    fun moveStopDown(index: Int) {
        if (index !in _selection.value.via.indices) return
        moveStopUp(index + 1)
    }

    fun removeStop(index: Int) {
        val via = _selection.value.via
        if (index !in via.indices) return
        _selection.value = _selection.value.copy(via = via.filterIndexed { i, _ -> i != index })
        scheduleRecalc()
    }

    /** "Rundtour": destination becomes the start, the stop list sits in between. */
    fun setRoundTrip(enabled: Boolean) {
        roundTripJob?.cancel()
        _selection.value = if (enabled) {
            _selection.value.copy(roundTrip = true)
        } else {
            // Nothing forces the via list empty here on purpose: turning the switch back off
            // keeps whatever stops were on the loop as an ordinary multi-stop plan, since the
            // rider may well want to keep riding through them to a real destination instead.
            _selection.value.copy(roundTrip = false)
        }
        scheduleRecalc()
    }

    private var roundTripJob: Job? = null

    /**
     * Fills in a loop from [RoundTripPlanner] and calculates it, retrying with the points nudged
     * toward the start when BRouter cannot reach one of them - see the class doc there for why
     * this is a rough first cut, not a promise the ride comes out exactly [lengthKm] long.
     */
    fun suggestRoundTrip(lengthKm: Float) {
        val app = getApplication<Application>()
        if (!hasSegments) {
            _message.value = app.getString(com.motoroute.R.string.msg_no_segments)
            return
        }
        val start = _selection.value.start
            ?: container.navigation.lastFix.value?.point
            ?: mapController.center()
            ?: run {
                _message.value = app.getString(com.motoroute.R.string.msg_waiting_for_gps)
                return
            }
        roundTripJob?.cancel()
        roundTripJob = viewModelScope.launch {
            var points = RoundTripPlanner.suggestLoop(start, lengthKm * 1000.0)
            for (attempt in 0 until ROUND_TRIP_ATTEMPTS) {
                _selection.value = _selection.value.copy(
                    start = start,
                    roundTrip = true,
                    via = points.map { Stop(it, null) },
                )
                calculateRoute()
                val result = container.navigation.planning.first { it !is PlanningState.Calculating }
                if (result is PlanningState.Ready || attempt == ROUND_TRIP_ATTEMPTS - 1) break
                points = points.map { RoundTripPlanner.nudgeTowardStart(it, start) }
            }
        }
    }

    fun clearSelection() {
        recalcTrigger.cancel()
        roundTripJob?.cancel()
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
        // Rundtour: the destination the rider never had to pick is the point they are standing on.
        val destination = if (_selection.value.roundTrip) start else _selection.value.destination ?: run {
            _message.value = app.getString(com.motoroute.R.string.msg_pick_destination)
            return
        }
        container.navigation.plan(start, destination, _selection.value.via.map { it.point })
    }

    fun startNavigation(route: Route) {
        recordTripToHistory()
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
        recordTripToHistory()
        _followMode.value = true
        _manualZoom.value = false
        container.navigation.startDemo(route)
    }

    fun stopDemo() {
        container.navigation.stopDemo()
        clearSelection()
    }

    /** Snapshot of the current plan, written the moment a ride - real or demo - actually starts. */
    private fun recordTripToHistory() {
        val sel = _selection.value
        val start = sel.start ?: container.navigation.lastFix.value?.point ?: mapController.center() ?: return
        val destination = if (sel.roundTrip) start else sel.destination ?: return
        val stops = buildList {
            add(HistoryStop(name = null, latitude = start.latitude, longitude = start.longitude))
            sel.via.forEach { add(HistoryStop(it.name, it.point.latitude, it.point.longitude)) }
            add(HistoryStop(sel.destinationName, destination.latitude, destination.longitude))
        }
        val current = settings.value
        container.routeHistory.recordTrip(stops, current.profileId, current.curviness, sel.roundTrip)
        refreshHistory()
    }

    // ---- history ------------------------------------------------------------

    private val _recentDestinations = MutableStateFlow(container.routeHistory.recentDestinations())
    val recentDestinations: StateFlow<List<HistoryDestination>> = _recentDestinations.asStateFlow()

    private val _recentTrips = MutableStateFlow(container.routeHistory.recentTrips())
    val recentTrips: StateFlow<List<HistoryTrip>> = _recentTrips.asStateFlow()

    private fun refreshHistory() {
        _recentDestinations.value = container.routeHistory.recentDestinations()
        _recentTrips.value = container.routeHistory.recentTrips()
    }

    /** Loads a remembered tour's stops, profile and curve appetite back into the plan and rides it out. */
    fun loadHistoryTrip(trip: HistoryTrip) {
        if (trip.stops.size < 2) return
        val first = trip.stops.first()
        val last = trip.stops.last()
        val middle = trip.stops.subList(1, trip.stops.size - 1)
        container.settings.update { it.copy(profileId = trip.profileId, curviness = trip.curviness) }
        _selection.value = PlanSelection(
            start = GeoPoint(first.latitude, first.longitude),
            destination = if (trip.roundTrip) null else GeoPoint(last.latitude, last.longitude),
            destinationName = if (trip.roundTrip) null else last.name,
            via = middle.map { Stop(GeoPoint(it.latitude, it.longitude), it.name) },
            roundTrip = trip.roundTrip,
        )
        calculateRoute()
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

    /**
     * Debounces a profile/curviness change into one automatic recalculation.
     *
     * Only matters once there is something to recalculate: a fresh plan
     * still waits for the explicit "Calculate route" tap, so a rider who is
     * still picking a profile is not sent on a 30-80 s BRouter run before
     * they have even chosen a destination.
     */
    private val recalcTrigger = RecalcTrigger(viewModelScope)

    private fun scheduleRecalc() {
        if (!_selection.value.isComplete) return
        when (container.navigation.planning.value) {
            is PlanningState.Ready, PlanningState.Calculating -> recalcTrigger.request(::calculateRoute)
            else -> Unit
        }
    }

    fun setProfile(id: String) {
        container.settings.update { it.copy(profileId = id) }
        scheduleRecalc()
    }

    fun setCurviness(value: Float) {
        container.settings.update { it.copy(curviness = value) }
        scheduleRecalc()
    }

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

    /**
     * A search result picked in [mode]: the destination (the default, also used for the map's
     * own search bar), the end of the stop list, or the start.
     */
    fun chooseSearchResult(place: Place, mode: SearchMode = SearchMode.DESTINATION) {
        when (mode) {
            SearchMode.DESTINATION -> {
                _selection.value = _selection.value.copy(
                    destination = place.point,
                    destinationName = place.name,
                    roundTrip = false,
                )
            }
            SearchMode.STOP -> {
                _selection.value = _selection.value.let { it.copy(via = it.via + Stop(place.point, place.name)) }
                scheduleRecalc()
            }
            SearchMode.START -> {
                _selection.value = _selection.value.copy(start = place.point)
                scheduleRecalc()
            }
        }
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

        /** Initial suggestion plus this many nudge-and-retry rounds for [suggestRoundTrip]. */
        private const val ROUND_TRIP_ATTEMPTS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MapViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!)
            }
        }
    }
}
