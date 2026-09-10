package com.motoroute.domain

import com.motoroute.data.location.FilteredFix
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo
import com.motoroute.domain.guidance.AnnouncementTiming
import com.motoroute.domain.guidance.CurveCombo
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

/** What kind of thing the voice layer is being asked to say. */
enum class AnnouncementKind {
    /** An ordinary turn-by-turn call for the current manoeuvre. */
    MANEUVER,

    /** A single warning covering a whole run of close/sharp bends - see [CurveCombo]. */
    CURVE_WARNING,

    /** "Still on the right road" cue after a long stretch with nothing to say. */
    FREE_RIDE,
    ARRIVAL,
    OFF_ROUTE,
}

/**
 * A spoken announcement the voice layer should read out.
 *
 * This is deliberately a plain description of *what to say*, with no opinion
 * on wording (that is [com.motoroute.domain.guidance.Phrasebook]'s job) or on
 * how it reaches the headset (that is `voice.VoiceGuidance`'s job). Its only
 * other consumer-facing property is [isFinal], which the audio layer uses to
 * decide whether this announcement is allowed to interrupt one still playing.
 */
data class VoiceAnnouncement(
    val kind: AnnouncementKind,
    val maneuver: Maneuver = Maneuver.CONTINUE,
    val distanceMeters: Int = 0,
    val roundaboutExit: Int = 0,
    /** True for the last-chance ("jetzt"/"now") call - always allowed to cut in. */
    val isFinal: Boolean = false,
    /** How many linked manoeuvres this single utterance covers (see [CurveCombo]). */
    val comboCount: Int = 1,
    /** Set on a two-manoeuvre combo's final call: "..., then immediately <this>". */
    val secondManeuver: Maneuver? = null,
    /** Set on [AnnouncementKind.FREE_RIDE]: how far the quiet stretch still runs. */
    val freeRideKm: Int = 0,
)

