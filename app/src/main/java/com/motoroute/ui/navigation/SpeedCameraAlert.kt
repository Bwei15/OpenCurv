package com.motoroute.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motoroute.R
import com.motoroute.domain.cameras.SpeedCameraWarning
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TypeScale

/**
 * The speed-camera warning, as the resting screen shows it.
 *
 * A full-screen red field used to cover the map the moment a camera came into
 * range while riding. That was the single most disruptive thing the HUD did -
 * it hid the route at exactly the moment the rider was braking - and the ride
 * report asked for the opposite: a small pill that changes nothing else. The
 * riding warning is therefore [com.motoroute.ui.navigation.StatusPill], built
 * in `ActiveNavigationScreen`; what is left here is the compact banner for the
 * screen used standing still.
 */

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
