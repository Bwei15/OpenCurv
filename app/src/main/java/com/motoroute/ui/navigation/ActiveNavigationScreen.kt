package com.motoroute.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.model.Curviness
import com.motoroute.domain.NavigationState
import com.motoroute.ui.theme.LocalRideColors
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The riding HUD.
 *
 * Layout follows the cockpit spec exactly:
 *
 *   top bar    >= 140 dp   maneuver arrow | distance | the one after it
 *   centre                 the map, heading-up, owned by the caller
 *   bottom bar >= 90 dp    speed vs limit | distance left | ETA
 *
 * Everything is drawn over the map rather than beside it, because on a phone
 * clamped to a handlebar the map is what the rider looks at and the numbers are
 * what they glance at.
 */
@Composable
fun ActiveNavigationScreen(
    state: NavigationState,
    onStop: () -> Unit,
    onToggleVoice: () -> Unit,
    onRecenter: () -> Unit,
    onForceReroute: () -> Unit,
    voiceEnabled: Boolean,
    following: Boolean,
    isDemo: Boolean,
    modifier: Modifier = Modifier,
    map: @Composable (() -> Unit)? = null,
) {
    val colors = LocalRideColors.current

    Box(modifier = modifier.fillMaxSize()) {
        map?.invoke()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            ManeuverBar(state)

            if (isDemo) {
                StatusBanner(stringResource(R.string.nav_demo_running), colors.route)
            }
            if (state.isRerouting) {
                StatusBanner(stringResource(R.string.rerouting), colors.warning)
            } else if (state.isOffRoute) {
                StatusBanner(stringResource(R.string.off_route), colors.danger)
            } else if (state.hasArrived) {
                StatusBanner(stringResource(R.string.arrived), colors.ok)
            }

            Spacer(Modifier.weight(1f))

            // Side controls sit above the bottom bar, on the right, where a
            // thumb reaches without letting go of the grip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GloveButton(
                        iconRes = if (voiceEnabled) {
                            R.drawable.ic_action_sound_on
                        } else {
                            R.drawable.ic_action_sound_off
                        },
                        contentDescription = stringResource(
                            if (voiceEnabled) R.string.action_mute else R.string.action_unmute,
                        ),
                        onClick = onToggleVoice,
                    )
                    // Lit up while the map is not following, so the way back to
                    // the rider is obvious after a look ahead down the route.
                    GloveButton(
                        iconRes = R.drawable.ic_action_center,
                        contentDescription = stringResource(R.string.action_center),
                        onClick = onRecenter,
                        background = if (following) colors.hudBackground else colors.route,
                        tint = if (following) colors.hudForeground else Color.Black,
                    )
                    GloveButton(
                        iconRes = R.drawable.ic_action_reroute,
                        contentDescription = stringResource(R.string.action_reroute),
                        onClick = onForceReroute,
                    )
                    GloveButton(
                        iconRes = R.drawable.ic_action_stop,
                        contentDescription = stringResource(R.string.action_stop),
                        onClick = onStop,
                        background = colors.danger,
                        tint = Color.Black,
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            BottomBar(state)
        }
    }
}

/**
 * Top bar: the next maneuver, how far away it is, and a preview of the one
 * after. The preview is what makes a "left then immediately right" rideable.
 */
@Composable
private fun ManeuverBar(state: NavigationState) {
    val colors = LocalRideColors.current
    val current = state.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .background(colors.hudBackground.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current != null) {
                ManeuverIcon(current.maneuver, size = 104.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    DistanceReadout(state.distanceToManeuverMeters)
                    if (current.roundaboutExit > 0) {
                        Text(
                            text = stringResource(R.string.nav_exit, current.roundaboutExit),
                            color = colors.muted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.nav_waiting_gps),
                    color = colors.hudForeground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.weight(1f))

            state.next?.let { next ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ManeuverIcon(next.maneuver, size = 52.dp, tint = colors.muted)
                    val (value, unit) = formatDistance(state.distanceBetweenManeuversMeters)
                    Text(
                        text = if (unit.isEmpty()) value else "$value $unit",
                        color = colors.muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Bottom bar: speed against the limit, distance left, arrival time. */
@Composable
private fun BottomBar(state: NavigationState) {
    val colors = LocalRideColors.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 90.dp)
            .background(colors.hudBackground.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SpeedBadge(
                speedKmh = state.speedKmh,
                limitKmh = state.speedLimitKmh,
                speeding = state.isSpeeding,
            )

            MetricReadout(
                value = formatRemaining(state.remainingDistanceMeters),
                caption = stringResource(R.string.remaining),
            )

            MetricReadout(
                value = formatEta(state.etaEpochMillis),
                caption = stringResource(R.string.eta),
            )

            state.route?.let { route ->
                MetricReadout(
                    value = Curviness.label(route.curvinessScore),
                    caption = "${route.curvinessScore.toInt()} deg/km",
                    valueColor = colors.route,
                )
            }
        }
    }
}

private fun formatRemaining(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 100_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    else -> "${(meters / 1000).toInt()} km"
}

private fun formatEta(epochMillis: Long): String {
    if (epochMillis <= 0L) return "--:--"
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}

@Suppress("unused")
private fun formatDuration(seconds: Int): String {
    val hours = TimeUnit.SECONDS.toHours(seconds.toLong())
    val minutes = TimeUnit.SECONDS.toMinutes(seconds.toLong()) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
