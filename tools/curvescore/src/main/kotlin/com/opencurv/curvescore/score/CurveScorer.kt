package com.opencurv.curvescore.score

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.geom.TurnEvent
import com.opencurv.curvescore.geom.TurnVertex
import com.opencurv.curvescore.geom.analyseTurns
import com.opencurv.curvescore.geom.cumulativeLength
import com.opencurv.curvescore.geom.douglasPeuckerIndices
import com.opencurv.curvescore.geom.minStepIndices
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmWay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The per-way result: the level, the raw value, and every ingredient that produced it. */
data class WayScore(
    val wayId: Long,
    val level: Int,
    val raw01: Double,
    val continuous: Double,
    val lengthM: Double,
    val confidence: Double,
    val terms: Map<String, Double>,
    val penalties: Map<String, Double>,
    val stats: Map<String, Double>,
)

/**
 * The OpenCurv curve score.
 *
 * ```
 *  raw = clamp01( Attract * Penalty )
 *
 *  Attract = 0.50*Density + 0.12*Engagement + 0.12*SCurves
 *          + 0.05*Rhythm  + 0.07*Terrain    + 0.08*Scenery + 0.06*RoadClass
 *
 *  Penalty = P_corner * P_settlement * P_surface * P_traffic
 *
 *  level   = floor(raw * levels), clamped to levels-1        (levels = 16)
 * ```
 *
 * The split into an additive attractiveness and a multiplicative penalty is the
 * important structural choice. Bonuses add up because they are independent
 * reasons to like a road. Penalties multiply because they *devalue what is
 * there*: the brief's "zigzag through a village devalues its curves" is exactly
 * a factor, not a subtraction - a curvy road through a housing estate must lose
 * most of its curve value, while a straight road through the same estate has
 * nothing to lose and stays near zero either way.
 *
 * Everything except the way-constant surface and road-class factors is computed
 * on sliding 1000 m windows over the corridor and averaged back onto the way -
 * see [ScoreConfig.WINDOW_NOTE].
 */
