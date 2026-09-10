package com.opencurv.curvescore.score

/**
 * Every constant of the OpenCurv curve score, in one place, each with the
 * reason it has the value it has.
 *
 * Rule of the project: a constant that cannot be justified to a motorcyclist
 * in one sentence is the wrong constant. Where a number comes from physics it
 * says so; where it comes from a named real-world road it names the road.
 * Nothing here is fitted or trained.
 */
data class ScoreConfig(
    // ---------------------------------------------------------------- geometry

    /**
     * Douglas-Peucker tolerance applied before any angle is measured, in metres.
     *
     * 0.5 m, chosen against the sagitta of the curves that must survive: a
     * chord of length c on radius R bulges by c^2/(8R), so at this tolerance a
     * 24 m hairpin keeps a node every 9.8 m and a 900 m sweeper every 60 m -
     * both still measure correctly, because the chord/deflection relation that
     * recovers a radius is scale-free. It is also about the positional accuracy
     * of OSM road geometry, so the filter throws away only what is below the
     * data's own resolution. See [com.opencurv.curvescore.geom.douglasPeuckerIndices].
     */
    val simplifyEpsilonM: Double = 0.5,

    /**
     * Floor on the measurement base after simplification, in metres.
     *
     * Douglas-Peucker can legitimately leave two points a metre apart at a
     * sharp feature, and dividing a deflection by a one-metre chord gives a
     * nonsense radius. 4 m is below the tightest spacing the testarena produces
     * (8 m) and below anything a real hairpin needs.
     */
    val minStepM: Double = 4.0,

    /** Deflections below this are digitising noise, not a bend. */
    val noiseDeg: Double = 1.5,

    /** An event turning less than this in total is not a bend worth naming. */
    val minEventDeg: Double = 4.0,

    /**
     * A direction change of at least this much concentrated on a *single*
     * vertex is a corner, not a curve.
     *
     * 40 deg because a road built to a radius, sampled at the OSM-typical 10-30 m,
     * cannot turn 40 deg at one node: that would mean a radius under 15 m *and*
     * a node spacing of 10 m, i.e. the mapper drew a hairpin with one point.
     * Below 40 deg the ambiguity between "coarsely mapped curve" and "kink" is
     * real, and the benefit of the doubt goes to the curve.
     */
    val cornerDeg: Double = 40.0,

    // ------------------------------------------------------- radius quality q_R

    /**
     * Lower end of the sweet-spot plateau, in metres.
     *
     * At a relaxed-but-spirited 0.4 g the cornering speed is v = sqrt(a*R) =
     * sqrt(4 * 40) = 12.6 m/s = 45 km/h - second gear, full lean, with reserve.
     * That is the tightest radius which still feels like riding rather than
     * manoeuvring.
     */
    val radiusSweetLoM: Double = 40.0,

    /**
     * Upper end of the sweet-spot plateau, in metres.
     *
     * Same 0.4 g gives sqrt(4 * 130) = 22.8 m/s = 82 km/h - the top of a legal
     * country-road pace. Above this radius you cannot reach a satisfying lean
     * angle without breaking the speed limit.
     */
    val radiusSweetHiM: Double = 130.0,

    /**
     * Below this radius a bend stops being a road curve at all: a hotel
     * driveway, a roundabout, a farm turn-off.
     */
    val radiusFloorM: Double = 8.0,

    /**
     * Residual quality at [radiusFloorM]. Hairpins are the heart of an alpine
     * pass, so the tight side must not fall to zero - but a 8 m turn is
     * steering-lock work at walking pace, worth a quarter of a real curve.
     */
    val radiusFloorQuality: Double = 0.25,

    /**
     * Decay exponent above [radiusSweetHiM]: q = (R_hi / R)^p.
     *
     * At a fixed 80 km/h the required lean angle is 22 deg at R = 130 m, 11 deg at
     * 260 m, 6 deg at 520 m, 3 deg at 900 m - it falls as 1/R. The exponent is set
     * slightly steeper (1.5) because a wide sweeper additionally costs the
     * rider nothing: no braking, no line choice, no gear change. Result:
     * "twice the sweet-spot radius is worth a third, four times an eighth, a
     * motorway sweeper nothing".
     */
    val radiusDecayExponent: Double = 1.5,

    // ------------------------------------------------------------ curve density

    /**
     * Reference curvature density at which the density term saturates, in
     * radians of **quality-weighted** direction change per metre.
     *
     * 0.003 rad/m = 3 rad/km = 172 deg/km of sweet-spot-grade cornering. Read it
     * as: *a 60 deg curve of ideal radius every 350 m, sustained*.
     *
     * Cross-check against a road everyone knows: the north ramp of the Stelvio
     * has 48 hairpins over 24.3 km, ~170 deg each at a radius near 15 m. The
     * radius quality of a 15 m hairpin is 0.41, so its quality-weighted density
     * is 2 * 2.97 * 0.41 = 2.4 rad/km - it reaches 0.90 of the reference, not
     * 1.0. That is deliberate and defensible: the Stelvio is magnificent, but
     * per kilometre of *riding* a continuous 45 m-radius sweeper road is denser
     * and less first-gear work. What makes the Stelvio the Stelvio - the
     * altitude, the gradient, the view - is picked up by the terrain and
     * scenery terms, not by pretending its curve density is unbeatable.
     */
    val densityRefRadPerM: Double = 0.003,

    /**
     * Compression exponent applied to density/densityRef before it becomes the
     * term value (0.5 = square root).
     *
     * Without it the score is unusable in practice: 95 % of the European road
     * network sits below 10 % of the Stelvio reference, so almost everything
     * would collapse into levels 0-1 and the router would have no signal to
     * work with. The square root spreads the ordinary range across the scale
     * while keeping the ordering intact - it is monotone, so no comparison
     * between two roads can flip because of it.
     */
    val densityCompression: Double = 0.5,

    // --------------------------------------------------------- curve engagement

    /**
     * How far before and after a curve the rider is still "in" it - braking,
     * choosing the line, looking through, driving out.
     *
     * 100 m is about 4.5 s at 80 km/h, which is roughly the horizon over which
     * a curve is being set up and unwound.
     */
    val engagementHaloM: Double = 100.0,

    /** Sampling step of the engagement profile. Fine enough for a 100 m halo. */
    val engagementSampleM: Double = 25.0,

    // ----------------------------------------------------------- S-curve linkage

    /**
     * Decay length for the "the next curve goes the other way" bonus.
     *
     * At ~70 km/h (19 m/s) 300 m is about 15 s. Beyond that the bike has been
     * upright and stable for long enough that the two curves are separate
     * events, not a linked change of direction. Value decays as exp(-gap/300 m).
     */
    val alternationLinkLengthM: Double = 300.0,

    /**
     * A curve only counts towards the S-curve term if its radius quality is at
     * least this - otherwise a pair of motorway sweepers would earn the same
     * "dynamic direction change" bonus as a real S.
     */
    val alternationMinQuality: Double = 0.30,

    /**
     * Radius quality at which a bend counts as fully "present" for the
     * engagement term. Presence is not the same question as quality: the
     * density term already discounts a 15 m hairpin for being first-gear work,
     * and discounting it a second time here would punish exactly the roads the
     * app exists for. So engagement weights a curve by min(1, q / 0.5) - any
     * curve between roughly 19 m and 215 m radius is simply "a curve", while a
     * motorway sweeper (q = 0.05) barely registers.
     */
    val engagementFullQuality: Double = 0.50,

    // ------------------------------------------------------------------- rhythm

    /**
     * Context length over which curve spacing regularity is judged. Longer than
     * the scoring window on purpose: rhythm is a property of a stretch of road,
     * not of a single kilometre, and needs at least three curves to exist.
     */
    val rhythmWindowM: Double = 3000.0,

    // ----------------------------------------------------------------- gradient

    /** Gradient at which the terrain bonus reaches its plateau (fraction, 0.04 = 4 %). */
    val gradientPlateauLo: Double = 0.04,

    /** Upper end of the plateau. Alpine passes average 6-8 %. */
    val gradientPlateauHi: Double = 0.08,

    /**
     * Gradient above which the bonus is gone: beyond 15 % a road is a first-gear
     * ramp - usually a mountain-hut access road, often with a surface to match.
     */
    val gradientZeroAt: Double = 0.15,

    /** Smoothing length of the elevation profile, to suppress DEM step noise. */
    val gradientSmoothM: Double = 200.0,

    // ------------------------------------------------------------------ weights
    // The six positive terms; they sum to 1.0 so the weighted sum is in [0,1]
    // and every weight reads directly as "share of the verdict".

    /** Curvature density - how much good-quality direction change per metre. */
    val wDensity: Double = 0.50,

    /** Curve engagement - what share of the road is curve, approach or exit. */
    val wEngagement: Double = 0.12,

    /** S-curves - dynamic changes of direction, explicitly requested by the brief. */
    val wAlternation: Double = 0.12,

    /** Rhythm - are the curves evenly strung together or bunched. */
    val wRhythm: Double = 0.05,

    /** Terrain - gradient as a proxy for mountains, views and engine load. */
    val wGradient: Double = 0.07,

    /** Surroundings - forest, water, meadow versus industrial estate. */
    val wScenery: Double = 0.08,

    /** Road character - the kind of road it is, independent of its geometry. */
    val wRoadClass: Double = 0.06,

    // ---------------------------------------------------------------- penalties

    /**
     * Corner rate (weighted 90-deg-equivalents per km) at which the value is
     * halved. P = 1 / (1 + (rate/k)^2).
     *
     * k = 0.8/km: roughly one right-angle turn per 1.25 km costs half the
     * score. Below that the corners are isolated features a rider absorbs;
     * above it you are navigating a street grid or a field-track network and
     * are stopping and restarting constantly. The exponent 2 (rather than 1)
     * encodes that corners compound - each one also destroys the rhythm the
     * previous curves built.
     */
    val cornerRateHalfPerKm: Double = 0.8,

    /**
     * Share of the value that a fully built-up stretch loses.
     *
     * 0.75, i.e. a road entirely inside a residential/commercial/industrial
     * area keeps a quarter of its score. Not zero, because a village high
     * street is still rideable and sometimes unavoidable - but a curve at
     * 50 km/h between parked cars and driveways is not a curve you came for.
     */
    val settlementMaxPenalty: Double = 0.75,

    /**
     * Interruption rate (weighted stops per km) at which the value is halved:
     * P = 1 / (1 + rate/k), k = 3.0/km. Three interruptions per kilometre is
     * town-centre density. A single traffic light every 2 km costs ~14 %.
     */
    val trafficRateHalfPerKm: Double = 3.0,

    // -------------------------------------------------------------- quantisation

    /**
     * Number of discrete output levels. 16 (0..15) by default because that is
     * one nibble and fits any plausible BRouter lookup encoding; the value is
     * configurable because whether a .brf profile can use the number
     * arithmetically or only by equality comparison is still open.
     */
    val levels: Int = 16,

    /** Sliding-window length. See ScoreConfig.windowNote below. */
    val windowM: Double = 1000.0,

    /** Spacing of window centres. */
    val windowStepM: Double = 100.0,

    /**
     * How far the corridor is followed beyond each end of the way to fill the
     * window. One window length on each side, so every window over the way's
     * own extent can be complete.
     */
    val contextM: Double = 1000.0,

    /** If true, unpaved surfaces are *not* penalised (enduro / adventure profile). */
    val enduroProfile: Boolean = false,
) {
    init {
        val sum = wDensity + wEngagement + wAlternation + wRhythm + wGradient + wScenery + wRoadClass
        require(kotlin.math.abs(sum - 1.0) < 1e-9) { "positive-term weights must sum to 1.0, got $sum" }
        require(levels >= 2) { "levels must be >= 2" }
    }

    companion object {
        /**
         * Why a 1000 m sliding window and not the OSM way.
         *
         * An OSM way is an arbitrary unit: the same physical road is one way of
         * 9 km here and eleven ways of 40 m there, split wherever a bridge, a
         * speed limit or a name changes. Scoring per way would make the number
         * depend on how a mapper happened to cut the data - the same road would
         * score differently in two neighbouring districts.
         *
         * A rider's judgement is not per way either. "This road is good" forms
         * over roughly half a minute of riding, which at 60-80 km/h is
         * 500-1300 m. 1000 m sits in the middle of that.
         *
         * So the score is computed on 1000 m windows sliding in 100 m steps
         * along a *corridor* - the way plus its straight continuation on both
         * sides - and only afterwards projected back onto the way as the mean
         * over the window centres that fall inside it. A 40 m way therefore
         * inherits the character of the kilometre it sits in, which is what a
         * rider experiences, and splitting or merging ways does not change the
         * result.
         */
        const val WINDOW_NOTE = "sliding 1000 m windows over the corridor, projected back onto the way"
    }
}
