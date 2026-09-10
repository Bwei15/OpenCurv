package com.motoroute.ui.plan

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.motoroute.data.model.Curviness
import com.motoroute.data.model.Route
import com.motoroute.data.settings.Settings
import com.motoroute.domain.PlanningState
import com.motoroute.ui.components.DraggableSheet
import com.motoroute.ui.components.PrimaryButton
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
            // Peek content: what the sheet has to say when it is pushed down.
            Text(
                text = destinationName
                    ?: stringResource(
                        if (hasDestination) R.string.plan_destination_pin else R.string.plan_no_destination,
                    ),
                color = colors.onPanel,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )

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
                            color = colors.onPanel,
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
            Curviness.label(route.curvinessScore),
            "${route.curvinessScore.roundToInt()} °/km",
            colors.route,
        )
    }
}

@Composable
private fun Metric(
    value: String,
    caption: String,
    color: Color = LocalRideColors.current.onPanel,
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

private val PEEK_HEIGHT_EMPTY = 60.dp
private val PEEK_HEIGHT_DESTINATION = 132.dp

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
