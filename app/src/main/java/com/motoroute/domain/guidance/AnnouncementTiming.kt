package com.motoroute.domain.guidance

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
 */
object AnnouncementTiming {

    enum class Tier { EARLY, CONFIRM, FINAL }

    /** Heads-up, always given: the "Vorankündigung" in the brief. */
    const val EARLY_SECONDS = 15.0

    /** Middle "still on for that turn" call - only worth it at open-road speed. */
    const val CONFIRM_SECONDS = 7.0

    /** Last-chance cue right before the manoeuvre. */
    const val FINAL_SECONDS = 3.0

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
     * The tiers applicable at [speedMps], in firing order (EARLY, [CONFIRM],
     * FINAL) together with their fixed bookkeeping index. CONFIRM is left out
     * below [RURAL_SPEED_MPS] on purpose - it is the tier the brief only lists
     * for Landstraße.
     */
    fun applicableTiers(speedMps: Double): List<Pair<Int, Double>> = buildList {
        add(0 to EARLY_SECONDS)
        if (speedMps >= RURAL_SPEED_MPS) add(1 to CONFIRM_SECONDS)
        add(FINAL_TIER_INDEX to FINAL_SECONDS)
    }

    /**
     * The deepest (soonest) tier index whose threshold is already met, among
     * tiers not yet announced, or -1 if the manoeuvre is still further away
     * than the widest applicable tier.
     *
     * Thresholds are strictly descending (15 > 7 > 3), so the tiers whose
     * condition holds always form a prefix starting at EARLY - the loop can
     * simply walk forward and stop at the first one that does not hold yet.
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
