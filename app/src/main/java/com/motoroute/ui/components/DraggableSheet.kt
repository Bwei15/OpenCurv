package com.motoroute.ui.components

import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far the sheet is pushed down, and who is allowed to push it.
 *
 * Hoisted out of the sheet so the map can push it too: a rider who drags the
 * map is looking at the map, and the panel should get out of the way rather
 * than wait to be dismissed by its own little handle.
 *
 * The offset is a plain float state rather than an `Animatable` because the
 * nested-scroll connection has to say *synchronously* how much of a gesture it
 * consumed, and a suspending animation cannot answer that in time.
 */
class SheetState internal constructor() {

    /** Pixels the sheet is pushed down; 0 is fully open. */
    internal var offset by mutableFloatStateOf(0f)
        private set

    internal var maxOffset by mutableFloatStateOf(0f)
        private set

    private var heightPx by mutableFloatStateOf(0f)

    private var animation: Job? = null

    /** True while the sheet is showing more than its peek. */
    val isExpanded: Boolean get() = maxOffset <= 0f || offset < maxOffset / 2f

    /**
     * How much of the screen the sheet is covering right now. The map reads
     * this so "centre on me" aims at the middle of what is still visible.
     */
    val visibleHeightPx: Float get() = (heightPx - offset).coerceAtLeast(0f)

    internal fun measured(height: Float, peek: Float) {
        heightPx = height
        maxOffset = (height - peek).coerceAtLeast(0f)
        if (offset > maxOffset) offset = maxOffset
    }

    /**
     * Moves the sheet by [delta] pixels and reports how much of that it used.
     * Anything left over belongs to whoever asked - a scrolling child, or the
     * gesture that started on the map.
     */
    internal fun dragBy(delta: Float): Float {
        val target = (offset + delta).coerceIn(0f, maxOffset)
        val used = target - offset
        offset = target
        return used
    }

    /**
     * Stops a settle animation that is still running, so a finger put back on
     * the sheet takes over instead of fighting it.
     */
    internal fun cancelAnimation() {
        animation?.cancel()
        animation = null
    }

    /**
     * Animates to [target]. Cancelling this cancels the calling coroutine, so
     * call it from a coroutine launched for nothing else.
     */
    internal suspend fun animateTo(target: Float) {
        cancelAnimation()
        val job = currentCoroutineContext()[Job]
        animation = job
        try {
            animate(
                initialValue = offset,
                targetValue = target,
                animationSpec = tween(ANIMATION_MILLIS),
            ) { value, _ -> offset = value }
        } finally {
            if (animation === job) animation = null
        }
    }

    /** Settles to whichever end the sheet is nearer, or the way a fling points. */
    internal suspend fun settle(velocity: Float) {
        val collapse = when {
            velocity > FLING_VELOCITY -> true
            velocity < -FLING_VELOCITY -> false
            else -> offset > maxOffset / 2f
        }
        animateTo(if (collapse) maxOffset else 0f)
    }

    suspend fun collapse() = animateTo(maxOffset)

    suspend fun expand() = animateTo(0f)
}

@Composable
fun rememberSheetState(): SheetState = remember { SheetState() }

/**
 * A bottom sheet the rider can push out of the way.
 *
 * The route panel used to be a fixed slab across the bottom of the map, so a
 * destination behind it could not be looked at, let alone tapped. This behaves
 * like the sheet on every phone map: drag it down to a peek, drag it back up
 * for the details, and it settles to whichever end it was nearer when let go.
 *
 * The drag works anywhere on the sheet, not only on the handle. That needs the
 * nested-scroll connection below: the scrolling column inside would otherwise
 * swallow every vertical gesture before the sheet ever saw it, which is exactly
 * why the panel used to be grabbable by its top edge alone.
 *
 * Written by hand rather than with a scaffold because the sheet has to float
 * over a map that keeps its own gestures - anything that consumed the whole
 * screen's drag events would break panning.
 */
@Composable
fun DraggableSheet(
    peekHeight: Dp,
    modifier: Modifier = Modifier,
    state: SheetState = rememberSheetState(),
    background: Color = Color.Black,
    handleColor: Color = Color.White,
    maxHeight: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val peekPx = with(density) { peekHeight.toPx() }
    val scope = rememberCoroutineScope()

    // Up first expands the sheet, then scrolls the content; down first scrolls
    // the content back to its top, then collapses the sheet. That ordering is
    // what makes one continuous finger movement feel like a single gesture.
    val nested = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                state.cancelAnimation()
                return Offset(0f, state.dragBy(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                state.cancelAnimation()
                return Offset(0f, state.dragBy(available.y))
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                state.settle(-available.y)
                return Velocity.Zero
            }
        }
    }

    Surface(
        color = background,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, state.offset.roundToInt()) }
            .onSizeChanged { state.measured(it.height.toFloat(), peekPx) }
            .nestedScroll(nested)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> state.dragBy(delta) },
                onDragStarted = { state.cancelAnimation() },
                onDragStopped = { velocity -> scope.launch { state.settle(velocity) } },
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
