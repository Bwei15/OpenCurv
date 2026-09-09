package com.motoroute.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
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
import com.motoroute.data.brouter.RoutingProfile
import com.motoroute.data.model.Route
import com.motoroute.data.settings.Settings
import com.motoroute.domain.PlanningState
import com.motoroute.ui.components.DraggableSheet
import com.motoroute.ui.components.SheetState
import com.motoroute.ui.components.rememberSheetState
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.components.curvinessRatingLabel
import com.motoroute.ui.components.SecondaryButton
import com.motoroute.ui.theme.LocalRideColors
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
 */
@Composable
fun RoutePlanSheet(
    settings: Settings,
    profiles: List<RoutingProfile>,
    planning: PlanningState,
    destinationName: String?,
    hasDestination: Boolean,
    onProfileChange: (String) -> Unit,
    onCurvinessChange: (Float) -> Unit,
    onAlternativesChange: (Boolean) -> Unit,
    onCalculate: () -> Unit,
    onStart: (Route) -> Unit,
    onDemo: (Route) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberSheetState(),
) {
    val colors = LocalRideColors.current

    // Once there is a destination the panel is about that ride, not about how
    // routes are searched, so the options fold away. They stay one tap out
    // rather than disappearing for good - swapping curvy for fast should not
    // cost the rider the destination they just picked.
    var showOptions by remember { mutableStateOf(false) }

    DraggableSheet(
        peekHeight = PEEK_HEIGHT,
        state = sheetState,
        background = colors.hudBackground.copy(alpha = 0.96f),
        handleColor = colors.hudForeground,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Peek content: what the sheet has to say when it is pushed down.
            Text(
                text = destinationName
                    ?: stringResource(
                        if (hasDestination) R.string.plan_destination_pin else R.string.plan_no_destination,
                    ),
                color = colors.hudForeground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )

            when (planning) {
                PlanningState.Idle -> {
                    PrimaryButton(
                        label = stringResource(R.string.plan_calculate),
                        enabled = hasDestination,
                        onClick = onCalculate,
                    )
                }

                PlanningState.Calculating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.height(60.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.route,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = stringResource(R.string.plan_calculating),
                            color = colors.hudForeground,
                            fontSize = 16.sp,
                        )
                    }
                }

                is PlanningState.Ready -> {
                    RouteSummary(planning.route)
                    PrimaryButton(
                        label = stringResource(R.string.plan_start),
                        onClick = { onStart(planning.route) },
                    )
                    SecondaryButton(
                        label = stringResource(R.string.plan_demo),
                        onClick = { onDemo(planning.route) },
                    )
                    SecondaryButton(
                        label = stringResource(R.string.plan_discard),
                        onClick = onClear,
                    )
                }

                is PlanningState.Failed -> {
                    Text(
                        text = planning.message,
                        color = colors.danger,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    PrimaryButton(
                        label = stringResource(R.string.plan_retry),
                        onClick = onCalculate,
                    )
                    SecondaryButton(
                        label = stringResource(R.string.plan_clear),
                        onClick = onClear,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (hasDestination) {
                Text(
                    text = stringResource(
                        if (showOptions) R.string.plan_options_hide else R.string.plan_options_show,
                    ),
                    color = colors.route,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOptions = !showOptions }
                        .padding(vertical = 10.dp),
                )
            }

            if (!hasDestination || showOptions) {
                RouteOptions(
                    settings = settings,
                    profiles = profiles,
                    onProfileChange = onProfileChange,
                    onCurvinessChange = onCurvinessChange,
                    onAlternativesChange = onAlternativesChange,
                )
            }
        }
    }
}

/** How routes are searched: which profile, how many curves, how hard to look. */
@Composable
private fun RouteOptions(
    settings: Settings,
    profiles: List<RoutingProfile>,
    onProfileChange: (String) -> Unit,
    onCurvinessChange: (Float) -> Unit,
    onAlternativesChange: (Boolean) -> Unit,
) {
    val colors = LocalRideColors.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    modifier = Modifier.height(46.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.route,
                        selectedLabelColor = Color.Black,
                        labelColor = colors.hudForeground,
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
                color = colors.hudForeground,
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
            Column {
                Text(
                    text = stringResource(R.string.plan_alternatives),
                    color = colors.hudForeground,
                    fontSize = 14.sp,
                )
                Text(
                    text = stringResource(R.string.plan_alternatives_cost),
                    color = colors.muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun RouteSummary(route: Route) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Metric(
            String.format(Locale.getDefault(), "%.1f km", route.distanceMeters / 1000.0),
            stringResource(R.string.plan_distance),
        )
        Metric(formatDuration(route.estimatedSeconds), stringResource(R.string.plan_time))
        Metric("${route.ascendMeters} m", stringResource(R.string.plan_climb))
        Metric(
            curvinessRatingLabel(route.curvinessScore),
            "${route.curvinessScore.roundToInt()} °/km",
            colors.route,
        )
    }
}

@Composable
private fun Metric(
    value: String,
    caption: String,
    color: Color = LocalRideColors.current.hudForeground,
) {
    Column {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(caption, color = LocalRideColors.current.muted, fontSize = 11.sp)
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

private val PEEK_HEIGHT = 132.dp

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
        color = colors.hudBackground.copy(alpha = 0.94f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
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
                color = colors.hudForeground,
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
