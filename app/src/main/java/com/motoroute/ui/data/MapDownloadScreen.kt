package com.motoroute.ui.data

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.download.DownloadQueueState
import com.motoroute.data.download.MapRegion
import com.motoroute.data.download.RegionDownloadProgress
import com.motoroute.data.download.byRegion
import com.motoroute.ui.components.ScreenHeader
import com.motoroute.ui.components.formatSize
import com.motoroute.ui.theme.LocalRideColors

/**
 * Pick a region, get everything needed to ride it.
 *
 * The screen deliberately hides the distinction the rider should not have to
 * care about: choosing "Niedersachsen" queues the map *and* every BRouter tile
 * that covers it, and reports progress as one package rather than as six
 * separate files. Working out which `.rd5` files a region needs is the single
 * most confusing part of setting up an offline navigator by hand, and it is
 * pure arithmetic - so the app does it.
 */
@Composable
fun MapDownloadScreen(
    regions: Map<String, List<MapRegion>>,
    queue: DownloadQueueState,
    installedRegions: Set<String>,
    freeSpaceBytes: Long,
    blockedReason: String?,
    onDownload: (MapRegion) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    var filter by remember { mutableStateOf("") }

    val visible = remember(regions, filter) {
        if (filter.isBlank()) {
            regions
        } else {
            regions.mapValues { (_, list) ->
                list.filter { it.name.contains(filter, ignoreCase = true) }
            }.filterValues { it.isNotEmpty() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.download_title),
            subtitle = stringResource(R.string.download_subtitle_free, formatSize(freeSpaceBytes)),
            onBack = onBack,
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.download_filter)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )

            blockedReason?.let {
                Surface(color = colors.warning, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = it,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            queue.byRegion().forEach { progress ->
                QueueCard(
                    progress = progress,
                    isRunning = queue.isRunning,
                    onCancel = onCancel,
                    onRetry = onRetry,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            visible.forEach { (country, list) ->
                item(key = "header-$country") {
                    Text(
                        text = country,
                        color = colors.muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.path }) { region ->
                    RegionRow(
                        region = region,
                        installed = region.path in installedRegions,
                        enabled = blockedReason == null,
                        onDownload = { onDownload(region) },
                    )
                }
            }
            item(key = "footer") {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.download_sources),
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: MapRegion,
    installed: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val colors = LocalRideColors.current
    val tiles = region.segmentTiles.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(enabled = enabled && !installed) { onDownload() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = region.name,
                color = colors.onPanel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.download_region_summary,
                    region.approxSizeMb,
                    tiles + 1,
                ),
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        Text(
            text = stringResource(
                if (installed) R.string.download_installed else R.string.action_download,
            ),
            color = if (installed) colors.ok else colors.route,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/** One region's download, as one bar and one file counter. */
@Composable
private fun QueueCard(
    progress: RegionDownloadProgress,
    isRunning: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalRideColors.current

    Surface(
        color = colors.panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progress.regionName,
                    color = colors.onPanel,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(progress.fraction * 100).toInt()} %",
                    color = colors.muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            LinearProgressIndicator(
                progress = { progress.fraction },
                color = if (progress.filesFailed > 0) colors.danger else colors.route,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )

            Text(
                text = when {
                    progress.isFinished && progress.filesFailed == 0 ->
                        stringResource(R.string.download_done)
                    progress.isRunning -> stringResource(
                        R.string.download_file_of,
                        progress.filesDone + 1,
                        progress.filesTotal,
                        formatSize(progress.bytesDone),
                    )
                    else -> stringResource(
                        R.string.download_waiting,
                        progress.filesDone,
                        progress.filesTotal,
                    )
                },
                color = colors.muted,
                fontSize = 13.sp,
            )

            progress.error?.let {
                Text(
                    text = it,
                    color = colors.danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning && !progress.isFinished) {
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.danger,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Black)
                    }
                }
                if (progress.filesFailed > 0) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.route,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}
