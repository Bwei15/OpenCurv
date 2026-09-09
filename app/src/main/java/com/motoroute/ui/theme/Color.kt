package com.motoroute.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Two palettes, both built for one job: being readable through a visor, in
 * direct sun or at night, at a glance measured in tenths of a second.
 *
 * Nothing here is decorative. Every colour is either maximum-contrast
 * information or deliberate background.
 */
object OpenCurvColors {

    // ---- day: near-black on off-white, high contrast without the glare ----
    val DayBackground = Color(0xFFF6F4F0)
    val DaySurface = Color(0xFFFFFFFF)
    val DayOnSurface = Color(0xFF111111)
    val DayOnSurfaceMuted = Color(0xFF4A4A4A)
    val DayRoute = Color(0xFF0055FF)
    val DayAccent = Color(0xFF0044CC)
    val DayHudBackground = Color(0xFF14171A)
    val DayHudForeground = Color(0xFFFFFFFF)
    val DayPanel = Color(0xFFFFFFFF)

    // ---- night: true black for OLED, neon green for the route ----
    val NightBackground = Color(0xFF000000)
    val NightSurface = Color(0xFF000000)
    val NightOnSurface = Color(0xFFEDEDED)
    val NightOnSurfaceMuted = Color(0xFF9A9A9A)
    val NightRoute = Color(0xFF00FF66)
    val NightAccent = Color(0xFF00FF66)
    val NightHudBackground = Color(0xFF000000)
    val NightHudForeground = Color(0xFFFFFFFF)
    val NightPanel = Color(0xFF111417)

    // ---- the rider and the destination, on the map ----
    /** The position puck. Blue because every map on earth uses blue for "you". */
    val DayRider = Color(0xFF1A73E8)
    val NightRider = Color(0xFF54A6FF)

    /** The destination pin: the one thing on the map that has to be found instantly. */
    val DayDestination = Color(0xFFD93025)
    val NightDestination = Color(0xFFFF6B5E)

    // ---- shared status colours ----
    val Warning = Color(0xFFFFB300)
    val Danger = Color(0xFFFF2D2D)
    val Ok = Color(0xFF00C853)
}
