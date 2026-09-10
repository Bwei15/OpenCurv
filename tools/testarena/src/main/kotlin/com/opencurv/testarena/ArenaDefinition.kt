package com.opencurv.testarena

import com.opencurv.testarena.geometry.PathBuilder
import com.opencurv.testarena.geometry.Point
import com.opencurv.testarena.geometry.Primitive
import com.opencurv.testarena.geometry.Turn
import com.opencurv.testarena.geometry.elevationGainLoss
import com.opencurv.testarena.geometry.metrics
import com.opencurv.testarena.model.OsmDocument
import com.opencurv.testarena.model.OsmNode
import com.opencurv.testarena.truth.ArenaMeta
import com.opencurv.testarena.truth.ArenaTruth
import com.opencurv.testarena.truth.ElementTruth
import com.opencurv.testarena.truth.Expectation
import com.opencurv.testarena.truth.LatLonDto
import com.opencurv.testarena.truth.ToleranceNotes
import com.opencurv.testarena.geometry.Projection

/** Tag key under which every route-carrying way records which arena element it belongs to. */
const val ROUTE_TAG = "opencurv:route"

/**
 * Builds the whole Testarena: nodes, ways and the matching [ArenaTruth]. This is the single
 * place that defines every element listed in tools/testarena's task brief - see
 * 1.Doku/Testarena.md for the human-readable tour and an ASCII sketch of the layout.
 *
 * Determinism: everything here is a fixed sequence of arithmetic operations on constants -
 * no randomness, no wall-clock time, no filesystem iteration order - so the same code always
 * produces the same [OsmDocument]/[ArenaTruth], and DeterminismTest checks exactly that.
 */
object ArenaDefinition {

    // ALPHA/OMEGA sit 4 km apart on a plain north/south line. Every R*-route below starts at
    // ALPHA on its own heading (fanned out between -60° and +60° so the routes do not tangle
    // near the shared start node) and is closed onto OMEGA with an ordinary straight
    // "approach into the junction" (PathBuilder.connectTo) - never a fake identical ending.
    private val ALPHA = Point(0.0, 0.0)
    private const val D = 4000.0
    private val OMEGA = Point(0.0, D)

    // Populated fresh by every build() call; exposed via [lastBuildPaths] purely so
    // GeometryTruthTest can check the exact analytic primitives behind each element without
    // re-deriving arcs from the sampled OSM output. Not used by GenerateArena/the harness.
    private val lastPaths = mutableMapOf<String, PathBuilder>()

    /** The [PathBuilder] used to build each `routeId`, as of the most recent [build] call. */
    fun lastBuildPaths(): Map<String, PathBuilder> = lastPaths.toMap()

