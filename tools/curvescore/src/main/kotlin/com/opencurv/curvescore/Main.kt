package com.opencurv.curvescore

import com.google.gson.GsonBuilder
import com.opencurv.curvescore.geom.LocalPlane
import com.opencurv.curvescore.geom.Pt
import com.opencurv.curvescore.io.OsmReader
import com.opencurv.curvescore.io.OsmXmlTagWriter
import com.opencurv.curvescore.io.WayTagValues
import com.opencurv.curvescore.model.OsmData
import com.opencurv.curvescore.model.OsmNode
import com.opencurv.curvescore.model.OsmWay
import com.opencurv.curvescore.report.ArenaRunner
import com.opencurv.curvescore.report.ArenaSvg
import com.opencurv.curvescore.score.CurveScorer
import com.opencurv.curvescore.score.Environment
import com.opencurv.curvescore.score.ScoreConfig
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

private const val USAGE = """
opencurv-curvescore - der Kurven-Score von OpenCurv

  score  --in <file.osm|file.osm.pbf> [--json <out.json>] [Optionen]
         Bewertet jede Strasse und schreibt die Ergebnisse als JSON.

  tag    --in <file.osm> --out <file.osm> [Optionen]
         Liest eine OSM-Datei und schreibt sie mit dem Tag
         opencurv:curve=<0..levels-1> an jeder bewerteten Strasse wieder aus.
         Das ist die Schnittstelle zur Cloud-Pipeline.

  arena  --arena-dir <tools/testarena> [--out-dir <dir>]
         Bewertet die Testarena, druckt die Rangliste, prueft E1-E9 und
         schreibt report/arena.svg + report/arena_scores.json.
         Exit-Code 1, wenn eine harte Erwartung verletzt ist.

  bench  [--ways N] [--nodes N]
         Misst den Durchsatz der Bewertung auf synthetischer Geometrie.

  bench-io [--ways N] [--nodes N]
         Schreibt eine synthetische .osm.pbf und misst, wie schnell sie
         wieder eingelesen wird (fuer die Laufzeit-Hochrechnung).

Optionen:
  --tag-name <k>        Name des Score-Tags        (Standard opencurv:curve)
  --raw-tag <k>         zusaetzlich den Rohwert schreiben (Standard: aus)
  --conf-tag <k>        zusaetzlich die Datenkonfidenz    (Standard: aus)
  --levels <n>          Anzahl der Stufen          (Standard 16 -> 0..15)
  --window <m>          Fensterlaenge in Metern    (Standard 1000)
  --enduro              Schotter nicht abwerten (Enduro-/Adventure-Profil)
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { println(USAGE); exitProcess(2) }
    val opts = parseOptions(args.drop(1))
    val cfg = ScoreConfig(
        levels = opts["levels"]?.toIntOrNull() ?: 16,
        windowM = opts["window"]?.toDoubleOrNull() ?: 1000.0,
        enduroProfile = opts.containsKey("enduro"),
    )
    when (args[0]) {
        "score" -> cmdScore(opts, cfg)
        "tag" -> cmdTag(opts, cfg)
        "arena" -> cmdArena(opts, cfg)
        "bench" -> cmdBench(opts, cfg)
        "bench-io" -> cmdBenchIo(opts, cfg)
        else -> { println(USAGE); exitProcess(2) }
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) { i++; continue }
        val key = a.removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next != null && !next.startsWith("--")) { m[key] = next; i += 2 } else { m[key] = "true"; i++ }
    }
    return m
}

private fun loadAndScore(file: File, cfg: ScoreConfig): Triple<OsmData, CurveScorer, List<com.opencurv.curvescore.score.WayScore>> {
    val t0 = System.nanoTime()
    val data = OsmReader.read(file)
    val centre = data.centre()
    val plane = LocalPlane(centre.first, centre.second)
    val env = Environment.build(data, plane)
    val scorer = CurveScorer(data, cfg, env, plane)
    val scores = scorer.scoreAll()
    System.err.println(
        String.format(
            Locale.ROOT, "gelesen: %d Knoten, %d Ways -> %d bewertet in %.2f s",
            data.nodes.size, data.ways.size, scores.size, (System.nanoTime() - t0) / 1e9,
        )
    )
    return Triple(data, scorer, scores)
}

private fun cmdScore(opts: Map<String, String>, cfg: ScoreConfig) {
    val input = File(opts["in"] ?: err("--in fehlt"))
    val (_, _, scores) = loadAndScore(input, cfg)
    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    val json = gson.toJson(scores.sortedByDescending { it.raw01 })
    val out = opts["json"]
    if (out == null) println(json) else File(out).also { it.parentFile?.mkdirs() }.writeText(json)
    val hist = IntArray(cfg.levels)
    var totalLen = 0.0
    for (s in scores) { hist[s.level]++; totalLen += s.lengthM }
    System.err.println("Stufenverteilung: " + hist.withIndex().joinToString(" ") { "${it.index}:${it.value}" })
    System.err.println(String.format(Locale.ROOT, "bewertete Gesamtlaenge: %.1f km", totalLen / 1000.0))
}

private fun cmdTag(opts: Map<String, String>, cfg: ScoreConfig) {
    val input = File(opts["in"] ?: err("--in fehlt"))
    val output = File(opts["out"] ?: err("--out fehlt"))
    require(input.name.endsWith(".osm") || input.name.endsWith(".xml")) {
        "tag schreibt nur OSM-XML zurueck; PBF-Ausgabe uebernimmt die rd5-Pipeline"
    }
    val (_, _, scores) = loadAndScore(input, cfg)
    val map = scores.associate { it.wayId to WayTagValues(it.level, it.raw01, it.confidence) }
    OsmXmlTagWriter.write(
        input, output, map,
        tagName = opts["tag-name"] ?: "opencurv:curve",
        rawTagName = opts["raw-tag"],
        confTagName = opts["conf-tag"],
    )
    System.err.println("geschrieben: ${output.absolutePath} (${map.size} Ways getaggt)")
}

private fun cmdArena(opts: Map<String, String>, cfg: ScoreConfig) {
    val arenaDir = File(opts["arena-dir"] ?: err("--arena-dir fehlt"))
    val outDir = File(opts["out-dir"] ?: "report")
    val outcome = ArenaRunner.run(arenaDir, cfg)
    print(ArenaRunner.render(outcome))
    ArenaRunner.writeJson(outcome, File(outDir, "arena_scores.json"))
    val data = OsmReader.read(File(arenaDir, "data/arena.osm"))
    ArenaSvg.write(data, outcome, cfg, File(outDir, "arena.svg"))
    println("\ngeschrieben: ${File(outDir, "arena.svg").absolutePath}")
    println("geschrieben: ${File(outDir, "arena_scores.json").absolutePath}")
    if (outcome.hardFailures.isNotEmpty()) {
        System.err.println("HARTE ERWARTUNG VERLETZT: " + outcome.hardFailures.joinToString(", ") { it.id })
        exitProcess(1)
    }
}

/**
 * Builds a synthetic road network: chains of arcs of random radius, i.e.
 * geometry the turn analysis has to do real work on, not straight lines.
 * Optionally with landuse polygons so the scenery lookup is measured too.
 */
private fun syntheticNetwork(
    wayCount: Int,
    nodesPerWay: Int,
    plane: LocalPlane,
    withPolygons: Boolean,
): OsmData {
    val nodes = HashMap<Long, OsmNode>(wayCount * nodesPerWay * 2)
    val ways = ArrayList<OsmWay>(wayCount)
    var nodeId = 1L
    val rnd = java.util.Random(20260910L)
    for (w in 0 until wayCount) {
        val ids = LongArray(nodesPerWay)
        var x = (w % 400) * 900.0
        var y = (w / 400) * 900.0
        var heading = rnd.nextDouble() * 2 * Math.PI
        var curvature = 0.0
        for (k in 0 until nodesPerWay) {
            if (k % 7 == 0) curvature = (rnd.nextDouble() - 0.5) / 60.0
            val step = 18.0
            heading += curvature * step
            x += sin(heading) * step
            y += cos(heading) * step
            val ll = plane.toLatLon(Pt(x, y))
            nodes[nodeId] = OsmNode(nodeId, ll[0], ll[1], null, emptyMap())
            ids[k] = nodeId
            nodeId++
        }
        ways.add(OsmWay(1_000_000L + w, ids, mapOf("highway" to "tertiary", "surface" to "asphalt")))
    }
    if (withPolygons) {
        // One 800 m landuse square per four ways, so the scenery lookup and its
        // grid index are part of the measurement rather than being optimised
        // away by an empty environment.
        var polyId = 900_000_000L
        for (w in 0 until wayCount / 4) {
            val cx = (w % 200) * 1800.0
            val cy = (w / 200) * 1800.0
            val ring = LongArray(5)
            val corners = listOf(0.0 to 0.0, 800.0 to 0.0, 800.0 to 800.0, 0.0 to 800.0)
            for ((i, c) in corners.withIndex()) {
                val ll = plane.toLatLon(Pt(cx + c.first, cy + c.second))
                nodes[nodeId] = OsmNode(nodeId, ll[0], ll[1], null, emptyMap())
                ring[i] = nodeId
                nodeId++
            }
            ring[4] = ring[0]
            ways.add(OsmWay(polyId++, ring, mapOf("landuse" to if (w % 3 == 0) "forest" else "farmland")))
        }
    }
    return OsmData(nodes, ways)
}

/**
 * Throughput measurement of the scoring stage, used for the Bavaria
 * extrapolation in 1.Doku/Kurven_Score.md. Synthetic on purpose: it isolates
 * *this* stage from PBF decoding, which `bench-io` measures separately.
 */
private fun cmdBench(opts: Map<String, String>, cfg: ScoreConfig) {
    val wayCount = opts["ways"]?.toIntOrNull() ?: 40_000
    val nodesPerWay = opts["nodes"]?.toIntOrNull() ?: 40
    println("Baue synthetische Geometrie: $wayCount Ways x $nodesPerWay Knoten ...")
    val plane = LocalPlane(48.5, 11.5)
    val data = syntheticNetwork(wayCount, nodesPerWay, plane, withPolygons = true)
    val polygons = data.ways.count { it.tags.containsKey("landuse") }

    val tEnv = System.nanoTime()
    val env = Environment.build(data, plane)
    val envSec = (System.nanoTime() - tEnv) / 1e9
    val scorer = CurveScorer(data, cfg, env, plane)
    // Warm-up so the measurement is of steady-state JIT-compiled code.
    scorer.scoreAll()

    val cores = Runtime.getRuntime().availableProcessors()
    val t0 = System.nanoTime()
    val scores = scorer.scoreAll(parallel = true)
    val dtPar = (System.nanoTime() - t0) / 1e9
    val t1 = System.nanoTime()
    scorer.scoreAll(parallel = false)
    val dtSer = (System.nanoTime() - t1) / 1e9

    val km = scores.sumOf { it.lengthM } / 1000.0
    println(
        String.format(
            Locale.ROOT,
            "Umgebungsindex (%d Polygone): %.2f s%n" +
                "parallel  (%d Kerne): %.2f s  ->  %.0f Ways/s, %.0f km/s%n" +
                "seriell   (1 Kern)  : %.2f s  ->  %.0f Ways/s, %.0f km/s%n" +
                "%d Ways / %.0f km bewertet",
            polygons, envSec,
            cores, dtPar, scores.size / dtPar, km / dtPar,
            dtSer, scores.size / dtSer, km / dtSer,
            scores.size, km,
        )
    )
}

/** Measures how fast a realistically sized .osm.pbf is decoded into the model. */
private fun cmdBenchIo(opts: Map<String, String>, cfg: ScoreConfig) {
    val wayCount = opts["ways"]?.toIntOrNull() ?: 100_000
    val nodesPerWay = opts["nodes"]?.toIntOrNull() ?: 20
    val data = syntheticNetwork(wayCount, nodesPerWay, LocalPlane(48.5, 11.5), withPolygons = false)
    val tmp = File.createTempFile("curvescore-bench", ".osm.pbf")
    tmp.deleteOnExit()
    val tW = System.nanoTime()
    com.opencurv.curvescore.io.OsmPbfWriter.write(data, tmp)
    val writeSec = (System.nanoTime() - tW) / 1e9
    val entities = data.nodes.size + data.ways.size
    // Warm-up, then measure.
    OsmReader.read(tmp)
    val t0 = System.nanoTime()
    val back = OsmReader.read(tmp)
    val readSec = (System.nanoTime() - t0) / 1e9
    println(
        String.format(
            Locale.ROOT,
            "Datei: %.1f MB, %d Knoten + %d Ways = %d Entities%n" +
                "schreiben: %.2f s%n" +
                "lesen:     %.2f s  ->  %.0f Entities/s, %.1f MB/s (1 Kern)%n" +
                "gelesen zurueck: %d Knoten, %d Ways",
            tmp.length() / 1e6, data.nodes.size, data.ways.size, entities,
            writeSec, readSec, entities / readSec, tmp.length() / 1e6 / readSec,
            back.nodes.size, back.ways.size,
        )
    )
}

private fun err(msg: String): Nothing {
    System.err.println(msg); println(USAGE); exitProcess(2)
}
