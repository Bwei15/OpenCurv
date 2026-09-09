package com.motoroute

import com.motoroute.data.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverTest {

    @Test
    fun `brouter commands map onto maneuvers`() {
        assertEquals(Maneuver.TURN_LEFT, Maneuver.fromBRouter("TL", -85f))
        assertEquals(Maneuver.TURN_RIGHT, Maneuver.fromBRouter("TR", 85f))
        assertEquals(Maneuver.SLIGHT_LEFT, Maneuver.fromBRouter("TSLL", -25f))
        assertEquals(Maneuver.KEEP_RIGHT, Maneuver.fromBRouter("KR", 5f))
        assertEquals(Maneuver.CONTINUE, Maneuver.fromBRouter("C", 0f))
        assertEquals(Maneuver.UTURN_RIGHT, Maneuver.fromBRouter("TRU", 175f))
        assertEquals(Maneuver.OFF_ROUTE, Maneuver.fromBRouter("OFFR", 0f))
    }

    @Test
    fun `a roundabout keeps its exit information`() {
        assertEquals(Maneuver.ROUNDABOUT, Maneuver.fromBRouter("RNDB3", 40f))
        assertEquals(Maneuver.ROUNDABOUT_LEFT, Maneuver.fromBRouter("RNLB2", -40f))
    }

    /**
     * The distinction that matters on a mountain pass: a 95 degree corner is a
     * sharp turn, a 150 degree switchback is a hairpin and needs its own icon
     * and its own early warning.
     */
    @Test
    fun `a sharp turn becomes a hairpin past the threshold`() {
        assertEquals(Maneuver.SHARP_LEFT, Maneuver.fromBRouter("TSHL", -95f))
        assertEquals(Maneuver.HAIRPIN_LEFT, Maneuver.fromBRouter("TSHL", -150f))
        assertEquals(Maneuver.SHARP_RIGHT, Maneuver.fromBRouter("TSHR", 95f))
        assertEquals(Maneuver.HAIRPIN_RIGHT, Maneuver.fromBRouter("TSHR", 150f))
    }

    @Test
    fun `an unknown command degrades to continue rather than crashing`() {
        assertEquals(Maneuver.CONTINUE, Maneuver.fromBRouter("WAT", 0f))
    }

    @Test
    fun `only real maneuvers count as turns`() {
        assertTrue(Maneuver.TURN_LEFT.isTurn)
        assertTrue(Maneuver.ROUNDABOUT.isTurn)
        assertFalse(Maneuver.CONTINUE.isTurn)
        assertFalse(Maneuver.DESTINATION.isTurn)
    }
}
