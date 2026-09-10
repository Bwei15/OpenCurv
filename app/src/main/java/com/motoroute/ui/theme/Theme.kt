package com.motoroute.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.motoroute.data.settings.MapTheme

/**
 * The five stops of the curviness ramp, from "get me there" to "I have all day".
 *
 * A list would make [RideColors] unstable for Compose, so the stops are named
 * fields. Straighter is bluer, curvier is pinker.
 */
@Immutable
data class CurvinessRamp(
    val direct: Color,
    val mild: Color,
    val balanced: Color,
    val hungry: Color,
    val maximum: Color,
) {
    /** The stop for a 0..1 position on the scale. */
    fun at(fraction: Float): Color = when {
        fraction < 0.2f -> direct
        fraction < 0.4f -> mild
        fraction < 0.6f -> balanced
        fraction < 0.8f -> hungry
        else -> maximum
    }

    /** The stop for the slider's own 0..2 range, as used by the planning sheet. */
    fun forSlider(value: Float): Color = at((value / 2f).coerceIn(0f, 1f))

    /** The stop for a BRouter curviness score in degrees per kilometre. */
    fun forScore(degreesPerKm: Float): Color = at((degreesPerKm / 240f).coerceIn(0f, 1f))
}

/**
 * Colours the two registers need that Material's single scheme has no slot for.
 *
 * The first block is the interface the app already imports and is kept
 * byte-for-byte compatible. Everything after it is new and defaulted, so no
 * call site has to change to keep compiling.
 */
@Immutable
data class RideColors(
    val route: Color,
    val hudBackground: Color,
    val hudForeground: Color,
    val muted: Color,
    val warning: Color,
    val danger: Color,
    val ok: Color,
    /** The position puck on the map. */
    val rider: Color,
    /** The destination pin. */
    val destination: Color,
    /** Card and sheet background on the screens used with the engine off. */
    val panel: Color,
    /** Text on [panel]. */
    val onPanel: Color,
    val isNight: Boolean,

    // ---- surfaces ---------------------------------------------------------

    /** Screen background where no map shows through. */
    val canvas: Color = OpenCurvColors.DayCanvas,

    /** Inputs and wells, one step below [panel]. */
    val panelSunken: Color = OpenCurvColors.DayPlateSunken,

    /**
     * The 1 dp casing that separates a floating plate from the map. Mandatory
     * on anything that floats; a shadow is not a substitute.
     */
    val panelRim: Color = OpenCurvColors.DayPlateRim,

    /** Hairline between rows *inside* a plate. Decorative, never a boundary. */
    val divider: Color = OpenCurvColors.DayDivider,

    // ---- content ----------------------------------------------------------

    /** Quietest readable text. Below [muted], still >= 4.5:1 on both surfaces. */
    val faint: Color = OpenCurvColors.DayInkFaint,

    // ---- brand ------------------------------------------------------------

    val primary: Color = OpenCurvColors.Indigo,
    val onPrimary: Color = Color.White,
    val primaryPressed: Color = OpenCurvColors.IndigoPressed,
    val primaryTint: Color = OpenCurvColors.IndigoTint,
    val accent: Color = OpenCurvColors.Magenta,
    val accentTint: Color = OpenCurvColors.MagentaTint,

    // ---- HUD --------------------------------------------------------------

    /** Muted text inside the HUD. >= 7:1, because it is read in motion. */
    val hudMuted: Color = OpenCurvColors.HudInkMuted,

    /** Static HUD captions. Under 20 arcminutes - must carry no information. */
    val hudCaption: Color = OpenCurvColors.HudInkFaint,

    val hudDivider: Color = OpenCurvColors.HudDivider,

    /** Curviness and other brand readouts inside the HUD. */
    val hudPrimary: Color = OpenCurvColors.IndigoLight,

    /** Banner fields: light field, near-black text, the inverse of the HUD. */
    val hudWarningField: Color = OpenCurvColors.HudWarning,
    val hudDangerField: Color = OpenCurvColors.HudDanger,
    val hudOkField: Color = OpenCurvColors.HudOk,

    /** Text to put on any of the three banner fields. */
    val onBanner: Color = OpenCurvColors.HudSurface,

    // ---- geometry on the map ----------------------------------------------

    /** The dark casing under the route, the puck and the pin. */
    val routeCasing: Color = OpenCurvColors.DayRouteCasing,

    /** A route that was calculated but not chosen. */
    val routeAlternative: Color = OpenCurvColors.DayRouteAlternative,

    /** The white ring that lifts the puck off whatever it stands on. */
    val riderRing: Color = OpenCurvColors.RiderRing,

    // ---- the playful bit ---------------------------------------------------

    val curviness: CurvinessRamp = CurvinessRamp(
        direct = OpenCurvColors.DayCurviness[0],
        mild = OpenCurvColors.DayCurviness[1],
        balanced = OpenCurvColors.DayCurviness[2],
        hungry = OpenCurvColors.DayCurviness[3],
        maximum = OpenCurvColors.DayCurviness[4],
    ),
) {
    /** Same surface as [hudBackground]; the name says what it is, not where. */
    val hudSurface: Color get() = hudBackground
}

