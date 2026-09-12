package com.motoroute

import com.motoroute.ui.components.SheetTarget
import com.motoroute.ui.components.snapTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DraggableSheetSnapTest {

    @Test
    fun `a slow drag that barely moves snaps back to where it started`() {
        assertEquals(
            SheetTarget.EXPANDED,
            snapTarget(startedExpanded = true, endFraction = 0.1f, velocityDpPerSecond = 0f),
        )
        assertEquals(
            SheetTarget.COLLAPSED,
            snapTarget(startedExpanded = false, endFraction = 0.9f, velocityDpPerSecond = 0f),
        )
    }

    @Test
    fun `a quarter of the way is enough to flip state, not just the midpoint`() {
        // Old behaviour needed to cross 0.5; this is the actual fix for "too stubborn".
        assertEquals(
            SheetTarget.COLLAPSED,
            snapTarget(startedExpanded = true, endFraction = 0.3f, velocityDpPerSecond = 0f),
        )
        assertEquals(
            SheetTarget.EXPANDED,
            snapTarget(startedExpanded = false, endFraction = 0.7f, velocityDpPerSecond = 0f),
        )
    }

    @Test
    fun `just under the threshold still snaps back`() {
        assertEquals(
            SheetTarget.EXPANDED,
            snapTarget(startedExpanded = true, endFraction = 0.24f, velocityDpPerSecond = 0f),
        )
        assertEquals(
            SheetTarget.COLLAPSED,
            snapTarget(startedExpanded = false, endFraction = 0.76f, velocityDpPerSecond = 0f),
        )
    }

    @Test
    fun `a fast flick wins over position, even near the start`() {
        assertEquals(
            SheetTarget.COLLAPSED,
            snapTarget(startedExpanded = true, endFraction = 0.02f, velocityDpPerSecond = 650f),
        )
        assertEquals(
            SheetTarget.EXPANDED,
            snapTarget(startedExpanded = false, endFraction = 0.98f, velocityDpPerSecond = -650f),
        )
    }

    @Test
    fun `a slow flick below the fling threshold falls back to position`() {
        assertEquals(
            SheetTarget.EXPANDED,
            snapTarget(startedExpanded = true, endFraction = 0.1f, velocityDpPerSecond = 300f),
        )
    }
}
