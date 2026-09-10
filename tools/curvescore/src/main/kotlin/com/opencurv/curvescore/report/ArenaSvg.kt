package com.opencurv.curvescore.report

import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmWay
import com.opencurv.curvescore.score.ScoreConfig
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the arena with every route coloured by its score, so a human can check
 * in two seconds what the numbers claim: the serpentine has to be green and the
 * housing-estate grid has to be red.
 *
 * Three panels rather than one map. The arena's two comparison pairs sit eight
 * kilometres east of the ALPHA-OMEGA fan; drawing everything on one scale would
 * shrink the nine alternatives - the part that matters - into a thumbnail. Each
 * panel therefore gets its own extent, which is honest here because the panels
 * are not meant to be compared geographically, only route by route.
 */
object ArenaSvg {

    private const val W = 1640
    private const val H = 1000

    private class Panel(
        val x: Double, val y: Double, val w: Double, val h: Double,
        val title: String,
        val routeIds: Set<String>,
    )

    fun write(data: OsmData, outcome: ArenaOutcome, cfg: ScoreConfig, file: File) {
        val centre = data.centre()
        val plane = LocalPlane(centre.first, centre.second)

        val fanIds = setOf(
            "R1_HIGHWAY", "R2_SERPENTINE", "R3_FLOWING", "R4_S_CURVES", "R5_GRID",
            "R6_GRAVEL", "R7_MOTORWAY", "R8_JOG90", "R9_DOGLEG",
        )
        val panels = listOf(
            Panel(24.0, 56.0, 700.0, 916.0, "ALPHA → OMEGA: neun Alternativen", fanIds),
            Panel(744.0, 56.0, 300.0, 440.0, "Steigung (E8)", setOf("R_HILL_FLAT", "R_HILL_CLIMB")),
            Panel(744.0, 532.0, 300.0, 440.0, "Wald / Industrie (E9)", setOf("R_FOREST", "R_INDUSTRIAL")),
        )

        val routeOfWay = HashMap<Long, RouteResult>()
        for (r in outcome.ranking) for (id in r.truth.wayIds) routeOfWay[id] = r

        val sb = StringBuilder(1 shl 16)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$W" height="$H" viewBox="0 0 $W $H" font-family="Helvetica, Arial, sans-serif">""").append('\n')
        sb.append("""  <rect width="$W" height="$H" fill="#12161c"/>""").append('\n')
        sb.append(text(24.0, 34.0, "OpenCurv Kurven-Score – Testarena", 21.0, "#f2f5f9", weight = 700))
        sb.append(text(392.0, 34.0, "Farbe = Stufe 0..${cfg.levels - 1}", 13.0, "#8d97a5"))

        for (p in panels) sb.append(renderPanel(p, data, plane, routeOfWay, cfg))
        sb.append(renderLegend(1064.0, outcome, cfg))
        sb.append("</svg>").append('\n')

        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ panels

