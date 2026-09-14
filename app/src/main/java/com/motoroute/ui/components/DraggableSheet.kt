package com.motoroute.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableStateOf
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
import com.motoroute.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which end a [DraggableSheet] is resting at, or travelling toward. */
enum class SheetTarget { EXPANDED, COLLAPSED }

/**
 * Decides where a released sheet should come to rest.
 *
 * Pure and Android-free, because "is this too stubborn" is exactly the
 * question a unit test can answer without a finger on a device - which is
 * how the old rule ("snap to whichever end is nearer") turned out to be the
 * actual complaint: it made riders drag the sheet most of the way across
 * before anything moved.
 *
 * [startedExpanded] is where the drag began - not the live position, which
 * moves continuously while dragging, but the state the sheet had settled
 * into before this gesture. [endFraction] is the release position, 0 at
 * fully expanded and 1 at the peek. [velocityDpPerSecond] is the release
 * velocity along the drag, positive toward the peek and negative toward
 * fully expanded.
 */
fun snapTarget(
    startedExpanded: Boolean,
    endFraction: Float,
    velocityDpPerSecond: Float,
): SheetTarget = when {
    velocityDpPerSecond > FLING_VELOCITY_DP -> SheetTarget.COLLAPSED
    velocityDpPerSecond < -FLING_VELOCITY_DP -> SheetTarget.EXPANDED
    // No fling: a quarter of the way off the state it started in is enough -
    // see the class doc above for why that beats comparing to the midpoint.
    startedExpanded -> if (endFraction >= SNAP_THRESHOLD) SheetTarget.COLLAPSED else SheetTarget.EXPANDED
    else -> if (endFraction <= 1f - SNAP_THRESHOLD) SheetTarget.EXPANDED else SheetTarget.COLLAPSED
}

private const val SNAP_THRESHOLD = 0.25f
private const val FLING_VELOCITY_DP = 600f

