package com.opencurv.curvescore.score

/**
 * Everything the score reads out of a way's tags - road character, surface,
 * access, interruptions - together with the fallbacks for the (very common)
 * case that the tag simply is not there.
 *
 * Data reality, and the reason this file is as defensive as it is: in a typical
 * German Geofabrik extract roughly two thirds of all `highway=*` ways carry no
 * `surface`, and a similar share carries no `maxspeed`. A score that needs
 * those tags is worthless outside a demo. So every lookup here has a documented
 * prior, and the confidence that prior deserves is carried along and reported.
 */
object RoadTags {

    /** Highway values that a motorcycle may not or would not use at all. */
    private val NON_ROAD = setOf(
        "footway", "path", "cycleway", "steps", "pedestrian", "bridleway",
        "corridor", "platform", "proposed", "construction", "raceway",
        "elevator", "via_ferrata", "escape", "bus_guideway", "busway",
    )

    /**
     * Road character baseline B in [0,1]: how good a road of this class is for
     * a motorcyclist *before* looking at a single curve.
     *
     * The classic motorcycle road is the tertiary/secondary Landstrasse: little
     * traffic, decent surface, no tolls, direct access to the landscape.
     * Primary carries lorries and overtaking pressure. Motorway is transit -
     * fast, monotonous, and the opposite of the reason to own a motorcycle.
     * Residential and service are places, not roads.
     */
    private val CLASS_BASE = mapOf(
        "motorway" to 0.30, "motorway_link" to 0.25,
        "trunk" to 0.40, "trunk_link" to 0.35,
        "primary" to 0.60, "primary_link" to 0.50,
        "secondary" to 0.85, "secondary_link" to 0.70,
        "tertiary" to 0.90, "tertiary_link" to 0.75,
        "unclassified" to 0.80,
        "residential" to 0.25,
        "living_street" to 0.10,
        "service" to 0.15,
        "track" to 0.35,
        "road" to 0.55,
    )

    /**
     * Surface factor for a road/sport-touring profile. Paved and smooth is 1.0;
     * everything else costs, because grip and comfort are what let a rider use
     * a curve at all.
     *
     * Cobblestone/sett is scored *worse* than gravel on purpose: it is nominally
     * "paved", but on two wheels a wet cobbled bend is the least confidence-
     * inspiring surface there is.
     */
    private val SURFACE_FACTOR = mapOf(
        "asphalt" to 1.0, "paved" to 1.0, "concrete" to 0.98,
        "concrete:plates" to 0.80, "concrete:lanes" to 0.70, "chipseal" to 0.95,
        "metal" to 0.70, "wood" to 0.55,
        "paving_stones" to 0.60, "bricks" to 0.50, "sett" to 0.45, "cobblestone" to 0.40,
        "unhewn_cobblestone" to 0.30,
        "compacted" to 0.70, "fine_gravel" to 0.70,
        "gravel" to 0.50, "pebblestone" to 0.45, "unpaved" to 0.50, "rock" to 0.30,
        "ground" to 0.30, "dirt" to 0.30, "earth" to 0.30, "grass" to 0.25,
        "sand" to 0.20, "mud" to 0.15, "woodchips" to 0.20, "salt" to 0.25,
        "ice" to 0.10, "snow" to 0.10,
    )

    /**
     * Prior surface factor when the tag is missing, per highway class, together
     * with the confidence of that prior.
     *
     * The values are shrunk towards 1.0 relative to what the class-typical
     * surface would give: a `track` without a surface tag is *probably*
     * unpaved (factor 0.50), but often enough it is a paved farm road, so the
     * prior is 0.62 rather than 0.50. That is deliberate statistical shrinkage
     * towards the neutral value - guessing hard from a missing tag is how a
     * scorer ends up confidently wrong.
     */
    private val SURFACE_PRIOR = mapOf(
        "motorway" to (0.98 to 0.95), "motorway_link" to (0.98 to 0.95),
        "trunk" to (0.98 to 0.95), "trunk_link" to (0.98 to 0.95),
        "primary" to (0.98 to 0.93), "primary_link" to (0.98 to 0.93),
        "secondary" to (0.98 to 0.92), "secondary_link" to (0.98 to 0.92),
        "tertiary" to (0.97 to 0.88), "tertiary_link" to (0.97 to 0.88),
        "unclassified" to (0.94 to 0.75),
        "residential" to (0.97 to 0.88),
        "living_street" to (0.95 to 0.80),
        "service" to (0.92 to 0.70),
        "track" to (0.62 to 0.35),
        "road" to (0.90 to 0.55),
    )

    /** `tracktype` is a decent stand-in when `surface` is missing on a track. */
    private val TRACKTYPE_FACTOR = mapOf(
        "grade1" to 0.85, "grade2" to 0.65, "grade3" to 0.50, "grade4" to 0.35, "grade5" to 0.25,
    )

