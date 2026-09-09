package com.motoroute.domain

import com.motoroute.data.location.FilteredFix
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction
import com.motoroute.data.model.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Everything the HUD needs to draw one frame.
 */
data class NavigationState(
    val route: Route? = null,
    val isNavigating: Boolean = false,
    val position: GeoPoint? = null,
    val snappedPosition: GeoPoint? = null,
    val headingDegrees: Double = 0.0,
    val speedMps: Double = 0.0,
    val speedLimitKmh: Int? = null,
    val current: NavigationInstruction? = null,
    val distanceToManeuverMeters: Double = 0.0,
    val next: NavigationInstruction? = null,
    val distanceBetweenManeuversMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingSeconds: Int = 0,
    val etaEpochMillis: Long = 0L,
    val crossTrackMeters: Double = 0.0,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val hasArrived: Boolean = false,
) {
    val speedKmh: Int get() = (speedMps * 3.6).roundToInt()

    /** True when the rider is over the posted limit by a margin worth colouring red. */
    val isSpeeding: Boolean
        get() = speedLimitKmh?.let { speedKmh > it + SPEEDING_TOLERANCE_KMH } == true

    companion object {
        const val SPEEDING_TOLERANCE_KMH = 5
    }
}

/** A spoken announcement the voice layer should read out. */
data class VoiceAnnouncement(
    val maneuver: Maneuver,
    val distanceMeters: Int,
    val roundaboutExit: Int,
    val isImmediate: Boolean,
)

/**
 * The turn-by-turn state machine.
 *
 * Feed it filtered fixes; it decides which instruction is current, how far the
 * maneuver is, when to speak, when the rider has left the route, and when the
 * destination is reached. It owns no Android types so it can be unit tested
 * against a synthetic GPS track.
 */
