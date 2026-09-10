package com.opencurv.curvescore.score

import com.opencurv.curvescore.geom.TurnEvent
import com.opencurv.curvescore.geom.TurnVertex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The individual scoring terms. Each one is a pure function of geometry (plus
 * config) and returns a value in [0,1], so every term can be tested on its own
 * and every weight reads as "share of the verdict".
 */
object Terms {

    /**
     * Radius quality q_R(R) in [0,1] - "how good a motorcycle curve is a curve
     * of this radius", independent of how long it is.
     *
     * Shape:
     * ```
     *  1.0            _______________
     *                /               \
     *                                 \___
     *  0.25    ____ /                      ----____
     *  0.0    /
     *         0    8      40        130         900   R [m]
     * ```
     * - 0 .. 8 m     : not a road curve at all (driveway, roundabout) - ramps to 0.25
     * - 8 .. 40 m    : hairpin territory - valuable but slow and physical, 0.25 -> 1.0
     * - 40 .. 130 m  : the sweet spot - 45-82 km/h at a comfortable 0.4 g
     * - above 130 m  : (130/R)^1.5 - the lean angle you can legally reach falls away
     *
     * A corner (radius 0 by classification) therefore scores exactly 0, which is
     * the mechanism that keeps the testarena's `R5_GRID` housing estate out of
     * the curve terms entirely.
     */
    fun radiusQuality(radiusM: Double, cfg: ScoreConfig): Double {
        if (radiusM.isNaN()) return 0.0
        if (radiusM <= 0.0) return 0.0
        if (radiusM.isInfinite()) return 0.0
        return when {
            radiusM < cfg.radiusFloorM ->
                cfg.radiusFloorQuality * (radiusM / cfg.radiusFloorM)
            radiusM < cfg.radiusSweetLoM -> {
                val t = (radiusM - cfg.radiusFloorM) / (cfg.radiusSweetLoM - cfg.radiusFloorM)
                cfg.radiusFloorQuality + (1.0 - cfg.radiusFloorQuality) * t
            }
            radiusM <= cfg.radiusSweetHiM -> 1.0
            else -> (cfg.radiusSweetHiM / radiusM).pow(cfg.radiusDecayExponent)
        }
    }

    /**
     * Curvature density term.
     *
     * rho = sum over the window's non-corner vertices of |deflection| * q_R(R),
     * divided by the window length -> radians of *quality-weighted* direction
     * change per metre. Then compressed against the Stelvio reference:
     *
     *     C = min(1, (rho / rho_ref) ^ 0.5)
     *
     * Weighting by turn angle rather than by arc length is deliberate. Arc
     * length rewards long gentle curves twice (a 25 deg bend of radius 250 m is
     * 109 m of arc; a 170 deg hairpin of radius 24 m is only 71 m), which would
     * make an ordinary rural road out-score a serpentine. What the rider spends
     * is steering input, and that is the turn angle.
     */
    fun density(vertices: List<TurnVertex>, windowLengthM: Double, cfg: ScoreConfig): Double {
        if (windowLengthM <= 0.0) return 0.0
        var acc = 0.0
        for (v in vertices) {
            if (v.isCorner) continue
            acc += abs(v.deflectionRad) * radiusQuality(v.radiusM, cfg)
        }
        val rho = acc / windowLengthM
        val ratio = rho / cfg.densityRefRadPerM
        return min(1.0, ratio.pow(cfg.densityCompression))
    }

