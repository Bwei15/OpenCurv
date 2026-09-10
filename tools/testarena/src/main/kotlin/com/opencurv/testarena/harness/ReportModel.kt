package com.opencurv.testarena.harness

data class RouteIdShare(
    val routeId: String,
    val name: String?,
    val matchedLengthM: Double,
    val shareOfTotal: Double,
)

data class ExpectationResult(
    val id: String,
    val from: String,
    val to: String,
    val severity: String,
    /** "met" | "violated" | "not_applicable" | "info" */
    val status: String,
    val preferRouteIds: List<String>,
    val overRouteIds: List<String>,
    val preferShare: Double,
    val overShare: Double,
    val reason: String,
)

data class EvaluationReport(
    val routeFile: String,
    val pointCount: Int,
    val totalLengthM: Double,
    val unmatchedLengthM: Double,
    val unmatchedSharePct: Double,
    val curvinessDegPerKm: Double,
    val townSharePct: Double,
    val gravelSharePct: Double,
    val motorwaySharePct: Double,
    val routeIdShares: List<RouteIdShare>,
    val expectationResults: List<ExpectationResult>,
    val hardViolations: Int,
    val softViolations: Int,
) {
    val ok: Boolean get() = hardViolations == 0

    fun toConsoleText(): String {
        val sb = StringBuilder()
        sb.appendLine("OpenCurv Testarena - Bewertungsbericht")
        sb.appendLine("=".repeat(60))
        sb.appendLine("Route:              $routeFile ($pointCount Punkte)")
        sb.appendLine("Gesamtlänge:        ${"%.1f".format(totalLengthM)} m")
        sb.appendLine("Nicht zugeordnet:   ${"%.1f".format(unmatchedLengthM)} m (${"%.1f".format(unmatchedSharePct)} %)")
        sb.appendLine("Kurvigkeit:         ${"%.1f".format(curvinessDegPerKm)} °/km")
        sb.appendLine("Ortsdurchfahrt:     ${"%.1f".format(townSharePct)} %")
        sb.appendLine("Schotteranteil:     ${"%.1f".format(gravelSharePct)} %")
        sb.appendLine("Autobahnanteil:     ${"%.1f".format(motorwaySharePct)} %")
        sb.appendLine()
        sb.appendLine("Benutzte Arena-Elemente:")
        if (routeIdShares.isEmpty()) {
            sb.appendLine("  (keine erkannt - Route liegt außerhalb der Snap-Toleranz zur Arena)")
        }
        for (s in routeIdShares.sortedByDescending { it.shareOfTotal }) {
            sb.appendLine("  ${s.routeId.padEnd(18)} ${"%6.1f".format(s.shareOfTotal * 100)} %  (${s.name ?: ""})")
        }
        sb.appendLine()
        sb.appendLine("Erwartungen:")
        for (r in expectationResults) {
            val mark = when (r.status) {
                "met" -> "OK  "
                "violated" -> "FAIL"
                "info" -> "INFO"
                else -> "n/a "
            }
            sb.appendLine("  [$mark] (${r.severity}) ${r.id}: ${r.from} -> ${r.to}")
            sb.appendLine("         bevorzugt ${r.preferRouteIds} (${"%.1f".format(r.preferShare * 100)}%)" +
                " vs. ${r.overRouteIds} (${"%.1f".format(r.overShare * 100)}%)")
            sb.appendLine("         ${r.reason}")
        }
        sb.appendLine()
        sb.appendLine(if (ok) "ERGEBNIS: OK (keine harten Erwartungen verletzt)" else "ERGEBNIS: FEHLGESCHLAGEN ($hardViolations harte Verletzung(en))")
        return sb.toString()
    }
}
