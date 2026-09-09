package com.motoroute.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.search.IndexState
import com.motoroute.data.search.Place
import com.motoroute.data.search.PlaceKind
import com.motoroute.domain.geo.Geo
import com.motoroute.ui.components.IconTapButton
import com.motoroute.ui.theme.LocalRideColors
import java.util.Locale

/**
 * Destination search, offline.
 *
 * The app used to say destinations could only be set by tapping the map,
 * because an offline geocoder needs an index nobody has. It turned out the
 * rider already has one: the Mapsforge map on the phone carries the place nodes
 * and street names it draws with. So this searches the map itself - towns and
 * villages from an index built once per map, streets scanned live around where
 * the rider is.
 */
@Composable
fun SearchScreen(
    query: String,
    results: List<Place>,
    indexState: IndexState,
    searching: Boolean,
    near: GeoPoint?,
    onQueryChange: (String) -> Unit,
    onPick: (Place) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Every keystroke produces a new best match, and a list still scrolled to
    // where the previous query ended hides it. Back to the top on every result.
    LaunchedEffect(results) {
        if (results.isNotEmpty()) listState.scrollToItem(0)
    }

    // The search key takes the top hit. Pressing it while the search is still
    // running is a promise to be kept, not an accident: the pick is held until
    // there is something to pick.
    var awaitingResult by remember { mutableStateOf(false) }
    LaunchedEffect(results, searching) {
        if (!awaitingResult) return@LaunchedEffect
        val first = results.firstOrNull()
        if (first != null) {
            awaitingResult = false
            onPick(first)
        } else if (!searching) {
            awaitingResult = false
        }
    }
    val submit = {
        val first = results.firstOrNull()
        if (first != null) onPick(first) else awaitingResult = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTapButton(
                iconRes = R.drawable.ic_action_back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .focusRequester(focus),
            )
        }

        when (val state = indexState) {
            is IndexState.Building -> IndexBanner(
                text = stringResource(
                    R.string.search_indexing,
                    (state.fraction * 100).toInt(),
                    state.places,
                ),
                fraction = state.fraction,
            )
            IndexState.NoMaps -> Text(
                text = stringResource(R.string.search_no_maps),
                color = colors.warning,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            else -> Unit
        }

        if (searching) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = colors.route, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.search_searching),
                    color = colors.muted,
                    fontSize = 14.sp,
                )
            }
        }

        if (query.isNotBlank() && results.isEmpty() && !searching) {
            Text(
                text = stringResource(R.string.search_no_results),
                color = colors.muted,
                fontSize = 15.sp,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(results, key = { it.dedupeKey }) { place ->
                ResultRow(
                    place = place,
                    distanceMeters = near?.let { Geo.distanceMeters(it, place.point) },
                    onClick = { onPick(place) },
                )
                HorizontalDivider()
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IndexBanner(text: String, fraction: Float) {
    val colors = LocalRideColors.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = text, color = colors.muted, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { fraction },
            color = colors.route,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

@Composable
private fun ResultRow(place: Place, distanceMeters: Double?, onClick: () -> Unit) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                color = colors.onPanel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = listOfNotNull(kindLabel(place.kind), place.detail).joinToString(" · "),
                color = colors.muted,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        distanceMeters?.let {
            Text(
                text = formatDistance(it),
                color = colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun kindLabel(kind: PlaceKind): String = stringResource(
    when (kind) {
        PlaceKind.CITY -> R.string.place_city
        PlaceKind.TOWN -> R.string.place_town
        PlaceKind.VILLAGE -> R.string.place_village
        PlaceKind.SUBURB -> R.string.place_suburb
        PlaceKind.HAMLET -> R.string.place_hamlet
        PlaceKind.STREET -> R.string.place_street
        PlaceKind.FUEL -> R.string.place_fuel
        PlaceKind.POI -> R.string.place_poi
    },
)

private fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 100_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
    else -> "${(meters / 1000).toInt()} km"
}
