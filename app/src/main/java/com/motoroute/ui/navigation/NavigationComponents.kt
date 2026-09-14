package com.motoroute.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.model.Maneuver
import com.motoroute.ui.theme.GloveTargetSize
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.Scrim
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TypeScale
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Maps a maneuver onto its vector icon. */
fun Maneuver.iconRes(): Int = when (this) {
    Maneuver.CONTINUE -> R.drawable.ic_maneuver_straight
    Maneuver.KEEP_LEFT -> R.drawable.ic_maneuver_keep_left
    Maneuver.KEEP_RIGHT -> R.drawable.ic_maneuver_keep_right
    Maneuver.SLIGHT_LEFT -> R.drawable.ic_maneuver_slight_left
    Maneuver.SLIGHT_RIGHT -> R.drawable.ic_maneuver_slight_right
    Maneuver.TURN_LEFT -> R.drawable.ic_maneuver_left
    Maneuver.TURN_RIGHT -> R.drawable.ic_maneuver_right
    Maneuver.SHARP_LEFT -> R.drawable.ic_maneuver_sharp_left
    Maneuver.SHARP_RIGHT -> R.drawable.ic_maneuver_sharp_right
    Maneuver.HAIRPIN_LEFT, Maneuver.UTURN_LEFT -> R.drawable.ic_maneuver_uturn_left
    Maneuver.HAIRPIN_RIGHT, Maneuver.UTURN_RIGHT -> R.drawable.ic_maneuver_uturn_right
    Maneuver.ROUNDABOUT -> R.drawable.ic_maneuver_roundabout
    Maneuver.ROUNDABOUT_LEFT -> R.drawable.ic_maneuver_roundabout_left
    Maneuver.DESTINATION -> R.drawable.ic_maneuver_destination
    Maneuver.OFF_ROUTE -> R.drawable.ic_maneuver_offroute
}

fun Maneuver.label(): String = when (this) {
    Maneuver.CONTINUE -> "Straight on"
    Maneuver.KEEP_LEFT -> "Keep left"
    Maneuver.KEEP_RIGHT -> "Keep right"
    Maneuver.SLIGHT_LEFT -> "Slightly left"
    Maneuver.SLIGHT_RIGHT -> "Slightly right"
    Maneuver.TURN_LEFT -> "Left"
    Maneuver.TURN_RIGHT -> "Right"
    Maneuver.SHARP_LEFT -> "Sharp left"
    Maneuver.SHARP_RIGHT -> "Sharp right"
    Maneuver.HAIRPIN_LEFT -> "Hairpin left"
    Maneuver.HAIRPIN_RIGHT -> "Hairpin right"
    Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "U-turn"
    Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_LEFT -> "Roundabout"
    Maneuver.DESTINATION -> "Destination"
    Maneuver.OFF_ROUTE -> "Off route"
}

/**
 * The maneuver arrow. Sized in dp rather than sp so it never shrinks when the
 * rider has a small system font, and never overflows when they have a huge one.
 */
