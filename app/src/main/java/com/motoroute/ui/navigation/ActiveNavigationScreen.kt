package com.motoroute.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.domain.NavigationState
import com.motoroute.domain.cameras.SpeedCameraWarning
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Motion
import com.motoroute.ui.theme.RideTargetGap
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TapTargetSize

/**
 * The riding HUD.
 *
 *   maneuver bar   top, compact - arrow, distance, the one after it
 *   centre         the map, heading-up, owned by the caller
 *   right edge     speed + limit, between the maneuver bar and the button stack
 *   bottom left    arrival + remaining distance, one compact chip
 *   bottom right   Centre, a menu button, and the three buttons it tucks away
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
    // No camera-detection source is wired up yet (see SpeedCameraAlert.kt);
    // this stays null until AppContainer exposes one, so the HUD already knows
    // how to render a warning the day it does.
    cameraWarning: SpeedCameraWarning? = null,
    map: @Composable (() -> Unit)? = null,
) {
    val colors = LocalRideColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        map?.invoke()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // The one thing that must never disappear mid-ride, so it is a
            // sibling of the camera-alert overlay below rather than something
            // the overlay could ever cover.
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

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                // Arrival + remaining distance: bottom-left, out of the thumb's way.
                EtaDistanceChip(
                    etaEpochMillis = state.etaEpochMillis,
                    remainingMeters = state.remainingDistanceMeters,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.Lg),
                )

                // Speed + limit: right edge, between the maneuver bar above
                // (outside this box) and the button stack pinned to the
                // bottom of this same box.
                SpeedLimitStack(
                    speedKmh = state.speedKmh,
                    limitKmh = state.speedLimitKmh,
                    speeding = state.isSpeeding,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = Space.Lg, end = Space.Md),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = Space.Md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RideTargetGap),
                ) {
                    // Mute, recalculate and stop, tucked behind the menu
                    // button so only Centre - the one control reached for
                    // every time the map has drifted off the rider - is
                    // always on screen. Grows upward, toward the thumb.
                    AnimatedVisibility(
                        visible = menuExpanded,
                        enter = expandVertically(
                            animationSpec = tween(Motion.Fast, easing = Motion.Standard_),
                            expandFrom = Alignment.Bottom,
                        ) + fadeIn(tween(Motion.Fast)),
                        exit = shrinkVertically(
                            animationSpec = tween(Motion.Fast, easing = Motion.Exit),
                            shrinkTowards = Alignment.Bottom,
                        ) + fadeOut(tween(Motion.Fast)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(RideTargetGap)) {
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
                                size = TapTargetSize,
                            )
                            GloveButton(
                                iconRes = R.drawable.ic_action_reroute,
                                contentDescription = stringResource(R.string.action_reroute),
                                onClick = onForceReroute,
                                size = TapTargetSize,
                            )
                            GloveButton(
                                iconRes = R.drawable.ic_action_stop,
                                contentDescription = stringResource(R.string.action_stop),
                                onClick = onStop,
                                background = colors.danger,
                                tint = Color.Black,
                                size = TapTargetSize,
                            )
                        }
                    }

                    GloveButton(
                        iconRes = menuToggleIcon(menuExpanded),
                        contentDescription = stringResource(
                            if (menuExpanded) R.string.action_close_menu else R.string.action_menu,
                        ),
                        onClick = { menuExpanded = !menuExpanded },
                        size = TapTargetSize,
                    )

                    // Lit up while the map is not following, so the way back
                    // to the rider is obvious after a look ahead down the
                    // route. Stays at its own, larger size and never moves
                    // behind the menu - this is the control found by shape
                    // alone, without reading its icon.
                    GloveButton(
                        iconRes = R.drawable.ic_action_center,
                        contentDescription = stringResource(R.string.action_center),
                        onClick = onRecenter,
                        background = if (following) colors.hudBackground else colors.route,
                        tint = if (following) colors.hudForeground else Color.Black,
                    )
                }

                SpeedCameraAlert(
                    warning = cameraWarning,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
                .padding(horizontal = Space.Lg, vertical = Space.Sm),
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