/**
 * A bottom sheet the rider can push out of the way.
 *
 * The route panel used to be a fixed slab across the bottom of the map, so a
 * destination behind it could not be looked at, let alone tapped. This
 * behaves like the sheet on every phone map: drag it down to a peek, drag it
 * back up for the details, and it settles to whichever end the drag - or a
 * fast flick - was actually headed for.
 *
 * Unlike the first version, the whole plate is draggable, not just the
 * handle: the handle gets its own small [draggable] (nothing scrollable
 * lives there), and everything below it is one [verticalScroll] column
 * wired through [NestedScrollConnection] so a vertical drag on a button, a
 * chip or a line of text moves the sheet first and only scrolls the content
 * once the sheet is fully open - standard bottom-sheet behaviour. The
 * horizontal curviness slider is unaffected: its own drag detector is
 * orientation-locked to the other axis, so it never competes for the same
 * gesture.
 *
 * ## One gesture does one job
 *
 * The ride report: scrolling down inside an opened sheet made the sheet
 * disappear. The chain was real but wrong - a downward swipe that started
 * halfway down the content scrolled the content to its top and then handed
 * the leftover movement (and the leftover fling velocity) to the sheet, so a
 * single flick both scrolled *and* closed. Reading a list and losing it is
 * not a gesture anyone asked for.
 *
 * So the sheet only follows a downward drag while the content is already at
 * its top: [contentScrolledThisGesture] records whether the child consumed
 * anything during this gesture, and while it has, the sheet stays put and the
 * fling belongs entirely to the content. Push down again from the top and the
 * sheet goes - which is the behaviour on every phone map, and what the report
 * asked for ("es muss dafuer an der obersten Stelle sein").
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
    // The sheet opens collapsed - the map owns the screen in the resting
    // state, and the rider drags it up when they actually want the plan.
    // Only the very first layout pass snaps to the peek; once the rider (or
    // the sheet itself, later) has moved it, a change in content height
    // (a different planning state, say) must not yank it back down again.
    var collapsedOnce by remember { mutableStateOf(false) }
    var lastMaxOffset by remember { mutableFloatStateOf(0f) }
    // Where the sheet last came to rest. snapTarget() needs this, not the
    // live offset, to tell "a small nudge away from fully open" apart from
    // "a small nudge away from the peek" - same release position, opposite
    // correct answer.
    var restState by remember { mutableStateOf(SheetTarget.COLLAPSED) }
    val scope = rememberCoroutineScope()

    // Hoisted so the nested-scroll connection can ask "is the content at its
    // top?" - the question that decides whether a downward drag belongs to the
    // content or to the sheet. See the class doc.
    val scrollState = rememberScrollState()

    /**
     * True once the content has consumed scroll during the current gesture.
     *
     * Reset on the release (onPreFling/onPostFling), which is the only moment a
     * NestedScrollConnection reliably learns that a gesture ended.
     */
    var contentScrolledThisGesture by remember { mutableStateOf(false) }

    suspend fun settleTo(target: SheetTarget) {
        val value = if (target == SheetTarget.COLLAPSED) maxOffset else 0f
        offset.animateTo(value, Motion.settle())
        restState = target
    }

    fun dragBy(delta: Float) {
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset)) }
    }

    LaunchedEffect(maxOffset) {
        offset.updateBounds(0f, maxOffset)
        val wasCollapsed = !collapsedOnce || kotlin.math.abs(offset.value - lastMaxOffset) < 4f || offset.value >= lastMaxOffset
        if (!collapsedOnce && maxOffset > 0f) {
            offset.snapTo(maxOffset)
            collapsedOnce = true
            restState = SheetTarget.COLLAPSED
        } else if (wasCollapsed && maxOffset > 0f) {
            offset.animateTo(maxOffset, Motion.standard())
            restState = SheetTarget.COLLAPSED
        } else if (offset.value > maxOffset) {
            offset.snapTo(maxOffset)
        }
        lastMaxOffset = maxOffset
    }

    // Makes the content's own verticalScroll cooperate with the sheet drag:
    // an upward drag expands the sheet first and only scrolls once it is
    // fully open (onPreScroll); a downward drag scrolls the content back to
    // its top first and only collapses the sheet once there is nothing left
    // to scroll (onPostScroll catches what the child could not consume). A
    // release - fling or not - is decided by snapTarget() in onPreFling.
    val nestedScrollConnection = remember(maxOffset) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = available.y
                if (delta < 0f && offset.value > 0f) {
                    val consumed = (offset.value + delta).coerceIn(0f, maxOffset) - offset.value
                    dragBy(consumed)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (consumed.y != 0f) contentScrolledThisGesture = true
                val delta = available.y
                // Downward, and the content has nothing left to give: the sheet
                // may move - but only if this gesture did not start as a scroll.
                // Otherwise one flick would scroll the list and then close the
                // sheet out from under it.
                if (delta > 0f &&
                    offset.value < maxOffset &&
                    !contentScrolledThisGesture &&
                    scrollState.value == 0
                ) {
                    val moved = (offset.value + delta).coerceIn(0f, maxOffset) - offset.value
                    dragBy(moved)
                    return Offset(0f, moved)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val scrolled = contentScrolledThisGesture
                contentScrolledThisGesture = false
                // The gesture was a content scroll; its momentum is the
                // content's, not an invitation to close the sheet.
                if (scrolled) return Velocity.Zero
                val velocityDp = available.y / density.density
                // At an extreme already and flinging further that way: there
                // is nothing left for the sheet to do, so let the content's
                // own momentum scroll run instead of swallowing it for free.
                if (offset.value <= 0f && velocityDp < 0f) return Velocity.Zero
                if (offset.value >= maxOffset && velocityDp > 0f) return Velocity.Zero
                val target = snapTarget(
                    startedExpanded = restState == SheetTarget.EXPANDED,
                    endFraction = if (maxOffset > 0f) offset.value / maxOffset else 0f,
                    velocityDpPerSecond = velocityDp,
                )
                scope.launch { settleTo(target) }
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Deliberately does nothing but clear the flag. This used to
                // hand a content fling's leftover velocity to the sheet so a
                // flick from mid-list would carry on into a collapse; that is
                // exactly the behaviour the ride report called a bug. A
                // downward drag that starts at the top never reaches here -
                // onPostScroll moves the sheet directly and onPreFling settles
                // it - so nothing is lost by leaving this inert.
                contentScrolledThisGesture = false
                return Velocity.Zero
            }
        }
    }

    Surface(
        color = background,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        border = BorderStroke(1.dp, handleColor),
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .onSizeChanged { contentHeightPx = it.height.toFloat() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> dragBy(delta) },
                        onDragStopped = { velocityPxPerSec ->
                            val velocityDp = velocityPxPerSec / density.density
                            val target = snapTarget(
                                startedExpanded = restState == SheetTarget.EXPANDED,
                                endFraction = if (maxOffset > 0f) offset.value / maxOffset else 0f,
                                velocityDpPerSecond = velocityDp,
                            )
                            settleTo(target)
                        },
                    ),
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
                    .nestedScroll(nestedScrollConnection)
                    .verticalScroll(scrollState),
                content = content,
            )
        }
    }
}
