package com.motoroute

import com.motoroute.domain.guidance.AnnouncementTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementTimingTest {

    @Test
    fun `town speed only gets the early and final tiers`() {
        val tiers = AnnouncementTiming.applicableTiers(10.0).map { it.first }
        assertEquals(listOf(0, AnnouncementTiming.FINAL_TIER_INDEX), tiers)
    }

    @Test
    fun `rural speed adds the confirm tier`() {
        val tiers = AnnouncementTiming.applicableTiers(20.0).map { it.first }
        assertEquals(listOf(0, 1, AnnouncementTiming.FINAL_TIER_INDEX), tiers)
    }

    @Test
    fun `time to manoeuvre is distance over speed`() {
        assertEquals(15.0, AnnouncementTiming.timeToManeuverSeconds(300.0, 20.0), 0.001)
    }

    @Test
    fun `a stopped bike never gets an absurd time-to-go`() {
        // Without a floor this would be "infinity" and every tier would look due.
        val time = AnnouncementTiming.timeToManeuverSeconds(100.0, 0.0)
        assertEquals(100.0 / AnnouncementTiming.MIN_TIMING_SPEED_MPS, time, 0.001)
    }

    @Test
    fun `deepest due tier is the narrowest one already reached, not the widest`() {
        // This is the exact shape of the old bug: at 40 m and 20 m per second,
        // time-to-go is 2 s, which satisfies all three tiers at once. The
        // correct answer is FINAL, not EARLY - firing EARLY here and leaving
        // CONFIRM/FINAL to fire on the next two fixes is what produced three
        // near-identical announcements a second apart.
        val applicable = AnnouncementTiming.applicableTiers(20.0)
        val time = AnnouncementTiming.timeToManeuverSeconds(40.0, 20.0)
        assertEquals(AnnouncementTiming.FINAL_TIER_INDEX, AnnouncementTiming.deepestDueTierIndex(applicable, time))
    }

    @Test
    fun `nothing is due while still far out`() {
        val applicable = AnnouncementTiming.applicableTiers(20.0)
        val time = AnnouncementTiming.timeToManeuverSeconds(2000.0, 20.0)
        assertEquals(-1, AnnouncementTiming.deepestDueTierIndex(applicable, time))
    }

    @Test
    fun `mid-range distance is due for confirm, not final`() {
        val applicable = AnnouncementTiming.applicableTiers(20.0)
        // 100 m at 20 m/s = 5 s: past confirm's 7 s threshold, short of final's 3 s.
        val time = AnnouncementTiming.timeToManeuverSeconds(100.0, 20.0)
        assertEquals(1, AnnouncementTiming.deepestDueTierIndex(applicable, time))
    }

    @Test
    fun `active cornering needs both a real turn rate and real speed`() {
        assertTrue(AnnouncementTiming.isActivelyCornering(25.0, 10.0))
        assertFalse("too slow to be a real lean", AnnouncementTiming.isActivelyCornering(25.0, 1.0))
        assertFalse("too gentle to be a real lean", AnnouncementTiming.isActivelyCornering(5.0, 10.0))
    }
}
