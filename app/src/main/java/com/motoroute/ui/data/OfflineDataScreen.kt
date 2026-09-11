package com.motoroute.ui.data

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.download.RegionStatus
import com.motoroute.data.map.OfflineFile
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.ui.components.ConfirmDialog
import com.motoroute.ui.components.IconTapButton
import com.motoroute.ui.components.PanelCard
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.components.ScreenHeader
import com.motoroute.ui.components.SecondaryButton
import com.motoroute.ui.components.formatSize
import com.motoroute.ui.theme.LocalRideColors

/**
 * Offline data, in the rider's units.
 *
 * The screen used to list every file: one map, five routing tiles, three
 * profiles, each with its own delete button. Nobody downloaded "E5_N50.rd5" -
 * they downloaded Niedersachsen. So a region is now one row that installs and
 * deletes as one thing, and the file list only exists for what does not belong
 * to a region: hand-imported maps and routing profiles.
 */
@Composable
fun OfflineDataScreen(
    regions: List<RegionStatus>,
    looseFiles: List<OfflineFile>,
    profiles: List<OfflineFile>,
    freeSpaceBytes: Long,
    onImport: () -> Unit,
    onDownload: () -> Unit,
    onDeleteRegion: (RegionStatus) -> Unit,
    onDeleteFile: (OfflineFile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    var pendingRegion by remember { mutableStateOf<RegionStatus?>(null) }
    var pendingFile by remember { mutableStateOf<OfflineFile?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.data_title),
            subtitle = stringResource(R.string.data_free_space, formatSize(freeSpaceBytes)),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        label = stringResource(R.string.data_download_maps),
                        onClick = onDownload,
                    )
                    SecondaryButton(
                        label = stringResource(R.string.data_import),
                        onClick = onImport,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            item(key = "regions-header") {
                SectionTitle(stringResource(R.string.data_regions))
            }

            if (regions.isEmpty()) {
                item(key = "regions-empty") {
                    Text(
                        text = stringResource(R.string.data_regions_empty),
                        color = colors.muted,
                        fontSize = 14.sp,
                    )
                }
            }

            items(regions, key = { it.path }) { region ->
                RegionCard(region = region, onDelete = { pendingRegion = region })
            }

            if (looseFiles.isNotEmpty()) {
                item(key = "loose-header") {
                    SectionTitle(stringResource(R.string.data_other_files))
                }
                items(looseFiles, key = { it.file.absolutePath }) { file ->
                    FileRow(file) { pendingFile = file }
                }
            }

            item(key = "profiles-header") {
                SectionTitle(stringResource(R.string.data_profiles))
            }
            items(profiles, key = { it.file.absolutePath }) { file ->
                FileRow(file) { pendingFile = file }
            }

            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingRegion?.let { region ->
        ConfirmDialog(
            title = stringResource(R.string.data_delete_region_title, region.name),
            text = stringResource(
                R.string.data_delete_region_text,
                region.filesPresent,
                formatSize(region.sizeBytes),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDeleteRegion(region)
                pendingRegion = null
            },
            onDismiss = { pendingRegion = null },
        )
    }

    pendingFile?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.data_delete_file_title),
            text = file.name,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDeleteFile(file)
                pendingFile = null
            },
            onDismiss = { pendingFile = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalRideColors.current
    Text(
        text = text,
        color = colors.muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/**
 * One region: what it is, how much of it is here, and one button to remove it.
 *
 * An incomplete region is shown as incomplete rather than hidden - a download
 * that stopped halfway is exactly the case where the rider needs to know why
 * routing is refusing to work.
 */
@Composable
private fun RegionCard(region: RegionStatus, onDelete: () -> Unit) {
    val colors = LocalRideColors.current
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = region.name,
                    color = colors.onPanel,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text(
                    text = if (region.isComplete) {
                        stringResource(
                            R.string.data_region_complete,
                            region.filesTotal,
                            formatSize(region.sizeBytes),
                        )
                    } else {
                        stringResource(
                            R.string.data_region_partial,
                            region.filesPresent,
                            region.filesTotal,
                            formatSize(region.sizeBytes),
                        )
                    },
                    color = if (region.isComplete) colors.muted else colors.warning,
                    fontSize = 13.sp,
                )
            }
            IconTapButton(
                iconRes = R.drawable.ic_action_delete,
                contentDescription = stringResource(R.string.action_delete),
                onClick = onDelete,
                tint = colors.danger,
            )
        }
        if (!region.isComplete) {
            LinearProgressIndicator(
                progress = { region.filesPresent.toFloat() / region.filesTotal },
                color = colors.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
    }
}

@Composable
private fun FileRow(file: OfflineFile, onDelete: () -> Unit) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = colors.onPanel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "${formatSize(file.sizeBytes)}  ·  ${kindLabel(file.kind)}",
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        IconTapButton(
            iconRes = R.drawable.ic_action_delete,
            contentDescription = stringResource(R.string.action_delete),
            onClick = onDelete,
            tint = colors.danger,
        )
    }
}

@Composable
private fun kindLabel(kind: OfflineFileKind): String = stringResource(
    when (kind) {
        OfflineFileKind.MAP -> R.string.kind_map
        OfflineFileKind.SEGMENT -> R.string.kind_segment
        OfflineFileKind.PROFILE -> R.string.kind_profile
        OfflineFileKind.MAPTILES -> R.string.kind_maptiles
        OfflineFileKind.CAMERAS -> R.string.kind_cameras
    },
)
