package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Maneuver
import com.motoroute.data.model.NavigationInstruction
import com.motoroute.domain.guidance.CurveCombo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveComboTest {

    private val somewhere = GeoPoint(0.0, 0.0)

    private fun instr(distance: Double, maneuver: Maneuver) =
        NavigationInstruction(0, somewhere, maneuver, distance, 0.0)

    @Test
    fun `a lone turn is a run of one`() {
        val list = listOf(instr(100.0, Maneuver.TURN_LEFT), instr(2000.0, Maneuver.DESTINATION))
        assertEquals(listOf(0), CurveCombo.run(list, 0, speedMps = 20.0))
    }

    @Test
    fun `two turns close in time link into one run`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(80.0, Maneuver.TURN_RIGHT))
        // 80 m at 20 m/s = 4 s, well under the 8 s link window.
        assertEquals(listOf(0, 1), CurveCombo.run(list, 0, speedMps = 20.0))
    }

    @Test
    fun `the same gap at speed does not link`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(80.0, Maneuver.TURN_RIGHT))
        // 80 m at 2.5 m/s (the timing floor) is 32 s - plenty of time to speak both separately.
        assertEquals(listOf(0), CurveCombo.run(list, 0, speedMps = 1.0))
    }

    @Test
    fun `a continue waypoint between two turns does not break the chain`() {
        val list = listOf(
            instr(0.0, Maneuver.TURN_LEFT),
            instr(40.0, Maneuver.CONTINUE),
            instr(80.0, Maneuver.TURN_RIGHT),
        )
        assertEquals(listOf(0, 2), CurveCombo.run(list, 0, speedMps = 20.0))
    }

    @Test
    fun `destination ends a run even if it is close`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(20.0, Maneuver.DESTINATION))
        assertEquals(listOf(0), CurveCombo.run(list, 0, speedMps = 20.0))
    }

    @Test
    fun `a serpentine of three or more hairpins is always a lockout`() {
        val list = listOf(
            instr(0.0, Maneuver.HAIRPIN_LEFT),
            instr(80.0, Maneuver.HAIRPIN_RIGHT),
            instr(160.0, Maneuver.HAIRPIN_LEFT),
        )
        val run = CurveCombo.run(list, 0, speedMps = 20.0)
        assertEquals(3, run.size)
        assertTrue(CurveCombo.isLockout(list, run))
    }

    @Test
    fun `two ordinary turns are not a lockout - just a hand-off`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(80.0, Maneuver.TURN_RIGHT))
        val run = CurveCombo.run(list, 0, speedMps = 20.0)
        assertFalse(CurveCombo.isLockout(list, run))
    }

    @Test
    fun `a turn paired with a hairpin is a lockout`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(80.0, Maneuver.HAIRPIN_RIGHT))
        val run = CurveCombo.run(list, 0, speedMps = 20.0)
        assertTrue(CurveCombo.isLockout(list, run))
        assertEquals(Maneuver.HAIRPIN_RIGHT, CurveCombo.sharpestIn(list, run))
    }

    @Test
    fun `a lone hairpin is its own trivial lockout`() {
        val list = listOf(instr(0.0, Maneuver.HAIRPIN_LEFT), instr(2000.0, Maneuver.DESTINATION))
        val run = CurveCombo.run(list, 0, speedMps = 20.0)
        assertEquals(listOf(0), run)
        assertTrue(CurveCombo.isLockout(list, run))
    }

    @Test
    fun `a lone ordinary turn is never a lockout`() {
        val list = listOf(instr(0.0, Maneuver.TURN_LEFT), instr(2000.0, Maneuver.DESTINATION))
        val run = CurveCombo.run(list, 0, speedMps = 20.0)
        assertFalse(CurveCombo.isLockout(list, run))
    }
}
