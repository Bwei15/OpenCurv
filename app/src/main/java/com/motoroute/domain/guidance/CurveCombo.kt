package com.motoroute.domain.guidance

import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction

/**
 * Groups consecutive turn instructions that sit close enough in time to be
 * ridden as one connected sequence of bends - a switchback combination is
 * OpenCurv's whole reason to exist, and BRouter emits a separate voice-hint
 * instruction for every one of those apexes.
 *
 * Without this, the state machine treats each hairpin as its own unrelated
 * manoeuvre and runs the full early/confirm/final cascade for every one of
 * them, which is what produced the "kommt oft mehrfach" complaint on any road
 * this app is actually built to find: three or four instructions 60-150 m
 * apart each getting their own three-call build-up overlap into a wall of
 * "turn left ... turn right ... turn left" while the rider is still leaned
 * into the first one.
 *
 * There is no lean-angle sensor here - the phone has no IMU fusion wired into
 * navigation. What stands in for it is the route's own geometry, which the
 * app already has for free: how sharp the upcoming manoeuvres are
 * ([isSharp]) and how close together they sit in *time* at the speed the
 * rider is actually doing right now, not a fixed metre gap.
 */
object CurveCombo {

    /** Manoeuvres sharp enough on their own to be worth a dedicated warning. */
    private val SHARP = setOf(
        Maneuver.SHARP_LEFT,
        Maneuver.SHARP_RIGHT,
        Maneuver.HAIRPIN_LEFT,
        Maneuver.HAIRPIN_RIGHT,
    )

    fun isSharp(maneuver: Maneuver): Boolean = maneuver in SHARP

    /** How close two manoeuvres may sit in time and still count as one connected run. */
    const val LINK_SECONDS = 8.0

    /**
     * Looks ahead from [fromIndex] and returns the indices (including
     * [fromIndex]) of every turn instruction that belongs to the same run:
     * consecutive turns whose gap, ridden at [speedMps], takes less than
     * [linkSeconds]. A plain "continue" waypoint in between does not break the
     * chain, it is simply skipped - only arrival/off-route markers and a gap
     * that is actually long stop it.
     *
     * Returns an empty list when [fromIndex] itself is not a turn.
     */
    fun run(
        instructions: List<NavigationInstruction>,
        fromIndex: Int,
        speedMps: Double,
        linkSeconds: Double = LINK_SECONDS,
    ): List<Int> {
        if (fromIndex !in instructions.indices || !instructions[fromIndex].maneuver.isTurn) {
            return emptyList()
        }
        val effectiveSpeed = speedMps.coerceAtLeast(AnnouncementTiming.MIN_TIMING_SPEED_MPS)
        val result = mutableListOf(fromIndex)
        var anchor = fromIndex
        var i = fromIndex + 1
        while (i < instructions.size) {
            val candidate = instructions[i]
            if (!candidate.maneuver.isTurn) {
                // DESTINATION/OFF_ROUTE end the run; a CONTINUE waypoint is
                // just skipped over without breaking the chain.
                if (candidate.maneuver == Maneuver.DESTINATION || candidate.maneuver == Maneuver.OFF_ROUTE) {
                    break
                }
                i++
                continue
            }
            val gapSeconds =
                (candidate.distanceFromStart - instructions[anchor].distanceFromStart) / effectiveSpeed
            if (gapSeconds > linkSeconds) break
            result += i
            anchor = i
            i++
        }
        return result
    }

    /**
     * Whether [run] is tight/sharp enough that the voice should reduce to one
     * warning and go quiet through the rest of it, rather than announcing
     * every manoeuvre in it individually:
     *
     *  - three or more linked turns is always a lockout - naming each one
     *    would be more words than a rider can use while leaned over;
     *  - two linked turns is a lockout only if one of them is sharp enough
     *    that a plain "then immediately" hand-off is not warning enough;
     *  - a single sharp/hairpin turn on its own is a (trivial, one-member)
     *    lockout too: it gets the "Achtung, scharfe Rechtskehre" warning
     *    instead of the ordinary tiered cascade, and nothing else follows it.
     */
    fun isLockout(instructions: List<NavigationInstruction>, run: List<Int>): Boolean = when {
        run.size >= 3 -> true
        run.size == 2 -> run.any { isSharp(instructions[it].maneuver) }
        run.size == 1 -> isSharp(instructions[run.first()].maneuver)
        else -> false
    }

    /** The sharpest manoeuvre in [run] - what a combo warning should name. */
    fun sharpestIn(instructions: List<NavigationInstruction>, run: List<Int>): Maneuver {
        for (index in run) {
            val maneuver = instructions[index].maneuver
            if (isSharp(maneuver)) return maneuver
        }
        return instructions[run.first()].maneuver
    }
}
