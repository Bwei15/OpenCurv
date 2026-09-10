package com.motoroute

import com.motoroute.data.model.Maneuver
import com.motoroute.domain.AnnouncementKind
import com.motoroute.domain.VoiceAnnouncement
import com.motoroute.domain.guidance.EnglishPhrasebook
import com.motoroute.domain.guidance.GermanPhrasebook
import com.motoroute.domain.guidance.Phrasebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Announcements are the part of the app a rider cannot look at to check, so
 * the wording is asserted rather than eyeballed.
 */
class PhrasebookTest {

    private fun maneuver(
        maneuver: Maneuver,
        meters: Int,
        exit: Int = 0,
        isFinal: Boolean = false,
        secondManeuver: Maneuver? = null,
    ) = VoiceAnnouncement(
        kind = AnnouncementKind.MANEUVER,
        maneuver = maneuver,
        distanceMeters = meters,
        roundaboutExit = exit,
        isFinal = isFinal,
        secondManeuver = secondManeuver,
    )

    @Test
    fun `german announces distance first, then the manoeuvre`() {
        assertEquals(
            "In 300 Metern rechts abbiegen",
            GermanPhrasebook.announce(maneuver(Maneuver.TURN_RIGHT, 300)),
        )
    }

    @Test
    fun `english announces distance first, then the manoeuvre`() {
        assertEquals(
            "In 300 metres, turn right",
            EnglishPhrasebook.announce(maneuver(Maneuver.TURN_RIGHT, 300)),
        )
    }

    @Test
    fun `the final call drops the distance and leads with the manoeuvre`() {
        assertEquals(
            "Jetzt links abbiegen",
            GermanPhrasebook.announce(maneuver(Maneuver.TURN_LEFT, 30, isFinal = true)),
        )
        assertEquals(
            "Turn left now",
            EnglishPhrasebook.announce(maneuver(Maneuver.TURN_LEFT, 30, isFinal = true)),
        )
    }

    @Test
    fun `german names the roundabout exit`() {
        assertEquals(
            "In 200 Metern im Kreisverkehr die 2. Ausfahrt nehmen",
            GermanPhrasebook.announce(maneuver(Maneuver.ROUNDABOUT, 200, exit = 2)),
        )
    }

    @Test
    fun `german warns about a hairpin by name`() {
        val text = GermanPhrasebook.announce(maneuver(Maneuver.HAIRPIN_LEFT, 150))
        assertTrue(text, text.contains("Spitzkehre links"))
    }

    @Test
    fun `a two-turn hand-off names the second direction, not a vague repeat`() {
        val german = GermanPhrasebook.announce(
            maneuver(Maneuver.TURN_RIGHT, 40, isFinal = true, secondManeuver = Maneuver.TURN_LEFT),
        )
        assertEquals("Jetzt rechts abbiegen, dann sofort links abbiegen", german)

        val english = EnglishPhrasebook.announce(
            maneuver(Maneuver.TURN_RIGHT, 40, isFinal = true, secondManeuver = Maneuver.TURN_LEFT),
        )
        assertEquals("Turn right now, then immediately turn left", english)
    }

    @Test
    fun `a tight combo warning names the sharp bend, short and command-first`() {
        val warning = VoiceAnnouncement(
            kind = AnnouncementKind.CURVE_WARNING,
            maneuver = Maneuver.HAIRPIN_RIGHT,
            comboCount = 1,
        )
        assertEquals("Achtung, scharfe Spitzkehre rechts", GermanPhrasebook.announce(warning))
        assertEquals("Attention, hairpin right", EnglishPhrasebook.announce(warning))
    }

    @Test
    fun `a longer combo warning stays generic rather than naming every bend`() {
        val warning = VoiceAnnouncement(kind = AnnouncementKind.CURVE_WARNING, comboCount = 4)
        assertEquals("Achtung, mehrere Kurven", GermanPhrasebook.announce(warning))
        assertEquals("Attention, sequence of bends", EnglishPhrasebook.announce(warning))
    }

    @Test
    fun `the free-ride cue names the distance still to cover`() {
        val cue = VoiceAnnouncement(kind = AnnouncementKind.FREE_RIDE, freeRideKm = 12)
        assertEquals("Dem Straßenverlauf 12 Kilometer folgen", GermanPhrasebook.announce(cue))
        assertEquals("Follow the road for 12 kilometres", EnglishPhrasebook.announce(cue))
    }

    @Test
    fun `arrival and off-route are plain sentences in both languages`() {
        val arrival = VoiceAnnouncement(kind = AnnouncementKind.ARRIVAL, isFinal = true)
        val offRoute = VoiceAnnouncement(kind = AnnouncementKind.OFF_ROUTE)

        assertEquals("Sie haben Ihr Ziel erreicht", GermanPhrasebook.announce(arrival))
        assertEquals("You have arrived", EnglishPhrasebook.announce(arrival))
        assertTrue(GermanPhrasebook.announce(offRoute).contains("neue Route"))
    }

    @Test
    fun `distances are rounded to something a person would say`() {
        assertTrue(GermanPhrasebook.announce(maneuver(Maneuver.TURN_RIGHT, 247)).contains("250 Metern"))
        assertTrue(GermanPhrasebook.announce(maneuver(Maneuver.TURN_RIGHT, 1200)).contains("einem Kilometer"))
        assertTrue(EnglishPhrasebook.announce(maneuver(Maneuver.TURN_RIGHT, 1200)).contains("one kilometre"))
    }

    @Test
    fun `language choice falls back to english`() {
        assertSame(GermanPhrasebook, Phrasebook.forLanguage("de"))
        assertSame(GermanPhrasebook, Phrasebook.forLanguage("de-AT"))
        assertSame(EnglishPhrasebook, Phrasebook.forLanguage("fr"))
        assertSame(EnglishPhrasebook, Phrasebook.forLanguage(""))
    }

    @Test
    fun `the test announcement says something in both languages`() {
        assertTrue(GermanPhrasebook.testAnnouncement.contains("Sprachansage"))
        assertTrue(EnglishPhrasebook.testAnnouncement.contains("Voice guidance"))
    }
}
