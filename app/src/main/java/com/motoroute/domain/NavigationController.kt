package com.motoroute.domain

import com.motoroute.data.brouter.BRouterEngine
import com.motoroute.data.brouter.ProfileManager
import com.motoroute.data.brouter.RouteRequest
import com.motoroute.data.location.FilteredFix
import com.motoroute.data.location.LocationProvider
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.SettingsRepository
import com.motoroute.diagnostics.CrashLog
import com.motoroute.domain.geo.Geo
import com.motoroute.voice.VoiceGuidance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the route-planning screen is currently doing. */
sealed interface PlanningState {
    data object Idle : PlanningState
    data object Calculating : PlanningState
    data class Ready(val route: Route) : PlanningState
    data class Failed(val message: String) : PlanningState
}

/**
 * The one place that knows how a ride actually runs.
 *
 * It owns the location stream, the state machine, rerouting and the voice, and
 * is deliberately a plain object rather than a ViewModel: navigation has to
 * survive the UI being destroyed when the rider's screen turns off or the app
 * is rotated on the handlebar.
 */
class NavigationController(
    private val locationProvider: LocationProvider,
    private val routingEngine: BRouterEngine,
    private val profileManager: ProfileManager,
    private val offlineData: OfflineDataRepository,
    private val settings: SettingsRepository,
    private val voice: VoiceGuidance,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    private val manager = NavigationManager()
    private val camera = CameraController()

    val state: StateFlow<NavigationState> = manager.state

    private val _planning = MutableStateFlow<PlanningState>(PlanningState.Idle)
    val planning: StateFlow<PlanningState> = _planning.asStateFlow()

    private val _lastFix = MutableStateFlow<FilteredFix?>(null)
    val lastFix: StateFlow<FilteredFix?> = _lastFix.asStateFlow()

    private val _zoom = MutableStateFlow(16)
    val recommendedZoom: StateFlow<Int> = _zoom.asStateFlow()

    private val _demoRunning = MutableStateFlow(false)

    /** True while a demo ride is driving the navigation instead of the GPS. */
    val demoRunning: StateFlow<Boolean> = _demoRunning.asStateFlow()

    private var destination: GeoPoint? = null
    private var viaPoints: List<GeoPoint> = emptyList()
    private var locationJob: Job? = null
    private var demoJob: Job? = null

    private val rerouting = ReroutingEngine(
        scope = scope,
        calculate = { from, to, via -> runCatching { calculate(listOf(from) + via + to) } },
    )

    init {
        manager.announcements
            .onEach { voice.speak(it) }
            .launchIn(scope)
    }

    /** Starts the location stream. Safe to call repeatedly. */
    fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = locationProvider.fixes()
            .catch { CrashLog.record("location", it) }
            .onEach(::onFix)
            .launchIn(scope)
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
    }

    private fun onFix(fix: FilteredFix, allowReroute: Boolean = true) {
        _lastFix.value = fix
        _zoom.value = camera.zoomFor(fix.speedMps * 3.6)

        val needsReroute = manager.onLocation(fix)
        if (!needsReroute || !allowReroute) return

        val target = destination ?: return
        manager.setRerouting(true)
        rerouting.request(fix.point, target, viaPoints) { result ->
            when (result) {
                is RerouteResult.Success -> manager.replaceRoute(result.route)
                is RerouteResult.Failure -> manager.setRerouting(false)
                RerouteResult.Skipped -> manager.setRerouting(false)
            }
        }
    }

    /** Calculates a route to [to] and parks it as the plan, without starting guidance. */
    fun plan(from: GeoPoint, to: GeoPoint, via: List<GeoPoint> = emptyList()) {
        destination = to
        viaPoints = via
        _planning.value = PlanningState.Calculating
        scope.launch {
            runCatching { calculate(listOf(from) + via + to) }
                .onSuccess { _planning.value = PlanningState.Ready(it) }
                .onFailure {
                    CrashLog.record("routing", it)
                    _planning.value = PlanningState.Failed(it.message ?: "routing failed")
                }
        }
    }

    fun clearPlan() {
        _planning.value = PlanningState.Idle
        destination = null
        viaPoints = emptyList()
    }

    fun startNavigation(route: Route) {
        stopDemo(resumeLocation = false)
        voice.enabled = settings.current.voiceEnabled
        camera.reset()
        rerouting.reset()
        manager.start(route)
        startLocationUpdates()
    }

    fun stopNavigation() {
        stopDemo(resumeLocation = false)
        manager.stop()
        rerouting.cancel()
        voice.stop()
        _planning.value = PlanningState.Idle
    }

    /**
     * Rides the calculated route without a motorcycle.
     *
     * The demo feeds the ordinary pipeline - state machine, map matcher, voice -
     * with positions walked along the route, so a rider can check the
     * announcements, the HUD and the map behaviour at the kitchen table before
     * trusting them at 100 km/h. Rerouting is the one thing switched off: there
     * is nothing to reroute from when the fixes are on the route by
     * construction.
     */
    fun startDemo(route: Route) {
        val simulator = RouteSimulator(route)
        if (!simulator.isRunnable) return

        stopLocationUpdates()
        demoJob?.cancel()
        voice.enabled = settings.current.voiceEnabled
        camera.reset()
        rerouting.cancel()
        // Flagged before the state machine starts, so nothing ever sees a
        // navigating state that is not yet marked as a demo.
        _demoRunning.value = true
        manager.start(route)

        demoJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var elapsed = 0L
            while (isActive) {
                val fix = simulator.fixAt(elapsed, startedAt + elapsed) ?: break
                onFix(fix, allowReroute = false)
                delay(DEMO_TICK_MILLIS)
                elapsed += DEMO_TICK_MILLIS
            }
            // Nudge the state machine onto the very last point so the arrival
            // announcement fires exactly as it would at the end of a real ride.
            if (isActive) {
                onFix(
                    simulator.fixAtDistance(
                        simulator.totalMeters,
                        System.currentTimeMillis(),
                    ),
                    allowReroute = false,
                )
            }
            _demoRunning.value = false
        }
    }

    fun stopDemo(resumeLocation: Boolean = true) {
        if (demoJob == null && !_demoRunning.value) return
        demoJob?.cancel()
        demoJob = null
        _demoRunning.value = false
        manager.stop()
        voice.stop()
        if (resumeLocation) startLocationUpdates()
    }

    /** Forces a recalculation from the current position, e.g. the panic button. */
    fun forceReroute() {
        val fix = _lastFix.value ?: return
        val target = destination ?: return
        rerouting.reset()
        manager.setRerouting(true)
        rerouting.request(fix.point, target, viaPoints) { result ->
            when (result) {
                is RerouteResult.Success -> manager.replaceRoute(result.route)
                else -> manager.setRerouting(false)
            }
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voice.enabled = enabled
        if (!enabled) voice.stop()
        settings.update { it.copy(voiceEnabled = enabled) }
    }

    private suspend fun calculate(waypoints: List<GeoPoint>): Route {
        val current = settings.current
        val profile = profileManager.profile(current.profileId)
            ?: profileManager.defaultProfile()
            ?: throw IllegalStateException("no routing profile installed")

        val request = RouteRequest(
            waypoints = waypoints,
            profile = profile.file,
            segmentDir = offlineData.segmentDir,
            profileParams = mapOf("curviness" to current.curviness.toString()),
            memoryClassMb = MEMORY_CLASS_MB,
        ).withCorridorFor(waypoints)

        val startedAt = System.currentTimeMillis()
        val route = if (current.searchAlternatives) {
            routingEngine.routeCurviest(request)
        } else {
            routingEngine.route(request)
        }
        CrashLog.record(
            "routing",
            "%.1f km in %.1f s%s, curviness %.0f (raw %.0f, %d junctions)".format(
                route.distanceMeters / 1000.0,
                (System.currentTimeMillis() - startedAt) / 1000.0,
                if (current.searchAlternatives) ", alternatives compared" else "",
                route.curvinessScore,
                com.motoroute.data.model.Curviness.score(route.points),
                route.instructions.count { it.maneuver.isTurn },
            ),
        )
        return route
    }

    /**
     * Narrows the search to a corridor when the destination is far away.
     *
     * BRouter's search is an A* whose heuristic weight decides how much of the
     * map it opens up. At the profile's own settings a 100 km route explores an
     * enormous disc and takes minutes on a phone - long enough that the app is
     * simply not usable for the rides it exists for. Weighting the heuristic
     * harder as the distance grows turns that disc into a corridor towards the
     * destination.
     *
     * The trade is real and deliberate: a corridor can miss a detour that is
     * cheaper by a few percent. On a curvy-road profile that is a far smaller
     * loss than a five-minute wait, and short routes - where a detour is most
     * likely to matter and the search is cheap anyway - keep the exact settings.
     */
    private fun RouteRequest.withCorridorFor(waypoints: List<GeoPoint>): RouteRequest {
        var crowFlies = 0.0
        for (i in 1 until waypoints.size) {
            crowFlies += Geo.distanceMeters(waypoints[i - 1], waypoints[i])
        }
        val km = crowFlies / 1000.0
        return when {
            km < SHORT_ROUTE_KM -> this
            km < LONG_ROUTE_KM -> copy(pass1Coefficient = 2.0, pass2Coefficient = 0.8)
            // Past this the refinement pass is the whole cost of the search,
            // and a single well-guided pass is what makes the wait bearable.
            else -> copy(pass1Coefficient = 2.5, pass2Coefficient = -1.0)
        }
    }

    private companion object {
        /**
         * BRouter's node-cache budget.
         *
         * This is a cache ceiling, not an allocation. The old 48 MB was small
         * enough that a 100 km search kept throwing away routing tiles it was
         * about to need again and decoding them a second time; the map renderer
         * still has room at this size on a 4 GB phone.
         */
        const val MEMORY_CLASS_MB = 96

        /** Demo fixes arrive twice a second, like a good GPS on a fast bike. */
        const val DEMO_TICK_MILLIS = 500L

        /** Below this, the exact search is fast enough to be worth having. */
        const val SHORT_ROUTE_KM = 25.0

        /** Past this, only a single guided pass finishes in a usable time. */
        const val LONG_ROUTE_KM = 75.0
    }
}