    /**
     * Curve engagement term: the share of the window in which the rider is
     * inside a curve, or in its approach or its exit.
     *
     * This is the term that separates "four wide sweepers spread over five
     * kilometres" from "one surprise hairpin after a three-kilometre straight"
     * - two roads whose raw curvature density is nearly the same but which feel
     * nothing alike. It is computed from an engagement profile: inside a curve
     * the value is that curve's radius quality, and it ramps linearly to zero
     * over [ScoreConfig.engagementHaloM] on each side. Overlapping halos take
     * the maximum, so densely strung curves saturate at 1 instead of
     * double-counting.
     */
    fun engagementProfile(events: List<TurnEvent>, totalLengthM: Double, cfg: ScoreConfig): DoubleArray {
        val n = max(2, (totalLengthM / cfg.engagementSampleM).toInt() + 1)
        val prof = DoubleArray(n)
        for (e in events) {
            if (e.isCorner) continue
            // Presence, not quality: a hairpin is fully a curve even though the
            // density term rightly discounts it for being first-gear work.
            val q = min(1.0, radiusQuality(e.radiusM, cfg) / cfg.engagementFullQuality)
            if (q <= 0.0) continue
            val from = e.startS - cfg.engagementHaloM
            val to = e.endS + cfg.engagementHaloM
            var i = max(0, (from / cfg.engagementSampleM).toInt())
            val iEnd = min(n - 1, (to / cfg.engagementSampleM).toInt() + 1)
            while (i <= iEnd) {
                val s = i * cfg.engagementSampleM
                val d = when {
                    s < e.startS -> e.startS - s
                    s > e.endS -> s - e.endS
                    else -> 0.0
                }
                val ramp = if (d <= 0.0) 1.0 else max(0.0, 1.0 - d / cfg.engagementHaloM)
                val v = q * ramp
                if (v > prof[i]) prof[i] = v
                i++
            }
        }
        return prof
    }

    /** Mean of the engagement profile between two arc-length positions. */
    fun engagement(profile: DoubleArray, fromS: Double, toS: Double, cfg: ScoreConfig): Double {
        if (profile.isEmpty() || toS <= fromS) return 0.0
        val i0 = max(0, (fromS / cfg.engagementSampleM).toInt())
        val i1 = min(profile.size - 1, (toS / cfg.engagementSampleM).toInt())
        if (i1 < i0) return 0.0
        var acc = 0.0
        for (i in i0..i1) acc += profile[i]
        return acc / (i1 - i0 + 1)
    }

    /**
     * S-curve term: how often the next curve goes the *other* way, and how
     * tightly it is linked to the previous one.
     *
     * The brief calls this out explicitly and it is right to: reversing the
     * bike from one side to the other is the single most engaging thing a road
     * asks of a rider, and it is worth strictly more than the same curve twice
     * in the same direction. Formally, over consecutive quality curve events:
     *
     *     A = sum( w_pair * [signs differ] * exp(-gap / 300 m) ) / sum( w_pair )
     *
     * with w_pair = min(|turn_a|, |turn_b|) - a linked pair is only as strong as
     * its weaker half - and `gap` the straight between the two curves.
     * Corners are excluded: a left-right zigzag through a housing grid must not
     * earn an S-curve bonus.
     */
    fun alternation(events: List<TurnEvent>, cfg: ScoreConfig): Double {
        val good = events.filter { !it.isCorner && radiusQuality(it.radiusM, cfg) >= cfg.alternationMinQuality }
        if (good.size < 2) return 0.0
        var num = 0.0
        var den = 0.0
        for (i in 0 until good.size - 1) {
            val a = good[i]
            val b = good[i + 1]
            val w = min(a.absTurnRad, b.absTurnRad)
            if (w <= 0.0) continue
            den += w
            if (a.sign != b.sign) {
                val gap = max(0.0, b.startS - a.endS)
                num += w * exp(-gap / cfg.alternationLinkLengthM)
            }
        }
        return if (den <= 0.0) 0.0 else min(1.0, num / den)
    }