/**
 * The turn-by-turn state machine.
 *
 * Feed it filtered fixes; it decides which instruction is current, how far the
 * manoeuvre is, when to speak, when the rider has left the route, and when the
 * destination is reached. It owns no Android types so it can be unit tested
 * against a synthetic GPS track.
 *
 * Timing is worked out in [AnnouncementTiming] and manoeuvre-clustering in
 * [CurveCombo]; this class only holds the per-ride bookkeeping (which tier of
 * which instruction has already been said) and wires the two together.
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

    /** Which of [AnnouncementTiming.applicableTiers] have already fired for the current instruction. */
    private var announcedTiers = BooleanArray(TIER_COUNT)

    /** The run of linked manoeuvres [instructionIndex] currently starts, per [CurveCombo]. */
    private var currentRun: List<Int> = emptyList()
    private var currentRunStartIndex = -1
    private var currentRunIsLockout = false

    /** True once a lockout run's single warning has been spoken. */
    private var lockoutAnnounced = false

    /** Distance-from-start at which something was last actually said - drives the free-ride cue. */
    private var lastSpokenAtDistance = 0.0

    /** For the live "are we actively leaned into a bend right now" heading-rate check. */
    private var lastFixHeadingDegrees: Double? = null
    private var lastFixTimeMillis: Long = 0L

    /** Wall-clock time a due, non-final tier first started being deferred for active cornering. */
    private var corneringDeferredSinceMillis: Long? = null

    private var consecutiveOffRouteFixes = 0
    private var arrivalAnnounced = false

    fun start(route: Route) {
        this.route = route
        resetAnnouncementState()
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
        resetAnnouncementState()
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

    private fun resetAnnouncementState() {
        instructionIndex = 0
        announcedTiers = BooleanArray(TIER_COUNT)
        currentRun = emptyList()
        currentRunStartIndex = -1
        currentRunIsLockout = false
        lockoutAnnounced = false
        lastSpokenAtDistance = 0.0
        lastFixHeadingDegrees = null
        lastFixTimeMillis = 0L
        corneringDeferredSinceMillis = null
        consecutiveOffRouteFixes = 0
        arrivalAnnounced = false
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

        val headingRateDegPerSec = headingRateSince(fix)

        advanceInstructions(route, match.distanceFromStart)

        val current = route.instructions.getOrNull(instructionIndex)
        val next = route.instructions.getOrNull(instructionIndex + 1)
        val distanceToManeuver =
            (current?.distanceFromStart?.minus(match.distanceFromStart) ?: 0.0).coerceAtLeast(0.0)

        maybeAnnounce(
            route = route,
            current = current,
            distanceToManeuver = distanceToManeuver,
            distanceFromStart = match.distanceFromStart,
            speedMps = fix.speedMps,
            headingRateDegPerSec = headingRateDegPerSec,
            nowMillis = nowMillis,
        )
        maybeAnnounceFreeRide(current, match.distanceFromStart, headingRateDegPerSec, fix.speedMps)

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
            emitArrival(match.distanceFromStart)
        }
        return shouldReroute
    }

    /** Live yaw rate in degrees/second, derived from consecutive fixes' heading. */
    private fun headingRateSince(fix: FilteredFix): Double {
        val previousHeading = lastFixHeadingDegrees
        val previousTime = lastFixTimeMillis
        lastFixHeadingDegrees = fix.headingDegrees
        lastFixTimeMillis = fix.timestampMillis
        if (previousHeading == null) return 0.0
        val dtSeconds = (fix.timestampMillis - previousTime).coerceAtLeast(1) / 1000.0
        return Geo.bearingDifference(previousHeading, fix.headingDegrees) / dtSeconds
    }

    /**
     * Steps to the next instruction once the manoeuver point is behind us.
     *
     * A manoeuver counts as executed as soon as the snapped position is
     * [PASSED_METERS] past it - waiting for the exact node would leave the HUD
     * showing a turn the rider has already taken.
     */
    private fun advanceInstructions(route: Route, distanceFromStart: Double) {
        while (instructionIndex < route.instructions.size - 1) {
            val instruction = route.instructions[instructionIndex]
            if (distanceFromStart - instruction.distanceFromStart > PASSED_METERS) {
                instructionIndex++
            } else {
                break
            }
        }
    }

    /**
     * Decides whether to speak about the current instruction, and if so, says
     * exactly one thing.
     *
     * Two problems are solved together here, because they are really the same
     * problem: a fixed-distance trigger cannot tell "far away" from "close",
     * so on this app's own curvy roads - where the next manoeuvre is
     * routinely under 300 m away - it used to announce the same turn two or
     * three times in as many seconds as the rider closed in on it (widest
     * ring fires, then the narrower ones each fire again separately a moment
     * later). Working in time-to-manoeuvre instead of distance, and always
     * retiring every wider tier the instant a narrower one is already due,
     * fixes that for a single manoeuvre. [CurveCombo] fixes the other half:
     * a run of hairpins close together in time is spoken about once, not once
     * per apex.
     */
    private fun maybeAnnounce(
        route: Route,
        current: NavigationInstruction?,
        distanceToManeuver: Double,
        distanceFromStart: Double,
        speedMps: Double,
        headingRateDegPerSec: Double,
        nowMillis: Long,
    ) {
        if (current == null || !current.maneuver.isTurn) return

        if (instructionIndex !in currentRun) {
            currentRun = CurveCombo.run(route.instructions, instructionIndex, speedMps)
            currentRunStartIndex = instructionIndex
            currentRunIsLockout = CurveCombo.isLockout(route.instructions, currentRun)
            announcedTiers = BooleanArray(TIER_COUNT)
            lockoutAnnounced = false
            corneringDeferredSinceMillis = null
        }

        // A later member of an already-announced run: it was covered by the
        // run's own announcement (or is a lone sharp bend already warned
        // about), stay silent while it is passed.
        if (instructionIndex != currentRunStartIndex) return

        if (currentRunIsLockout && lockoutAnnounced) return

        val applicable = AnnouncementTiming.applicableTiers(speedMps)
        val timeToManeuver = AnnouncementTiming.timeToManeuverSeconds(distanceToManeuver, speedMps)
        val deepest = AnnouncementTiming.deepestDueTierIndex(applicable, timeToManeuver)
        if (deepest == -1) return

        val isFinalTier = deepest == AnnouncementTiming.FINAL_TIER_INDEX
        val activelyCornering = AnnouncementTiming.isActivelyCornering(headingRateDegPerSec, speedMps)
        if (activelyCornering && !isFinalTier) {
            val deferredSince = corneringDeferredSinceMillis ?: nowMillis.also {
                corneringDeferredSinceMillis = it
            }
            if (nowMillis - deferredSince < AnnouncementTiming.ACTIVE_CORNER_DEFER_CAP_MILLIS) return
        }
        corneringDeferredSinceMillis = null

        if (currentRunIsLockout) {
            lockoutAnnounced = true
            emit(
                VoiceAnnouncement(
                    kind = AnnouncementKind.CURVE_WARNING,
                    maneuver = CurveCombo.sharpestIn(route.instructions, currentRun),
                    isFinal = isFinalTier,
                    comboCount = currentRun.size,
                ),
                distanceFromStart,
            )
            return
        }

        if (announcedTiers[deepest]) return
        for (i in 0..deepest) announcedTiers[i] = true

        val secondManeuver = if (isFinalTier && currentRun.size == 2) {
            route.instructions[currentRun[1]].maneuver
        } else {
            null
        }

        emit(
            VoiceAnnouncement(
                kind = AnnouncementKind.MANEUVER,
                maneuver = current.maneuver,
                distanceMeters = distanceToManeuver.roundToInt(),
                roundaboutExit = current.roundaboutExit,
                isFinal = isFinalTier,
                secondManeuver = secondManeuver,
            ),
            distanceFromStart,
        )
    }

    /**
     * "Dem Straßenverlauf 12 Kilometer folgen" - a rider who has heard nothing
     * for a long stretch should not have to check the screen to know the app
     * is still tracking them. Fires once per [AnnouncementTiming.FREE_RIDE_METERS]
     * of silence, and defers to the same live-cornering check as everything
     * else (there is even less reason to interrupt a bend for a reassurance).
     */
    private fun maybeAnnounceFreeRide(
        current: NavigationInstruction?,
        distanceFromStart: Double,
        headingRateDegPerSec: Double,
        speedMps: Double,
    ) {
        if (current == null) return
        if (distanceFromStart - lastSpokenAtDistance < AnnouncementTiming.FREE_RIDE_METERS) return
        val distanceToNext = current.distanceFromStart - distanceFromStart
        if (distanceToNext < AnnouncementTiming.FREE_RIDE_MIN_LEAD_METERS) return
        if (AnnouncementTiming.isActivelyCornering(headingRateDegPerSec, speedMps)) return

        val km = (distanceToNext / 1000.0).roundToInt().coerceAtLeast(1)
        emit(VoiceAnnouncement(kind = AnnouncementKind.FREE_RIDE, freeRideKm = km), distanceFromStart)
    }

    private fun emit(announcement: VoiceAnnouncement, atDistanceFromStart: Double) {
        lastSpokenAtDistance = atDistanceFromStart
        _announcements.tryEmit(announcement)
    }

    private fun emitArrival(distanceFromStart: Double) {
        if (arrivalAnnounced) return
        arrivalAnnounced = true
        emit(VoiceAnnouncement(kind = AnnouncementKind.ARRIVAL, isFinal = true), distanceFromStart)
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
        /** Number of tiers tracked per instruction: EARLY, CONFIRM, FINAL. */
        const val TIER_COUNT = 3

        /** Cross-track distance that counts as "off route". */
        const val OFF_ROUTE_METERS = 35.0

        /** How many consecutive off-route fixes before a reroute is requested. */
        const val OFF_ROUTE_FIXES = 3

        /** Metres past a manoeuver before the state machine steps on. */
        const val PASSED_METERS = 15.0

        /** Distance to the destination that counts as arrived. */
        const val ARRIVAL_RADIUS_M = 25.0
    }
}