@Composable
fun ManeuverIcon(
    maneuver: Maneuver,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    tint: Color = LocalRideColors.current.hudForeground,
    /** 1-based exit number; a roundabout with one is drawn rather than iconified. */
    roundaboutExit: Int = 0,
) {
    val isRoundabout = maneuver == Maneuver.ROUNDABOUT || maneuver == Maneuver.ROUNDABOUT_LEFT
    if (isRoundabout && roundaboutExit > 0) {
        RoundaboutIcon(
            exit = roundaboutExit,
            mirrored = maneuver == Maneuver.ROUNDABOUT_LEFT,
            modifier = modifier,
            size = size,
            tint = tint,
        )
        return
    }
    Icon(
        painter = painterResource(maneuver.iconRes()),
        contentDescription = maneuver.label(),
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * A roundabout drawn as a ring with the exit number inside it.
 *
 * The static vector this replaces showed a generic ring-with-an-arrow and left
 * the exit number to a line of small text beside it - so the one piece of
 * information the rider actually needs ("which exit?") was the smallest thing
 * on the bar. Here the number *is* the icon: an open ring, like a reload
 * glyph, with a gap at the bottom where the rider enters, an arrowhead at the
 * top, and the exit number filling the middle.
 *
 * Everything scales off [size], so the same composable serves the 112 dp
 * maneuver arrow and the 52 dp "then" preview without a second asset. The ring
 * is mirrored for [Maneuver.ROUNDABOUT_LEFT]; on the right-hand-traffic roads
 * this app is built for that is the rare case, but a clockwise roundabout
 * drawn counter-clockwise would be actively misleading.
 */
@Composable
fun RoundaboutIcon(
    exit: Int,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    size: Dp = 104.dp,
    tint: Color = LocalRideColors.current.hudForeground,
) {
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Roundabout, exit $exit" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f)) {
            val side = kotlin.math.min(this.size.width, this.size.height)
            val strokeWidth = side * RING_STROKE_FRACTION
            // Room for the arrowhead, which sticks out past the ring.
            val inset = strokeWidth / 2f + side * RING_INSET_FRACTION
            val diameter = side - inset * 2f
            val radius = diameter / 2f
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)

            // Sweep clockwise, leaving GAP_DEGREES open centred on the bottom
            // (90 degrees, since y grows downward) - that gap is the road the
            // rider comes in on.
            drawArc(
                color = tint,
                startAngle = 90f + GAP_DEGREES / 2f,
                sweepAngle = 360f - GAP_DEGREES,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Arrowhead at the top of the ring, apex pointing out.
            val half = strokeWidth * ARROW_HALF_WIDTH
            val tip = Offset(centre.x, centre.y - radius - strokeWidth * ARROW_REACH)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(centre.x - half, centre.y - radius + strokeWidth * 0.5f)
                lineTo(centre.x + half, centre.y - radius + strokeWidth * 0.5f)
                close()
            }
            drawPath(path, tint)

            // A tick for the exit road itself, so the ring reads as a junction
            // rather than a plain circle.
            val exitAngle = Math.toRadians(-42.0)
            drawLine(
                color = tint,
                start = Offset(
                    centre.x + (radius * cos(exitAngle)).toFloat(),
                    centre.y + (radius * sin(exitAngle)).toFloat(),
                ),
                end = Offset(
                    centre.x + ((radius + strokeWidth * 1.6f) * cos(exitAngle)).toFloat(),
                    centre.y + ((radius + strokeWidth * 1.6f) * sin(exitAngle)).toFloat(),
                ),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        Text(
            text = exit.toString(),
            color = tint,
            // In dp-derived sp so the number scales with the icon rather than
            // with the system font, same reason ManeuverIcon is sized in dp.
            fontSize = (size.value * EXIT_NUMBER_FRACTION).sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

private const val RING_STROKE_FRACTION = 0.085f
private const val RING_INSET_FRACTION = 0.09f
private const val GAP_DEGREES = 62f
private const val ARROW_HALF_WIDTH = 1.25f
private const val ARROW_REACH = 1.5f
private const val EXIT_NUMBER_FRACTION = 0.40f

/**
 * Distance to the maneuver.
 *
 * Rounding is coarse on purpose: "247 m" reads as noise at speed, "250 m" reads
 * as a number. Below 100 m the steps get finer because that is where the rider
 * is actually judging the turn-in point.
 */
fun formatDistance(meters: Double): Pair<String, String> = when {
    meters < 10 -> "now" to ""
    meters < 100 -> "${(meters / 10.0).roundToInt() * 10}" to "m"
    meters < 1000 -> "${(meters / 50.0).roundToInt() * 50}" to "m"
    // Locale.US, like the other HUD readouts in this file - a rider's speed
    // and distance should not flip to a comma decimal on a German phone.
    meters < 10_000 -> String.format(Locale.US, "%.1f", meters / 1000.0) to "km"
    else -> "${(meters / 1000.0).roundToInt()}" to "km"
}

/** Remaining ride distance, e.g. "38 km" or "450 m". */
fun formatRemaining(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 100_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    else -> "${(meters / 1000).toInt()} km"
}

/** Clock-style arrival time, e.g. "14:32". */
fun formatEta(epochMillis: Long): String {
    if (epochMillis <= 0L) return "--:--"
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}

/**
 * Arrival and remaining distance as the one compact readout the bottom-left
 * chip shows, e.g. "14:32 · 38 km".
 */
fun formatEtaAndRemaining(epochMillis: Long, remainingMeters: Double): String =
    "${formatEta(epochMillis)} · ${formatRemaining(remainingMeters)}"

@Composable
fun DistanceReadout(
    meters: Double,
    modifier: Modifier = Modifier,
    color: Color = LocalRideColors.current.hudForeground,
    /**
     * Landscape on a phone. One step down the riding scale, not off it: the
     * distance stays the biggest thing on screen, it just stops being the
     * tallest thing on a 400 dp window.
     */
    compact: Boolean = false,
) {
    val (value, unit) = formatDistance(meters)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            color = color,
            // The tokens, not literals: these two used to be 56/26 sp written
            // out here, so raising the HUD display size in Type.kt did nothing
            // to the one number it was raised for.
            fontSize = if (compact) TypeScale.HudPrimary else TypeScale.HudDisplay,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                color = color,
                fontSize = if (compact) TypeScale.HudBanner else TypeScale.HudUnit,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = Space.Xs, bottom = 6.dp),
            )
        }
    }
}

