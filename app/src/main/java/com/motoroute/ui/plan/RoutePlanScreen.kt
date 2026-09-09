package com.motoroute.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.data.brouter.RoutingProfile
import com.motoroute.data.model.Curviness
import com.motoroute.data.model.Route
import com.motoroute.data.settings.Settings
import com.motoroute.domain.PlanningState
import com.motoroute.ui.theme.GloveTargetSize
import com.motoroute.ui.theme.LocalRideColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Route planning: pick a destination on the map, choose how much fun you want,
 * calculate, then ride.
 *
 * Destination entry is by map tap rather than a search box on purpose. Offline
 * geocoding would need a place index the rider does not have, and inventing one
 * would either need a network call - which this app does not have permission to
 * make - or a database bigger than the map itself.
 */
@Composable
fun RoutePlanPanel(
    settings: Settings,
    profiles: List<RoutingProfile>,
    planning: PlanningState,
    hasDestination: Boolean,
    onProfileChange: (String) -> Unit,
    onCurvinessChange: (Float) -> Unit,
    onAlternativesChange: (Boolean) -> Unit,
    onCalculate: () -> Unit,
    onStart: (Route) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    Surface(
        color = colors.hudBackground.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Route",
                color = colors.hudForeground,
                fontSize = 22.sp,
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
                                fontSize = 16.sp,
                            )
                        },
                        modifier = Modifier.height(56.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.route,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.Black,
                            labelColor = colors.hudForeground,
                        ),
                    )
                }
            }

            Column {
                Text(
                    text = "Curve appetite: ${curvinessLabel(settings.curviness)}",
                    color = colors.hudForeground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Slider(
                    value = settings.curviness,
                    onValueChange = onCurvinessChange,
                    valueRange = 0f..2f,
                    steps = 3,
                    modifier = Modifier.height(GloveTargetSize),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(
                    checked = settings.searchAlternatives,
                    onCheckedChange = onAlternativesChange,
                )
                Text(
                    text = "Compare alternatives and keep the twistiest",
                    color = colors.hudForeground,
                    fontSize = 15.sp,
                )
            }

            when (planning) {
                PlanningState.Idle -> {
                    Text(
                        text = if (hasDestination) {
                            "Destination set. Calculate when ready."
                        } else {
                            "Tap the map to drop a destination."
                        },
                        color = colors.muted,
                        fontSize = 15.sp,
                    )
                    PrimaryAction(
                        label = "Calculate route",
                        enabled = hasDestination,
                        onClick = onCalculate,
                    )
                }

                PlanningState.Calculating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.route,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "Calculating offline…",
                            color = colors.hudForeground,
                            fontSize = 16.sp,
                        )
                    }
                }

                is PlanningState.Ready -> {
                    RouteSummary(planning.route)
                    PrimaryAction(label = "Start navigation") { onStart(planning.route) }
                    SecondaryAction(label = "Discard", onClick = onClear)
                }

                is PlanningState.Failed -> {
                    Text(
                        text = planning.message,
                        color = colors.danger,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    PrimaryAction(label = "Try again", onClick = onCalculate)
                    SecondaryAction(label = "Clear", onClick = onClear)
                }
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
            String.format(Locale.US, "%.1f km", route.distanceMeters / 1000.0),
            "distance",
        )
        Metric(formatDuration(route.estimatedSeconds), "riding time")
        Metric("${route.ascendMeters} m", "climb")
        Metric(
            Curviness.label(route.curvinessScore),
            "${route.curvinessScore.roundToInt()} deg/km",
            colors.route,
        )
    }
}

@Composable
private fun Metric(
    value: String,
    caption: String,
    color: androidx.compose.ui.graphics.Color = LocalRideColors.current.hudForeground,
) {
    Column {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(caption, color = LocalRideColors.current.muted, fontSize = 12.sp)
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.route,
            contentColor = androidx.compose.ui.graphics.Color.Black,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(GloveTargetSize),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = colors.muted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(GloveTargetSize),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun curvinessLabel(value: Float): String = when {
    value < 0.4f -> "direct"
    value < 0.9f -> "mild"
    value < 1.4f -> "balanced"
    value < 1.8f -> "hungry"
    else -> "maximum"
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Empty-state card shown when there is no offline data yet. */
@Composable
fun MissingDataNotice(
    hasMaps: Boolean,
    hasSegments: Boolean,
    onOpenData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasMaps && hasSegments) return
    val colors = LocalRideColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.hudBackground.copy(alpha = 0.9f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "No offline data yet",
                color = colors.hudForeground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = buildString {
                    if (!hasMaps) appendLine("• a map to draw from")
                    if (!hasSegments) appendLine("• routing tiles to calculate routes")
                    appendLine()
                    append("Download them in the app, or import files you already have.")
                },
                color = colors.muted,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            PrimaryAction(label = "Get maps", onClick = onOpenData)
        }
    }
}