    fun build(): Pair<OsmDocument, ArenaTruth> {
        lastPaths.clear()
        val doc = OsmDocument()
        val elements = mutableListOf<ElementTruth>()

        val alphaNode = doc.addNamedEndpoint("ALPHA", ALPHA)
        val omegaNode = doc.addNamedEndpoint("OMEGA", OMEGA)

        elements += buildHighway(doc, alphaNode, omegaNode)
        elements += buildMotorway(doc, alphaNode, omegaNode)
        elements += buildSerpentine(doc, alphaNode, omegaNode)
        elements += buildFlowing(doc, alphaNode, omegaNode)
        elements += buildSCurveCombo(doc, alphaNode, omegaNode)
        elements += buildGravelTrack(doc, alphaNode, omegaNode)
        elements += buildJog90(doc, alphaNode, omegaNode)
        elements += buildDogleg(doc, alphaNode, omegaNode)
        elements += buildResidentialGrid(doc, alphaNode, omegaNode)

        val (hillFlat, hillClimb, hillStart, hillEnd) = buildHillPair(doc)
        elements += hillFlat
        elements += hillClimb

        val (forest, industrial, greenStart, greenEnd) = buildLandusePair(doc)
        elements += forest
        elements += industrial

        val namedNodes = linkedMapOf(
            "ALPHA" to alphaNode,
            "OMEGA" to omegaNode,
            "HILL_START" to hillStart,
            "HILL_END" to hillEnd,
            "GREEN_START" to greenStart,
            "GREEN_END" to greenEnd,
        ).mapValues { (_, n) -> Projection.toLatLon(n.point).let { LatLonDto(it.lat, it.lon) } }

        val truth = ArenaTruth(
            meta = ArenaMeta(
                originLat = com.opencurv.testarena.geometry.ArenaOrigin.LAT_DEG,
                originLon = com.opencurv.testarena.geometry.ArenaOrigin.LON_DEG,
                whyThisLocation = "Offener Atlantik vor Westafrika, einige Zehnerkilometer von Null Island " +
                    "(0°N/0°E) entfernt - fernab jeder echten Straße, aber immer noch 'rund um 0/0' wie " +
                    "gefordert, und bewusst NICHT exakt auf 0/0 (das ist ein bekannter Sammelpunkt für " +
                    "fehlerhafte GPS-Fixes und wird von Werkzeugen mitunter speziell behandelt).",
                pointSpacingMetersMin = 8.0,
                pointSpacingMetersMax = 30.0,
                alphaOmegaStraightLineDistanceM = D,
                tolerances = ToleranceNotes(
                    radiusRelativeTolerance = 0.01,
                    lengthRelativeTolerance = 0.03,
                    turnDegRelativeTolerance = 0.03,
                    explanation = "Jeder Kurvenpunkt liegt exakt (bis auf Gleitkomma-Rauschen) auf dem " +
                        "kommandierten Kreisbogen, daher ist die Radius-Toleranz eng (1%). Länge und " +
                        "Gesamt-Richtungsänderung werden aus der abgetasteten Punktfolge als Sehnen-Summe " +
                        "gemessen; bei engen Radien (15-30 m) mit Stützpunktabstand ~10 m weicht eine " +
                        "Sehne vom wahren Bogen um bis zu ~(Abstand/Radius)²/24 ab - daher 3% Toleranz.",
                ),
            ),
            namedNodes = namedNodes,
            elements = elements,
            expectations = buildExpectations(),
        )

        return doc to truth
    }