/** Arrival chip: a small plate, "14:32 · 38 km" in one line, bottom-left of the HUD. */
@Composable
fun EtaDistanceChip(
    etaEpochMillis: Long,
    remainingMeters: Double,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    Surface(
        color = colors.hudBackground,
        shape = RoundedCornerShape(Radius.Md),
        modifier = modifier,
    ) {
        Text(
            text = formatEtaAndRemaining(etaEpochMillis, remainingMeters),
            color = colors.hudForeground,
            // Secondary now that curviness and the four-value footer are gone -
            // read once on a glance down, not in motion like the speed or the
            // next-turn distance, so it sits under the 34 sp HUD floor on
            // purpose. Still bold: it is a value, not a caption.
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Space.Lg, vertical = Space.Sm),
        )
    }
}

/** Red once clearly over the limit, the HUD's own ink otherwise. */
fun speedColor(speeding: Boolean, danger: Color, normal: Color): Color =
    if (speeding) danger else normal

/**
 * Current speed against the posted limit, stacked so the pair can live as one
 * element at the right edge instead of stretching a bottom bar.
 *
 * The limit sign is the one place the app draws a literal traffic sign, so its
 * colours are the real red/white/black rather than a token - a rider's eye
 * reads that shape by its real-world colours. Without a limit only the speed
 * square remains.
 */
@Composable
fun SpeedLimitStack(
    speedKmh: Int,
    limitKmh: Int?,
    speeding: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.Sm),
    ) {
        if (limitKmh != null) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$limitKmh",
                        color = Color.Black,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 72.dp)
                .clip(RoundedCornerShape(Radius.Md))
                .background(colors.hudBackground.copy(alpha = Scrim.FloatingControl)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = Space.Md, vertical = Space.Sm),
            ) {
                Text(
                    text = "$speedKmh",
                    color = speedColor(speeding, colors.danger, colors.hudForeground),
                    fontSize = TypeScale.HudPrimary,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text(
                    text = "km/h",
                    color = colors.muted,
                    fontSize = TypeScale.HudCaption,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A control big enough to hit with winter gloves on a bumpy road.
 *
 * 84 dp square is the floor set by the cockpit spec - more than 1.7x Android's
 * usual 48 dp minimum - and the whole square is the target, not just the icon.
 * [size] lets a caller ask for something smaller for controls that are not on
 * the always-visible riding path (e.g. the ones tucked behind the HUD menu).
 */
@Composable
fun GloveButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = LocalRideColors.current.hudBackground,
    tint: Color = LocalRideColors.current.hudForeground,
    enabled: Boolean = true,
    size: Dp = GloveTargetSize,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = background.copy(alpha = 0.86f),
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                // Icon fills roughly half of whatever square it sits in, the
                // same proportion the 84 dp / 40 dp riding button already uses.
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

/** Hamburger while closed, an X once the three tucked-away buttons are open. */
fun menuToggleIcon(expanded: Boolean): Int =
    if (expanded) R.drawable.ic_action_close else R.drawable.ic_action_menu

/**
 * A transient piece of news, as a pill that floats over the map.
 *
 * This replaces the full-width status strip that used to sit between the
 * maneuver bar and the map. The strip was a layout participant, so every time
 * it appeared - a recalculation, a camera, going off route - the speed readout
 * and everything under it jumped down by 44 dp and back up again. The ride
 * report called that out directly: the HUD never settles.
 *
 * A pill is an overlay instead. It is smaller, it is centred where the eye
 * already is, and crucially it changes nothing about the rest of the HUD's
 * geometry. [dotColor] carries the severity, so the pill itself keeps the
 * HUD's own dark surface rather than flashing a full field of red at a rider
 * mid-corner.
 */
@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
) {
    val colors = LocalRideColors.current
    Surface(
        color = colors.hudBackground.copy(alpha = Scrim.FloatingControl),
        shape = RoundedCornerShape(Radius.Full),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.Lg, vertical = Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = text,
                color = colors.hudForeground,
                fontWeight = FontWeight.Black,
                // A pill is read in motion, so it takes the banner size rather
                // than the resting-register label size it would look right at.
                fontSize = TypeScale.HudBanner,
                maxLines = 1,
            )
        }
    }
}

/** Full-width status strip. Kept for the resting screens; the HUD uses [StatusPill]. */
@Composable
fun StatusBanner(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
        }
    }
}
