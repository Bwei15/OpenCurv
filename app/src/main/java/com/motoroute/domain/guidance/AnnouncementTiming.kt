package com.motoroute.domain.guidance

import kotlin.math.max

/**
 * When to say something, worked out from time-to-manoeuvre rather than a
 * fixed distance.
 *
 * A fixed metre trigger cannot work across the speed range a motorcycle
 * actually rides: 300 m of warning is 6 seconds at 180 km/h and a full minute
 * at 20 km/h through a village. Per `1.Doku/AI_README.md` §2.3 every tier here
 * is defined in seconds before the manoeuvre instead ("immer 15 Sekunden und
 * 3 Sekunden vor dem Manöver"), with a middle tier added once the rider is
 * moving fast enough that the extra confirmation the brief describes for
 * Landstraße actually buys something.
 *
 * ## Why the numbers grew (ride report, September 2026)
 *
 * The first version used a flat 15/7/3 s. On the road that lands too late, for
 * two reasons the plain "seconds before the manoeuvre" model ignores:
 *
 *  * **The words take time.** A Bluetooth helmet headset needs up to 1.5 s to
 *    open its audio path (see `1.Doku/Sprachausgabe.md` §3) and the sentence
 *    itself runs another 2-3 s. A tier nominally 3 s out therefore *finishes*
 *    at the junction, not before it. [SPEECH_LEAD_SECONDS] pays for that on
 *    every tier, so the nominal time is when the rider has finished hearing it.
 *  * **Fast riding needs disproportionately more warning, not proportionally
 *    more.** Reaction plus braking with a bike that still has to be stood up
 *    out of a lean does not scale linearly with speed, and the rider wants to
 *    plan the line, not just the turn. So each tier's lead is stretched by
 *    [speedStretch], strongest on the early heads-up and barely at all on the
 *    last-chance call - that call has to stay near the junction to mean "now".
 *
 * At 100 km/h the tiers now land at roughly 700 m / 260 m / 110 m (was
 * 420/196/84), which is what the brief asks for as the Landstraße cadence
 * (600-800 m Vorankündigung, 200 m Bestätigung); in town at 50 km/h they land
 * at roughly 250 m / - / 60 m.
 */
object AnnouncementTiming {

    enum class Tier { EARLY, CONFIRM, FINAL }

    /** Heads-up, always given: the "Vorankündigung" in the brief. */
    const val EARLY_SECONDS = 15.0

    /** Middle "still on for that turn" call - only worth it at open-road speed. */
    const val CONFIRM_SECONDS = 6.0

    /** Last-chance cue right before the manoeuvre. */
    const val FINAL_SECONDS = 2.0

    /**
     * Slack added to every tier for the headset waking up and the sentence
     * being spoken, so the tier's nominal time is when the rider has *heard*
     * the announcement rather than when the TTS engine started on it.
     */
    const val SPEECH_LEAD_SECONDS = 2.0

    /**
     * How much of the speed stretch ([speedStretch]) each tier takes.
     *
     * The early heads-up gets all of it - that is the call a fast rider wants
     * much sooner. The last-chance call gets almost none: stretched, it would
     * stop meaning "now".
     */
    private val TIER_STRETCH_WEIGHT = doubleArrayOf(1.0, 0.45, 0.1)

    /** Base lead per tier index, before [SPEECH_LEAD_SECONDS] and the stretch. */
    private val TIER_BASE_SECONDS = doubleArrayOf(EARLY_SECONDS, CONFIRM_SECONDS, FINAL_SECONDS)

    /**
     * Shortest distance each tier ever fires at, whatever the speed says.
     *
     * Stopped at a red light 200 m before the turn, the time model would put
     * the heads-up at 45 m; these floors keep an announcement useful at walking
     * pace and in stop-and-go traffic.
     */
    private val TIER_MIN_METERS = doubleArrayOf(180.0, 90.0, 35.0)

    /** Below this speed the stretch is 1.0 - town cadence needs no extra reach. */
    const val STRETCH_BASE_SPEED_MPS = 14.0 // ~50 km/h

    /** At and above this speed the stretch is at its maximum. */
    const val STRETCH_FULL_SPEED_MPS = 36.0 // ~130 km/h

    /** The stretch at [STRETCH_FULL_SPEED_MPS] and beyond. */
    const val MAX_SPEED_STRETCH = 1.55

    /**
     * Above this speed the ride is "Landstraße" in the brief's sense and gets
     * the extra confirmation tier; below it, town cadence (early + final only)
     * is enough and a third call would just be noise.
     */
    const val RURAL_SPEED_MPS = 15.0 // ~54 km/h

    /** Floor so a stopped or crawling bike never turns into a huge, useless time-to-go. */
    const val MIN_TIMING_SPEED_MPS = 2.5