private val DayRideColors = RideColors(
    route = OpenCurvColors.DayRouteCore,
    hudBackground = OpenCurvColors.HudSurface,
    hudForeground = OpenCurvColors.HudInk,
    muted = OpenCurvColors.DayInkMuted,
    warning = OpenCurvColors.DayWarning,
    danger = OpenCurvColors.DayDanger,
    ok = OpenCurvColors.DayOk,
    rider = OpenCurvColors.DayRider,
    destination = OpenCurvColors.DayDestination,
    panel = OpenCurvColors.DayPlate,
    onPanel = OpenCurvColors.DayInk,
    isNight = false,
    canvas = OpenCurvColors.DayCanvas,
    panelSunken = OpenCurvColors.DayPlateSunken,
    panelRim = OpenCurvColors.DayPlateRim,
    divider = OpenCurvColors.DayDivider,
    faint = OpenCurvColors.DayInkFaint,
    primary = OpenCurvColors.Indigo,
    onPrimary = Color.White,
    primaryPressed = OpenCurvColors.IndigoPressed,
    primaryTint = OpenCurvColors.IndigoTint,
    accent = OpenCurvColors.Magenta,
    accentTint = OpenCurvColors.MagentaTint,
    routeCasing = OpenCurvColors.DayRouteCasing,
    routeAlternative = OpenCurvColors.DayRouteAlternative,
    curviness = CurvinessRamp(
        direct = OpenCurvColors.DayCurviness[0],
        mild = OpenCurvColors.DayCurviness[1],
        balanced = OpenCurvColors.DayCurviness[2],
        hungry = OpenCurvColors.DayCurviness[3],
        maximum = OpenCurvColors.DayCurviness[4],
    ),
)

private val NightRideColors = RideColors(
    route = OpenCurvColors.NightRouteCore,
    hudBackground = OpenCurvColors.HudSurface,
    hudForeground = OpenCurvColors.HudInk,
    muted = OpenCurvColors.NightInkMuted,
    warning = OpenCurvColors.NightWarning,
    danger = OpenCurvColors.NightDanger,
    ok = OpenCurvColors.NightOk,
    rider = OpenCurvColors.NightRider,
    destination = OpenCurvColors.NightDestination,
    panel = OpenCurvColors.NightPlate,
    onPanel = OpenCurvColors.NightInk,
    isNight = true,
    canvas = OpenCurvColors.NightCanvas,
    panelSunken = OpenCurvColors.NightPlateSunken,
    panelRim = OpenCurvColors.NightPlateRim,
    divider = OpenCurvColors.NightDivider,
    faint = OpenCurvColors.NightInkFaint,
    primary = OpenCurvColors.IndigoLight,
    onPrimary = OpenCurvColors.IndigoDeep,
    primaryPressed = Color(0xFF6D8CF0),
    primaryTint = Color(0xFF1B2440),
    accent = OpenCurvColors.MagentaLight,
    accentTint = Color(0xFF2E1622),
    routeCasing = OpenCurvColors.NightRouteCasing,
    routeAlternative = OpenCurvColors.NightRouteAlternative,
    curviness = CurvinessRamp(
        direct = OpenCurvColors.NightCurviness[0],
        mild = OpenCurvColors.NightCurviness[1],
        balanced = OpenCurvColors.NightCurviness[2],
        hungry = OpenCurvColors.NightCurviness[3],
        maximum = OpenCurvColors.NightCurviness[4],
    ),
)

