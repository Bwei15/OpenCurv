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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.motoroute.R
import com.motoroute.data.brouter.RoutingProfile
import com.motoroute.data.history.HistoryTrip
import com.motoroute.data.model.Curviness
import com.motoroute.data.model.Route
import com.motoroute.data.settings.Settings
import com.motoroute.domain.PlanningState
import com.motoroute.ui.components.AddStopTile
import com.motoroute.ui.components.DraggableSheet
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.components.ReorderableStopColumn
import com.motoroute.ui.components.SecondaryButton
import com.motoroute.ui.components.StopRole
import com.motoroute.ui.components.StopTile
import com.motoroute.ui.map.Stop
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TapTargetSize
import com.motoroute.ui.theme.TypeScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Route planning: say where you are going, put the stops in the order you want
 * them, calculate, then ride.
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
 *
 * ## What the September 2026 ride report changed
 *
 * The sheet had grown into a single scrolling column of everything: a hint, a
 * demo-ride link, the stop list as bare rows with up/down/remove glyphs, a
 * profile chip row, a curviness slider, and two switches - one of which
 * ("Rundtour") was not an option but a decision about what kind of route this
 * is, hidden at the very bottom where a rider would never find it.
 *
 * Now: the mode choice is a segmented control right under the peek, the stops
 * are draggable tiles with one X each, and the options are a single row that
 * opens [RouteOptionsScreen]. The demo-ride link is gone entirely - it was a
 * development tool sitting on the rider's critical path.
 */
@Composable
fun RoutePlanSheet(
    settings: Settings,
    profiles: List<RoutingProfile>,
    planning: PlanningState,
    destinationName: String?,
    hasDestination: Boolean,
    hasExplicitStart: Boolean,
    via: List<Stop>,
    roundTrip: Boolean,
    recentTrips: List<HistoryTrip>,
    onRoundTripChange: (Boolean) -> Unit,
    onSuggestRoundTrip: (Float) -> Unit,
    onAddStop: () -> Unit,
    onMoveStop: (Int, Int) -> Unit,
    onRemoveStop: (Int) -> Unit,
    onPickRecentTrip: (HistoryTrip) -> Unit,
    onOpenOptions: () -> Unit,
    onCalculate: () -> Unit,
    onStart: (Route) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    // A resting-state panel, not a driving HUD: Design_System.md is explicit
    // that the sheet is playful and light while parked and only turns dark
    // and HUD-like once the rider is actually navigating (a different screen
    // entirely - see ActiveNavigationScreen).
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
                .padding(horizontal = Space.Lg)
                .padding(bottom = Space.Xl),
            verticalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            PeekRow(
                planning = planning,
                destinationName = destinationName,
                hasDestination = hasDestination,
                roundTrip = roundTrip,
                onCalculate = onCalculate,
                onStart = onStart,
            )

            // Everything below here is outside the peek window - it only shows
            // once the rider has actually dragged the sheet open.

            // What kind of route this is: the first question, so the first
            // control. It used to be a switch at the very bottom.
            ModeSelector(
                roundTrip = roundTrip,
                onChange = onRoundTripChange,
            )

            if (planning is PlanningState.Failed) {
                Text(
                    text = planning.message,
                    color = colors.danger,
                    fontSize = TypeScale.Label,
                )
            }

            if (hasDestination || roundTrip) {
                StopSection(
                    hasExplicitStart = hasExplicitStart,
                    via = via,
                    destinationName = destinationName,
                    roundTrip = roundTrip,
                    onMove = onMoveStop,
                    onRemove = onRemoveStop,
                    onAddStop = onAddStop,
                )
            } else if (recentTrips.isNotEmpty()) {
                RecentTripsSection(trips = recentTrips, onPick = onPickRecentTrip)
            }

            // Nothing to loop through yet: offer to fill the stop list in rather
            // than leaving the rider staring at "Ziel = Start" with no stops.
            if (roundTrip && via.isEmpty()) {
                RoundTripSuggestCard(onSuggest = onSuggestRoundTrip)
            }

            OptionsRow(
                summary = routeOptionsSummary(settings, profiles),
                onClick = onOpenOptions,
            )

            if (planning is PlanningState.Ready) {
                val route = planning.route
                Text(
                    text = stringResource(
                        R.string.plan_peek_detail,
                        route.ascendMeters,
                        Curviness.label(route.curvinessScore),
                        route.curvinessScore.roundToInt(),
                    ),
                    color = colors.muted,
                    fontSize = TypeScale.Micro,
                )
                SecondaryButton(
                    label = stringResource(R.string.plan_discard),
                    onClick = onClear,
                )
            } else if (planning is PlanningState.Failed) {
                SecondaryButton(
                    label = stringResource(R.string.plan_clear),
                    onClick = onClear,
                )
            } else if (!hasDestination && !roundTrip) {
                Text(
                    text = stringResource(
                        if (hasExplicitStart) R.string.plan_start_set_hint else R.string.plan_hint,
                    ),
                    color = colors.muted,
                    fontSize = TypeScale.Label,
                )
            }
        }
    }
}