    /**
     * Rhythm term: are the curves evenly strung together, or is it one curve
     * and then nothing for two kilometres?
     *
     * Measured as 1 - CV of the gaps between consecutive curves, where CV is
     * the coefficient of variation (standard deviation over mean). Evenly
     * spaced curves give CV -> 0 and rhythm -> 1; one bunch and one long
     * straight gives a large CV and rhythm -> 0. Needs three curves (two gaps)
     * to mean anything; below that the term is 0, because a rhythm you cannot
     * yet hear is not a rhythm.
     *
     * This is the weakest-founded of the terms and carries the smallest weight
     * (0.05) for that reason - see "Wo dieser Score falsch liegt" in
     * 1.Doku/Kurven_Score.md.
     */
    fun rhythm(events: List<TurnEvent>, cfg: ScoreConfig): Double {
        val good = events.filter { !it.isCorner && radiusQuality(it.radiusM, cfg) >= cfg.alternationMinQuality }
        if (good.size < 3) return 0.0
        val gaps = ArrayList<Double>(good.size - 1)
        for (i in 0 until good.size - 1) gaps.add(max(1.0, good[i + 1].midS - good[i].midS))
        val mean = gaps.average()
        if (mean <= 0.0) return 0.0
        var v = 0.0
        for (g in gaps) v += (g - mean) * (g - mean)
        val sd = sqrt(v / gaps.size)
        return max(0.0, min(1.0, 1.0 - sd / mean))
    }

    /**
     * Terrain term from the elevation profile.
     *
     * 0 on the flat, ramping to 1 at 4 % mean absolute gradient, plateau to
     * 8 % (the classic alpine-pass gradient), then back to 0 at 15 % where a
     * road becomes a first-gear ramp. Bonus only - never negative - because
     * flat is dull, not bad.
     *
     * Objection on record (see the report): this term double-counts. Mountain
     * roads are already curvy, so the gradient mostly rewards what the density
     * term has rewarded already, and it makes the score depend on a DEM that
     * may not be there. It carries 0.06 for that reason, and it is 0 whenever
     * elevation data is missing, so its absence never penalises a road.
     */
    fun gradient(meanAbsGradient: Double?, cfg: ScoreConfig): Double {
        val g = meanAbsGradient ?: return 0.0
        val a = abs(g)
        return when {
            a <= 0.0 -> 0.0
            a < cfg.gradientPlateauLo -> a / cfg.gradientPlateauLo
            a <= cfg.gradientPlateauHi -> 1.0
            a >= cfg.gradientZeroAt -> 0.0
            else -> 1.0 - (a - cfg.gradientPlateauHi) / (cfg.gradientZeroAt - cfg.gradientPlateauHi)
        }
    }

    /**
     * Corner penalty, in (0,1].
     *
     * rate = weighted 90-deg-equivalent corners per kilometre;
     * P = 1 / (1 + (rate / 0.8)^2).
     *
     * This, together with radiusQuality(0) = 0, is what makes the testarena's
     * `R5_GRID` collapse: 8 right-angle corners over 4.5 km give a rate of
     * 1.8/km and a factor of 0.17, on top of a curve value that is already
     * zero because none of those 717 deg comes from anything with a radius.
     */
    fun cornerPenalty(cornerTurnRadSum: Double, windowLengthM: Double, cfg: ScoreConfig): Double {
        if (windowLengthM <= 0.0) return 1.0
        val equivalents = cornerTurnRadSum / (Math.PI / 2.0)
        val ratePerKm = equivalents / (windowLengthM / 1000.0)
        val x = ratePerKm / cfg.cornerRateHalfPerKm
        return 1.0 / (1.0 + x * x)
    }

    /** Built-up-area penalty, in (0,1]. Linear in the share of the window inside a settlement. */
    fun settlementPenalty(settlementFraction: Double, cfg: ScoreConfig): Double =
        1.0 - cfg.settlementMaxPenalty * max(0.0, min(1.0, settlementFraction))

    /** Interruption penalty (lights, roundabouts, level crossings, calming), in (0,1]. */
    fun trafficPenalty(weightedStops: Double, windowLengthM: Double, cfg: ScoreConfig): Double {
        if (windowLengthM <= 0.0) return 1.0
        val ratePerKm = weightedStops / (windowLengthM / 1000.0)
        return 1.0 / (1.0 + ratePerKm / cfg.trafficRateHalfPerKm)
    }

    /**
     * Quantises the raw [0,1] score into [0, levels-1] with equal-width bins.
     * Configurable level count; 16 by default.
     */
    fun quantise(raw01: Double, levels: Int): Int {
        val clamped = max(0.0, min(1.0, raw01))
        val l = (clamped * levels).toInt()
        return min(levels - 1, max(0, l))
    }
}