class CurveScorer(
    private val data: OsmData,
    private val cfg: ScoreConfig = ScoreConfig(),
    private val environment: Environment = Environment.EMPTY,
    plane: LocalPlane? = null,
) {
    private val plane: LocalPlane = plane ?: data.centre().let { LocalPlane(it.first, it.second) }
    private val corridors = CorridorBuilder(data, this.plane, cfg)

    val scoredWays: List<OsmWay> get() = corridors.roadWays

    fun scoreAll(parallel: Boolean = true): List<WayScore> {
        val idx = corridors.roadWays.indices
        val stream = if (parallel) idx.toList().parallelStream() else idx.toList().stream()
        return stream.map { scoreWay(it) }.filter { it != null }.map { it!! }.toList()
    }

    fun scoreWay(wayIndex: Int): WayScore? {
        val way = corridors.roadWays[wayIndex]
        val corridor = corridors.build(wayIndex) ?: return null
        return score(way, corridor)
    }

    /** Scores a single way given its corridor. Split out so tests can feed synthetic corridors. */
    fun score(way: OsmWay, corridor: Corridor): WayScore? {
        val rawPts = corridor.points.map { it.pt }
        if (rawPts.size < 2) return null

        // ---- 1. normalise the sampling, keeping the sharp vertices ------------
        val keep = BooleanArray(rawPts.size)
        val decimated = decimateKeepingIndices(rawPts, corridor, keep)
        val pts = decimated.pts
        val sourceIndex = decimated.sourceIndex
        if (pts.size < 2) return null

        val cumS = cumulativeLength(pts)
        val totalS = cumS[cumS.size - 1]
        val wayFromS = cumS[decimated.wayFrom]
        val wayToS = cumS[decimated.wayTo]
        val wayLengthM = wayToS - wayFromS
        if (wayLengthM <= 0.0) return null

        // ---- 2. pure geometry -------------------------------------------------
        val analysis = analyseTurns(pts, cfg.noiseDeg, cfg.minEventDeg, cfg.cornerDeg)
        val engagementProfile = Terms.engagementProfile(analysis.events, totalS, cfg)

        // ---- 3. per-corridor-point context -----------------------------------
        val env = DoubleArray(pts.size)
        val builtUp = DoubleArray(pts.size)
        val stopW = DoubleArray(pts.size)
        for (i in pts.indices) {
            val s = environment.sample(pts[i])
            env[i] = s.scenery
            builtUp[i] = s.settlement
            val tags = corridor.points[sourceIndex[i]].nodeTags
            if (tags != null) stopW[i] = RoadTags.stopWeight(tags)
        }
        val ele = smoothedElevation(corridor, sourceIndex, cumS)

        // ---- 4. way-constant factors -----------------------------------------
        val (surfaceFactor, surfaceConf) = RoadTags.surfaceFactor(way.tags, cfg)
        val roadClass = RoadTags.classBase(way.tags)
        val wayStops = RoadTags.wayStopWeight(way.tags)
        val forbidden = RoadTags.isForbidden(way.tags)
        val (speed, speedGuessed) = RoadTags.maxspeedKmh(way.tags)

        // ---- 5. slide the window ---------------------------------------------
        val half = cfg.windowM / 2.0
        val centres = ArrayList<Double>()
        var c = wayFromS
        while (c < wayToS) { centres.add(c); c += cfg.windowStepM }
        centres.add(wayToS)

        var accRaw = 0.0
        var accDensity = 0.0; var accEng = 0.0; var accAlt = 0.0; var accRhy = 0.0
        var accGrad = 0.0; var accScen = 0.0
        var accPCorner = 0.0; var accPSettle = 0.0; var accPTraffic = 0.0
        var accCurvature = 0.0; var accCorner = 0.0
        for (cs in centres) {
            val from = max(0.0, cs - half)
            val to = min(totalS, cs + half)
            val len = max(1.0, to - from)

            val wv = analysis.vertices.filter { it.sM in from..to }
            val we = analysis.events.filter { it.midS in from..to }
            val rhyFrom = max(0.0, cs - cfg.rhythmWindowM / 2.0)
            val rhyTo = min(totalS, cs + cfg.rhythmWindowM / 2.0)
            val rhyEvents = analysis.events.filter { it.midS in rhyFrom..rhyTo }

            val density = Terms.density(wv, len, cfg)
            val eng = Terms.engagement(engagementProfile, from, to, cfg)
            val alt = Terms.alternation(we, cfg)
            val rhy = Terms.rhythm(rhyEvents, cfg)
            val grad = Terms.gradient(meanAbsGradient(ele, cumS, from, to), cfg)
            val scen = meanOverRange(env, cumS, from, to, 0.5)
            val settleFrac = meanOverRange(builtUp, cumS, from, to, 0.0)

            var cornerTurn = 0.0
            for (e in we) if (e.isCorner) cornerTurn += e.absTurnRad
            var stops = sumOverRange(stopW, cumS, from, to) + wayStops * (len / 1000.0)
            if (speed != null && speed <= 30) stops += 0.5 * (len / 1000.0)

            val pCorner = Terms.cornerPenalty(cornerTurn, len, cfg)
            // A residential/living-street class is itself evidence of a
            // settlement even where nobody drew a landuse polygon.
            val classSettle = when (way.tags["highway"]) {
                "residential", "living_street" -> 1.0
                "service" -> 0.7
                else -> 0.0
            }
            val speedSettle = if (speed != null && speed <= 30) 1.0 else 0.0
            val settle = maxOf(settleFrac, classSettle, speedSettle)
            val pSettle = Terms.settlementPenalty(settle, cfg)
            val pTraffic = Terms.trafficPenalty(stops, len, cfg)

            val attract = cfg.wDensity * density +
                cfg.wEngagement * eng +
                cfg.wAlternation * alt +
                cfg.wRhythm * rhy +
                cfg.wGradient * grad +
                cfg.wScenery * scen +
                cfg.wRoadClass * roadClass
            val raw = attract * pCorner * pSettle * pTraffic * surfaceFactor

            accRaw += raw
            accDensity += density; accEng += eng; accAlt += alt; accRhy += rhy
            accGrad += grad; accScen += scen
            accPCorner += pCorner; accPSettle += pSettle; accPTraffic += pTraffic
            var curvature = 0.0
            for (v in wv) if (!v.isCorner) curvature += abs(v.deflectionRad)
            accCurvature += curvature / len * 1000.0
            accCorner += cornerTurn / (Math.PI / 2.0) / (len / 1000.0)
        }
        val n = centres.size.toDouble()
        val raw01 = if (forbidden) 0.0 else max(0.0, min(1.0, accRaw / n))

        // Confidence is about the *inputs*, not the verdict: it says how much
        // of the score rests on tags that were actually there. A missing
        // maxspeed costs little because the score barely uses it; a missing
        // surface on a track costs a lot, because the surface factor is a
        // multiplier and the prior for a track is a coin flip.
        val confidence = min(1.0, surfaceConf * (if (speedGuessed) 0.95 else 1.0) *
            (if (ele == null) 0.95 else 1.0))

        return WayScore(
            wayId = way.id,
            level = Terms.quantise(raw01, cfg.levels),
            raw01 = raw01,
            continuous = raw01 * (cfg.levels - 1),
            lengthM = wayLengthM,
            confidence = confidence,
            terms = linkedMapOf(
                "density" to accDensity / n,
                "engagement" to accEng / n,
                "alternation" to accAlt / n,
                "rhythm" to accRhy / n,
                "gradient" to accGrad / n,
                "scenery" to accScen / n,
                "roadClass" to roadClass,
            ),
            penalties = linkedMapOf(
                "corner" to accPCorner / n,
                "settlement" to accPSettle / n,
                "traffic" to accPTraffic / n,
                "surface" to surfaceFactor,
            ),
            stats = linkedMapOf(
                "curveCount" to analysis.events.count { !it.isCorner && it.midS in wayFromS..wayToS }.toDouble(),
                "cornerCount" to analysis.events.count { it.isCorner && it.midS in wayFromS..wayToS }.toDouble(),
                "minRadiusM" to (analysis.events.filter { !it.isCorner && it.midS in wayFromS..wayToS }
                    .minOfOrNull { it.radiusM } ?: 0.0),
                "curvatureDegPerKm" to accCurvature / n * 180.0 / Math.PI,
                "cornersPerKm" to accCorner / n,
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private class Decimated(
        val pts: List<Pt>,
        val sourceIndex: IntArray,
        val wayFrom: Int,
        val wayTo: Int,
    )

    /**
     * Runs the geometry pre-filter over the corridor while remembering which
     * original point each kept point came from (needed to read node tags and
     * elevations back) and where the scored way starts and ends afterwards.
     */
    private fun decimateKeepingIndices(rawPts: List<Pt>, corridor: Corridor, scratch: BooleanArray): Decimated {
        // The way's own endpoints must survive so the projection back is exact.
        val forced = setOf(corridor.wayFromIndex, corridor.wayToIndex, 0, rawPts.size - 1)
        val dp = com.opencurv.curvescore.geom.douglasPeuckerIndices(rawPts, cfg.simplifyEpsilonM, forced)
        val src = com.opencurv.curvescore.geom.minStepIndices(rawPts, dp, cfg.minStepM, cfg.cornerDeg, forced)
        val pts = src.map { rawPts[it] }
        val wayFrom = src.indexOfFirst { it >= corridor.wayFromIndex }.coerceAtLeast(0)
        var wayTo = src.indexOfLast { it <= corridor.wayToIndex }
        if (wayTo <= wayFrom) wayTo = min(src.size - 1, wayFrom + 1)
        return Decimated(pts, src, wayFrom, wayTo)
    }

    /**
     * Elevation per kept point, low-pass filtered over
     * [ScoreConfig.gradientSmoothM]. Returns null when no point has one - and
     * "no elevation" then means the terrain term is 0, never a penalty.
     */
    private fun smoothedElevation(corridor: Corridor, sourceIndex: IntArray, cumS: DoubleArray): DoubleArray? {
        var any = false
        val raw = DoubleArray(sourceIndex.size)
        val has = BooleanArray(sourceIndex.size)
        for (i in sourceIndex.indices) {
            val e = corridor.points[sourceIndex[i]].eleM
            if (e != null) { raw[i] = e; has[i] = true; any = true }
        }
        if (!any) return null
        // Fill gaps by nearest known value, then box-filter over the smoothing length.
        var lastKnown = -1
        for (i in raw.indices) {
            if (has[i]) { lastKnown = i; continue }
            raw[i] = if (lastKnown >= 0) raw[lastKnown] else raw.first { true }
        }
        val out = DoubleArray(raw.size)
        val halfWin = cfg.gradientSmoothM / 2.0
        var lo = 0
        var hi = 0
        for (i in raw.indices) {
            while (lo < i && cumS[i] - cumS[lo] > halfWin) lo++
            while (hi < raw.size - 1 && cumS[hi + 1] - cumS[i] <= halfWin) hi++
            var acc = 0.0
            for (k in lo..hi) acc += raw[k]
            out[i] = acc / (hi - lo + 1)
        }
        return out
    }

    private fun meanAbsGradient(ele: DoubleArray?, cumS: DoubleArray, from: Double, to: Double): Double? {
        if (ele == null) return null
        var climb = 0.0
        var run = 0.0
        for (i in 0 until ele.size - 1) {
            if (cumS[i] < from || cumS[i] > to) continue
            val d = cumS[i + 1] - cumS[i]
            if (d <= 0.0) continue
            climb += abs(ele[i + 1] - ele[i])
            run += d
        }
        return if (run <= 0.0) null else climb / run
    }

    private fun meanOverRange(v: DoubleArray, cumS: DoubleArray, from: Double, to: Double, fallback: Double): Double {
        var acc = 0.0
        var n = 0
        for (i in v.indices) {
            if (cumS[i] < from || cumS[i] > to) continue
            acc += v[i]; n++
        }
        return if (n == 0) fallback else acc / n
    }

    private fun sumOverRange(v: DoubleArray, cumS: DoubleArray, from: Double, to: Double): Double {
        var acc = 0.0
        for (i in v.indices) {
            if (cumS[i] < from || cumS[i] > to) continue
            acc += v[i]
        }
        return acc
    }
}

/**
 * Convenience: run the same geometry pipeline on an isolated polyline (no
 * corridor, no tags). Used by tests and by anyone who just wants the numbers.
 */
fun analysePolyline(pts: List<Pt>, cfg: ScoreConfig = ScoreConfig()): Pair<List<TurnVertex>, List<TurnEvent>> {
    val dp = douglasPeuckerIndices(pts, cfg.simplifyEpsilonM)
    val idx = minStepIndices(pts, dp, cfg.minStepM, cfg.cornerDeg, emptySet())
    val a = analyseTurns(idx.map { pts[it] }, cfg.noiseDeg, cfg.minEventDeg, cfg.cornerDeg)
    return a.vertices to a.events
}
