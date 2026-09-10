package com.motoroute.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OpenCurv colour tokens.
 *
 * The hard constraint is the map, not taste. `1.Doku/Map_Design.md` paints a
 * warm paper ground (#F1EFE8) with yellow trunk roads (#FFE082 over #E5C16C),
 * *white* minor roads, green woodland and pale blue water. The map fills the
 * screen; every control floats on top of it. So:
 *
 *  1. Nothing readable ever sits directly on the map. Text always gets a plate.
 *     Reason: the brightest map element is pure white, so no light-on-map text
 *     can be guaranteed any contrast at all (1.00:1 worst case).
 *  2. Every floating plate carries a casing (a 1 dp rim) that reaches >= 3:1
 *     against *every* map colour, including the white minor roads. A drop
 *     shadow alone disappears in low sun through a tinted visor.
 *  3. Only geometry - the route, the puck, the pin - is drawn straight onto the
 *     map, and it always wears a dark casing, exactly the way the map casings
 *     its own roads.
 *
 * Every value below was measured, not guessed; the ratios live in
 * `1.Doku/Design_System.md`. Two registers share one palette: the resting
 * register (planning, regions, settings) may be light and playful, the riding
 * register (HUD) is dark and blunt in both day and night.
 */
object OpenCurvColors {

    // ------------------------------------------------------------------
    // Brand
    // ------------------------------------------------------------------

    /**
     * Kurvenblau. Cool and saturated so it wins against a warm yellow-and-beige
     * map: 7.47:1 on white, 4.55:1 as a route line against #FFE082.
     */
    val Indigo = Color(0xFF2440D9)
    val IndigoPressed = Color(0xFF1A2FA6)
    val IndigoTint = Color(0xFFE4E7FD)
    val IndigoLight = Color(0xFF8AA6FF)
    val IndigoDeep = Color(0xFF06102E)

    /**
     * Kurvenmagenta. The other pole of the brand: the far end of the curviness
     * scale and the destination pin. Deliberately magenta rather than red so it
     * never reads as a warning (1.03:1 against the danger red - a different
     * hue, not a different brightness, is what tells them apart).
     */
    val Magenta = Color(0xFFB31D6B)
    val MagentaTint = Color(0xFFF9E6F0)
    val MagentaLight = Color(0xFFFF7A9E)

    // ------------------------------------------------------------------
    // Day register - the resting screens, and the map underneath them
    // ------------------------------------------------------------------

    /** The map's own paper. Map-less screens use it so the app feels continuous. */
    val DayCanvas = Color(0xFFF1EFE8)

    /** Cards, sheets, floating controls. */
    val DayPlate = Color(0xFFFFFFFF)

    /** Input fields and list wells, one step below the plate. */
    val DayPlateSunken = Color(0xFFE9E5DB)

    /**
     * The plate's casing. Warm graphite, dark enough to hold >= 3:1 against the
     * *darkest* thing the map can put behind it (#E5C16C, 3.36:1) as well as
     * against the brightest (#FFFFFF, 5.79:1).
     */
    val DayPlateRim = Color(0xFF6B6558)

    /** Hairline between rows inside a plate. Decorative only - never a boundary. */
    val DayDivider = Color(0xFFDDD8CC)

    val DayInk = Color(0xFF14181F)
    val DayInkMuted = Color(0xFF4F5A66)
    val DayInkFaint = Color(0xFF5E6773)

    // ------------------------------------------------------------------
    // Night register
    // ------------------------------------------------------------------

    /**
     * Not pure black. A true #000000 makes the panel edge invisible against the
     * phone bezel, and OLED black smear during a map pan is worst from zero.
     */
    val NightCanvas = Color(0xFF0A0E14)
    val NightPlate = Color(0xFF151B24)
    val NightPlateSunken = Color(0xFF0F141B)

    /**
     * At night the plate and the map are both near-black (1.05:1), so the rim
     * is the *only* thing separating them and has to be light: 4.38:1 against
     * the plate, 4.59:1 against the night map ground.
     */
    val NightPlateRim = Color(0xFF77818E)
    val NightDivider = Color(0xFF262E3A)

    val NightInk = Color(0xFFF3F5F8)
    val NightInkMuted = Color(0xFFA6B1BE)
    val NightInkFaint = Color(0xFF8A95A2)

    // ------------------------------------------------------------------
    // HUD - identical day and night
    // ------------------------------------------------------------------

    /**
     * The riding chrome stays dark around the clock. In sun a dark plate with
     * white glyphs loses less to veiling glare than the reverse, it separates
     * maximally from a light map, and at night it costs no OLED power.
     */
    val HudSurface = Color(0xFF0D1219)
    val HudInk = Color(0xFFFFFFFF)
    val HudInkMuted = Color(0xFFB9C3CF)

    /** Static captions only ("km/h", "Rest"). Learned once, never read at speed. */
    val HudInkFaint = Color(0xFF8B96A3)
    val HudDivider = Color(0xFF232C3A)

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    val DayOk = Color(0xFF0F7A34)
    val DayWarning = Color(0xFF8A5200)
    val DayDanger = Color(0xFFC0172B)

    val NightOk = Color(0xFF5BD98A)
    val NightWarning = Color(0xFFFFC061)
    val NightDanger = Color(0xFFFF6B6B)

    /**
     * Banner fields in the HUD. Light field, near-black text - the inverse of
     * the rest of the HUD, which is exactly why a banner cannot be overlooked.
     */
    val HudOk = Color(0xFF5BD98A)
    val HudWarning = Color(0xFFFFC061)
    val HudDanger = Color(0xFFFF7B7B)

    // ------------------------------------------------------------------
    // Geometry drawn onto the map
    // ------------------------------------------------------------------

    /** Route core and its casing, in the map's own two-part road grammar. */
    val DayRouteCore = Color(0xFF2F4BFF)
    val DayRouteCasing = Color(0xFF0A1560)
    val NightRouteCore = Color(0xFF6E90FF)
    val NightRouteCasing = Color(0xFF050B26)

    /** An alternative that was calculated but not chosen. */
    val DayRouteAlternative = Color(0xFF7C8794)
    val NightRouteAlternative = Color(0xFF5A6675)

    /** The position puck. Blue because every map on earth uses blue for "you". */
    val DayRider = Color(0xFF0A84FF)
    val NightRider = Color(0xFF4FA8FF)

    /** The ring that lifts the puck off whatever it is standing on. */
    val RiderRing = Color(0xFFFFFFFF)

    /** The destination pin: the one thing on the map found without searching. */
    val DayDestination = Magenta
    val NightDestination = MagentaLight

    // ------------------------------------------------------------------
    // Curviness ramp - the one place the palette is allowed to have fun
    // ------------------------------------------------------------------

    /**
     * Five stops on a single continuous hue path from Kurvenblau to
     * Kurvenmagenta. Straighter is bluer, curvier is pinker; the ramp is not an
     * arbitrary rainbow but a walk between the two brand poles. Every stop
     * clears 5.5:1 on the day canvas and 6.4:1 on the night plate, so the
     * *label* stays readable even for a rider who cannot separate the hues.
     */
    val DayCurviness = listOf(
        Color(0xFF2440D9),
        Color(0xFF5B34C9),
        Color(0xFF8E2CB5),
        Color(0xFFA82392),
        Color(0xFFB31D6B),
    )
    val NightCurviness = listOf(
        Color(0xFF8AA6FF),
        Color(0xFFA88CFF),
        Color(0xFFD080F0),
        Color(0xFFFF7ECB),
        Color(0xFFFF7A9E),
    )

    // ------------------------------------------------------------------
    // Compatibility aliases
    //
    // The names the rest of the app already imports. Kept so this palette can
    // be swapped in without touching a single call site.
    // ------------------------------------------------------------------

    val DayBackground = DayCanvas
    val DaySurface = DayPlate
    val DayOnSurface = DayInk
    val DayOnSurfaceMuted = DayInkMuted
    val DayRoute = DayRouteCore
    val DayAccent = Indigo
    val DayHudBackground = HudSurface
    val DayHudForeground = HudInk
    val DayPanel = DayPlate

    val NightBackground = NightCanvas
    val NightSurface = NightCanvas
    val NightOnSurface = NightInk
    val NightOnSurfaceMuted = NightInkMuted
    val NightRoute = NightRouteCore
    val NightAccent = IndigoLight
    val NightHudBackground = HudSurface
    val NightHudForeground = HudInk
    val NightPanel = NightPlate

    /** @suppress kept for source compatibility; prefer the day/night variants. */
    val Warning = HudWarning

    /** @suppress kept for source compatibility; prefer the day/night variants. */
    val Danger = HudDanger

    /** @suppress kept for source compatibility; prefer the day/night variants. */
    val Ok = HudOk
}
