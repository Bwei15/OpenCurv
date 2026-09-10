package com.motoroute.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One spacing scale, one radius scale, one elevation scale. Everything in the
 * app is a member of these; a number that is not in here needs a reason in
 * `1.Doku/Design_System.md`.
 */
object Space {
    /** Optical nudges only: icon against its own label. */
    val Hair: Dp = 2.dp

    /** Inside a chip, between a value and its caption. */
    val Xs: Dp = 4.dp

    /** Between tightly related items - the rungs of one readout. */
    val Sm: Dp = 8.dp

    /** Between controls in a group. Also the minimum gap between riding targets. */
    val Md: Dp = 12.dp

    /** Plate padding, screen gutter on the resting screens. */
    val Lg: Dp = 16.dp

    /** Between blocks that mean different things. */
    val Xl: Dp = 24.dp

    /** Section break inside a scrolling screen. */
    val Xxl: Dp = 32.dp

    /** Above the primary action at the bottom of a full-screen step. */
    val Huge: Dp = 48.dp

    /** The 4 dp grid everything above is built from. */
    val Grid: Dp = 4.dp
}

/**
 * Radii.
 *
 * The resting register is generous and rounded - that is where the app is
 * allowed to look friendly. The riding register is not: HUD bars run edge to
 * edge with no radius at all, because a rounded corner on a full-width bar
 * only buys back a sliver of map nobody looks at, while costing the bar its
 * hard horizon line.
 */
object Radius {
    /** Chips, badges, hairline containers. */
    val Xs: Dp = 8.dp

    /** Small icon buttons on the resting screens. */
    val Sm: Dp = 12.dp

    /** Inputs, list rows, secondary buttons. */
    val Md: Dp = 16.dp

    /** Plates: cards, floating map controls, the search bar. */
    val Lg: Dp = 20.dp

    /** Sheets, dialogs, the region cards in the download list. */
    val Xl: Dp = 28.dp

    /** Pills and the position puck. */
    val Full: Dp = 999.dp

    /** HUD bars. Deliberately square. */
    val None: Dp = 0.dp
}

/**
 * Elevation.
 *
 * Elevation is depth, never separation - the casing does separation. A shadow
 * is invisible in direct sun and worse than invisible on a white minor road,
 * so no element may rely on it to be found.
 */
object Elevation {
    val Flat: Dp = 0.dp

    /** A plate resting on the canvas (no map behind it). */
    val Resting: Dp = 1.dp

    /** A plate floating over the map. */
    val Floating: Dp = 3.dp

    /** A draggable sheet. */
    val Sheet: Dp = 6.dp

    /** A dialog, which must clearly own the screen. */
    val Dialog: Dp = 12.dp
}

/**
 * Stroke weights, in dp, for the drawn parts of the interface.
 */
object Stroke {
    /** The casing of a floating plate. */
    val Rim: Dp = 1.dp

    /** A row divider inside a plate. */
    val Divider: Dp = 1.dp

    /** The white ring around the position puck. */
    val PuckRing: Dp = 3.dp

    /** The dark casing around the puck, the pin and the route. */
    val Casing: Dp = 2.dp

    /** The route core while planning. */
    val RoutePlanned: Dp = 6.dp

    /** The route core while riding - thicker, because it is read at a glance. */
    val RouteActive: Dp = 10.dp

    /** Focus indicator, drawn as white outside and Indigo inside. */
    val Focus: Dp = 2.dp
}

// ----------------------------------------------------------------------
// Touch targets
// ----------------------------------------------------------------------

/**
 * Minimum touch target while the bike is moving.
 *
 * 84 dp is 13.3 mm - still narrower than a gloved fingertip's contact patch
 * (about 16-20 mm), which is why the *gap* matters as much as the square:
 * combined with [RideTargetGap] the pitch is 96 dp = 15.2 mm, and a miss lands
 * in dead space rather than on the neighbouring control. Four such controls
 * plus their gaps are 372 dp, which still fits between the maneuver bar and
 * the bottom bar on a 640 dp screen. That is the reason it is 84 and not 96.
 */
val GloveTargetSize: Dp = 84.dp

/** Alias with a name that says when it applies rather than what you wear. */
val RideTargetSize: Dp = GloveTargetSize

/** Mandatory dead space between two riding targets. Never smaller. */
val RideTargetGap: Dp = Space.Md

/**
 * The single most consequential riding control - Stop, and Recenter while the
 * map is loose - gets a bigger square than its neighbours so it can be found
 * by shape alone, without reading its icon.
 */
val CriticalTargetSize: Dp = 96.dp

/**
 * Touch target for the screens used standing still.
 *
 * Downloads, settings and the file list are operated with the engine off and
 * usually without gloves. Keeping the 84 dp riding targets there wasted half
 * the screen and pushed the controls into each other; 56 dp is 8.9 mm, above
 * Android's 48 dp minimum and above WCAG 2.5.5's 44 x 44.
 */
val TapTargetSize: Dp = 56.dp

/** The bar heights the HUD layout is built on. */
object HudMetrics {
    /** Maneuver bar. Holds a 104 dp arrow plus the 56 sp distance. */
    val ManeuverBarHeight: Dp = 140.dp

    /** Bottom bar: speed, remaining, arrival, curviness. */
    val BottomBarHeight: Dp = 96.dp

    /** Status strip ("Rerouting", "Off route"). */
    val BannerHeight: Dp = 48.dp

    /** The maneuver arrow itself. */
    val ManeuverIconSize: Dp = 104.dp

    /** The preview of the maneuver after the next one. */
    val ManeuverPreviewSize: Dp = 52.dp

    /** Icon inside a riding button: 48% of the 84 dp square. */
    val RideIconSize: Dp = 40.dp

    /** Icon inside a resting button: 46% of the 56 dp square. */
    val TapIconSize: Dp = 26.dp
}

/**
 * Opacity of surfaces that sit over the map.
 */
object Scrim {
    /**
     * HUD bars are opaque. Translucency there buys a glimpse of map the rider
     * is not looking at and costs real contrast: at 92% over a white minor road
     * the muted caption colour falls from 10.53:1 to 8.65:1, and the plate's
     * own colour starts to drift with whatever is underneath.
     */
    const val Hud: Float = 1f

    /**
     * Icon-only floating buttons may be slightly transparent - they cover map
     * the rider may want, and they carry no text. 94% keeps white glyphs at
     * 16.34:1 even over a white road.
     */
    const val FloatingControl: Float = 0.94f

    /** Behind a dialog. */
    const val Dialog: Float = 0.6f
}
