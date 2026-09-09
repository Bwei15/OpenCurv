package com.motoroute.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.data.map.OfflineFile
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.ui.navigation.GloveButton
import com.motoroute.ui.theme.GloveTargetSize
import com.motoroute.ui.theme.LocalRideColors
import java.util.Locale

/**
 * Offline data management: import, review, delete.
 *
 * This screen is the whole reason the app can promise no network at runtime.
 * Everything the router and the renderer need is a file the rider put here.
 */
@Composable
fun OfflineDataScreen(
    maps: List<OfflineFile>,
    segments: List<OfflineFile>,
    profiles: List<OfflineFile>,
    freeSpaceBytes: Long,
    onImport: () -> Unit,
    onDelete: (OfflineFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Offline data",
            color = colors.hudForeground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "${formatSize(freeSpaceBytes)} free on this device",
            color = colors.muted,
            fontSize = 14.sp,
        )

        Button(
            onClick = onImport,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.route,
                contentColor = Color.Black,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(GloveTargetSize),
        ) {
            Text("Import .map / .rd5 / .brf", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section("Maps (.map)", maps, onDelete)
            section("Routing tiles (.rd5)", segments, onDelete)
            section("Profiles (.brf)", profiles, onDelete)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    files: List<OfflineFile>,
    onDelete: (OfflineFile) -> Unit,
) {
    item {
        Column {
            HorizontalDivider()
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (files.isEmpty()) {
                Text("nothing imported yet", fontSize = 14.sp)
            }
        }
    }
    items(files, key = { it.file.absolutePath }) { file ->
        FileRow(file, onDelete)
    }
}

@Composable
private fun FileRow(file: OfflineFile, onDelete: (OfflineFile) -> Unit) {
    val colors = LocalRideColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "${formatSize(file.sizeBytes)}  ·  ${file.kind.name.lowercase()}",
                color = colors.muted,
                fontSize = 13.sp,
            )
        }
        GloveButton(
            iconRes = R.drawable.ic_action_delete,
            contentDescription = "Delete ${file.name}",
            onClick = { onDelete(file) },
            background = colors.hudBackground,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
