package com.motoroute.ui.diagnostics

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.diagnostics.CrashLog
import com.motoroute.diagnostics.LogEntry
import com.motoroute.ui.components.PanelCard
import com.motoroute.ui.components.ScreenHeader
import com.motoroute.ui.components.SecondaryButton
import com.motoroute.ui.theme.LocalRideColors

/**
 * What went wrong, in the app that it went wrong in.
 *
 * "It just closed" is all a rider can report about a crash on a handlebar, and
 * it is not enough to fix anything. This shows the stack the app recorded on
 * its way down, and offers it as text so it can be sent to someone who reads
 * stacks for a living. Nothing leaves the phone unless the rider sends it.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    var reload by remember { mutableStateOf(0) }
    val entries = remember(reload) { CrashLog.entries() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(title = stringResource(R.string.diagnostics_title), onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                label = stringResource(R.string.diagnostics_share),
                onClick = { onShare(CrashLog.asText()) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = stringResource(R.string.diagnostics_clear),
                onClick = {
                    CrashLog.clear()
                    reload++
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_empty),
                color = colors.muted,
                fontSize = 15.sp,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(entries) { entry -> EntryCard(entry) }
        }
    }
}

@Composable
private fun EntryCard(entry: LogEntry) {
    val colors = LocalRideColors.current
    var expanded by remember { mutableStateOf(false) }

    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.time,
                color = colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (entry.fatal) {
                    stringResource(R.string.diagnostics_crash)
                } else {
                    entry.tag
                },
                color = if (entry.fatal) colors.danger else colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            text = entry.message,
            color = colors.onPanel,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (entry.stack.isNotBlank()) {
            SecondaryButton(
                label = stringResource(
                    if (expanded) R.string.diagnostics_hide_stack else R.string.diagnostics_show_stack,
                ),
                onClick = { expanded = !expanded },
                height = 44.dp,
            )
            if (expanded) {
                // A stack trace has no wrap point that keeps it readable, so it
                // scrolls sideways rather than turning into a grey block.
                Text(
                    text = entry.stack,
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