    /** `smoothness` refines whatever surface says - it is about the state, not the material. */
    private val SMOOTHNESS_FACTOR = mapOf(
        "excellent" to 1.05, "good" to 1.02, "intermediate" to 1.0,
        "bad" to 0.75, "very_bad" to 0.55, "horrible" to 0.40,
        "very_horrible" to 0.30, "impassable" to 0.10,
    )

    /** Interruption weights for node tags, in "equivalent full stops". */
    private val STOP_WEIGHT_HIGHWAY = mapOf(
        "traffic_signals" to 1.0,
        "stop" to 0.7,
        "give_way" to 0.25,
        "crossing" to 0.3,
        "mini_roundabout" to 0.6,
        "turning_circle" to 0.2,
        "speed_camera" to 0.4,
    )

    fun isRoutableRoad(tags: Map<String, String>): Boolean {
        val hw = tags["highway"] ?: return false
        if (hw in NON_ROAD) {
            // A path that explicitly allows motor vehicles is a road after all.
            return tags["motor_vehicle"] == "yes" || tags["motorcycle"] == "yes"
        }
        return true
    }

    /** Explicitly forbidden to us: still tagged, but with level 0, so the router sees it. */
    fun isForbidden(tags: Map<String, String>): Boolean {
        val access = tags["access"]
        val mv = tags["motor_vehicle"] ?: tags["motorcar"]
        val mc = tags["motorcycle"]
        if (mc == "no") return true
        if (mv == "no" || mv == "private") return mc != "yes"
        if (access == "no" || access == "private") return mc != "yes" && mv != "yes" && tags["motorcar"] != "yes"
        return false
    }

    fun classBase(tags: Map<String, String>): Double =
        CLASS_BASE[tags["highway"]] ?: 0.55

    /**
     * Surface factor plus the confidence in it.
     * @return factor in (0,1], confidence in [0,1]
     */
    fun surfaceFactor(tags: Map<String, String>, cfg: ScoreConfig): Pair<Double, Double> {
        if (cfg.enduroProfile) return 1.0 to 1.0
        val smooth = SMOOTHNESS_FACTOR[tags["smoothness"]]
        val explicit = tags["surface"]?.let { SURFACE_FACTOR[it.substringBefore(';')] }
        if (explicit != null) {
            val f = (explicit * (smooth ?: 1.0)).coerceIn(0.05, 1.0)
            return f to (if (smooth != null) 1.0 else 0.9)
        }
        val trackType = tags["tracktype"]?.let { TRACKTYPE_FACTOR[it] }
        if (trackType != null) {
            val f = (trackType * (smooth ?: 1.0)).coerceIn(0.05, 1.0)
            return f to 0.7
        }
        val (prior, conf) = SURFACE_PRIOR[tags["highway"]] ?: (0.90 to 0.5)
        val f = (prior * (smooth ?: 1.0)).coerceIn(0.05, 1.0)
        return f to (if (smooth != null) minOf(1.0, conf + 0.2) else conf)
    }

    /**
     * Speed limit with a fallback, plus a flag whether it was guessed.
     * Only used as a *settlement* indicator, never as a curve-quality input -
     * see the report for why tying curve value to maxspeed would be circular.
     */
    fun maxspeedKmh(tags: Map<String, String>): Pair<Int?, Boolean> {
        val raw = tags["maxspeed"] ?: return null to true
        val t = raw.trim()
        if (t == "none") return 200 to false
        if (t == "walk") return 7 to false
        val mph = t.endsWith("mph")
        val num = t.removeSuffix("mph").trim().toIntOrNull() ?: return null to true
        return (if (mph) (num * 1.609).toInt() else num) to false
    }

    /** Weighted "full stop equivalents" contributed by a node's own tags. */
    fun stopWeight(tags: Map<String, String>): Double {
        var w = 0.0
        STOP_WEIGHT_HIGHWAY[tags["highway"]]?.let { w += it }
        if (tags.containsKey("traffic_calming")) w += 0.5
        when (tags["barrier"]) {
            "gate", "lift_gate", "swing_gate" -> w += 0.6
            "bollard", "block", "cycle_barrier" -> w += 0.4
            "toll_booth" -> w += 0.8
        }
        if (tags["railway"] == "level_crossing") w += 0.4
        return w
    }

    /** Way-level interruption weight (roundabouts, toll gates spread over the way). */
    fun wayStopWeight(tags: Map<String, String>): Double {
        var w = 0.0
        if (tags["junction"] == "roundabout" || tags["junction"] == "circular") w += 0.8
        if (tags["toll"] == "yes") w += 0.3
        if (tags["highway"]?.endsWith("_link") == true) w += 0.3
        return w
    }
}
