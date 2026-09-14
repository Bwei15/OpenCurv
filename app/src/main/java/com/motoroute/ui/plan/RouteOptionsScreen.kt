package com.motoroute.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motoroute.R
import com.motoroute.data.brouter.RoutingProfile
import com.motoroute.data.settings.Settings
import com.motoroute.ui.components.PanelCard
import com.motoroute.ui.components.ScreenHeader
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TapTargetSize
import com.motoroute.ui.theme.TypeScale
import kotlin.math.roundToInt

/**
 * Route options, on their own screen.
 *
 * These used to be stacked at the bottom of the planning sheet: the profile
 * chips, the curviness slider, an "alternatives" switch and a "round trip"
 * switch, all below the stop list, all reached by scrolling past the thing the
 * rider actually came to the sheet for. Two of them are settings you touch once
 * a season; one of them (round trip) is not an option at all but a choice about
 * what kind of route this is, and has moved up into the sheet itself.
 *
 * What is left belongs here, grouped by the question it answers: how do you
 * want to ride, how hungry for curves are you, what should be avoided, and how
 * hard should the router look.
 */
@Composable
fun RouteOptionsScreen(
    settings: Settings,
    profiles: List<RoutingProfile>,
    onBack: () -> Unit,
    onProfileChange: (String) -> Unit,
    onCurvinessChange: (Float) -> Unit,
    onAlternativesChange: (Boolean) -> Unit,
    onAvoidMotorwaysChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(title = stringResource(R.string.plan_options_open), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.Lg),
            verticalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            PanelCard {
                CardTitle(stringResource(R.string.plan_options_style_section))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
                    profiles.forEach { profile ->
                        FilterChip(
                            selected = profile.id == settings.profileId,
                            onClick = { onProfileChange(profile.id) },
                            label = {
                                Text(
                                    profile.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = TypeScale.BodySmall,
                                )
                            },
                            modifier = Modifier.height(TapTargetSize),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primary,
                                selectedLabelColor = colors.onPrimary,
                                labelColor = colors.onPanel,
                            ),
                        )
                    }
                }
            }

            PanelCard {
                CardTitle(stringResource(R.string.plan_options_curviness_section))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(curvinessLabelRes(settings.curviness)),
                        color = colors.onPanel,
                        fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.Body,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // One decimal: the slider has five stops, and a rider
                        // who moved it wants to see that something changed.
                        text = String.format(java.util.Locale.getDefault(), "%.1f", settings.curviness),
                        color = colors.muted,
                        fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.BodySmall,
                    )
                }
                Slider(
                    value = settings.curviness,
                    onValueChange = onCurvinessChange,
                    valueRange = 0f..2f,
                    steps = 3,
                    modifier = Modifier.height(48.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.curviness_direct),
                        color = colors.faint,
                        fontSize = TypeScale.Micro,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.curviness_max),
                        color = colors.faint,
                        fontSize = TypeScale.Micro,
                    )
                }
            }

            PanelCard {
                CardTitle(stringResource(R.string.plan_options_avoid_section))
                SwitchRow(
                    title = stringResource(R.string.plan_options_avoid_motorways),
                    subtitle = stringResource(R.string.plan_options_avoid_motorways_hint),
                    checked = settings.avoidMotorways,
                    onChange = onAvoidMotorwaysChange,
                )
            }

            PanelCard {
                CardTitle(stringResource(R.string.plan_options_search_section))
                SwitchRow(
                    title = stringResource(R.string.plan_alternatives),
                    subtitle = stringResource(R.string.plan_alternatives_hint),
                    checked = settings.searchAlternatives,
                    onChange = onAlternativesChange,
                )
            }

            Spacer(Modifier.height(Space.Xl))
        }
    }
}

/** A one-line summary of the options, for the row that opens this screen. */
@Composable
fun routeOptionsSummary(settings: Settings, profiles: List<RoutingProfile>): String {
    val profileName = profiles.firstOrNull { it.id == settings.profileId }?.displayName
        ?: stringResource(R.string.plan_options_style_section)
    val curviness = stringResource(curvinessLabelRes(settings.curviness))
    val avoid = if (settings.avoidMotorways) {
        " · " + stringResource(R.string.plan_options_avoid_motorways)
    } else {
        ""
    }
    return "$profileName · $curviness$avoid"
}

/** The label the curviness value reads as. Shared with the planning sheet. */
fun curvinessLabelRes(value: Float): Int = when {
    value < 0.4f -> R.string.curviness_direct
    value < 0.9f -> R.string.curviness_mild
    value < 1.4f -> R.string.curviness_balanced
    value < 1.8f -> R.string.curviness_hungry
    else -> R.string.curviness_max
}

/** Kilometres of motorway on a route, for the planning sheet's summary line. */
fun motorwayKilometres(motorwayMeters: Double): Int = (motorwayMeters / 1000.0).roundToInt()

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        color = LocalRideColors.current.onPanel,
        fontSize = TypeScale.Subtitle,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onPanel,
                fontSize = TypeScale.Body,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, color = colors.muted, fontSize = TypeScale.Micro)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = Color.White,
            ),
        )
    }
}
