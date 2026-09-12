package com.motoroute.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.brouter.RoutingProfile
import com.motoroute.data.model.Curviness
import com.motoroute.data.model.Route
import com.motoroute.data.settings.Settings
import com.motoroute.domain.PlanningState
import com.motoroute.ui.components.DraggableSheet
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.components.SecondaryButton
import com.motoroute.ui.theme.LocalRideColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Route planning: pick a destination, choose how much fun you want, calculate,
 * then ride - or watch the app ride it for you first.
 *
 * The panel is a sheet that can be pushed down out of the way, because the map
 * underneath it is half the decision. Destinations come from the search box or
 * from a tap on the map; a long press sets where the route starts, which is
 * what makes planning without a GPS fix possible.
 *
 * The first row is pinned at the top of the content on purpose: it is the
 * *entire* peek (see [DraggableSheet] - the peek height only ever reveals the
 * top of the sheet), and it stays the first thing you see once dragged out
 * too, so the ride button never needs a second, floating copy of itself.
 */
@Composable
fun RoutePlanSheet(
    settings: Settings,
    profiles: List<RoutingProfile>,
    planning: PlanningState,
    destinationName: String?,
    hasDestination: Boolean,
    hasExplicitStart: Boolean,
    onProfileChange: (String) -> Unit,
    onCurvinessChange: (Float) -> Unit,
    onAlternativesChange: (Boolean) -> Unit,
    onCalculate: () -> Unit,
    onStart: (Route) -> Unit,
    onDemo: (Route) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    // A resting-state panel, not a driving HUD: Design_System.md is explicit
    // that the sheet is playful and light while parked and only turns dark
    // and HUD-like once the rider is actually navigating (a different screen
    // entirely - see ActiveNavigationScreen). Regel 2 applies too: a floating
    // plate needs its own 1 dp rim, since a shadow alone is invisible in
    // direct sun and directionless over a plain road.
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peekHeight = (if (hasDestination) PEEK_HEIGHT_DESTINATION else PEEK_HEIGHT_EMPTY) + navBarBottom

    DraggableSheet(
        peekHeight = peekHeight,
        background = colors.panel,
        handleColor = colors.panelRim,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PeekRow(
                planning = planning,
                destinationName = destinationName,
                hasDestination = hasDestination,
                onCalculate = onCalculate,
                onStart = onStart,
            )

            // Everything below here is outside the peek window - it only
            // shows once the rider has actually dragged the sheet open.
            when (planning) {
                PlanningState.Idle -> {
                    Text(
                        text = stringResource(
                            if (hasExplicitStart) {
                                R.string.plan_start_set_hint
                            } else {
                                R.string.plan_hint
                            },
                        ),
                        color = colors.muted,
                        fontSize = 13.sp,
                    )
                }

                PlanningState.Calculating -> Unit

                is PlanningState.Ready -> {
                    val route = planning.route
                    // Climb and curviness used to sit in a four-up metrics
                    // row with distance and time; both of those moved into
                    // the peek line above, and these two are demoted to a
                    // single small caption - useful, but not what the sheet
                    // leads with any more.
                    Text(
                        text = stringResource(
                            R.string.plan_peek_detail,
                            route.ascendMeters,
                            Curviness.label(route.curvinessScore),
                            route.curvinessScore.roundToInt(),
                        ),
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = { onDemo(route) }) {
                        Text(
                            text = stringResource(R.string.plan_demo),
                            color = colors.route,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                    Text(
                        text = stringResource(R.string.plan_demo_hint),
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                    SecondaryButton(
                        label = stringResource(R.string.plan_discard),
                        onClick = onClear,
                    )
                }

                is PlanningState.Failed -> {
                    SecondaryButton(
                        label = stringResource(R.string.plan_clear),
                        onClick = onClear,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.plan_options),
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    FilterChip(
                        selected = profile.id == settings.profileId,
                        onClick = { onProfileChange(profile.id) },
                        label = {
                            Text(
                                profile.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        },
                        // 56 dp: the resting-register minimum (Design_System.md
                        // §5), not the 46 dp this used to be.
                        modifier = Modifier.height(56.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.route,
                            selectedLabelColor = Color.Black,
                            labelColor = colors.onPanel,
                        ),
                    )
                }
            }

            Column {
                Text(
                    text = stringResource(
                        R.string.plan_curviness,
                        stringResource(curvinessLabel(settings.curviness)),
                    ),
                    color = colors.onPanel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Slider(
                    value = settings.curviness,
                    onValueChange = onCurvinessChange,
                    valueRange = 0f..2f,
                    steps = 3,
                    modifier = Modifier.height(48.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.material3.Switch(
                    checked = settings.searchAlternatives,
                    onCheckedChange = onAlternativesChange,
                )
                Text(
                    text = stringResource(R.string.plan_alternatives),
                    color = colors.onPanel,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * The sheet's peek, in full: everything a rider needs without dragging
 * anything open. A calculated route leads with the one number that matters -
 * riding time - and a round ride button big enough not to be mistaken for a
 * secondary action; anything still being decided gets a compact line plus
 * whichever action applies, or a thin progress bar while BRouter is working.
 */
@Composable
private fun PeekRow(
    planning: PlanningState,
    destinationName: String?,
    hasDestination: Boolean,
    onCalculate: () -> Unit,
    onStart: (Route) -> Unit,
) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PLAY_BUTTON_SIZE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (planning) {
            is PlanningState.Ready -> {
                val route = planning.route
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatDuration(route.estimatedSeconds),
                        color = colors.onPanel,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = stringResource(
                            R.string.plan_peek_summary,
                            String.format(Locale.getDefault(), "%.0f", route.distanceMeters / 1000.0),
                            formatArrival(route.estimatedSeconds),
                        ),
                        color = colors.muted,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                PlayButton(onClick = { onStart(route) })
            }

            PlanningState.Calculating -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = destinationName ?: stringResource(R.string.plan_calculating),
                        color = colors.onPanel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        color = colors.route,
                        trackColor = colors.panelSunken,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = destinationName
                            ?: stringResource(
                                if (hasDestination) R.string.plan_destination_pin else R.string.plan_no_destination,
                            ),
                        color = colors.onPanel,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                    if (planning is PlanningState.Failed) {
                        Text(
                            text = planning.message,
                            color = colors.danger,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                    }
                }
                if (hasDestination) {
                    Spacer(Modifier.width(12.dp))
                    PeekActionButton(
                        label = stringResource(
                            if (planning is PlanningState.Failed) R.string.plan_retry else R.string.plan_calculate,
                        ),
                        onClick = onCalculate,
                    )
                }
            }
        }
    }
}

/**
 * The one button that matters once a route exists: round, in the accent
 * colour rather than the route colour so it reads as "go" and not as part of
 * the route readout, and at 64 dp well past the resting-register minimum so
 * it is never mistaken for a secondary action.
 */
@Composable
private fun PlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRideColors.current
    val label = stringResource(R.string.plan_start)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.accent,
        modifier = modifier
            .size(PLAY_BUTTON_SIZE)
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_action_start),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * The "Calculate route" / "Try again" action in the peek row.
 *
 * Not [PrimaryButton]: that one insists on filling the width it is given,
 * which is exactly wrong here - the peek row needs it to size to its own
 * label and leave the rest to the destination text next to it, or a
 * two-line wrap gets clipped by the peek's fixed height.
 */
@Composable
private fun PeekActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRideColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = colors.route,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun curvinessLabel(value: Float): Int = when {
    value < 0.4f -> R.string.curviness_direct
    value < 0.9f -> R.string.curviness_mild
    value < 1.4f -> R.string.curviness_balanced
    value < 1.8f -> R.string.curviness_hungry
    else -> R.string.curviness_max
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours} h ${minutes} min" else "$minutes min"
}

/** Wall-clock arrival, computed from "now" - there is no other clock to ask offline. */
private fun formatArrival(estimatedSeconds: Int): String {
    val arrival = Calendar.getInstance().apply { add(Calendar.SECOND, estimatedSeconds) }
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival.time)
}

/** Peek height with no destination picked yet: just the hint text. */
val PEEK_HEIGHT_EMPTY = 64.dp

/**
 * Peek height once a destination exists: tall enough for the 64 dp ride
 * button plus its own breathing room, which is also enough for the
 * destination-plus-calculate-button row and the progress bar.
 */
val PEEK_HEIGHT_DESTINATION = 96.dp

private val PLAY_BUTTON_SIZE = 64.dp

/**
 * A card, not a curtain: the old empty state covered the whole map until data
 * appeared, which hid the very thing a new rider wants to look at.
 */
@Composable
fun MissingDataCard(
    hasMaps: Boolean,
    hasSegments: Boolean,
    onOpenData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasMaps && hasSegments) return
    val colors = LocalRideColors.current

    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    androidx.compose.material3.Surface(
        color = colors.panel,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.panelRim),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (!hasMaps) R.string.empty_no_map else R.string.empty_no_segments,
                ),
                color = colors.onPanel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = stringResource(R.string.empty_body),
                color = colors.muted,
                fontSize = 14.sp,
            )
            PrimaryButton(
                label = stringResource(R.string.empty_action),
                onClick = onOpenData,
                height = 52.dp,
            )
            SecondaryButton(
                label = stringResource(R.string.empty_later),
                onClick = { dismissed = true },
            )
        }
    }
}
