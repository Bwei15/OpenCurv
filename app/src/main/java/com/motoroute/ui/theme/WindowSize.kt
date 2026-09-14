package com.motoroute.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What shape the window is, for the handful of places that have to care.
 *
 * A phone on a handlebar is mounted whichever way the bar clamp allows, and
 * plenty of riders run landscape because that is how the mount fits. The app
 * already *allowed* rotation (`android:screenOrientation="fullUser"`), but every
 * layout in it was written for a tall window: the riding HUD's maneuver card
 * alone is 144 dp, which is a third of a landscape phone's height, and the
 * planning sheet's 460 dp ceiling is taller than the whole screen.
 *
 * Rather than a second set of layouts, the few affected composables ask this
 * what they are working with and pick their own numbers. That keeps portrait -
 * the case the design system was measured for - byte-for-byte unchanged.
 */
@Immutable
data class WindowShape(
    val widthDp: Dp,
    val heightDp: Dp,
) {
    /** Wider than tall: the rider has room beside the map, not under it. */
    val isLandscape: Boolean get() = widthDp > heightDp

    /**
     * Too short to spend 144 dp on a maneuver card and still show map.
     *
     * 520 dp is the threshold, not 480: a 480 dp-tall window with a 144 dp card,
     * a 96 dp sheet peek and the system bars leaves under 200 dp of map, which
     * is not a navigator.
     */
    val isShort: Boolean get() = heightDp < SHORT_HEIGHT_DP.dp

    /** Largest height a bottom sheet may claim, leaving the map legible underneath. */
    val maxSheetHeight: Dp
        get() = minOf(DEFAULT_MAX_SHEET_HEIGHT.dp, heightDp * MAX_SHEET_FRACTION)

    private companion object {
        const val SHORT_HEIGHT_DP = 520
        const val DEFAULT_MAX_SHEET_HEIGHT = 460
        const val MAX_SHEET_FRACTION = 0.62f
    }
}

@Composable
@ReadOnlyComposable
fun rememberWindowShape(): WindowShape {
    val configuration = LocalConfiguration.current
    return WindowShape(
        widthDp = configuration.screenWidthDp.dp,
        heightDp = configuration.screenHeightDp.dp,
    )
}
