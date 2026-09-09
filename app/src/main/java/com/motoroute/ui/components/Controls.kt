package com.motoroute.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.motoroute.R
import com.motoroute.data.model.Curviness
import com.motoroute.data.model.CurvinessRating
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.TapTargetSize
import java.util.Locale

/**
 * The furniture of the screens used with the engine off.
 *
 * These are deliberately smaller and quieter than the riding controls: a
 * settings list operated in a car park does not need 84 dp buttons, and the
 * back button belongs at the top left where every Android app puts it, not
 * floating over the delete buttons in the bottom right corner.
 */

/** A screen header: back arrow, title, optional subtitle and trailing action. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalRideColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconTapButton(
                iconRes = R.drawable.ic_action_back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            subtitle?.let {
                Text(text = it, color = colors.muted, fontSize = 13.sp, maxLines = 2)
            }
        }
        trailing?.invoke()
    }
}

/** A compact square icon button for the non-riding screens. */
@Composable
fun IconTapButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalRideColors.current.onPanel,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = background,
        modifier = modifier
            .defaultMinSize(minWidth = TapTargetSize, minHeight = TapTargetSize)
            .size(TapTargetSize)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** The one obvious action on a screen. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 60.dp,
    container: Color = LocalRideColors.current.route,
    content: Color = Color.Black,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.Black)
    }
}

/** A quieter action that sits under the primary one. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val colors = LocalRideColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.panel,
            contentColor = colors.onPanel,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** A card with the screen's panel colour. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRideColors.current
    Surface(
        color = colors.panel,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Deleting a downloaded region is slow to undo, so it gets a confirmation. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRideColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = { Text(text, fontSize = 15.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = colors.danger, fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Bytes, rounded the way a person would say them. */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** The rider-facing name of a curviness score, in their language. */
@Composable
fun curvinessRatingLabel(score: Double): String = stringResource(
    when (Curviness.rating(score)) {
        CurvinessRating.STRAIGHT -> R.string.curviness_rating_straight
        CurvinessRating.FLOWING -> R.string.curviness_rating_flowing
        CurvinessRating.CURVY -> R.string.curviness_rating_curvy
        CurvinessRating.TWISTY -> R.string.curviness_rating_twisty
        CurvinessRating.EXTREME -> R.string.curviness_rating_extreme
    },
)
