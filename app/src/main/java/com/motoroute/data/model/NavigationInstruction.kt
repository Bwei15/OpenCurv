package com.motoroute.data.model

/**
 * The maneuver types OpenCurv renders. These map 1:1 onto BRouter's voice-hint
 * commands, with [HAIRPIN_LEFT] / [HAIRPIN_RIGHT] split out of the sharp turns
 * because a Spitzkehre needs its own icon and its own early warning.
 */
enum class Maneuver {
    CONTINUE,
    KEEP_LEFT,
    KEEP_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    HAIRPIN_LEFT,
    HAIRPIN_RIGHT,
    UTURN_LEFT,
    UTURN_RIGHT,
    ROUNDABOUT,
    ROUNDABOUT_LEFT,
    DESTINATION,
    OFF_ROUTE,
    ;

    val isTurn: Boolean
        get() = this != CONTINUE && this != DESTINATION && this != OFF_ROUTE

    companion object {
        /**
         * Translates a BRouter command name ("TL", "TSHR", "RNDB3", ...) plus
         * the measured turn angle into a maneuver.
         *
         * BRouter has no dedicated hairpin command, so anything past
         * [HAIRPIN_DEGREES] of heading change is promoted from a sharp turn to
         * a hairpin - that is exactly the switchback a rider wants warned about
         * early.
         */
        const val HAIRPIN_DEGREES = 110.0

        fun fromBRouter(command: String, angleDegrees: Float): Maneuver {
            val sharp = kotlin.math.abs(angleDegrees) >= HAIRPIN_DEGREES
            return when {
                command.startsWith("RNDB") -> ROUNDABOUT
                command.startsWith("RNLB") -> ROUNDABOUT_LEFT
                command == "C" || command == "BL" -> CONTINUE
                command == "KL" -> KEEP_LEFT
                command == "KR" -> KEEP_RIGHT
                command == "TSLL" -> SLIGHT_LEFT
                command == "TSLR" -> SLIGHT_RIGHT
                command == "TL" -> TURN_LEFT
                command == "TR" -> TURN_RIGHT
                command == "TSHL" -> if (sharp) HAIRPIN_LEFT else SHARP_LEFT
                command == "TSHR" -> if (sharp) HAIRPIN_RIGHT else SHARP_RIGHT
                command == "TLU" || command == "TU" -> UTURN_LEFT
                command == "TRU" -> UTURN_RIGHT
                command == "OFFR" -> OFF_ROUTE
                else -> CONTINUE
            }
        }
    }
}

/**
 * One turn-by-turn step.
 *
 * @param pointIndex index into [Route.points] at which the maneuver happens
 * @param distanceFromStart metres from the route start to the maneuver point
 * @param turnAngleDegrees signed heading change, negative = left
 * @param roundaboutExit 1-based exit number, 0 when not a roundabout
 */
data class NavigationInstruction(
    val pointIndex: Int,
    val location: GeoPoint,
    val maneuver: Maneuver,
    val distanceFromStart: Double,
    val turnAngleDegrees: Double,
    val roundaboutExit: Int = 0,
    val roadClass: String? = null,
)