class NavigationManager(
    private val matcher: MapMatcher = MapMatcher(),
) {

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _announcements = MutableSharedFlow<VoiceAnnouncement>(extraBufferCapacity = 4)
    val announcements: SharedFlow<VoiceAnnouncement> = _announcements.asSharedFlow()

    private var route: Route? = null
    private var instructionIndex = 0

    /** Announcement rings already fired for the current instruction. */
    private var announcedRings = BooleanArray(ANNOUNCE_RINGS_M.size)

    private var consecutiveOffRouteFixes = 0

    fun start(route: Route) {
        this.route = route
        instructionIndex = 0
        announcedRings = BooleanArray(ANNOUNCE_RINGS_M.size)
        consecutiveOffRouteFixes = 0
        arrivalAnnounced = false
        matcher.reset()
        _state.value = NavigationState(
            route = route,
            isNavigating = true,
            current = route.instructions.firstOrNull(),
            next = route.instructions.getOrNull(1),
            remainingDistanceMeters = route.distanceMeters,
            remainingSeconds = route.estimatedSeconds,
        )
    }

    fun stop() {
        route = null
        matcher.reset()
        _state.value = NavigationState()
    }

    fun setRerouting(active: Boolean) {
        _state.value = _state.value.copy(isRerouting = active)
    }

    /**
     * Swaps in a freshly calculated route without dropping out of navigation
     * mode - this is what the rerouting engine calls when it has a new line.
     */
    fun replaceRoute(newRoute: Route) {
        route = newRoute
        instructionIndex = 0
        announcedRings = BooleanArray(ANNOUNCE_RINGS_M.size)
        consecutiveOffRouteFixes = 0
        arrivalAnnounced = false
        matcher.reset()
        _state.value = _state.value.copy(
            route = newRoute,
            isNavigating = true,
            isRerouting = false,
            isOffRoute = false,
            hasArrived = false,
            current = newRoute.instructions.firstOrNull(),
            next = newRoute.instructions.getOrNull(1),
            remainingDistanceMeters = newRoute.distanceMeters,
            remainingSeconds = newRoute.estimatedSeconds,
        )
    }

    /**
     * Processes one fix.
     *
     * @return true when the rider is off route and a recalculation is wanted.
     */
    fun onLocation(fix: FilteredFix, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val route = this.route ?: run {
            _state.value = _state.value.copy(
                position = fix.point,
                headingDegrees = fix.headingDegrees,
                speedMps = fix.speedMps,
            )
            return false
        }

        val match = matcher.match(route, fix.point, fix.headingDegrees)
        if (match == null) {
            _state.value = _state.value.copy(position = fix.point, speedMps = fix.speedMps)
            return false
        }

        advanceInstructions(route, match.distanceFromStart)

        val current = route.instructions.getOrNull(instructionIndex)
        val next = route.instructions.getOrNull(instructionIndex + 1)
        val distanceToManeuver =
            (current?.distanceFromStart?.minus(match.distanceFromStart) ?: 0.0).coerceAtLeast(0.0)

        maybeAnnounce(current, next, distanceToManeuver)

        val remaining = (route.distanceMeters - match.distanceFromStart).coerceAtLeast(0.0)
        val remainingSeconds = estimateRemainingSeconds(route, remaining, fix.speedMps)
        val arrived = remaining < ARRIVAL_RADIUS_M

        val offRoute = match.crossTrackMeters > OFF_ROUTE_METERS
        consecutiveOffRouteFixes = if (offRoute) consecutiveOffRouteFixes + 1 else 0
        val shouldReroute = consecutiveOffRouteFixes >= OFF_ROUTE_FIXES && !arrived

        _state.value = _state.value.copy(
            route = route,
            isNavigating = true,
            position = fix.point,
            snappedPosition = match.snapped,
            headingDegrees = fix.headingDegrees,
            speedMps = fix.speedMps,
            speedLimitKmh = route.speedLimitAt(match.segmentIndex),
            current = current,
            distanceToManeuverMeters = distanceToManeuver,
            next = next,
            distanceBetweenManeuversMeters = distanceBetween(current, next),
            remainingDistanceMeters = remaining,
            remainingSeconds = remainingSeconds,
            etaEpochMillis = nowMillis + remainingSeconds * 1000L,
            crossTrackMeters = match.crossTrackMeters,
            isOffRoute = consecutiveOffRouteFixes >= OFF_ROUTE_FIXES,
            hasArrived = arrived,
        )

        if (arrived) {
            emitArrival()
        }
        return shouldReroute
    }

    /**
     * Steps to the next instruction once the maneuver point is behind us.
     *
     * A maneuver counts as executed as soon as the snapped position is
     * [PASSED_METERS] past it - waiting for the exact node would leave the HUD
     * showing a turn the rider has already taken.
     */
    private fun advanceInstructions(route: Route, distanceFromStart: Double) {
        while (instructionIndex < route.instructions.size - 1) {
            val instruction = route.instructions[instructionIndex]
            if (distanceFromStart - instruction.distanceFromStart > PASSED_METERS) {
                instructionIndex++
                announcedRings = BooleanArray(ANNOUNCE_RINGS_M.size)
            } else {
                break
            }
        }
    }

    /**
     * Fires the 1000 m / 300 m / 50 m announcements, plus an immediate "and
     * then" when two maneuvers follow each other inside [IMMEDIATE_LINK_M].
     *
     * The rings are triggers, not text: the announcement carries the distance
     * actually measured at that moment. Crossing several rings in one fix (a
     * fresh reroute can drop the rider 400 m from a turn) retires the wider
     * rings silently instead of announcing "in one kilometre" from 400 m out.
     */
    private fun maybeAnnounce(
        current: NavigationInstruction?,
        next: NavigationInstruction?,
        distance: Double,
    ) {
        if (current == null || !current.maneuver.isTurn) return

        for ((i, ring) in ANNOUNCE_RINGS_M.withIndex()) {
            if (announcedRings[i]) continue
            if (distance > ring) continue

            // Entering this ring retires every wider one.
            for (j in 0..i) announcedRings[j] = true

            val immediate = next != null &&
                next.distanceFromStart - current.distanceFromStart < IMMEDIATE_LINK_M
            _announcements.tryEmit(
                VoiceAnnouncement(
                    maneuver = current.maneuver,
                    distanceMeters = distance.roundToInt(),
                    roundaboutExit = current.roundaboutExit,
                    isImmediate = immediate && i == ANNOUNCE_RINGS_M.lastIndex,
                ),
            )
            return
        }
    }

    private var arrivalAnnounced = false

    private fun emitArrival() {
        if (arrivalAnnounced) return
        arrivalAnnounced = true
        _announcements.tryEmit(
            VoiceAnnouncement(Maneuver.DESTINATION, 0, 0, isImmediate = false),
        )
    }

    private fun distanceBetween(
        current: NavigationInstruction?,
        next: NavigationInstruction?,
    ): Double {
        if (current == null || next == null) return 0.0
        return (next.distanceFromStart - current.distanceFromStart).coerceAtLeast(0.0)
    }

    /**
     * Remaining time. BRouter's own estimate is used as the baseline and scaled
     * by how much of the route is left; once the rider has a stable speed, that
     * speed is blended in so a slow group ride does not keep promising an ETA
     * it will never hit.
     */
    private fun estimateRemainingSeconds(
        route: Route,
        remainingMeters: Double,
        speedMps: Double,
    ): Int {
        if (remainingMeters < 1.0) return 0
        val fraction = remainingMeters / route.distanceMeters.coerceAtLeast(1.0)
        val plannedSeconds = route.estimatedSeconds * fraction
        if (speedMps < 3.0) return plannedSeconds.roundToInt()
        val measuredSeconds = remainingMeters / speedMps
        return (0.6 * plannedSeconds + 0.4 * measuredSeconds).roundToInt()
    }

    companion object {
        /** Spoken warning distances, widest first. */
        val ANNOUNCE_RINGS_M = intArrayOf(1000, 300, 50)

        /** Cross-track distance that counts as "off route". */
        const val OFF_ROUTE_METERS = 35.0

        /** How many consecutive off-route fixes before a reroute is requested. */
        const val OFF_ROUTE_FIXES = 3

        /** Metres past a maneuver before the state machine steps on. */
        const val PASSED_METERS = 15.0

        /** Distance to the destination that counts as arrived. */
        const val ARRIVAL_RADIUS_M = 25.0

        /** Two maneuvers closer than this get an "and then immediately" hint. */
        const val IMMEDIATE_LINK_M = 150.0

    }
}
