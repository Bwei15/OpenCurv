package com.motoroute.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The type scale, derived from the viewing distance rather than from taste.
 *
 * A phone clamped to the bars sits about 650 mm from the rider's eyes. One dp
 * is 1/160 inch = 0.15875 mm, and a humanist sans has a cap height near 0.72 em,
 * so a size of *s* sp subtends
 *
 *     angle in arcminutes = 3438 * (s * 0.15875 * 0.72) / 650 = 0.605 * s
 *
 * ISO 15008 puts the floor for in-vehicle character height at 20 arcminutes and
 * the preferred value at 25. That converts to **34 sp minimum and 42 sp
 * preferred** for anything a rider actually reads in motion.
 *
 * That single number decides the whole HUD. It also settles an argument: the
 * 12-13 sp captions in today's bottom bar subtend 7-8 arcminutes and cannot be
 * read at speed by anyone. They are not a bug as long as they carry no
 * information - a caption's job is to teach the rider once, in the car park,
 * what the big number above it means. Captions may stay small. Values may not.
 *
 * All weights are heavy. Under vibration the eye integrates over the wobble, and
 * a thin stroke averages into the background; a Black weight keeps its mass.
 */
object TypeScale {

    // ---- riding register: read at 650 mm, in motion ----------------------

    /** Distance to the next maneuver. 56 sp = 33.9', the biggest thing on screen. */
    val HudDisplay: TextUnit = 56.sp

    /** Current speed. 44 sp = 26.6', above the ISO 15008 preferred value. */
    val HudPrimary: TextUnit = 44.sp

    /** Remaining distance, arrival, curviness. 34 sp = 20.6', at the floor. */
    val HudSecondary: TextUnit = 34.sp

    /** Unit suffix riding along with a display number ("km", "m"). */
    val HudUnit: TextUnit = 26.sp

    /** Banner text. 20 sp is under the floor, which is why a banner is also a colour. */
    val HudBanner: TextUnit = 20.sp

    /** Static captions. Never carry information; learned once while stationary. */
    val HudCaption: TextUnit = 13.sp

    // ---- resting register: read at ~350 mm, standing still ---------------

    /** Onboarding headline. */
    val Display: TextUnit = 40.sp

    /** Screen title. */
    val TitleLarge: TextUnit = 30.sp

    /** Card title, sheet headline. */
    val Title: TextUnit = 24.sp

    /** Row headline, metric value on the planning sheet. */
    val Subtitle: TextUnit = 20.sp

    /** Body text and button labels. 17 sp = 19' at arm's length. */
    val Body: TextUnit = 17.sp

    /** Secondary body, list subtitles. */
    val BodySmall: TextUnit = 15.sp

    /** Labels, chips, section headers. */
    val Label: TextUnit = 13.sp

    /** File sizes, timestamps, legal. */
    val Micro: TextUnit = 11.sp
}

/** One family. A second one buys nothing at 33 arcminutes and costs a download. */
private val Family = FontFamily.SansSerif

private fun ride(
    size: TextUnit,
    weight: FontWeight = FontWeight.Black,
    tracking: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = Family,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = tracking,
    lineHeight = lineHeight,
)

/**
 * Material's roles, filled with the scale above.
 *
 * `display*` is the riding register, `headline*`/`title*`/`body*`/`label*` the
 * resting one. Negative tracking on the big numbers because Black weights at
 * 40 sp and up open up otherwise; positive tracking on the small all-caps
 * labels because they close up.
 */
internal val OpenCurvTypography = Typography(
    // Riding register.
    displayLarge = ride(TypeScale.HudDisplay, tracking = (-1.5).sp),
    displayMedium = ride(TypeScale.HudPrimary, tracking = (-1).sp),
    displaySmall = ride(TypeScale.HudSecondary, tracking = (-0.5).sp),

    // Resting register.
    headlineLarge = ride(TypeScale.Display, tracking = (-0.8).sp, lineHeight = 46.sp),
    headlineMedium = ride(TypeScale.TitleLarge, tracking = (-0.5).sp, lineHeight = 36.sp),
    headlineSmall = ride(TypeScale.Title, tracking = (-0.3).sp, lineHeight = 30.sp),

    titleLarge = ride(TypeScale.Title, tracking = (-0.3).sp, lineHeight = 30.sp),
    titleMedium = ride(TypeScale.Subtitle, FontWeight.Bold, lineHeight = 26.sp),
    titleSmall = ride(TypeScale.Body, FontWeight.Bold, lineHeight = 24.sp),

    bodyLarge = ride(TypeScale.Body, FontWeight.Medium, lineHeight = 24.sp),
    bodyMedium = ride(TypeScale.BodySmall, FontWeight.Medium, lineHeight = 22.sp),
    bodySmall = ride(TypeScale.Label, FontWeight.Medium, lineHeight = 18.sp),

    labelLarge = ride(TypeScale.Body, FontWeight.Bold, tracking = 0.2.sp),
    labelMedium = ride(TypeScale.Label, FontWeight.Bold, tracking = 0.6.sp),
    labelSmall = ride(TypeScale.Micro, FontWeight.Bold, tracking = 0.8.sp),
)
