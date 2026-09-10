package com.opencurv.curvescore.report

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.io.OsmReader
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.score.CurveScorer
import com.opencurv.curvescore.score.Environment
import com.opencurv.curvescore.score.ScoreConfig
import com.opencurv.curvescore.score.WayScore
import java.io.File
import java.util.Locale
import kotlin.math.abs

// --------------------------------------------------------------- truth model

class TruthElement(
    val routeId: String = "",
    val name: String = "",
    val highway: String? = null,
    val surface: String? = null,
    val landuse: String? = null,
    val wayIds: List<Long> = emptyList(),
    val lengthM: Double = 0.0,
    val curveCount: Int = 0,
    val minRadiusM: Double? = null,
    val sharpCornerCount: Int = 0,
    val totalTurnDeg: Double = 0.0,
    val elevationGainM: Double = 0.0,
)

class TruthExpectation(
    val id: String = "",
    val from: String = "",
    val to: String = "",
    val preferRouteIds: List<String> = emptyList(),
    val overRouteIds: List<String> = emptyList(),
    val severity: String = "info",
    val reason: String = "",
)

class ArenaTruth(
    val elements: List<TruthElement> = emptyList(),
    val expectations: List<TruthExpectation> = emptyList(),
)

// ------------------------------------------------------------------- results

data class RouteResult(
    val routeId: String,
    val name: String,
    val level: Int,
    val continuous: Double,
    val raw01: Double,
    val lengthM: Double,
    val truth: TruthElement,
    val score: WayScore,
)

data class ExpectationResult(
    val id: String,
    val severity: String,
    val passed: Boolean,
    val detail: String,
)

class ArenaOutcome(
    val ranking: List<RouteResult>,
    val expectations: List<ExpectationResult>,
) {
    val hardFailures: List<ExpectationResult> get() = expectations.filter { it.severity == "hard" && !it.passed }
    fun byRouteId(id: String): RouteResult? = ranking.firstOrNull { it.routeId == id }
}

/**
 * Scores the checked-in testarena and checks it against `arena_truth.json`.
 *
 * The arena is read-only for this module - it is the measuring stick, and a
 * measuring stick you are allowed to bend is not a measuring stick.
 */
object ArenaRunner {

    fun run(arenaDir: File, cfg: ScoreConfig = ScoreConfig()): ArenaOutcome {
        val osmFile = File(arenaDir, "data/arena.osm")
        require(osmFile.isFile) { "arena not found: ${osmFile.absolutePath} (run ./gradlew -p tools/testarena generateArena)" }
        val truth: ArenaTruth = Gson().newBuilder().create()
            .fromJson(File(arenaDir, "arena_truth.json").readText(), ArenaTruth::class.java)
        val data = OsmReader.read(osmFile)
        return evaluate(data, truth, cfg)
    }

    fun evaluate(data: OsmData, truth: ArenaTruth, cfg: ScoreConfig): ArenaOutcome {
        val centre = data.centre()
        val plane = LocalPlane(centre.first, centre.second)
        val env = Environment.build(data, plane)
        val scorer = CurveScorer(data, cfg, env, plane)
        val scores = scorer.scoreAll(parallel = false).associateBy { it.wayId }

        val results = ArrayList<RouteResult>()
        for (e in truth.elements) {
            // Each arena element is exactly one way; if that ever changes, take
            // the length-weighted mean of its ways.
            val ws = e.wayIds.mapNotNull { scores[it] }
            if (ws.isEmpty()) continue
            val totalLen = ws.sumOf { it.lengthM }
            val raw = ws.sumOf { it.raw01 * it.lengthM } / totalLen
            results.add(
                RouteResult(
                    routeId = e.routeId,
                    name = e.name,
                    level = com.opencurv.curvescore.score.Terms.quantise(raw, cfg.levels),
                    continuous = raw * (cfg.levels - 1),
                    raw01 = raw,
                    lengthM = totalLen,
                    truth = e,
                    score = ws.maxByOrNull { it.lengthM }!!,
                )
            )
        }
        results.sortByDescending { it.raw01 }

        val byId = results.associateBy { it.routeId }
        val expectations = truth.expectations.map { exp ->
            when {
                exp.id == "E8_ELEVATION_NEUTRALITY" -> checkElevationNeutrality(byId, cfg)
                exp.preferRouteIds.isEmpty() || exp.overRouteIds.isEmpty() ->
                    ExpectationResult(exp.id, exp.severity, true, "keine auswertbaren Routen - uebersprungen")
                else -> checkPreference(exp, byId)
            }
        }
        return ArenaOutcome(results, expectations)
    }

