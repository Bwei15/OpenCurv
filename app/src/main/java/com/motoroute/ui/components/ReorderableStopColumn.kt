package com.motoroute.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.motoroute.ui.theme.Space
import kotlin.math.roundToInt

/**
 * A short list whose items the rider can drag into a different order.
 *
 * The stop list used to be reordered with a pair of up/down glyph buttons per
 * row, which is four taps to move a stop two places. The ride report asked for
 * what Apple Maps does: pick the tile up with a finger and put it where you
 * want it.
 *
 * ## Why this is hand-rolled, and what that costs
 *
 * There is no reorderable-list primitive in Compose Foundation, and the app
 * carries no third-party UI dependency (see `1.Doku/AI_Workspace_Overview.md` -
 * the dependency list is deliberately short and F-Droid-friendly). A stop list
 * is at most a handful of items, always visible at once, so the simple version
 * is enough: items sit at a fixed [itemHeight] pitch, the dragged one follows
 * the finger, and [onMove] fires each time the finger has travelled a whole
 * slot. The cost of the fixed pitch is that every item must be the same height,
 * which [StopTile] is.
 *
 * The drag starts on a long press rather than immediately, and that is not a
 * detail: this list lives inside a [DraggableSheet], whose own vertical drag
 * would otherwise swallow the gesture. A long press claims the pointer for this
 * list, so the sheet stays where it is while a stop is being moved.
 */
@Composable
fun ReorderableStopColumn(
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = DEFAULT_ITEM_HEIGHT,
    spacing: Dp = Space.Sm,
    /**
     * One item. [dragging] is true for the item currently under the finger, so
     * it can be drawn lifted; [dragHandle] must be attached to whatever part of
     * the item starts a drag - the whole tile, normally.
     */
    item: @Composable (index: Int, dragging: Boolean, dragHandle: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val pitchPx = with(density) { (itemHeight + spacing).toPx() }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (index in 0 until count) {
            val isDragging = index == draggingIndex
            val handle = Modifier.pointerInput(index, count, pitchPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggingIndex = index
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        draggingIndex = -1
                        dragOffsetPx = 0f
                    },
                    onDragCancel = {
                        draggingIndex = -1
                        dragOffsetPx = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetPx += dragAmount.y
                        val from = draggingIndex
                        if (from < 0) return@detectDragGesturesAfterLongPress
                        // One whole slot travelled: commit the swap and keep
                        // the remainder, so a slow drag across three tiles
                        // fires three moves rather than one big jump.
                        val slots = (dragOffsetPx / pitchPx).roundToInt()
                        if (slots != 0) {
                            val to = (from + slots).coerceIn(0, count - 1)
                            if (to != from) {
                                onMove(from, to)
                                draggingIndex = to
                                dragOffsetPx -= (to - from) * pitchPx
                            }
                        }
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    // The dragged tile has to paint above its neighbours, or it
                    // slides underneath the one it is being moved past.
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffsetPx.roundToInt() else 0) },
            ) {
                item(index, isDragging, handle)
            }
        }
    }
}

/** Matches [StopTile]'s own minimum height, which is the resting touch target. */
private val DEFAULT_ITEM_HEIGHT: Dp = 56.dp