    private fun renderPanel(
        p: Panel,
        data: OsmData,
        plane: LocalPlane,
        routeOfWay: Map<Long, RouteResult>,
        cfg: ScoreConfig,
    ): String {
        val ways = data.ways.filter { w ->
            val r = routeOfWay[w.id]
            r != null && r.routeId in p.routeIds
        }
        if (ways.isEmpty()) return ""
        val geom = ways.associate { it.id to geometry(it, data, plane) }

        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (g in geom.values) for (q in g) {
            minX = min(minX, q.x); maxX = max(maxX, q.x)
            minY = min(minY, q.y); maxY = max(maxY, q.y)
        }
        val inset = 34.0
        val availW = p.w - 2 * inset
        val availH = p.h - 2 * inset - 22.0
        val scale = min(availW / max(1.0, maxX - minX), availH / max(1.0, maxY - minY))
        val offX = p.x + inset + (availW - (maxX - minX) * scale) / 2.0
        val offY = p.y + inset + 22.0 + (availH + (maxY - minY) * scale) / 2.0
        fun sx(x: Double) = offX + (x - minX) * scale
        fun sy(y: Double) = offY - (y - minY) * scale

        val sb = StringBuilder()
        sb.append("""  <rect x="${f(p.x)}" y="${f(p.y)}" width="${f(p.w)}" height="${f(p.h)}" rx="10" fill="#171c24" stroke="#242c38"/>""").append('\n')
        sb.append(text(p.x + 16, p.y + 26, p.title, 13.5, "#aab4c2", weight = 700))

        // Landuse polygons that fall inside this panel's extent, as context.
        for (w in data.ways) {
            val kind = w.tags["landuse"] ?: continue
            if (!w.isClosed) continue
            val g = geometry(w, data, plane)
            if (g.size < 3) continue
            if (g.none { it.x in minX..maxX && it.y in minY..maxY }) continue
            val fill = when (kind) {
                "forest", "wood" -> "#1e5233"
                "residential" -> "#5a4460"
                "industrial" -> "#5c4326"
                else -> "#2a3038"
            }
            sb.append("""  <polygon points="""")
            sb.append(g.joinToString(" ") { f(sx(it.x)) + "," + f(sy(it.y)) })
            sb.append("""" fill="$fill" fill-opacity="0.5"/>""").append('\n')
        }

        // Routes, worst first so the good ones are drawn on top.
        val ordered = ways.sortedBy { routeOfWay.getValue(it.id).raw01 }
        for (w in ordered) {
            val r = routeOfWay.getValue(w.id)
            val g = geom.getValue(w.id)
            if (g.size < 2) continue
            val pts = g.joinToString(" ") { f(sx(it.x)) + "," + f(sy(it.y)) }
            sb.append("""  <polyline fill="none" stroke="#12161c" stroke-width="7.5" stroke-linecap="round" stroke-linejoin="round" points="$pts"/>""").append('\n')
            sb.append("""  <polyline fill="none" stroke="${levelColour(r.level, cfg.levels)}" stroke-width="4.2" stroke-linecap="round" stroke-linejoin="round" points="$pts"/>""").append('\n')
        }

        // Named nodes.
        for (n in data.nodes.values) {
            val name = n.tags["name"] ?: continue
            val q = plane.toLocal(n.lat, n.lon)
            if (q.x < minX || q.x > maxX || q.y < minY || q.y > maxY) continue
            sb.append("""  <circle cx="${f(sx(q.x))}" cy="${f(sy(q.y))}" r="5" fill="#f2f5f9" stroke="#12161c" stroke-width="2"/>""").append('\n')
            sb.append(text(sx(q.x) + 9, sy(q.y) + 4, name, 12.0, "#f2f5f9", weight = 700))
        }

        // Labels, staggered along each route so nine of them do not pile up in
        // the middle where the fan is narrowest.
        val labelled = ordered.sortedByDescending { routeOfWay.getValue(it.id).raw01 }
        for ((i, w) in labelled.withIndex()) {
            val r = routeOfWay.getValue(w.id)
            val g = geom.getValue(w.id)
            if (g.isEmpty()) continue
            val frac = if (labelled.size == 1) 0.5 else 0.20 + 0.62 * i / (labelled.size - 1.0)
            val q = alongPolyline(g, frac)
            val px = sx(q.x)
            val py = sy(q.y)
            val toRight = px < p.x + p.w / 2.0
            val label = "${r.routeId}  ${r.level}"
            sb.append("""  <circle cx="${f(px)}" cy="${f(py)}" r="3" fill="#f2f5f9"/>""").append('\n')
            sb.append(
                badge(
                    if (toRight) px + 8 else px - 8, py, label,
                    levelColour(r.level, cfg.levels), anchorEnd = !toRight,
                )
            )
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ legend

    private fun renderLegend(x: Double, outcome: ArenaOutcome, cfg: ScoreConfig): String {
        val sb = StringBuilder()
        sb.append("""  <rect x="${f(x)}" y="56" width="${f(W - x - 24.0)}" height="916" rx="10" fill="#171c24" stroke="#242c38"/>""").append('\n')
        sb.append(text(x + 18, 82.0, "Rangliste", 14.0, "#aab4c2", weight = 700))
        var y = 106.0
        sb.append(text(x + 18, y, "Route", 10.5, "#6f7a89", weight = 700))
        sb.append(text(x + 210, y, "Stufe", 10.5, "#6f7a89", weight = 700))
        sb.append(text(x + 268, y, "roh", 10.5, "#6f7a89", weight = 700))
        sb.append(text(x + 340, y, "°/km", 10.5, "#6f7a89", weight = 700))
        sb.append(text(x + 420, y, "Ecken/km", 10.5, "#6f7a89", weight = 700))
        y += 8
        sb.append("""  <line x1="${f(x + 18)}" y1="${f(y)}" x2="${f(W - 42.0)}" y2="${f(y)}" stroke="#2b3441"/>""").append('\n')
        y += 20
        for (r in outcome.ranking) {
            sb.append("""  <rect x="${f(x + 18)}" y="${f(y - 10)}" width="12" height="12" rx="2" fill="${levelColour(r.level, cfg.levels)}"/>""").append('\n')
            sb.append(text(x + 38, y, r.routeId, 12.0, "#dfe5ec"))
            sb.append(text(x + 226, y, r.level.toString(), 12.0, "#f2f5f9", weight = 700))
            sb.append(text(x + 268, y, fmt(r.raw01, 4), 12.0, "#aab4c2"))
            sb.append(text(x + 340, y, fmt(r.score.stats["curvatureDegPerKm"] ?: 0.0, 0), 12.0, "#aab4c2"))
            sb.append(text(x + 440, y, fmt(r.score.stats["cornersPerKm"] ?: 0.0, 2), 12.0, "#aab4c2"))
            y += 22
        }

        y += 16
        sb.append(text(x + 18, y, "Erwartungen der Testarena", 14.0, "#aab4c2", weight = 700))
        y += 22
        for (e in outcome.expectations) {
            val ok = e.passed
            val colour = if (ok) "#4ec97a" else "#ef5350"
            sb.append("""  <circle cx="${f(x + 24)}" cy="${f(y - 4)}" r="4.5" fill="$colour"/>""").append('\n')
            sb.append(text(x + 36, y, e.id, 11.5, if (ok) "#dfe5ec" else "#ffb4b2"))
            sb.append(text(x + 430, y, e.severity, 11.0, "#6f7a89"))
            y += 20
        }

        y += 20
        sb.append(text(x + 18, y, "Farbskala", 14.0, "#aab4c2", weight = 700))
        y += 14
        val swatch = (W - x - 60.0) / cfg.levels
        for (lv in 0 until cfg.levels) {
            sb.append("""  <rect x="${f(x + 18 + lv * swatch)}" y="${f(y)}" width="${f(swatch - 1)}" height="16" fill="${levelColour(lv, cfg.levels)}"/>""").append('\n')
        }
        y += 30
        sb.append(text(x + 18, y, "0 = meiden", 11.0, "#8d97a5"))
        sb.append(text(W - 42.0, y, "${cfg.levels - 1} = Traumstrecke", 11.0, "#8d97a5", anchorEnd = true))
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun geometry(w: OsmWay, data: OsmData, plane: LocalPlane): List<Pt> =
        w.nodeIds.map { data.nodes[it] }.filterNotNull().map { plane.toLocal(it.lat, it.lon) }

    private fun alongPolyline(pts: List<Pt>, frac: Double): Pt {
        if (pts.size < 2) return pts.first()
        var total = 0.0
        for (i in 0 until pts.size - 1) total += pts[i].distTo(pts[i + 1])
        var target = total * frac
        for (i in 0 until pts.size - 1) {
            val d = pts[i].distTo(pts[i + 1])
            if (target <= d) {
                val t = if (d < 1e-9) 0.0 else target / d
                return Pt(pts[i].x + (pts[i + 1].x - pts[i].x) * t, pts[i].y + (pts[i + 1].y - pts[i].y) * t)
            }
            target -= d
        }
        return pts.last()
    }

    private fun badge(x: Double, y: Double, label: String, colour: String, anchorEnd: Boolean): String {
        val w = 8.0 + label.length * 6.6
        val bx = if (anchorEnd) x - w else x
        return buildString {
            append("""  <rect x="${f(bx)}" y="${f(y - 10)}" width="${f(w)}" height="19" rx="4" fill="#0d1116" fill-opacity="0.88" stroke="$colour" stroke-width="1.2"/>""").append('\n')
            append(text(bx + 5, y + 4, label, 11.5, "#f2f5f9", weight = 700))
        }
    }

    private fun text(
        x: Double, y: Double, s: String, size: Double, fill: String,
        weight: Int = 400, anchorEnd: Boolean = false,
    ): String {
        val anchor = if (anchorEnd) """ text-anchor="end"""" else ""
        return """  <text x="${f(x)}" y="${f(y)}" font-size="$size" fill="$fill" font-weight="$weight"$anchor>${escape(s)}</text>""" + "\n"
    }

    /**
     * Red at level 0, amber in the middle, green at the top - the ramp a rider
     * already knows from every curvature map there is.
     */
    fun levelColour(level: Int, levels: Int): String {
        val t = if (levels <= 1) 0.0 else level.toDouble() / (levels - 1)
        val hue = 2.0 + 128.0 * t          // 2 deg = red ... 130 deg = green
        return hslToHex(hue, 0.72, 0.36 + 0.14 * t)
    }

    private fun hslToHex(h: Double, s: Double, l: Double): String {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = h / 60.0
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return String.format("#%02x%02x%02x", ch(r1), ch(g1), ch(b1))
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun fmt(v: Double, dec: Int) = String.format(Locale.ROOT, "%.${dec}f", v)
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