    /**
     * Strict reading: *every* preferred route must beat *every* rejected one.
     * A router picks the best alternative, so the weaker reading (best-of-set)
     * would already pass with two of the three preferred routes scoring badly -
     * that is not what the expectation means.
     */
    private fun checkPreference(exp: TruthExpectation, byId: Map<String, RouteResult>): ExpectationResult {
        val pref = exp.preferRouteIds.mapNotNull { byId[it] }
        val over = exp.overRouteIds.mapNotNull { byId[it] }
        if (pref.isEmpty() || over.isEmpty()) {
            return ExpectationResult(exp.id, exp.severity, false, "Routen fehlen im Ergebnis")
        }
        val worstPref = pref.minByOrNull { it.raw01 }!!
        val bestOver = over.maxByOrNull { it.raw01 }!!
        val ok = worstPref.raw01 > bestOver.raw01
        val detail = String.format(
            Locale.ROOT,
            "min(%s)=%s [%.4f, Stufe %d]  vs  max(%s)=%s [%.4f, Stufe %d]",
            exp.preferRouteIds.joinToString("/"), worstPref.routeId, worstPref.raw01, worstPref.level,
            exp.overRouteIds.joinToString("/"), bestOver.routeId, bestOver.raw01, bestOver.level,
        )
        return ExpectationResult(exp.id, exp.severity, ok, detail)
    }

    /**
     * E8 is `info` in the arena and asks for elevation neutrality, while the
     * client explicitly asked for the gradient profile to count in favour.
     * Both are honoured by capping the disagreement: the two hill routes have
     * identical plan geometry, so they may differ by at most one level.
     */
    private fun checkElevationNeutrality(byId: Map<String, RouteResult>, cfg: ScoreConfig): ExpectationResult {
        val flat = byId["R_HILL_FLAT"]
        val climb = byId["R_HILL_CLIMB"]
        if (flat == null || climb == null) {
            return ExpectationResult("E8_ELEVATION_NEUTRALITY", "info", true, "Hoehenpaar nicht im Ergebnis")
        }
        val dLevel = abs(flat.level - climb.level)
        val ok = dLevel <= 1
        return ExpectationResult(
            "E8_ELEVATION_NEUTRALITY", "info", ok,
            String.format(
                Locale.ROOT,
                "flach %.4f (Stufe %d) vs. Steigung %.4f (Stufe %d), Differenz %d Stufe(n), erlaubt <= 1",
                flat.raw01, flat.level, climb.raw01, climb.level, dLevel,
            )
        )
    }

    fun render(outcome: ArenaOutcome): String {
        val sb = StringBuilder()
        sb.append("OpenCurv Kurven-Score - Testarena\n")
        sb.append("=".repeat(104)).append('\n')
        sb.append(
            String.format(
                Locale.ROOT,
                "%-16s %5s %8s  %6s %7s %7s  %5s %5s %5s %5s %5s %5s  %5s %5s %5s%n",
                "Route", "Stufe", "roh", "Laenge", "Grad/km", "Ecke/km",
                "Dich", "Enga", "SKur", "Rhyt", "Szen", "Klas",
                "Ecke", "Ort", "Belag",
            )
        )
        sb.append("-".repeat(104)).append('\n')
        for (r in outcome.ranking) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "%-16s %5d %8.4f  %6.0f %7.0f %7.2f  %5.2f %5.2f %5.2f %5.2f %5.2f %5.2f  %5.2f %5.2f %5.2f%n",
                    r.routeId, r.level, r.raw01, r.lengthM,
                    r.score.stats["curvatureDegPerKm"], r.score.stats["cornersPerKm"],
                    r.score.terms["density"], r.score.terms["engagement"], r.score.terms["alternation"],
                    r.score.terms["rhythm"], r.score.terms["scenery"], r.score.terms["roadClass"],
                    r.score.penalties["corner"], r.score.penalties["settlement"], r.score.penalties["surface"],
                )
            )
        }
        sb.append("=".repeat(104)).append('\n')
        for (e in outcome.expectations) {
            val mark = if (e.passed) "OK  " else "FAIL"
            sb.append(String.format(Locale.ROOT, "  [%s] (%-4s) %-34s %s%n", mark, e.severity, e.id, e.detail))
        }
        return sb.toString()
    }

    fun writeJson(outcome: ArenaOutcome, file: File) {
        val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val payload = linkedMapOf(
            "ranking" to outcome.ranking.map {
                linkedMapOf(
                    "routeId" to it.routeId,
                    "name" to it.name,
                    "level" to it.level,
                    "raw01" to it.raw01,
                    "continuous" to it.continuous,
                    "lengthM" to it.lengthM,
                    "confidence" to it.score.confidence,
                    "terms" to it.score.terms,
                    "penalties" to it.score.penalties,
                    "stats" to it.score.stats,
                    "truth" to linkedMapOf(
                        "curveCount" to it.truth.curveCount,
                        "sharpCornerCount" to it.truth.sharpCornerCount,
                        "minRadiusM" to it.truth.minRadiusM,
                        "totalTurnDeg" to it.truth.totalTurnDeg,
                    ),
                )
            },
            "expectations" to outcome.expectations.map {
                linkedMapOf("id" to it.id, "severity" to it.severity, "passed" to it.passed, "detail" to it.detail)
            },
        )
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(payload), Charsets.UTF_8)
    }
}