    // ---------------------------------------------------------------------
    // R1: kerzengerade Schnellstraße (primary, kurz)
    // ---------------------------------------------------------------------
    private fun buildHighway(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = 0.0, pointSpacingM = 25.0)
        path.straight(D)
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R1_HIGHWAY", "Schnellstraße Alpha-Omega",
            "Die kürzeste, kerzengerade Verbindung zwischen ALPHA und OMEGA. Schnell, aber ohne jede Kurve.",
            highway = "primary", surface = "asphalt", maxspeedKmh = 100, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R7: Autobahn - schnell, fast gerade, minimal gekrümmt
    // ---------------------------------------------------------------------
    private fun buildMotorway(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = 6.0, pointSpacingM = 30.0)
        path.arc(900.0, 10.0, Turn.RIGHT)
        path.straight(1850.0)
        path.arc(900.0, 10.0, Turn.LEFT)
        path.straight(1850.0)
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R7_MOTORWAY", "Autobahn Alpha-Omega",
            "Die schnellste Alternative: kreuzungsfrei, breite Radien (900 m), fast schnurgerade. " +
                "Ein reiner Zeit-Optimierer würde immer diese Route wählen.",
            highway = "motorway", surface = "asphalt", maxspeedKmh = 130, landuse = null,
            extraTags = mapOf("oneway" to "yes", "lanes" to "2"),
        )
    }

    // ---------------------------------------------------------------------
    // R2: Serpentine mit echten Kehren (Radius 15-30 m)
    // ---------------------------------------------------------------------
    private fun buildSerpentine(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = -18.0, pointSpacingM = 10.0)
        val radius = 24.0
        val hairpinSweep = 168.0
        // Each unit is a pair of back-to-back opposite hairpins (net heading change zero,
        // like a real switchback climbing one side of a valley and then the other) followed
        // by a straight leg *after* heading has returned to the route's general direction -
        // that is what keeps the straight legs from cancelling each other out.
        repeat(7) {
            path.arc(radius, hairpinSweep, Turn.RIGHT)
            path.arc(radius, hairpinSweep, Turn.LEFT)
            path.straight(300.0)
        }
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R2_SERPENTINE", "Serpentine Alpha-Omega",
            "Ein Pass mit 14 echten Kehren, Radius ${radius.toInt()} m. Viel länger als die " +
                "Schnellstraße, aber die Art Kurven, für die OpenCurv gebaut ist.",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 50, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R3: fließende Landstraße, weite Schwünge (Radius 150-400 m)
    // ---------------------------------------------------------------------
    private fun buildFlowing(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = 12.0, pointSpacingM = 25.0)
        path.arc(180.0, 45.0, Turn.RIGHT)
        path.straight(550.0)
        path.arc(320.0, 60.0, Turn.LEFT)
        path.straight(550.0)
        path.arc(220.0, 50.0, Turn.RIGHT)
        path.straight(550.0)
        path.arc(300.0, 55.0, Turn.LEFT)
        path.straight(500.0)
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R3_FLOWING", "Landstraße Alpha-Omega",
            "Weite, fließende Schwünge mit Radius 180-320 m - die klassische 'Sonntagsausfahrt'-Straße.",
            highway = "secondary", surface = "asphalt", maxspeedKmh = 90, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R4: S-Kurven-Kombination, dichter Wechsel der Kurvenrichtung
    // ---------------------------------------------------------------------
    private fun buildSCurveCombo(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = -12.0, pointSpacingM = 12.0)
        repeat(10) {
            path.arc(45.0, 35.0, Turn.LEFT)
            path.arc(45.0, 70.0, Turn.RIGHT)
            path.arc(45.0, 35.0, Turn.LEFT)
            path.straight(180.0)
        }
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R4_S_CURVES", "Wellenstraße Alpha-Omega",
            "30 mittelenge Kurven (Radius 45 m) im dichten Richtungswechsel - eine echte Handling-Strecke.",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 60, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R6: Schotterpiste
    // ---------------------------------------------------------------------
    private fun buildGravelTrack(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = -6.0, pointSpacingM = 20.0)
        path.arc(90.0, 40.0, Turn.RIGHT)
        path.straight(900.0)
        path.arc(110.0, 45.0, Turn.LEFT)
        path.straight(900.0)
        path.arc(90.0, 35.0, Turn.RIGHT)
        path.straight(600.0)
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R6_GRAVEL", "Schotterpiste Alpha-Omega",
            "Ungeteerte Piste mit ähnlicher Kurvigkeit wie die Landstraße - für ein Straßen-Profil " +
                "trotzdem meist die falsche Wahl.",
            highway = "track", surface = "gravel", maxspeedKmh = 40, landuse = null,
            extraTags = mapOf("tracktype" to "grade3"),
        )
    }

    // ---------------------------------------------------------------------
    // R8: stumpfe 90°-Kreuzungsfolge auf sonst gerader Straße
    // ---------------------------------------------------------------------
    private fun buildJog90(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val baseHeading = 16.0
        val path = PathBuilder(ALPHA, startHeadingDeg = baseHeading, pointSpacingM = 20.0)
        repeat(3) {
            path.straight(950.0)
            path.straight(80.0, headingOverrideDeg = baseHeading + 90.0, isSharpCorner = true)
            path.straight(80.0, headingOverrideDeg = baseHeading, isSharpCorner = true)
        }
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R8_JOG90", "Kreuzungsfolge Alpha-Omega",
            "Eine sonst gerade Straße, die sechsmal im rechten Winkel versetzt ist (alte " +
                "Flurgrenzen). Viel Richtungsänderung, aber keine einzige echte Kurve.",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R9: Hundskurve - schnelle Gerade, die unvermittelt eng wird
    // ---------------------------------------------------------------------
    private fun buildDogleg(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val path = PathBuilder(ALPHA, startHeadingDeg = -10.0, pointSpacingM = 25.0)
        path.straight(3500.0)
        path.arc(20.0, 95.0, Turn.RIGHT)
        path.connectTo(OMEGA)
        return finish(
            doc, path, alpha, omega, "R9_DOGLEG", "Hundskurve Alpha-Omega",
            "3,5 km schnelle Gerade, die unvermittelt in einen 20-m-Radius läuft - eine einzelne " +
                "Überraschungskurve, kein Kurvenerlebnis.",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 90, landuse = null,
        )
    }

    // ---------------------------------------------------------------------
    // R5: Zickzack-Ortsnetz - geometrisch "kurvig", fahrerisch wertlos
    // ---------------------------------------------------------------------
    private fun buildResidentialGrid(doc: OsmDocument, alpha: OsmNode, omega: OsmNode): ElementTruth {
        val gridOrigin = Point(100.0, 1800.0)
        val block = 100.0
        val path = PathBuilder(ALPHA, startHeadingDeg = 45.0, pointSpacingM = 25.0)
        path.connectTo(gridOrigin)
        val eastHeading = 90.0
        val northHeading = 0.0
        repeat(4) {
            path.straight(block, headingOverrideDeg = eastHeading, isSharpCorner = true)
            path.straight(block, headingOverrideDeg = northHeading, isSharpCorner = true)
        }
        val gridExit = Point(gridOrigin.x + 4 * block, gridOrigin.y + 4 * block)
        path.connectTo(OMEGA)

        // The residential zone the staircase runs through (with a little margin), so the
        // element is visibly inside landuse=residential, not just an isolated zigzag way.
        val margin = 60.0
        doc.addAreaPolygon(
            listOf(
                Point(gridOrigin.x - margin, gridOrigin.y - margin),
                Point(gridExit.x + margin, gridOrigin.y - margin),
                Point(gridExit.x + margin, gridExit.y + margin),
                Point(gridOrigin.x - margin, gridExit.y + margin),
            ),
            mapOf("landuse" to "residential", "name" to "Ortsnetz Alpha-Omega"),
        )

        return finish(
            doc, path, alpha, omega, "R5_GRID", "Ortsdurchfahrt Alpha-Omega",
            "Acht rechtwinklige Ecken durch ein Wohngebiet (Tempo 50). Geometrisch die 'kurvigste' " +
                "Alternative (größte Gesamt-Richtungsänderung, siehe sharpCornerCount) - aber Radius 0, " +
                "Ortsdurchfahrt: ein guter Algorithmus MUSS das abwerten, nicht belohnen.",
            highway = "residential", surface = "asphalt", maxspeedKmh = 50, landuse = "residential",
        )
    }

    // ---------------------------------------------------------------------
    // Steigungsprofil vs. flache Alternative (identische Grundriss-Geometrie)
    // ---------------------------------------------------------------------
    private data class HillPairResult(
        val flat: ElementTruth,
        val climb: ElementTruth,
        val start: OsmNode,
        val end: OsmNode,
    )

    private fun buildHillPair(doc: OsmDocument): HillPairResult {
        val start = doc.addNamedEndpoint("HILL_START", Point(5500.0, 0.0))
        val end = doc.addNamedEndpoint("HILL_END", Point(5500.0, 1800.0))

        val flatPath = comparisonSCurve(start.point, mirror = false)
        flatPath.setElevationProfile { 0.0 }
        lastPaths["R_HILL_FLAT"] = flatPath
        val flatWay = doc.addWayFromPath(
            flatPath, start, end,
            wayTags("R_HILL_FLAT", "Talstraße Alpha-Omega", highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = null),
        )
        val (flatGainM, flatLossM) = flatPath.vertices.elevationGainLoss()
        val flatMetrics = flatPath.primitives.metrics()
        val flat = ElementTruth(
            routeId = "R_HILL_FLAT", name = "Talstraße Alpha-Omega",
            description = "Gleicher Grundriss wie R_HILL_CLIMB, aber eben (ele konstant 0 m). " +
                "Dient als Neutralitäts-Kontrolle: Kurvenbewertung darf sich zwischen den beiden " +
                "NICHT unterscheiden, denn die Grundriss-Geometrie ist identisch.",
            fromNode = "HILL_START", toNode = "HILL_END",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = null,
            wayIds = listOf(flatWay.id), lengthM = flatMetrics.lengthM, curveCount = flatMetrics.curveCount,
            minRadiusM = flatMetrics.minRadiusM, meanRadiusM = flatMetrics.meanRadiusM,
            sharpCornerCount = flatMetrics.sharpCornerCount, totalTurnDeg = flatMetrics.totalTurnDeg,
            elevationGainM = flatGainM, elevationLossM = flatLossM,
        )

        val climbPath = comparisonSCurve(start.point, mirror = true)
        val totalLen = climbPath.vertices.last().distanceFromStartM
        climbPath.setElevationProfile { d -> 350.0 * (d / totalLen) }
        lastPaths["R_HILL_CLIMB"] = climbPath
        val climbWay = doc.addWayFromPath(
            climbPath, start, end,
            wayTags("R_HILL_CLIMB", "Passstraße Alpha-Omega", highway = "tertiary", surface = "asphalt", maxspeedKmh = 60, landuse = null),
        )
        val (climbGainM, climbLossM) = climbPath.vertices.elevationGainLoss()
        val climbMetrics = climbPath.primitives.metrics()
        val climb = ElementTruth(
            routeId = "R_HILL_CLIMB", name = "Passstraße Alpha-Omega",
            description = "Gleicher Grundriss wie R_HILL_FLAT (gespiegelt, identische Radien/Längen), " +
                "aber mit 350 Höhenmetern (linearer Anstieg).",
            fromNode = "HILL_START", toNode = "HILL_END",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 60, landuse = null,
            wayIds = listOf(climbWay.id), lengthM = climbMetrics.lengthM, curveCount = climbMetrics.curveCount,
            minRadiusM = climbMetrics.minRadiusM, meanRadiusM = climbMetrics.meanRadiusM,
            sharpCornerCount = climbMetrics.sharpCornerCount, totalTurnDeg = climbMetrics.totalTurnDeg,
            elevationGainM = climbGainM, elevationLossM = climbLossM,
        )

        return HillPairResult(flat, climb, start, end)
    }

    // ---------------------------------------------------------------------
    // Wald vs. Industriegebiet (identische Geometrie, unterschiedliches Landuse)
    // ---------------------------------------------------------------------
    private data class GreenPairResult(
        val forest: ElementTruth,
        val industrial: ElementTruth,
        val start: OsmNode,
        val end: OsmNode,
    )

    private fun buildLandusePair(doc: OsmDocument): GreenPairResult {
        val start = doc.addNamedEndpoint("GREEN_START", Point(8000.0, 0.0))
        val end = doc.addNamedEndpoint("GREEN_END", Point(8000.0, 1800.0))

        val forestPath = comparisonSCurve(start.point, mirror = false)
        lastPaths["R_FOREST"] = forestPath
        val forestWay = doc.addWayFromPath(
            forestPath, start, end,
            wayTags("R_FOREST", "Waldstraße Alpha-Omega", highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = null),
        )
        addCorridorPolygon(doc, forestPath, mapOf("landuse" to "forest", "name" to "Wald Alpha-Omega"))
        val forestMetrics = forestPath.primitives.metrics()
        val forest = ElementTruth(
            routeId = "R_FOREST", name = "Waldstraße Alpha-Omega",
            description = "Gleicher Grundriss wie R_INDUSTRIAL (gespiegelt), führt aber durch " +
                "landuse=forest statt landuse=industrial.",
            fromNode = "GREEN_START", toNode = "GREEN_END",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = "forest",
            wayIds = listOf(forestWay.id), lengthM = forestMetrics.lengthM, curveCount = forestMetrics.curveCount,
            minRadiusM = forestMetrics.minRadiusM, meanRadiusM = forestMetrics.meanRadiusM,
            sharpCornerCount = forestMetrics.sharpCornerCount, totalTurnDeg = forestMetrics.totalTurnDeg,
            elevationGainM = 0.0, elevationLossM = 0.0,
        )

        val industrialPath = comparisonSCurve(start.point, mirror = true)
        lastPaths["R_INDUSTRIAL"] = industrialPath
        val industrialWay = doc.addWayFromPath(
            industrialPath, start, end,
            wayTags("R_INDUSTRIAL", "Industriestraße Alpha-Omega", highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = null),
        )
        addCorridorPolygon(doc, industrialPath, mapOf("landuse" to "industrial", "name" to "Industriegebiet Alpha-Omega"))
        val industrialMetrics = industrialPath.primitives.metrics()
        val industrial = ElementTruth(
            routeId = "R_INDUSTRIAL", name = "Industriestraße Alpha-Omega",
            description = "Gleicher Grundriss wie R_FOREST (identische Radien/Längen/Kurven), " +
                "führt aber durch landuse=industrial.",
            fromNode = "GREEN_START", toNode = "GREEN_END",
            highway = "tertiary", surface = "asphalt", maxspeedKmh = 70, landuse = "industrial",
            wayIds = listOf(industrialWay.id), lengthM = industrialMetrics.lengthM, curveCount = industrialMetrics.curveCount,
            minRadiusM = industrialMetrics.minRadiusM, meanRadiusM = industrialMetrics.meanRadiusM,
            sharpCornerCount = industrialMetrics.sharpCornerCount, totalTurnDeg = industrialMetrics.totalTurnDeg,
            elevationGainM = 0.0, elevationLossM = 0.0,
        )

        return GreenPairResult(forest, industrial, start, end)
    }

    /** The shared curve shape used by both members of a comparison pair (HILL_*, GREEN_*):
     *  same radii/sweeps/lengths either way, [mirror] only flips every turn direction so the
     *  two ways are physically distinct (they do not literally overlap) while remaining
     *  numerically identical in every truth metric. */
    private fun comparisonSCurve(start: Point, mirror: Boolean): PathBuilder {
        fun t(dir: Turn) = if (mirror) (if (dir == Turn.LEFT) Turn.RIGHT else Turn.LEFT) else dir
        val startHeading = if (mirror) -8.0 else 8.0
        val path = PathBuilder(start, startHeadingDeg = startHeading, pointSpacingM = 25.0)
        path.arc(220.0, 50.0, t(Turn.RIGHT))
        path.straight(400.0)
        path.arc(220.0, 50.0, t(Turn.LEFT))
        path.straight(400.0)
        return path
    }

    private fun addCorridorPolygon(doc: OsmDocument, path: PathBuilder, tags: Map<String, String>) {
        val pts = path.vertices.map { it.point }
        val minX = pts.minOf { it.x } - 80.0
        val maxX = pts.maxOf { it.x } + 80.0
        val minY = pts.minOf { it.y } - 80.0
        val maxY = pts.maxOf { it.y } + 80.0
        doc.addAreaPolygon(
            listOf(Point(minX, minY), Point(maxX, minY), Point(maxX, maxY), Point(minX, maxY)),
            tags,
        )
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private fun wayTags(
        routeId: String,
        name: String,
        highway: String,
        surface: String?,
        maxspeedKmh: Int?,
        landuse: String?,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val tags = mutableMapOf(
            "highway" to highway,
            "name" to name,
            ROUTE_TAG to routeId,
        )
        if (surface != null) tags["surface"] = surface
        if (maxspeedKmh != null) tags["maxspeed"] = maxspeedKmh.toString()
        tags.putAll(extra)
        return tags
    }

    private fun finish(
        doc: OsmDocument,
        path: PathBuilder,
        startNode: OsmNode,
        endNode: OsmNode,
        routeId: String,
        name: String,
        description: String,
        highway: String,
        surface: String?,
        maxspeedKmh: Int?,
        landuse: String?,
        extraTags: Map<String, String> = emptyMap(),
    ): ElementTruth {
        val way = doc.addWayFromPath(
            path, startNode, endNode,
            wayTags(routeId, name, highway, surface, maxspeedKmh, landuse, extraTags),
        )
        lastPaths[routeId] = path
        val metrics = path.primitives.metrics()
        val (gain, loss) = path.vertices.elevationGainLoss()
        if (System.getenv("TESTARENA_DEBUG") != null) {
            val connectorLen = path.primitives.filterIsInstance<Primitive.Straight>()
                .filter { it.isConnector }.sumOf { it.lengthM }
            System.err.println("DEBUG $routeId totalLen=${metrics.lengthM} connectorLen=$connectorLen frac=${connectorLen / metrics.lengthM}")
        }
        return ElementTruth(
            routeId = routeId, name = name, description = description,
            fromNode = "ALPHA", toNode = "OMEGA",
            highway = highway, surface = surface, maxspeedKmh = maxspeedKmh, landuse = landuse,
            wayIds = listOf(way.id), lengthM = metrics.lengthM, curveCount = metrics.curveCount,
            minRadiusM = metrics.minRadiusM, meanRadiusM = metrics.meanRadiusM,
            sharpCornerCount = metrics.sharpCornerCount, totalTurnDeg = metrics.totalTurnDeg,
            elevationGainM = gain, elevationLossM = loss,
        )
    }

    private fun buildExpectations(): List<Expectation> {
        val goodCurves = listOf("R2_SERPENTINE", "R3_FLOWING", "R4_S_CURVES")
        return listOf(
            Expectation(
                id = "E1_CURVES_OVER_HIGHWAY", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R1_HIGHWAY"), severity = "hard",
                reason = "Ein Kurven-Router darf nicht einfach die kürzeste/geradeste Straße nehmen, " +
                    "nur weil sie kürzer ist - das ist Calimotos Fehler, den OpenCurv vermeiden soll.",
            ),
            Expectation(
                id = "E2_CURVES_OVER_MOTORWAY", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R7_MOTORWAY"), severity = "hard",
                reason = "Die Autobahn ist die schnellste Option; ein Kurven-Router muss ihr trotzdem " +
                    "widerstehen können.",
            ),
            Expectation(
                id = "E3_CURVES_OVER_GRID", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R5_GRID"), severity = "hard",
                reason = "Kernfalle: R5_GRID hat die meisten Richtungswechsel überhaupt " +
                    "(sharpCornerCount = 8, totalTurnDeg ≈ 720°), aber Radius 0 und Tempo-50-Ortsdurchfahrt. " +
                    "Geometrische Kurvigkeit ist nicht dasselbe wie eine gute Motorradkurve.",
            ),
            Expectation(
                id = "E4_CURVES_OVER_DOGLEG", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R9_DOGLEG"), severity = "hard",
                reason = "Eine einzelne Überraschungskurve nach 2,6 km Gerade ist kein Kurvenerlebnis " +
                    "und obendrein ein Sicherheitsrisiko - niedrige Kurvendichte trotz vorhandener Kurve.",
            ),
            Expectation(
                id = "E5_CURVES_OVER_JOG90", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R8_JOG90"), severity = "hard",
                reason = "Stumpfe 90°-Ecken sind Richtungswechsel, aber keine fahrbaren Kurven " +
                    "(Radius 0, wie bei R5_GRID).",
            ),
            Expectation(
                id = "E6_CURVES_OVER_GRAVEL", from = "ALPHA", to = "OMEGA",
                preferRouteIds = goodCurves, overRouteIds = listOf("R6_GRAVEL"), severity = "soft",
                reason = "Für ein Straßen-/Sporttourenprofil sollte Asphalt vor Schotter gehen, obwohl " +
                    "R6_GRAVEL ähnlich kurvig ist wie R3_FLOWING. Bei einem Enduro-/Adventure-Profil " +
                    "kehrt sich diese Erwartung bewusst um - deshalb 'soft', nicht 'hard'.",
            ),
            Expectation(
                id = "E7_HIGHWAY_OVER_GRID", from = "ALPHA", to = "OMEGA",
                preferRouteIds = listOf("R1_HIGHWAY"), overRouteIds = listOf("R5_GRID"), severity = "hard",
                reason = "Selbst die langweilige Schnellstraße muss die Ortsdurchfahrt schlagen - " +
                    "R5_GRID darf nicht einmal die zweitbeste Wahl sein.",
            ),
            Expectation(
                id = "E8_ELEVATION_NEUTRALITY", from = "HILL_START", to = "HILL_END",
                preferRouteIds = emptyList(), overRouteIds = emptyList(), severity = "info",
                reason = "R_HILL_FLAT und R_HILL_CLIMB haben identische Grundriss-Geometrie " +
                    "(Radien, Längen, Kurvenzahl) und unterscheiden sich nur um 350 Höhenmeter. Eine " +
                    "reine Kurvenbewertung sollte beide gleich bewerten; eine starke, unbegründete " +
                    "Präferenz für eine der beiden zeigt, dass der Algorithmus fälschlich auf das " +
                    "ele-Tag statt auf Kurvengeometrie reagiert. Nicht automatisch auswertbar aus einer " +
                    "einzelnen Route - als Hinweis für manuelle Prüfung gedacht.",
            ),
            Expectation(
                id = "E9_SCENIC_FOREST_OVER_INDUSTRIAL", from = "GREEN_START", to = "GREEN_END",
                preferRouteIds = listOf("R_FOREST"), overRouteIds = listOf("R_INDUSTRIAL"), severity = "soft",
                reason = "Bei identischer Kurvengeometrie ist der Wald die landschaftlich schönere Wahl. " +
                    "Das ist ein Zukunfts-Hook für eine Szenerie-Bewertung, noch keine harte Anforderung - " +
                    "so lange OpenCurv Landuse nicht gewichtet, ist Gleichstand hier akzeptabel.",
            ),
        )
    }
}
