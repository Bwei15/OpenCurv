package com.motoroute.ui.settings

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import com.motoroute.data.settings.MapStyle
import com.motoroute.data.settings.MapTheme
import com.motoroute.data.settings.Settings
import com.motoroute.ui.components.PanelCard
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.components.ScreenHeader
import com.motoroute.ui.theme.LocalRideColors

/**
 * Everything that used to be undiscoverable.
 *
 * The map style, the perspective, heading-up, the volume-key zoom and the voice
 * were all decisions the app made silently. They are decisions a rider wants to
 * make once, at home - so they live on one screen, with a button that actually
 * speaks an announcement rather than a switch that promises one.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onMapStyle: (MapStyle) -> Unit,
    onMapTheme: (MapTheme) -> Unit,
    onPerspective: (Boolean) -> Unit,
    onHeadingUp: (Boolean) -> Unit,
    onVolumeZoom: (Boolean) -> Unit,
    onVoice: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onOpenData: () -> Unit,
    onSpeedCameraWarnings: (Boolean) -> Unit = {},
    onTrafficCredentials: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelCard {
                CardTitle(stringResource(R.string.settings_map_section))

                Text(
                    text = stringResource(R.string.settings_style),
                    color = colors.onPanel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(
                        label = stringResource(R.string.settings_style_colour),
                        selected = settings.mapStyle == MapStyle.COLOUR,
                    ) { onMapStyle(MapStyle.COLOUR) }
                    Choice(
                        label = stringResource(R.string.settings_style_contrast),
                        selected = settings.mapStyle == MapStyle.CONTRAST,
                    ) { onMapStyle(MapStyle.CONTRAST) }
                }
                Text(
                    text = stringResource(R.string.settings_style_hint),
                    color = colors.muted,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_theme),
                    color = colors.onPanel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(
                        label = stringResource(R.string.settings_theme_auto),
                        selected = settings.mapTheme == MapTheme.AUTO,
                    ) { onMapTheme(MapTheme.AUTO) }
                    Choice(
                        label = stringResource(R.string.settings_theme_day),
                        selected = settings.mapTheme == MapTheme.DAY,
                    ) { onMapTheme(MapTheme.DAY) }
                    Choice(
                        label = stringResource(R.string.settings_theme_night),
                        selected = settings.mapTheme == MapTheme.NIGHT,
                    ) { onMapTheme(MapTheme.NIGHT) }
                }
            }

            PanelCard {
                CardTitle(stringResource(R.string.settings_riding_section))
                SwitchRow(
                    title = stringResource(R.string.settings_heading_up),
                    subtitle = stringResource(R.string.settings_heading_up_hint),
                    checked = settings.headingUp,
                    onChange = onHeadingUp,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_perspective),
                    subtitle = stringResource(R.string.settings_perspective_hint),
                    checked = settings.perspectiveEnabled,
                    onChange = onPerspective,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_volume_zoom),
                    subtitle = stringResource(R.string.settings_volume_zoom_hint),
                    checked = settings.volumeKeyZoom,
                    onChange = onVolumeZoom,
                )
            }

            PanelCard {
                CardTitle(stringResource(R.string.settings_voice_section))
                SwitchRow(
                    title = stringResource(R.string.settings_voice),
                    subtitle = stringResource(R.string.settings_voice_hint),
                    checked = settings.voiceEnabled,
                    onChange = onVoice,
                )
                PrimaryButton(
                    label = stringResource(R.string.settings_test_voice),
                    onClick = onTestVoice,
                    height = 52.dp,
                )
            }

            PanelCard {
                CardTitle(stringResource(R.string.settings_data_section))
                Text(
                    text = stringResource(R.string.settings_data_hint),
                    color = colors.muted,
                    fontSize = 13.sp,
                )
                PrimaryButton(
                    label = stringResource(R.string.settings_open_data),
                    onClick = onOpenData,
                    height = 52.dp,
                )
            }

            PanelCard {
                CardTitle(stringResource(R.string.settings_traffic_section))
                Text(
                    text = stringResource(R.string.settings_traffic_hint),
                    color = colors.muted,
                    fontSize = 13.sp,
                )
                // Local edit state, committed on the button: writing every
                // keystroke through to SharedPreferences would also kick off a
                // refresh attempt per character.
                var key by remember(settings.trafficApiKey) { mutableStateOf(settings.trafficApiKey) }
                var url by remember(settings.trafficFeedUrl) { mutableStateOf(settings.trafficFeedUrl) }
                Field(
                    value = key,
                    onValueChange = { key = it },
                    label = stringResource(R.string.settings_traffic_key),
                )
                Field(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.settings_traffic_url),
                )
                Text(
                    text = if (settings.trafficApiKey.isBlank()) {
                        stringResource(R.string.settings_traffic_inactive)
                    } else {
                        stringResource(R.string.settings_traffic_active)
                    },
                    color = if (settings.trafficApiKey.isBlank()) colors.muted else colors.ok,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                PrimaryButton(
                    label = stringResource(R.string.settings_traffic_save),
                    onClick = { onTrafficCredentials(key.trim(), url.trim()) },
                    height = 52.dp,
                )
            }

            PanelCard {
                CardTitle(stringResource(R.string.settings_speed_camera_section))
                SwitchRow(
                    title = stringResource(R.string.settings_speed_camera_warnings),
                    subtitle = stringResource(R.string.settings_speed_camera_warnings_hint),
                    checked = settings.speedCameraWarnings,
                    onChange = onSpeedCameraWarnings,
                )
            }

            Text(
                text = stringResource(R.string.settings_about),
                color = colors.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        color = LocalRideColors.current.onPanel,
        fontSize = 18.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
        modifier = Modifier.height(44.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.route,
            selectedLabelColor = Color.Black,
            labelColor = colors.onPanel,
        ),
    )
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String) {
    val colors = LocalRideColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedTextColor = colors.onPanel,
            unfocusedTextColor = colors.onPanel,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedLabelColor = colors.route,
            unfocusedLabelColor = colors.muted,
            focusedIndicatorColor = colors.route,
            unfocusedIndicatorColor = colors.panelRim,
            cursorColor = colors.route,
        ),
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = subtitle, color = colors.muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
