package com.motoroute.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motoroute.ui.theme.LocalRideColors
import com.motoroute.ui.theme.Radius
import com.motoroute.ui.theme.Space
import com.motoroute.ui.theme.TypeScale

/** What a stop tile is: the start, a stop along the way, or the destination. */
enum class StopRole { START, VIA, DESTINATION }

/**
 * One stop on a route, as a rounded tile.
 *
 * The route used to be a stack of bare two-line rows with three glyph buttons
 * (up, down, remove) tacked on the right, which is a list of controls rather
 * than a list of places. This is the shape the ride report asked for, and the
 * shape Apple Maps uses: a rounded plate per stop, a grip on the left to drag
 * it, a numbered pip so the order is readable at a glance, and one X on the
 * right to take it out.
 *
 * Reordering by dragging is [ReorderableStopColumn]'s job; a tile itself is
 * only the plate. [onMoveUp] and [onMoveDown] stay available because a drag is
 * not the only way to reorder a list - they are what a screen reader and a
 * rider with gloves on both need.
 */
@Composable
fun StopTile(
    name: String,
    role: StopRole,
    modifier: Modifier = Modifier,
    /** Shown in the pip for a [StopRole.VIA]; the endpoints get a letter instead. */
    number: Int = 0,
    caption: String? = null,
    onRemove: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    dragging: Boolean = false,
    onDark: Boolean = false,
) {
    val colors = LocalRideColors.current
    val surface = when {
        onDark -> colors.hudForeground.copy(alpha = if (dragging) 0.20f else 0.10f)
        role == StopRole.VIA -> colors.panelSunken
        else -> colors.panel
    }
    val ink = if (onDark) colors.hudForeground else colors.onPanel
    val subInk = if (onDark) colors.hudMuted else colors.muted

    Surface(
        color = surface,
        shape = RoundedCornerShape(Radius.Md),
        // The endpoints are structural rather than editable, so they are drawn
        // as an outline instead of a filled tile - the fill says "you can move
        // this".
        border = if (!onDark && role != StopRole.VIA) {
            androidx.compose.foundation.BorderStroke(1.dp, colors.panelRim.copy(alpha = 0.25f))
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = TILE_MIN_HEIGHT)
                .padding(horizontal = Space.Md, vertical = Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            if (onMoveUp != null || onMoveDown != null) {
                DragGrip(tint = subInk)
            }
            StopPip(role = role, number = number)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = ink,
                    fontSize = TypeScale.BodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(text = caption, color = subInk, fontSize = TypeScale.Micro, maxLines = 1)
                }
            }
            if (onRemove != null) {
                RoundGlyphButton(
                    glyph = "✕",
                    onClick = onRemove,
                    tint = subInk,
                    background = if (onDark) colors.hudForeground.copy(alpha = 0.12f) else colors.panelSunken,
                )
            }
        }
    }
}

/** The "+" tile that adds a stop. An outline, because it is not a place yet. */
@Composable
fun AddStopTile(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val colors = LocalRideColors.current
    // The HUD's own lighter indigo on dark, where the resting primary would not carry.
    val tint = if (onDark) colors.hudPrimary else colors.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TILE_MIN_HEIGHT)
            .clip(RoundedCornerShape(Radius.Md))
            .border(
                width = 1.5.dp,
                color = tint.copy(alpha = 0.45f),
                shape = RoundedCornerShape(Radius.Md),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.Md, vertical = Space.Sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            Box(
                modifier = Modifier
                    .size(PIP_SIZE)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = colors.onPrimary, fontWeight = FontWeight.Black, fontSize = TypeScale.Body)
            }
            Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = TypeScale.BodySmall)
        }
    }
}

/** A / 1 / 2 / Z: reading the order without reading the names. */
@Composable
private fun StopPip(role: StopRole, number: Int) {
    val colors = LocalRideColors.current
    val (background, label) = when (role) {
        StopRole.START -> colors.ok to "A"
        StopRole.VIA -> colors.primary to number.toString()
        StopRole.DESTINATION -> colors.accent to "Z"
    }
    Box(
        modifier = Modifier
            .size(PIP_SIZE)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = TypeScale.Label,
            fontWeight = FontWeight.Black,
        )
    }
}

/** The six-dot grip. Two columns of three, drawn rather than an asset. */
@Composable
private fun DragGrip(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.size(width = 16.dp, height = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundGlyphButton(
    glyph: String,
    onClick: () -> Unit,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = background,
        modifier = modifier
            .size(REMOVE_BUTTON_SIZE)
            .semantics { contentDescription = glyph },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, color = tint, fontWeight = FontWeight.Black, fontSize = TypeScale.Label)
        }
    }
}

/** 56 dp: the resting-register touch target, and enough for two lines of text. */
private val TILE_MIN_HEIGHT: Dp = 56.dp
private val PIP_SIZE: Dp = 28.dp

/**
 * The X is smaller than the 56 dp resting target on purpose: it destroys work,
 * and it sits inside a tile whose own body is the safe thing to hit. 40 dp is
 * still above WCAG 2.5.5's 24 dp minimum for a control inside a larger target.
 */
private val REMOVE_BUTTON_SIZE: Dp = 40.dp