val LocalRideColors = staticCompositionLocalOf { DayRideColors }

/**
 * The whole app.
 *
 * Material's scheme is filled from the same tokens so that stock components -
 * chips, sliders, switches, dialogs - land inside the system without every call
 * site having to override them.
 */
@Composable
fun OpenCurvTheme(
    mapTheme: MapTheme = MapTheme.AUTO,
    content: @Composable () -> Unit,
) {
    val night = when (mapTheme) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> isSystemInDarkTheme()
    }

    val rideColors = if (night) NightRideColors else DayRideColors

    val colorScheme = if (night) {
        darkColorScheme(
            primary = OpenCurvColors.IndigoLight,
            onPrimary = OpenCurvColors.IndigoDeep,
            primaryContainer = Color(0xFF1B2440),
            onPrimaryContainer = OpenCurvColors.IndigoLight,
            secondary = OpenCurvColors.MagentaLight,
            onSecondary = Color(0xFF2E1622),
            secondaryContainer = Color(0xFF2E1622),
            onSecondaryContainer = OpenCurvColors.MagentaLight,
            background = OpenCurvColors.NightCanvas,
            onBackground = OpenCurvColors.NightInk,
            surface = OpenCurvColors.NightCanvas,
            onSurface = OpenCurvColors.NightInk,
            surfaceVariant = OpenCurvColors.NightPlate,
            onSurfaceVariant = OpenCurvColors.NightInkMuted,
            surfaceContainer = OpenCurvColors.NightPlate,
            surfaceContainerHigh = Color(0xFF1B222C),
            surfaceContainerLow = OpenCurvColors.NightPlateSunken,
            outline = OpenCurvColors.NightPlateRim,
            outlineVariant = OpenCurvColors.NightDivider,
            error = OpenCurvColors.NightDanger,
            onError = OpenCurvColors.NightCanvas,
            scrim = Color(0xFF000000),
        )
    } else {
        lightColorScheme(
            primary = OpenCurvColors.Indigo,
            onPrimary = Color.White,
            primaryContainer = OpenCurvColors.IndigoTint,
            onPrimaryContainer = OpenCurvColors.IndigoPressed,
            secondary = OpenCurvColors.Magenta,
            onSecondary = Color.White,
            secondaryContainer = OpenCurvColors.MagentaTint,
            onSecondaryContainer = OpenCurvColors.Magenta,
            background = OpenCurvColors.DayCanvas,
            onBackground = OpenCurvColors.DayInk,
            surface = OpenCurvColors.DayPlate,
            onSurface = OpenCurvColors.DayInk,
            surfaceVariant = OpenCurvColors.DayPlateSunken,
            onSurfaceVariant = OpenCurvColors.DayInkMuted,
            surfaceContainer = OpenCurvColors.DayPlate,
            surfaceContainerHigh = OpenCurvColors.DayPlateSunken,
            surfaceContainerLow = OpenCurvColors.DayCanvas,
            outline = OpenCurvColors.DayPlateRim,
            outlineVariant = OpenCurvColors.DayDivider,
            error = OpenCurvColors.DayDanger,
            onError = Color.White,
            scrim = Color(0xFF000000),
        )
    }

    CompositionLocalProvider(LocalRideColors provides rideColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenCurvTypography,
            shapes = OpenCurvShapes,
            content = content,
        )
    }
}
