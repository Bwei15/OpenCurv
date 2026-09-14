package com.motoroute.domain.cameras

/**
 * How often, and how far out, a speed camera is announced.
 *
 * The first version said one thing, once, the moment a camera came within a
 * kilometre - and said it as "Achtung, Blitzer, 70", which is a warning, a
 * number, and no indication of how far away any of it is. The ride report asked
 * for what a rider actually needs: several calls as the camera comes up, the
 * distance in each one, the posted limit spoken as a limit, and the whole thing
 * spaced by how fast the bike is going.
 *
 * The tiers are defined in **seconds to the camera**, for the same reason
 * [com.motoroute.domain.guidance.AnnouncementTiming] is: 1000 m is 36 seconds
 * at 100 km/h and a minute and a half through a village, and a warning that
 * early in town is noise a rider will switch off. The nominal distances the
 * report names - 1000, 500, 250 m - come out of these seconds at Landstraße
 * speed, which is where they were meant to apply.
 *
 * | speed | early | middle | last |
 * |---|---|---|---|
 * | 50 km/h | 500 m | 250 m | 125 m |
 * | 100 km/h | 1000 m | 500 m | 250 m |
 * | 130 km/h | 1300 m | 650 m | 325 m |
 *
 * Floors and caps keep both ends sane: below about 40 km/h the seconds would
 * put the first call closer than a rider can react, and above 150 km/h they
 * would put it past the range cameras are even loaded for.
 */
object CameraWarningTiming {

    /** Seconds before the camera at which each tier fires, widest first. */
    val TIER_SECONDS: DoubleArray = doubleArrayOf(36.0, 18.0, 9.0)

    /** Shortest distance each tier will ever fire at, in metres. */
    private val TIER_MIN_METERS = doubleArrayOf(320.0, 170.0, 90.0)

    /** Longest distance each tier will ever fire at, in metres. */
    private val TIER_MAX_METERS = doubleArrayOf(1400.0, 750.0, 400.0)

    /** Number of tiers, i.e. how many times one camera is announced per approach. */
    val TIER_COUNT: Int get() = TIER_SECONDS.size

    /** Index of the last-chance tier. */
    val LAST_TIER_INDEX: Int get() = TIER_SECONDS.size - 1

    /** Floor on the speed used for the seconds-to-metres conversion. */
    const val MIN_SPEED_MPS = 8.0

    /** Distance at which [tierIndex] fires, at [speedMps]. */
    fun triggerDistanceMeters(tierIndex: Int, speedMps: Double): Double {
        val fromTime = TIER_SECONDS[tierIndex] * speedMps.coerceAtLeast(MIN_SPEED_MPS)
        return fromTime.coerceIn(TIER_MIN_METERS[tierIndex], TIER_MAX_METERS[tierIndex])
    }

    /**
     * The deepest (closest) tier whose distance has been reached, or -1 when the
     * camera is still further out than the widest tier.
     *
     * Walks from wide to narrow and keeps the last one that holds, so crossing
     * two tiers between fixes - easy at 130 km/h - announces the nearer one
     * rather than firing both a second apart. Same rule, same reason, as
     * [com.motoroute.domain.guidance.AnnouncementTiming.deepestDueTierIndex].
     */
    fun deepestDueTierIndex(distanceMeters: Double, speedMps: Double): Int {
        var deepest = -1
        for (tier in TIER_SECONDS.indices) {
            if (distanceMeters <= triggerDistanceMeters(tier, speedMps)) deepest = tier else break
        }
        return deepest
    }

    /**
     * The radius cameras have to be searched within so the widest tier can
     * actually fire, with room for the fix that lands just outside it.
     */
    val SEARCH_RADIUS_METERS: Double get() = TIER_MAX_METERS[0] + 200.0
}
