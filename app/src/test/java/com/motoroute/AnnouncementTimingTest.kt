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

    // ---- the "announcements come too late" ride report ---------------------

    @Test
    fun `every tier now covers the time the words themselves take`() {
        // A tier nominally 2 s out used to finish speaking after the junction:
        // the headset needs up to 1.5 s to wake and the sentence runs longer
        // still. The lead has to be more than the bare tier seconds.
        val final = AnnouncementTiming.leadSecondsFor(AnnouncementTiming.FINAL_TIER_INDEX, 20.0)
        assertTrue("final lead was $final", final > AnnouncementTiming.FINAL_SECONDS)
        val early = AnnouncementTiming.leadSecondsFor(0, 20.0)
        assertTrue("early lead was $early", early > AnnouncementTiming.EARLY_SECONDS)
    }

    @Test
    fun `riding faster reaches disproportionately further ahead`() {
        val town = AnnouncementTiming.triggerDistanceMeters(0, 14.0)
        val rural = AnnouncementTiming.triggerDistanceMeters(0, 28.0)
        // Twice the speed, but more than twice the warning distance - that is
        // the whole point of the stretch.
        assertTrue("town $town m, rural $rural m", rural > 2.0 * town)
    }

    @Test
    fun `the Landstrasse heads-up lands in the band the brief asks for`() {
        // 1.Doku/AI_README.md 2.3: Vorankuendigung 600-800 m at 100 km/h.
        val meters = AnnouncementTiming.triggerDistanceMeters(0, 100 / 3.6)
        assertTrue("early fired at $meters m", meters in 600.0..800.0)
    }

    @Test
    fun `the last-chance call stays near the junction even at speed`() {
        // Stretching this one would stop it meaning "now".
        val meters = AnnouncementTiming.triggerDistanceMeters(AnnouncementTiming.FINAL_TIER_INDEX, 100 / 3.6)
        assertTrue("final fired at $meters m", meters in 80.0..160.0)
    }

    @Test
    fun `a crawling bike still gets a useful warning distance`() {
        // Pure time-to-go at 3 m/s would put the heads-up 50 m out; the per-tier
        // distance floor keeps it usable in stop-and-go traffic.
        val meters = AnnouncementTiming.triggerDistanceMeters(0, 3.0)
        assertTrue("early fired at $meters m", meters >= 150.0)
    }

    @Test
    fun `tier thresholds stay strictly descending at every speed`() {
        // deepestDueTierIndex walks the list and stops at the first tier that is
        // not due yet, which is only correct while the thresholds descend.
        for (tenths in 0..400) {
            val speed = tenths / 10.0
            val leads = AnnouncementTiming.applicableTiers(speed).map { it.second }
            leads.zipWithNext { wider, narrower ->
                assertTrue("at $speed m/s: $leads", narrower < wider)
            }
        }
    }

    @Test
    fun `the stretch is flat in town and capped on the Autobahn`() {
        assertEquals(1.0, AnnouncementTiming.speedStretch(10.0), 0.001)
        assertEquals(1.0, AnnouncementTiming.speedStretch(AnnouncementTiming.STRETCH_BASE_SPEED_MPS), 0.001)
        assertEquals(AnnouncementTiming.MAX_SPEED_STRETCH, AnnouncementTiming.speedStretch(60.0), 0.001)
    }
}