/**
 * "To a destination" / "Round trip", as one segmented control.
 *
 * A round trip is not a switch you flip on top of a route to somewhere - it is
 * the other kind of route, and the two are mutually exclusive. A segmented
 * control says that; a checkbox below the options did not, which is why the
 * ride report could not find it.
 */
@Composable
private fun ModeSelector(roundTrip: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalRideColors.current
    Surface(
        color = colors.panelSunken,
        shape = RoundedCornerShape(Radius.Full),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(Space.Xs)) {
            SegmentedOption(
                label = stringResource(R.string.plan_mode_to_destination),
                selected = !roundTrip,
                onClick = { onChange(false) },
                modifier = Modifier.weight(1f),
            )
            SegmentedOption(
                label = stringResource(R.string.plan_mode_round_trip),
                selected = roundTrip,
                onClick = { onChange(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentedOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Full),
        color = if (selected) colors.panel else Color.Transparent,
        modifier = modifier.heightIn(min = SEGMENT_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) colors.onPanel else colors.muted,
                fontWeight = FontWeight.Bold,
                fontSize = TypeScale.BodySmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * Start -> Stop 1 -> ... -> Ziel, as tiles.
 *
 * Only the stops in between can be moved or removed: the endpoints are
 * structural (Start comes from GPS or a long press, Ziel from search or a tap),
 * which is why they are drawn as outlines with no grip and no X.
 */
@Composable
private fun StopSection(
    hasExplicitStart: Boolean,
    via: List<Stop>,
    destinationName: String?,
    roundTrip: Boolean,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAddStop: () -> Unit,
) {
    val colors = LocalRideColors.current
    val onMapLabel = stringResource(R.string.plan_stop_on_map)

    Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
        Text(
            text = stringResource(R.string.plan_route_section),
            color = colors.faint,
            fontSize = TypeScale.Label,
            fontWeight = FontWeight.Black,
        )

        StopTile(
            name = if (hasExplicitStart) onMapLabel else stringResource(R.string.plan_stop_gps),
            role = StopRole.START,
            caption = stringResource(R.string.plan_stop_start),
        )

        if (via.isNotEmpty()) {
            ReorderableStopColumn(
                count = via.size,
                onMove = onMove,
            ) { index, dragging, dragHandle ->
                StopTile(
                    name = via[index].name ?: onMapLabel,
                    role = StopRole.VIA,
                    number = index + 1,
                    onRemove = { onRemove(index) },
                    // Passing these is what puts the grip on the tile; the
                    // actual movement comes from the drag handle.
                    onMoveUp = { if (index > 0) onMove(index, index - 1) },
                    onMoveDown = { if (index < via.lastIndex) onMove(index, index + 1) },
                    dragging = dragging,
                    modifier = dragHandle,
                )
            }
            Text(
                text = stringResource(R.string.plan_reorder_hint),
                color = colors.faint,
                fontSize = TypeScale.Micro,
            )
        }

        StopTile(
            name = if (roundTrip) {
                stringResource(R.string.plan_stop_roundtrip_destination)
            } else {
                destinationName ?: stringResource(R.string.plan_destination_pin)
            },
            role = StopRole.DESTINATION,
            caption = stringResource(R.string.plan_stop_destination),
        )

        AddStopTile(onClick = onAddStop, label = stringResource(R.string.plan_add_stop))
    }
}

/** The one row that replaces everything that used to be stacked below the stops. */
@Composable
private fun OptionsRow(summary: String, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    Surface(
        onClick = onClick,
        color = colors.panelSunken,
        shape = RoundedCornerShape(Radius.Md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TapTargetSize),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.Md, vertical = Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plan_options_open),
                    color = colors.onPanel,
                    fontWeight = FontWeight.Bold,
                    fontSize = TypeScale.BodySmall,
                )
                Text(
                    text = summary,
                    color = colors.muted,
                    fontSize = TypeScale.Micro,
                    maxLines = 1,
                )
            }
            Text(
                text = "›",
                color = colors.faint,
                fontSize = TypeScale.Subtitle,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Shown once "Rundtour" is on but there is nothing in the stop list yet to loop through. */
@Composable
private fun RoundTripSuggestCard(onSuggest: (Float) -> Unit) {
    val colors = LocalRideColors.current
    var lengthKm by remember { mutableStateOf(120f) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
        Text(
            text = stringResource(R.string.plan_round_trip_length, lengthKm.roundToInt()),
            color = colors.onPanel,
            fontWeight = FontWeight.Bold,
            fontSize = TypeScale.Body,
        )
        Slider(
            value = lengthKm,
            onValueChange = { lengthKm = it },
            valueRange = 50f..300f,
            modifier = Modifier.height(48.dp),
        )
        SecondaryButton(
            label = stringResource(R.string.plan_round_trip_suggest),
            onClick = { onSuggest(lengthKm) },
        )
    }
}

/** "Letzte Touren": shown while the sheet has no active plan, up to five, newest first. */
@Composable
private fun RecentTripsSection(trips: List<HistoryTrip>, onPick: (HistoryTrip) -> Unit) {
    val colors = LocalRideColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
        Text(
            text = stringResource(R.string.plan_recent_trips_title),
            color = colors.faint,
            fontSize = TypeScale.Label,
            fontWeight = FontWeight.Black,
        )
        trips.take(5).forEach { trip ->
            RecentTripRow(trip = trip, onClick = { onPick(trip) })
        }
    }
}

@Composable
private fun RecentTripRow(trip: HistoryTrip, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    val onMap = stringResource(R.string.plan_stop_on_map)
    val startName = trip.stops.first().name ?: onMap
    val destinationName = if (trip.roundTrip) {
        stringResource(R.string.plan_stop_roundtrip_destination)
    } else {
        trip.stops.last().name ?: onMap
    }
    val stopCount = (trip.stops.size - 2).coerceAtLeast(0)
    val date = remember(trip.timestampMillis) {
        SimpleDateFormat("dd.MM.", Locale.getDefault()).format(Date(trip.timestampMillis))
    }
    Surface(
        onClick = onClick,
        color = colors.panelSunken,
        shape = RoundedCornerShape(Radius.Md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TapTargetSize),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Text(
                text = stringResource(R.string.plan_recent_trip_row, startName, destinationName, stopCount, date),
                color = colors.onPanel,
                fontSize = TypeScale.BodySmall,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.Md, vertical = Space.Md),
            )
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
    roundTrip: Boolean,
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
                        fontSize = TypeScale.TitleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = stringResource(
                            R.string.plan_peek_summary,
                            String.format(Locale.getDefault(), "%.0f", route.distanceMeters / 1000.0),
                            formatArrival(route.estimatedSeconds),
                        ) + " · " + motorwayLabel(route),
                        color = colors.muted,
                        fontSize = TypeScale.Label,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Space.Md))
                PlayButton(onClick = { onStart(route) })
            }

            PlanningState.Calculating -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = destinationName ?: stringResource(R.string.plan_calculating),
                        color = colors.onPanel,
                        fontSize = TypeScale.Body,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(Space.Sm))
                    LinearProgressIndicator(
                        color = colors.route,
                        trackColor = colors.panelSunken,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(Radius.Full)),
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = destinationName
                            ?: stringResource(
                                when {
                                    roundTrip -> R.string.plan_mode_round_trip
                                    hasDestination -> R.string.plan_destination_pin
                                    else -> R.string.plan_no_destination
                                },
                            ),
                        color = colors.onPanel,
                        fontSize = TypeScale.Subtitle,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
                if (hasDestination || roundTrip) {
                    Spacer(Modifier.width(Space.Md))
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
 * "0 km Autobahn" / "12 km Autobahn".
 *
 * On the summary line because it is the one thing a motorcyclist wants to know
 * about a calculated route that the distance and the time do not tell them -
 * and because a number there is what makes the avoidance visible enough to
 * trust (see `motorcycle_curvy.brf`).
 */
@Composable
private fun motorwayLabel(route: Route): String {
    val km = motorwayKilometres(route.motorwayMeters)
    return if (km <= 0) {
        stringResource(R.string.plan_motorway_none)
    } else {
        stringResource(R.string.plan_motorway_share, km)
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
        shape = RoundedCornerShape(Radius.Md),
        color = colors.route,
        modifier = modifier.heightIn(min = TapTargetSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = TypeScale.BodySmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = Space.Xl, vertical = Space.Md),
            )
        }
    }
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

private val SEGMENT_HEIGHT = 46.dp

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

    Surface(
        color = colors.panel,
        shape = RoundedCornerShape(Radius.Lg),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.panelRim),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.Lg),
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            Text(
                text = stringResource(
                    if (!hasMaps) R.string.empty_no_map else R.string.empty_no_segments,
                ),
                color = colors.onPanel,
                fontSize = TypeScale.Subtitle,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = stringResource(R.string.empty_body),
                color = colors.muted,
                fontSize = TypeScale.BodySmall,
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
