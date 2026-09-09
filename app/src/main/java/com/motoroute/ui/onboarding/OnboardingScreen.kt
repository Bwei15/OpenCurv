package com.motoroute.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.R
import com.motoroute.ui.components.PrimaryButton
import com.motoroute.ui.theme.LocalRideColors

/**
 * Three screens between installing the app and having a map.
 *
 * It replaces a full-screen notice that simply sat on top of the map until data
 * appeared - which told a new rider what was missing but not what to do about
 * it, and could not be dismissed to look at the app first. This asks for two
 * taps and then puts them straight in the region list.
 */
@Composable
fun OnboardingScreen(
    onOpenDownloads: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRideColors.current
    var page by remember { mutableIntStateOf(0) }
    val pages = 3

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.hudBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = colors.muted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    0 -> Page(
                        title = stringResource(R.string.onboarding_welcome_title),
                        body = stringResource(R.string.onboarding_welcome_body),
                    )
                    1 -> Page(
                        title = stringResource(R.string.onboarding_data_title),
                        body = stringResource(R.string.onboarding_data_body),
                    )
                    else -> Page(
                        title = stringResource(R.string.onboarding_region_title),
                        body = stringResource(R.string.onboarding_region_body),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    repeat(pages) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == page) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (index == page) colors.route else colors.muted),
                        )
                    }
                }

                if (page < pages - 1) {
                    PrimaryButton(
                        label = stringResource(R.string.onboarding_next),
                        onClick = { page++ },
                    )
                } else {
                    PrimaryButton(
                        label = stringResource(R.string.onboarding_choose_region),
                        onClick = onOpenDownloads,
                    )
                    TextButton(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_import_instead),
                            color = colors.hudForeground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Page(title: String, body: String) {
    val colors = LocalRideColors.current
    Text(
        text = title,
        color = colors.hudForeground,
        fontSize = 32.sp,
        fontWeight = FontWeight.Black,
        lineHeight = 38.sp,
    )
    Text(
        text = body,
        color = colors.muted,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )
}
