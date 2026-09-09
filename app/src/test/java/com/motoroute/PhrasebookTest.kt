package com.motoroute

import com.motoroute.data.model.Maneuver
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

    private fun announcement(
        maneuver: Maneuver,
        meters: Int,
        exit: Int = 0,
        immediate: Boolean = false,
    ) = VoiceAnnouncement(maneuver, meters, exit, immediate)

    @Test
    fun `german announces distance first, then the manoeuvre`() {
        assertEquals(
            "In 300 Metern rechts abbiegen",
            GermanPhrasebook.announce(announcement(Maneuver.TURN_RIGHT, 300)),
        )
    }

    @Test
    fun `german drops the distance when the turn is now`() {
        assertEquals(
            "Jetzt links abbiegen",
            GermanPhrasebook.announce(announcement(Maneuver.TURN_LEFT, 30)),
        )
    }

    @Test
    fun `german names the roundabout exit`() {
        assertEquals(
            "In 200 Metern im Kreisverkehr die 2. Ausfahrt nehmen",
            GermanPhrasebook.announce(announcement(Maneuver.ROUNDABOUT, 200, exit = 2)),
        )
    }

    @Test
    fun `german warns about a hairpin by name`() {
        val text = GermanPhrasebook.announce(announcement(Maneuver.HAIRPIN_LEFT, 150))
        assertTrue(text, text.contains("Spitzkehre links"))
    }

    @Test
    fun `two manoeuvres in a row are announced as one instruction`() {
        val german = GermanPhrasebook.announce(
            announcement(Maneuver.TURN_RIGHT, 100, immediate = true),
        )
        assertTrue(german, german.endsWith("danach sofort noch einmal"))

        val english = EnglishPhrasebook.announce(
            announcement(Maneuver.TURN_RIGHT, 100, immediate = true),
        )
        assertTrue(english, english.endsWith("then immediately again"))
    }

    @Test
    fun `arrival and off-route are plain sentences in both languages`() {
        assertEquals(
            "Sie haben Ihr Ziel erreicht",
            GermanPhrasebook.announce(announcement(Maneuver.DESTINATION, 0)),
        )
        assertEquals(
            "You have arrived",
            EnglishPhrasebook.announce(announcement(Maneuver.DESTINATION, 0)),
        )
        assertTrue(
            GermanPhrasebook.announce(announcement(Maneuver.OFF_ROUTE, 0))
                .contains("neue Route"),
        )
    }

    @Test
    fun `distances are rounded to something a person would say`() {
        assertTrue(
            GermanPhrasebook.announce(announcement(Maneuver.TURN_RIGHT, 247))
                .contains("250 Metern"),
        )
        assertTrue(
            GermanPhrasebook.announce(announcement(Maneuver.TURN_RIGHT, 1200))
                .contains("einem Kilometer"),
        )
        assertTrue(
            EnglishPhrasebook.announce(announcement(Maneuver.TURN_RIGHT, 1200))
                .contains("one kilometre"),
        )
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