    /** Heading-rate above which the rider is actively leaned into a bend right now. */
    const val ACTIVE_CORNER_DEG_PER_S = 18.0

    /** Below this speed a heading swing is GPS noise, not a real lean. */
    const val ACTIVE_CORNER_MIN_SPEED_MPS = 4.0

    /** Never withhold a due announcement longer than this just because of a live lean. */
    const val ACTIVE_CORNER_DEFER_CAP_MILLIS = 4_000L

    /** Silence this long without a word earns a "still on the right road" cue. */
    const val FREE_RIDE_METERS = 15_000.0

    /** Don't reassure if a real manoeuvre is about to be announced anyway. */
    const val FREE_RIDE_MIN_LEAD_METERS = 1_000.0

    /** Index of [Tier.FINAL] in tier-index bookkeeping (0=EARLY, 1=CONFIRM, 2=FINAL). */
    const val FINAL_TIER_INDEX = 2

    /** Time to the manoeuvre at the current (floored) speed, in seconds. */
    fun timeToManeuverSeconds(distanceMeters: Double, speedMps: Double): Double =
        distanceMeters / speedMps.coerceAtLeast(MIN_TIMING_SPEED_MPS)

    /**
     * How much further ahead every tier reaches at [speedMps]: 1.0 up to
     * [STRETCH_BASE_SPEED_MPS], rising linearly to [MAX_SPEED_STRETCH] at
     * [STRETCH_FULL_SPEED_MPS] and flat above it.
     */
    fun speedStretch(speedMps: Double): Double {
        if (speedMps <= STRETCH_BASE_SPEED_MPS) return 1.0
        if (speedMps >= STRETCH_FULL_SPEED_MPS) return MAX_SPEED_STRETCH
        val t = (speedMps - STRETCH_BASE_SPEED_MPS) / (STRETCH_FULL_SPEED_MPS - STRETCH_BASE_SPEED_MPS)
        return 1.0 + t * (MAX_SPEED_STRETCH - 1.0)
    }

    /**
     * The lead time for [tierIndex] at [speedMps], in seconds before the
     * manoeuvre - base plus speech slack, stretched by speed according to the
     * tier's own weight, and never below the tier's distance floor.
     */
    fun leadSecondsFor(tierIndex: Int, speedMps: Double): Double {
        val base = TIER_BASE_SECONDS[tierIndex] + SPEECH_LEAD_SECONDS
        val stretch = 1.0 + (speedStretch(speedMps) - 1.0) * TIER_STRETCH_WEIGHT[tierIndex]
        val fromTime = base * stretch
        val fromFloor = TIER_MIN_METERS[tierIndex] / speedMps.coerceAtLeast(MIN_TIMING_SPEED_MPS)
        return max(fromTime, fromFloor)
    }

    /** Where [tierIndex] fires, in metres before the manoeuvre, at [speedMps]. Handy for docs and tests. */
    fun triggerDistanceMeters(tierIndex: Int, speedMps: Double): Double =
        leadSecondsFor(tierIndex, speedMps) * speedMps.coerceAtLeast(MIN_TIMING_SPEED_MPS)

    /**
     * The tiers applicable at [speedMps], in firing order (EARLY, [CONFIRM],
     * FINAL) together with their fixed bookkeeping index and their lead time at
     * this speed. CONFIRM is left out below [RURAL_SPEED_MPS] on purpose - it
     * is the tier the brief only lists for Landstraße.
     */
    fun applicableTiers(speedMps: Double): List<Pair<Int, Double>> = buildList {
        add(0 to leadSecondsFor(0, speedMps))
        if (speedMps >= RURAL_SPEED_MPS) add(1 to leadSecondsFor(1, speedMps))
        add(FINAL_TIER_INDEX to leadSecondsFor(FINAL_TIER_INDEX, speedMps))
    }

    /**
     * The deepest (soonest) tier index whose threshold is already met, among
     * tiers not yet announced, or -1 if the manoeuvre is still further away
     * than the widest applicable tier.
     *
     * Thresholds are strictly descending (the tier weights above keep them so
     * at every speed), so the tiers whose condition holds always form a prefix
     * starting at EARLY - the loop can simply walk forward and stop at the
     * first one that does not hold yet.
     */
    fun deepestDueTierIndex(applicable: List<Pair<Int, Double>>, timeToManeuverSeconds: Double): Int {
        var deepest = -1
        for ((index, thresholdSeconds) in applicable) {
            if (timeToManeuverSeconds <= thresholdSeconds) deepest = index else break
        }
        return deepest
    }

    /** True when the live heading is swinging fast enough that the bike is mid-lean. */
    fun isActivelyCornering(headingRateDegPerSec: Double, speedMps: Double): Boolean =
        speedMps >= ACTIVE_CORNER_MIN_SPEED_MPS && headingRateDegPerSec >= ACTIVE_CORNER_DEG_PER_S
}
