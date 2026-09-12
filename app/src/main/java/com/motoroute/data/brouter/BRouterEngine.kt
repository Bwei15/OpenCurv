package com.motoroute.data.brouter

import btools.router.OpenCurvTrackAccess
import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction
import com.motoroute.data.model.Route
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

import btools.router.OsmNogoPolygon
import com.motoroute.data.traffic.NoGoArea
import com.motoroute.data.traffic.NoGoPolygon

/** Everything a single routing request needs. */
data class RouteRequest(
    val waypoints: List<GeoPoint>,
    val profile: File,
    val segmentDir: File,
    /** Profile parameters, e.g. "curviness" to "1.6". Overrides the .brf defaults. */
    val profileParams: Map<String, String> = emptyMap(),
    /** 0 = best route, 1..3 = BRouter's alternatives. */
    val alternativeIndex: Int = 0,
    val maxRunningTimeMillis: Long = 60_000L,
    /**
     * Search memory in MB. BRouter sizes its node cache from this; on a 4 GB
     * phone 48 MB is plenty for a day-long route and leaves the map renderer
     * enough room to avoid thrashing.
     */
    val memoryClassMb: Int = 48,
    /** Avoidance areas (e.g. road closures, construction from traffic feeds). */
    val noGos: List<NoGoArea> = emptyList(),
    val noGoPolygons: List<NoGoPolygon> = emptyList(),
)

class RoutingException(message: String) : Exception(message)

/**
 * Thin, offline-only wrapper around BRouter's routing core.
 *
 * BRouter is a blocking, single-threaded engine, so the whole call is moved to
 * a background dispatcher and the coroutine's cancellation is wired to
 * [RoutingEngine.terminate] - a rider who changes their mind should not have to
 * wait out a 60-second search.
 */
