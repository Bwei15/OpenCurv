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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.data.download.DownloadQueueState
import com.motoroute.data.download.MapRegion
import com.motoroute.ui.theme.GloveTargetSize
import com.motoroute.ui.theme.LocalRideColors
import java.util.Locale

/**
 * Pick a region, get everything needed to ride it.
 *
 * The screen deliberately hides the distinction the rider should not have to
 * care about: choosing "Bayern" queues the map *and* the four BRouter tiles
 * that cover it. Working out which `.rd5` files a region needs is the single
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Download maps",
            color = colors.hudForeground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "Fetched once over Wi-Fi. Riding never uses the network.",
            color = colors.muted,
            fontSize = 14.sp,
        )
        Text(
            text = "${formatSize(freeSpaceBytes)} free",
            color = colors.muted,
            fontSize = 13.sp,
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

        if (queue.items.isNotEmpty()) {
            QueueCard(queue = queue, onCancel = onCancel, onRetry = onRetry)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            regions.forEach { (country, list) ->
                item(key = "header-$country") {
                    Column {
                        HorizontalDivider()
                        Text(
                            text = country,
                            color = colors.muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
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
            item(key = "footer") { Spacer(Modifier.height(96.dp)) }
        }

        Button(
            onClick = onBack,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.hudBackground,
                contentColor = colors.hudForeground,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(GloveTargetSize),
        ) {
            Text("Back", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
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
            .height(GloveTargetSize)
            .clickable(enabled = enabled && !installed) { onDownload() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = region.name,
                color = colors.hudForeground,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    if (region.approxSizeMb > 0) append("~${region.approxSizeMb} MB map")
                    append("  ·  $tiles routing ")
                    append(if (tiles == 1) "tile" else "tiles")
                },
                color = colors.muted,
                fontSize = 13.sp,
            )
        }
        Text(
            text = if (installed) "installed" else "download",
            color = if (installed) colors.ok else colors.route,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun QueueCard(
    queue: DownloadQueueState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalRideColors.current
    val active = queue.active

    Surface(
        color = colors.hudBackground.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = active?.target?.label
                    ?: if (queue.allDone) "All downloads finished" else "Waiting…",
                color = colors.hudForeground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )

            if (active != null) {
                val fraction = active.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = colors.route,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = colors.route,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                    )
                }
                Text(
                    text = "${formatSize(active.bytesDone)} of " +
                        if (active.bytesTotal > 0) formatSize(active.bytesTotal) else "?",
                    color = colors.muted,
                    fontSize = 13.sp,
                )
            }

            if (queue.remaining > 0) {
                Text(
                    text = "${queue.remaining} more queued",
                    color = colors.muted,
                    fontSize = 13.sp,
                )
            }

            queue.failed.forEach { failure ->
                Text(
                    text = "${failure.target.fileName}: ${failure.error ?: "failed"}",
                    color = colors.danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (queue.isRunning) {
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.danger,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Black)
                    }
                }
                if (queue.failed.isNotEmpty()) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.route,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("Retry", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
