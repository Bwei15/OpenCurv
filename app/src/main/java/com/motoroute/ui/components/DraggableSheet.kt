package com.motoroute.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A bottom sheet the rider can push out of the way.
 *
 * The route panel used to be a fixed slab across the bottom of the map, so a
 * destination behind it could not be looked at, let alone tapped. This behaves
 * like the sheet on every phone map: drag it down to a peek, drag it back up
 * for the details, and it settles to whichever end it was nearer when let go.
 *
 * Written by hand rather than with a scaffold because the sheet has to float
 * over a map that keeps its own gestures - anything that consumed the whole
 * screen's drag events would break panning.
 */
@Composable
fun DraggableSheet(
    peekHeight: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.Black,
    handleColor: Color = Color.White,
    maxHeight: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var contentHeightPx by remember { mutableFloatStateOf(0f) }
    val peekPx = with(density) { peekHeight.toPx() }
    val maxOffset = (contentHeightPx - peekPx).coerceAtLeast(0f)

    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(maxOffset) {
        offset.updateBounds(0f, maxOffset)
        if (offset.value > maxOffset) offset.snapTo(maxOffset)
    }

    Surface(
        color = background,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .onSizeChanged { contentHeightPx = it.height.toFloat() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset))
                    }
                },
                onDragStopped = { velocity ->
                    val collapse = when {
                        velocity > FLING_VELOCITY -> true
                        velocity < -FLING_VELOCITY -> false
                        else -> offset.value > maxOffset / 2f
                    }
                    offset.animateTo(
                        targetValue = if (collapse) maxOffset else 0f,
                        animationSpec = tween(ANIMATION_MILLIS),
                    )
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(handleColor.copy(alpha = 0.5f)),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

private const val FLING_VELOCITY = 400f
private const val ANIMATION_MILLIS = 220
