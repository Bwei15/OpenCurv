package com.motoroute.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.data.settings.MapTheme

/** Colours the HUD needs that Material's scheme has no slot for. */
@Immutable
data class RideColors(
    val route: Color,
    val hudBackground: Color,
    val hudForeground: Color,
    val muted: Color,
    val warning: Color,
    val danger: Color,
    val ok: Color,
    val isNight: Boolean,
)

val LocalRideColors = staticCompositionLocalOf {
    RideColors(
        route = OpenCurvColors.DayRoute,
        hudBackground = OpenCurvColors.DayHudBackground,
        hudForeground = OpenCurvColors.DayHudForeground,
        muted = OpenCurvColors.DayOnSurfaceMuted,
        warning = OpenCurvColors.Warning,
        danger = OpenCurvColors.Danger,
        ok = OpenCurvColors.Ok,
        isNight = false,
    )
}

/** Minimum touch target for a gloved hand, per the cockpit spec. */
val GloveTargetSize = 84.dp

/**
 * Typography is deliberately blunt: one sans-serif family, heavy weights, and
 * sizes that stay legible when the phone is 60 cm away and vibrating.
 */
private val RideTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 0.5.sp,
    ),
)

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

    val colorScheme = if (night) {
        darkColorScheme(
            primary = OpenCurvColors.NightAccent,
            onPrimary = Color.Black,
            background = OpenCurvColors.NightBackground,
            onBackground = OpenCurvColors.NightOnSurface,
            surface = OpenCurvColors.NightSurface,
            onSurface = OpenCurvColors.NightOnSurface,
            surfaceVariant = Color(0xFF121212),
            onSurfaceVariant = OpenCurvColors.NightOnSurfaceMuted,
            error = OpenCurvColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = OpenCurvColors.DayAccent,
            onPrimary = Color.White,
            background = OpenCurvColors.DayBackground,
            onBackground = OpenCurvColors.DayOnSurface,
            surface = OpenCurvColors.DaySurface,
            onSurface = OpenCurvColors.DayOnSurface,
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = OpenCurvColors.DayOnSurfaceMuted,
            error = OpenCurvColors.Danger,
        )
    }

    val rideColors = if (night) {
        RideColors(
            route = OpenCurvColors.NightRoute,
            hudBackground = OpenCurvColors.NightHudBackground,
            hudForeground = OpenCurvColors.NightHudForeground,
            muted = OpenCurvColors.NightOnSurfaceMuted,
            warning = OpenCurvColors.Warning,
            danger = OpenCurvColors.Danger,
            ok = OpenCurvColors.Ok,
            isNight = true,
        )
    } else {
        RideColors(
            route = OpenCurvColors.DayRoute,
            hudBackground = OpenCurvColors.DayHudBackground,
            hudForeground = OpenCurvColors.DayHudForeground,
            muted = OpenCurvColors.DayOnSurfaceMuted,
            warning = OpenCurvColors.Warning,
            danger = OpenCurvColors.Danger,
            ok = OpenCurvColors.Ok,
            isNight = false,
        )
    }

    CompositionLocalProvider(LocalRideColors provides rideColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RideTypography,
            content = content,
        )
    }
}
