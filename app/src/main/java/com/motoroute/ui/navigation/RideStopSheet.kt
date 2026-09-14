package com.motoroute.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motoroute.R
import com.motoroute.ui.components.AddStopTile
import com.motoroute.ui.components.DraggableSheet
import com.motoroute.ui.components.StopRole
import com.motoroute.ui.components.StopTile
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TypeScale
import com.motoroute.ui.theme.rememberWindowShape

/**
 * One entry in the riding stop list.
 *
 * [distanceMeters] and [etaEpochMillis] are how far along the route this stop
 * is from where the rider is *now*, which is the only version of a stop list
 * that is useful mid-ride: "Externsteine, in 12 km, 16:02".
 */
data class RideStop(
    val name: String,
    val role: StopRole,
    val number: Int = 0,
    val distanceMeters: Double? = null,
    val etaEpochMillis: Long? = null,
)

/**
 * The ride's stops, pulled up from the bottom of the HUD.
 *
 * Before this, dragging the HUD's menu open produced three buttons - mute,
 * recalculate, stop - and the rider had no way at all to see what was still
 * ahead of them without ending the ride. The ride report asked for the menu to
 * become the route instead: current position, the stops in order, the
 * destination, and a way to add one.
 *
 * It is the same [DraggableSheet] the planning screen uses, in the riding
 * register: dark, and with the stop tiles drawn on dark. Collapsed it shows one
 * line - arrival, remaining distance, how many stops - which is enough to know
 * whether pulling it up is worth doing at 100 km/h.
 */
@Composable
fun RideStopSheet(
    stops: List<RideStop>,
    etaEpochMillis: Long,
    remainingMeters: Double,
    modifier: Modifier = Modifier,
    onAddStop: (() -> Unit)? = null,
    onRemoveStop: ((Int) -> Unit)? = null,
) {
    val colors = LocalRideColors.current
    val viaCount = stops.count { it.role == StopRole.VIA }
    val window = rememberWindowShape()

    DraggableSheet(
        peekHeight = PEEK_HEIGHT,
        background = colors.hudBackground,
        handleColor = colors.hudDivider,
        maxHeight = minOf(MAX_HEIGHT, window.maxSheetHeight),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.Lg)
                .padding(bottom = Space.Lg),
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            // The peek: everything worth reading without opening anything.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatEta(etaEpochMillis),
                    color = colors.hudForeground,
                    fontSize = TypeScale.Title,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = " · ${formatRemaining(remainingMeters)}" +
                        if (viaCount > 0) {
                            " · " + stringResource(R.string.ride_stop_count, viaCount)
                        } else {
                            ""
                        },
                    color = colors.hudMuted,
                    fontSize = TypeScale.BodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            stops.forEachIndexed { index, stop ->
                StopTile(
                    name = stop.name,
                    role = stop.role,
                    number = stop.number,
                    caption = stopCaption(stop),
                    // Only a stop along the way can be taken out mid-ride;
                    // removing the destination would end the navigation, which
                    // is what the X on the maneuver card is for.
                    onRemove = if (stop.role == StopRole.VIA && onRemoveStop != null) {
                        { onRemoveStop(viaIndexOf(stops, index)) }
                    } else {
                        null
                    },
                    onDark = true,
                )
            }

            if (onAddStop != null) {
                AddStopTile(
                    onClick = onAddStop,
                    label = stringResource(R.string.plan_add_stop),
                    onDark = true,
                )
            }
        }
    }
}

/** "in 12 km · 16:02", or just one half of it, or nothing. */
@Composable
private fun stopCaption(stop: RideStop): String? {
    val distance = stop.distanceMeters?.let { stringResource(R.string.ride_stop_in, formatRemaining(it)) }
    val eta = stop.etaEpochMillis?.takeIf { it > 0L }?.let { formatEta(it) }
    return when {
        distance != null && eta != null -> "$distance · $eta"
        distance != null -> distance
        eta != null -> eta
        else -> null
    }
}

/**
 * Position of [index] among the VIA stops only.
 *
 * The list the sheet shows includes the endpoints, but `onRemoveStop` indexes
 * into the via list the view model keeps - so the two have to be translated,
 * and doing it here keeps the caller from having to know the sheet's layout.
 */
private fun viaIndexOf(stops: List<RideStop>, index: Int): Int =
    stops.take(index).count { it.role == StopRole.VIA }

/** One line: arrival, remaining distance, stop count. */
private val PEEK_HEIGHT = 72.dp

/** Deliberately shorter than the planning sheet's: the map matters more mid-ride. */
private val MAX_HEIGHT = 380.dp
