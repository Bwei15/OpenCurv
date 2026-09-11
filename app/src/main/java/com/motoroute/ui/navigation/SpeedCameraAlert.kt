package com.motoroute.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.domain.cameras.SpeedCameraWarning
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TypeScale

/**
 * The full-screen speed-camera warning: red field, camera icon, "Blitzer".
 *
 * Deliberately standalone rather than folded into [ActiveNavigationScreen] -
 * see its own doc comment for why: the warning is meant to fire with or
 * without an active ride, so it cannot live inside a screen that only exists
 * while navigating. `ui/OpenCurvRoot.kt` mounts this once, above every other
 * screen, exactly because of that. Welle 7 is expected to also reach it from
 * the HUD proper once the navigation screen is rebuilt there - this
 * composable is the reusable part that work will wire in, not duplicate.
 */
@Composable
fun SpeedCameraAlert(warning: SpeedCameraWarning?, modifier: Modifier = Modifier) {
    val colors = LocalRideColors.current
    AnimatedVisibility(
        visible = warning != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // warning can go null exactly as the exit animation starts; hang onto
        // the last non-null value so the fade-out shows the camera it was
        // warning about instead of popping to nothing mid-animation.
        val shown = warning ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.danger.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_poi_camera),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(112.dp),
                )
                Text(
                    text = stringResource(R.string.speed_camera_alert_title),
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                // Reuses NavigationComponents.kt's own formatDistance() - same
                // coarse rounding the HUD's distance readout uses, so "in 600
                // m" here and "600 m" on the maneuver bar never disagree.
                val (distanceValue, distanceUnit) = formatDistance(shown.distanceMeters)
                Text(
                    text = stringResource(
                        R.string.speed_camera_alert_distance,
                        "$distanceValue $distanceUnit".trim(),
                    ),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                shown.maxSpeedKmh?.let { limit ->
                    Box(modifier = Modifier.padding(top = 20.dp)) {
                        SpeedLimitSign(limit)
                    }
                }
            }
        }
    }
}

/** A round German Verkehrszeichen-274-style speed-limit sign: white disc, red ring, black number. */
@Composable
private fun SpeedLimitSign(limitKmh: Int) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(width = 7.dp, color = Color(0xFFE10600), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = limitKmh.toString(),
            color = Color.Black,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/** "600 m" / "1.2 km" - reuses the maneuver bar's own distance rounding. */
fun formatCameraDistance(meters: Double): String {
    val (value, unit) = formatDistance(meters)
    return if (unit.isEmpty()) value else "$value $unit"
}

/**
 * Compact banner for the resting screen: the same warning without owning the
 * whole screen, so it can sit above the search bar while the rider is still
 * standing still and planning.
 */
@Composable
fun SpeedCameraBanner(warning: SpeedCameraWarning?, modifier: Modifier = Modifier) {
    if (warning == null) return
    val colors = LocalRideColors.current
    Surface(
        color = colors.hudDangerField,
        shape = RoundedCornerShape(Radius.Md),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.Lg, vertical = Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_poi_camera),
                contentDescription = null,
                tint = colors.onBanner,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.speed_camera_banner, formatCameraDistance(warning.distanceMeters)),
                color = colors.onBanner,
                fontWeight = FontWeight.Black,
                fontSize = TypeScale.Label,
            )
        }
    }
}
