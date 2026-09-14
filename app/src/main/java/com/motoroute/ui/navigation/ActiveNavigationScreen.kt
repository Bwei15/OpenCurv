package com.motoroute.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import com.motoroute.R
import com.motoroute.domain.NavigationState
import com.motoroute.domain.cameras.SpeedCameraWarning
import com.motoroute.ui.theme.Elevation
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Motion
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.RideTargetGap
import com.motoroute.ui.theme.Scrim
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TapTargetSize
import com.motoroute.ui.theme.TypeScale
import com.motoroute.ui.theme.rememberWindowShape

/**
 * The riding HUD.
 *
 *   maneuver card  top - a floating plate: arrow, distance, the one after it,
 *                  and the X that ends the ride
 *   pill           centred under the card - news that must not move anything
 *   centre         the map, heading-up, owned by the caller
 *   right edge     speed + limit
 *   bottom left    arrival + remaining distance, one compact chip
 *   bottom right   Centre, plus mute and recalculate behind a menu button
 *   bottom sheet   the ride's stops, pulled up when the rider wants them
 *
 * Everything is drawn over the map rather than beside it, because on a phone
 * clamped to a handlebar the map is what the rider looks at and the numbers are
 * what they glance at.
 *
 * ## What the September 2026 ride report changed
 *
 * The maneuver bar ran edge to edge with square corners and shared the top of
 * the screen with a full-width status strip; every time that strip appeared the
 * whole HUD below it jumped down 44 dp. The bar is now a rounded plate like the
 * clock and distance chips it sits above, the distance is bigger (64 sp), and
 * the strip is a [StatusPill] overlay that changes nobody else's position.
 *
 * Ending the ride used to be a red button in the tucked-away menu, next to
 * mute and recalculate. It is now an X in the maneuver card's top-right corner,
 * where closing a thing lives in every other app, and the menu is free to be
 * what it should have been: the list of stops on this ride.
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
    cameraWarning: SpeedCameraWarning? = null,
    /** The ride's stops, start first and destination last - see [RideStopSheet]. */
    stops: List<RideStop> = emptyList(),
    onAddStop: (() -> Unit)? = null,
    onRemoveStop: ((Int) -> Unit)? = null,
    map: @Composable (() -> Unit)? = null,
) {
    val colors = LocalRideColors.current
    val window = rememberWindowShape()
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        map?.invoke()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // The one thing that must never disappear mid-ride.
            ManeuverCard(
                state = state,
                onStop = onStop,
                // A landscape phone on a handlebar has about 400 dp of height
                // in total; the portrait card would take more than a third of
                // it before the map got a look in.
                compact = window.isShort,
                modifier = Modifier.padding(horizontal = Space.Md, vertical = Space.Sm),
            )

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                // News, as an overlay: it hangs just under the maneuver card
                // and moves nothing. Only one at a time, most urgent first -
                // a rider who is off route does not also need to be told the
                // route is being recalculated.
                RidePill(
                    state = state,
                    isDemo = isDemo,
                    cameraWarning = cameraWarning,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Space.Sm),
                )

                // Arrival + remaining distance: bottom-left, out of the thumb's way.
                EtaDistanceChip(
                    etaEpochMillis = state.etaEpochMillis,
                    remainingMeters = state.remainingDistanceMeters,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.Lg),
                )

                // Speed + limit: right edge, clear of the pill above and the
                // button stack pinned to the bottom of this same box.
                SpeedLimitStack(
                    speedKmh = state.speedKmh,
                    limitKmh = state.speedLimitKmh,
                    speeding = state.isSpeeding,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = if (window.isShort) Space.Sm else SPEED_STACK_TOP, end = Space.Md),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = Space.Md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RideTargetGap),
                ) {
                    // Mute and recalculate, tucked behind the menu button so
                    // only Centre - the one control reached for every time the
                    // map has drifted off the rider - is always on screen.
                    // Stopping the ride is not in here any more: it is the X on
                    // the maneuver card.
                    AnimatedVisibility(
                        visible = menuExpanded,
                        enter = fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Fast), initialScale = 0.9f),
                        exit = fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.9f),
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
            }
        }

        // The stop list, pulled up over everything. Last in the Box so it wins
        // the z-order it needs, and only present when there is a list to show.
        if (stops.isNotEmpty()) {
            RideStopSheet(
                stops = stops,
                etaEpochMillis = state.etaEpochMillis,
                remainingMeters = state.remainingDistanceMeters,
                onAddStop = onAddStop,
                onRemoveStop = onRemoveStop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Top plate: the next maneuver, how far away it is, a preview of the one after,
 * and the X that ends the ride.
 *
 * The preview is what makes a "left then immediately right" rideable. The X is
 * deliberately the smallest target on the plate - it is the one control on the
 * riding path whose accidental press costs the rider their navigation, so it
 * sits in the corner furthest from the thumb rather than in the button stack
 * where a mis-grab lands.
 */
@Composable
private fun ManeuverCard(
    state: NavigationState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Short window (landscape on a phone): same information, less height. */
    compact: Boolean = false,
) {
    val colors = LocalRideColors.current
    val current = state.current
    val iconSize = if (compact) MANEUVER_ICON_SIZE_COMPACT else MANEUVER_ICON_SIZE
    val minHeight = if (compact) MANEUVER_CARD_MIN_HEIGHT_COMPACT else MANEUVER_CARD_MIN_HEIGHT

    Surface(
        color = colors.hudBackground.copy(alpha = Scrim.FloatingControl),
        shape = RoundedCornerShape(Radius.Xl),
        shadowElevation = Elevation.Floating,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(start = Space.Md, end = CLOSE_BUTTON_GUTTER, top = Space.Md, bottom = Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current != null) {
                    ManeuverIcon(
                        maneuver = current.maneuver,
                        size = iconSize,
                        roundaboutExit = current.roundaboutExit,
                    )
                    Spacer(Modifier.width(Space.Md))
                    // The exit number used to be a 16 sp line of text next to
                    // the arrow; it now lives inside the ring the arrow draws,
                    // which is why nothing but the distance is left here.
                    DistanceReadout(state.distanceToManeuverMeters, compact = compact)
                } else {
                    Text(
                        text = stringResource(R.string.nav_waiting_gps),
                        color = colors.hudForeground,
                        fontSize = TypeScale.HudSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.weight(1f))

                state.next?.let { next ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = Space.Sm),
                    ) {
                        ManeuverIcon(
                            maneuver = next.maneuver,
                            size = NEXT_ICON_SIZE,
                            tint = colors.hudMuted,
                            roundaboutExit = next.roundaboutExit,
                        )
                        val (value, unit) = formatDistance(state.distanceBetweenManeuversMeters)
                        Text(
                            text = if (unit.isEmpty()) value else "$value $unit",
                            color = colors.hudMuted,
                            fontSize = TypeScale.Label,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            CloseRideButton(
                onClick = onStop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Space.Sm),
            )
        }
    }
}

/** The X in the maneuver card's corner: ends the ride. */
@Composable
private fun CloseRideButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRideColors.current
    GloveButton(
        iconRes = R.drawable.ic_action_close,
        contentDescription = stringResource(R.string.action_stop),
        onClick = onClick,
        background = colors.hudForeground.copy(alpha = 0.14f),
        tint = colors.hudForeground,
        size = CLOSE_BUTTON_SIZE,
        modifier = modifier,
    )
}

/**
 * Whichever single piece of news is most urgent, as a pill.
 *
 * Order matters and is the whole design: a rider off route does not also need
 * "recalculating", and a camera 300 m away outranks a demo notice. One pill,
 * never a stack - two pills would start moving each other around, which is the
 * problem the pill exists to solve.
 */
@Composable
private fun RidePill(
    state: NavigationState,
    isDemo: Boolean,
    cameraWarning: SpeedCameraWarning?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    val pill: Pair<String, Color>? = when {
        cameraWarning != null -> {
            val limit = cameraWarning.maxSpeedKmh
            val distance = formatCameraDistance(cameraWarning.distanceMeters)
            val text = if (limit != null) {
                stringResource(R.string.pill_speed_camera_with_limit, distance, limit)
            } else {
                stringResource(R.string.pill_speed_camera, distance)
            }
            text to colors.warning
        }
        state.hasArrived -> stringResource(R.string.arrived) to colors.ok
        state.isOffRoute -> stringResource(R.string.off_route) to colors.danger
        state.isRerouting -> stringResource(R.string.rerouting) to colors.warning
        isDemo -> stringResource(R.string.nav_demo_running) to colors.route
        else -> null
    }

    AnimatedVisibility(
        visible = pill != null,
        // A pill appearing is news, not a control being pressed, so it may
        // animate - but only its own opacity and scale, never anyone's layout.
        enter = fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Standard), initialScale = 0.85f),
        exit = fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.9f),
        modifier = modifier,
    ) {
        // Can go null exactly as the exit animation starts; keep showing what
        // it was about rather than popping to nothing mid-fade.
        val shown = pill ?: return@AnimatedVisibility
        StatusPill(
            text = shown.first,
            dotColor = shown.second,
            iconRes = R.drawable.ic_poi_camera.takeIf { cameraWarning != null },
        )
    }
}

/** Plate height that fits the 112 dp arrow with its own breathing room. */
private val MANEUVER_CARD_MIN_HEIGHT = 144.dp

/** The same card on a landscape phone, where 144 dp is a third of the screen. */
private val MANEUVER_CARD_MIN_HEIGHT_COMPACT = 96.dp

/** Raised from 104 dp with the distance: the arrow is read before the number. */
private val MANEUVER_ICON_SIZE = 112.dp

private val MANEUVER_ICON_SIZE_COMPACT = 72.dp

private val NEXT_ICON_SIZE = 56.dp

private val CLOSE_BUTTON_SIZE = 48.dp

/** Keeps the "then" preview clear of the X above it. */
private val CLOSE_BUTTON_GUTTER = 64.dp

/** Below the pill's lane, so a pill never covers the speed. */
private val SPEED_STACK_TOP = 72.dp
