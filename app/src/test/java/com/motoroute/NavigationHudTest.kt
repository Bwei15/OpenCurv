package com.motoroute

import com.motoroute.R
import com.motoroute.ui.navigation.formatCameraDistance
import com.motoroute.ui.navigation.formatEta
import com.motoroute.ui.navigation.formatEtaAndRemaining
import com.motoroute.ui.navigation.formatRemaining
import com.motoroute.ui.navigation.menuToggleIcon
import com.motoroute.ui.navigation.speedColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

/**
 * The riding HUD's pure formatting and state-decision functions - everything
 * that does not need a Composable to be worth testing (see 7.3's brief for
 * why: ETA, remaining distance, the speeding colour and the menu's own icon
 * flip).
 */
class NavigationHudTest {

    @Test
    fun `eta formats as a 24-hour clock`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 14, 32, 0)
        }
        assertEquals("14:32", formatEta(calendar.timeInMillis))
    }

    @Test
    fun `eta with no estimate reads as a placeholder`() {
        assertEquals("--:--", formatEta(0L))
        assertEquals("--:--", formatEta(-1L))
    }

    @Test
    fun `remaining distance switches from metres to kilometres at 1000 m`() {
        assertEquals("450 m", formatRemaining(450.0))
        assertEquals("999 m", formatRemaining(999.0))
        assertEquals("1.0 km", formatRemaining(1000.0))
        assertEquals("38.0 km", formatRemaining(38_000.0))
    }

    @Test
    fun `remaining distance drops the decimal past 100 km`() {
        assertEquals("140 km", formatRemaining(140_000.0))
    }

    @Test
    fun `the bottom-left chip combines eta and remaining distance with a dot`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 9, 5, 0)
        }
        assertEquals(
            "09:05 · 38.0 km",
            formatEtaAndRemaining(calendar.timeInMillis, 38_000.0),
        )
    }

    @Test
    fun `speed reads red only once the rider is clearly speeding`() {
        val danger = androidx.compose.ui.graphics.Color.Red
        val normal = androidx.compose.ui.graphics.Color.White
        assertEquals(danger, speedColor(speeding = true, danger = danger, normal = normal))
        assertEquals(normal, speedColor(speeding = false, danger = danger, normal = normal))
        assertNotEquals(danger, normal)
    }

    @Test
    fun `the menu button flips from hamburger to a close icon when open`() {
        assertEquals(R.drawable.ic_action_menu, menuToggleIcon(expanded = false))
        assertEquals(R.drawable.ic_action_close, menuToggleIcon(expanded = true))
        assertNotEquals(menuToggleIcon(false), menuToggleIcon(true))
    }

    @Test
    fun `camera distance reuses the maneuver bar's own rounding`() {
        assertEquals("600 m", formatCameraDistance(600.0))
        assertEquals("1.0 km", formatCameraDistance(1000.0))
        assertEquals("now", formatCameraDistance(5.0))
    }
}
