package com.motoroute.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

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
) {
    Icon(
        painter = painterResource(maneuver.iconRes()),
        contentDescription = maneuver.label(),
        tint = tint,
        modifier = modifier.size(size),
    )
}

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
    meters < 10_000 -> String.format("%.1f", meters / 1000.0) to "km"
    else -> "${(meters / 1000.0).roundToInt()}" to "km"
}

@Composable
fun DistanceReadout(
    meters: Double,
    modifier: Modifier = Modifier,
    color: Color = LocalRideColors.current.hudForeground,
) {
    val (value, unit) = formatDistance(meters)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            color = color,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                color = color,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * Current speed against the posted limit.
 *
 * The limit is drawn as a European speed-limit sign because that is the shape
 * a rider's eye already knows; the number turns red only when clearly over,
 * so a GPS speed that reads 2 km/h high does not nag.
 */
@Composable
fun SpeedBadge(
    speedKmh: Int,
    limitKmh: Int?,
    speeding: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "$speedKmh",
                color = if (speeding) colors.danger else colors.hudForeground,
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Text(
                text = "km/h",
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
    }
}

/**
 * A control big enough to hit with winter gloves on a bumpy road.
 *
 * 84 dp square is the floor set by the cockpit spec - more than 1.7x Android's
 * usual 48 dp minimum - and the whole square is the target, not just the icon.
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
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = background.copy(alpha = 0.86f),
        modifier = modifier
            .defaultMinSize(minWidth = GloveTargetSize, minHeight = GloveTargetSize)
            .size(GloveTargetSize)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** A compact readout used in the bottom bar: a big value with a small caption. */
@Composable
fun MetricReadout(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalRideColors.current.hudForeground,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        Text(
            text = caption,
            color = LocalRideColors.current.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Full-width status strip, e.g. "Rerouting" or "Off route". */
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