class BRouterEngine(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun route(request: RouteRequest): Route = withContext(dispatcher) {
        val tEnter = System.currentTimeMillis()
        android.util.Log.d("BRouterEngine", "route() entered on ${Thread.currentThread().name}")
        require(request.waypoints.size >= 2) { "need at least a start and a destination" }
        if (!request.profile.isFile) {
            throw RoutingException("routing profile missing: ${request.profile.name}")
        }
        if (!File(request.profile.parentFile, LOOKUPS).isFile) {
            throw RoutingException("lookups.dat missing next to the routing profile")
        }
        if (!request.segmentDir.isDirectory ||
            request.segmentDir.listFiles { f -> f.name.endsWith(".rd5") }.isNullOrEmpty()
        ) {
            throw RoutingException("no .rd5 routing tiles imported yet")
        }

        val rc = RoutingContext().apply {
            localFunction = request.profile.absolutePath
            memoryclass = request.memoryClassMb
            turnInstructionMode = 2
            if (request.profileParams.isNotEmpty()) {
                keyValues = HashMap(request.profileParams)
            }
            setAlternativeIdx(request.alternativeIndex)

            if (request.noGos.isNotEmpty() || request.noGoPolygons.isNotEmpty()) {
                val nogoList = ArrayList<OsmNodeNamed>(request.noGos.size + request.noGoPolygons.size)
                for (nogo in request.noGos) {
                    nogoList.add(OsmNodeNamed().apply {
                        ilon = nogo.point.iLon()
                        ilat = nogo.point.iLat()
                        radius = nogo.radiusMeters.toDouble()
                        name = "nogo" + nogo.radiusMeters
                        isNogo = true
                    })
                }
                for (poly in request.noGoPolygons) {
                    val osmPoly = OsmNogoPolygon(poly.isClosed).apply {
                        for (pt in poly.points) {
                            addVertex(pt.iLon(), pt.iLat())
                        }
                        calcBoundingCircle()
                    }
                    nogoList.add(osmPoly)
                }
                nogopoints = nogoList
                RoutingContext.prepareNogoPoints(nogopoints)
            }
        }

        val nodes = request.waypoints.map { point ->
            OsmNodeNamed().apply {
                ilon = point.iLon()
                ilat = point.iLat()
                name = "wp"
            }
        }

        android.util.Log.d("BRouterEngine", "context ready after ${System.currentTimeMillis() - tEnter} ms")
        val engine = RoutingEngine(null, null, request.segmentDir, nodes, rc, 0)
        engine.quite = true
        android.util.Log.d("BRouterEngine", "engine constructed after ${System.currentTimeMillis() - tEnter} ms")

        // BRouter's search is a plain blocking loop that polls a termination
        // flag. Wiring coroutine cancellation to it means a rider who changes
        // the destination mid-search gets the CPU back immediately.
        val cancellation = this.coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) engine.terminate()
        }
        val t0 = System.currentTimeMillis()
        android.util.Log.d("BRouterEngine", "doRun start alt=${request.alternativeIndex} nogos=${request.noGos.size}")
        try {
            engine.doRun(request.maxRunningTimeMillis)
        } finally {
            android.util.Log.d("BRouterEngine", "doRun done in ${System.currentTimeMillis() - t0} ms")
            cancellation?.dispose()
        }
        ensureActive()

        engine.errorMessage?.let { throw RoutingException(riderMessage(it)) }
        val track = engine.foundTrack ?: throw RoutingException("no route found")
        toRoute(track, request.profile.nameWithoutExtension)
    }

    /**
     * Turns BRouter's internal complaints into something a rider can act on.
     *
     * The one worth translating is the lookup version. Every .rd5 tile carries
     * the version of the tag table it was built against, and when that table
     * changes upstream a tile downloaded before the change can no longer be
     * read. Nothing is broken about the install - the tiles just have to be
     * fetched again, and "lookup version mismatch (old rd5?)" does not say so.
     */
    private fun riderMessage(message: String): String =
        if (message.contains("lookup version mismatch")) {
            "these routing tiles were built for an older map format - " +
                "delete the affected regions and download them again ($message)"
        } else {
            message
        }

    /**
     * Calculates the plain route plus BRouter's alternatives and returns the
     * twistiest one that is not an absurd detour.
     *
     * This is the piece that turns "avoid main roads" into "actually find the
     * fun road": BRouter optimises cost, and two routes with near-identical
     * cost can differ hugely in how much fun they are.
     *
     * [alternatives] defaults to 1, not BRouter's full 3: every alternative is
     * another complete pass0/pass1/pass2 search, so the old default of 3 meant
     * every calculation ran BRouter **four** times. Measured on real
     * Niedersachsen tiles (1.Doku/Kurven_Score.md "Messung auf
     * Niedersachsen"), a second alternative past the first rarely changed the
     * winner - on a slow device that cost is better spent elsewhere (a
     * shorter timeout, a snappier reroute).
     *
     * @param maxDetourFactor how much longer than the best route an
     *   alternative may be before it is rejected.
     */
    suspend fun routeCurviest(
        request: RouteRequest,
        alternatives: Int = 1,
        maxDetourFactor: Double = 1.25,
    ): Route {
        val best = route(request.copy(alternativeIndex = 0))
        if (alternatives <= 0) return best

        var winner = best
        var winnerScore = best.curvinessScore

        for (index in 1..alternatives.coerceAtMost(3)) {
            val candidate = runCatching {
                route(request.copy(alternativeIndex = index))
            }.getOrNull() ?: continue

            if (candidate.distanceMeters > best.distanceMeters * maxDetourFactor) continue
            if (candidate.curvinessScore > winnerScore) {
                winner = candidate
                winnerScore = candidate.curvinessScore
            }
        }
        return winner
    }

    private fun toRoute(track: OsmTrack, profileName: String): Route {
        val coordinates = OpenCurvTrackAccess.coordinates(track)
        val elevations = OpenCurvTrackAccess.elevations(track)
        val count = coordinates.size / 2
        val points = ArrayList<GeoPoint>(count)
        for (i in 0 until count) {
            val elevation = elevations.getOrNull(i)?.takeIf { !it.isNaN() }
            points += GeoPoint.fromBRouter(coordinates[i * 2], coordinates[i * 2 + 1], elevation)
        }
        if (points.size < 2) throw RoutingException("route has no geometry")

        val route = Route(
            points = points,
            instructions = buildInstructions(track, points),
            profileName = profileName,
            estimatedSeconds = OpenCurvTrackAccess.totalSeconds(track),
            ascendMeters = track.ascend,
            speedLimitsKmh = readSpeedLimits(track, points.size),
        )
        return route
    }

    /**
     * Turns BRouter voice hints into [NavigationInstruction]s.
     *
     * The angle BRouter reports is only filled in for some commands, so the
     * turn angle is recomputed from the geometry around the maneuver point.
     * That geometry angle is also what decides whether a "sharp left" is really
     * a hairpin.
     */
    private fun buildInstructions(
        track: OsmTrack,
        points: List<GeoPoint>,
    ): List<NavigationInstruction> {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
        }

        val instructions = ArrayList<NavigationInstruction>()
        for (hint in OpenCurvTrackAccess.hints(track)) {
            val index = hint.indexInTrack.coerceIn(0, points.lastIndex)
            val angle = geometryAngleAt(points, index).takeIf { it != 0.0 }
                ?: hint.angle.toDouble()
            val maneuver = Maneuver.fromBRouter(hint.commandName, angle.toFloat())
            if (maneuver == Maneuver.CONTINUE) continue
            instructions += NavigationInstruction(
                pointIndex = index,
                location = points[index],
                maneuver = maneuver,
                distanceFromStart = cumulative[index],
                turnAngleDegrees = angle,
                roundaboutExit = hint.roundaboutExit,
            )
        }

        instructions += NavigationInstruction(
            pointIndex = points.lastIndex,
            location = points.last(),
            maneuver = Maneuver.DESTINATION,
            distanceFromStart = cumulative.last(),
            turnAngleDegrees = 0.0,
        )
        return instructions
    }

    /**
     * Heading change at [index], measured over [ANGLE_WINDOW_M] of road either
     * side. Measuring over a window instead of the two adjacent nodes is what
     * makes a hairpin read as 160 degrees rather than as six 27-degree kinks.
     */
    private fun geometryAngleAt(points: List<GeoPoint>, index: Int): Double {
        if (index <= 0 || index >= points.lastIndex) return 0.0

        var before = index
        var d = 0.0
        while (before > 0 && d < ANGLE_WINDOW_M) {
            d += Geo.distanceMeters(points[before - 1], points[before])
            before--
        }
        var after = index
        d = 0.0
        while (after < points.lastIndex && d < ANGLE_WINDOW_M) {
            d += Geo.distanceMeters(points[after], points[after + 1])
            after++
        }
        if (before == index || after == index) return 0.0
        return Geo.normalizeDelta(
            Geo.bearingDegrees(points[index], points[after]) -
                Geo.bearingDegrees(points[before], points[index]),
        )
    }

    /**
     * Pulls posted speed limits out of the track.
     *
     * BRouter exports every tag the profile references in the per-section way
     * description, and the OpenCurv profiles reference `maxspeed` precisely so
     * this works. Values are forward-filled: a section keeps its limit until
     * the next section states a different one.
     */
    private fun readSpeedLimits(track: OsmTrack, size: Int): IntArray? {
        val descriptions = OpenCurvTrackAccess.wayDescriptions(track)
        if (descriptions.isEmpty()) return null

        val limits = IntArray(size)
        var current = 0
        var found = false
        for (i in 0 until size) {
            descriptions.getOrNull(i)?.let { description ->
                parseMaxSpeed(description)?.let {
                    current = it
                    found = true
                }
            }
            limits[i] = current
        }
        return if (found) limits else null
    }

    private fun parseMaxSpeed(description: String): Int? {
        val marker = description.indexOf("maxspeed=")
        if (marker < 0) return null
        val value = description.substring(marker + "maxspeed=".length)
            .takeWhile { !it.isWhitespace() }
        return when (value) {
            "urban" -> 50
            "rural" -> 100
            else -> value.toIntOrNull()
        }
    }

    private companion object {
        const val LOOKUPS = "lookups.dat"
        const val ANGLE_WINDOW_M = 25.0
    }
}
