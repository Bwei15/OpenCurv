package com.motoroute.domain.guidance

import com.motoroute.data.model.Maneuver
import com.motoroute.domain.VoiceAnnouncement
import kotlin.math.roundToInt

/**
 * What the app says out loud.
 *
 * Split out of the speech engine so the wording is testable without a device,
 * and so a second language is a second object rather than a fork of the voice
 * code. Announcements are built to be understood through a helmet at speed:
 * distance first, then the manoeuvre, no filler, and numbers rounded to
 * something a human would actually say.
 */
interface Phrasebook {
    fun announce(announcement: VoiceAnnouncement): String

    /** Spoken by the "test the voice" button, so a rider can check the setup at home. */
    val testAnnouncement: String

    companion object {
        /** Picks by language tag; anything that is not German gets English. */
        fun forLanguage(language: String): Phrasebook =
            if (language.lowercase().startsWith("de")) GermanPhrasebook else EnglishPhrasebook
    }
}

object EnglishPhrasebook : Phrasebook {

    override val testAnnouncement =
        "Voice guidance is working. In three hundred metres, turn right."

    override fun announce(announcement: VoiceAnnouncement): String {
        if (announcement.maneuver == Maneuver.DESTINATION) return "You have arrived"
        if (announcement.maneuver == Maneuver.OFF_ROUTE) return "Off route, recalculating"

        val turn = when (announcement.maneuver) {
            Maneuver.TURN_LEFT -> "turn left"
            Maneuver.TURN_RIGHT -> "turn right"
            Maneuver.SLIGHT_LEFT -> "keep slightly left"
            Maneuver.SLIGHT_RIGHT -> "keep slightly right"
            Maneuver.SHARP_LEFT -> "turn sharply left"
            Maneuver.SHARP_RIGHT -> "turn sharply right"
            Maneuver.HAIRPIN_LEFT -> "hairpin left"
            Maneuver.HAIRPIN_RIGHT -> "hairpin right"
            Maneuver.KEEP_LEFT -> "keep left"
            Maneuver.KEEP_RIGHT -> "keep right"
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "make a U-turn"
            Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_LEFT ->
                if (announcement.roundaboutExit > 0) {
                    "at the roundabout take exit ${announcement.roundaboutExit}"
                } else {
                    "at the roundabout"
                }
            else -> "continue"
        }

        val distance = distance(announcement.distanceMeters)
        val tail = if (announcement.isImmediate) ", then immediately again" else ""
        return if (distance == null) "$turn now$tail" else "In $distance, $turn$tail"
    }

    private fun distance(meters: Int): String? = when {
        meters < 60 -> null
        meters < 400 -> "${(meters / 50.0).roundToInt() * 50} metres"
        meters < 900 -> "${(meters / 100.0).roundToInt() * 100} metres"
        meters < 1500 -> "one kilometre"
        else -> "${(meters / 1000.0).roundToInt()} kilometres"
    }
}

/**
 * German guidance.
 *
 * Written the way a German navigation system speaks rather than translated word
 * for word: the manoeuvre is an infinitive clause after the distance ("In
 * dreihundert Metern rechts abbiegen"), which is both shorter and what a rider
 * here already has an ear for.
 */
object GermanPhrasebook : Phrasebook {

    override val testAnnouncement =
        "Sprachansage funktioniert. In dreihundert Metern rechts abbiegen."

    override fun announce(announcement: VoiceAnnouncement): String {
        if (announcement.maneuver == Maneuver.DESTINATION) return "Sie haben Ihr Ziel erreicht"
        if (announcement.maneuver == Maneuver.OFF_ROUTE) {
            return "Abseits der Route, neue Route wird berechnet"
        }

        val turn = when (announcement.maneuver) {
            Maneuver.TURN_LEFT -> "links abbiegen"
            Maneuver.TURN_RIGHT -> "rechts abbiegen"
            Maneuver.SLIGHT_LEFT -> "leicht links halten"
            Maneuver.SLIGHT_RIGHT -> "leicht rechts halten"
            Maneuver.SHARP_LEFT -> "scharf links abbiegen"
            Maneuver.SHARP_RIGHT -> "scharf rechts abbiegen"
            Maneuver.HAIRPIN_LEFT -> "Spitzkehre links"
            Maneuver.HAIRPIN_RIGHT -> "Spitzkehre rechts"
            Maneuver.KEEP_LEFT -> "links halten"
            Maneuver.KEEP_RIGHT -> "rechts halten"
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "bitte wenden"
            Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_LEFT ->
                if (announcement.roundaboutExit > 0) {
                    "im Kreisverkehr die ${announcement.roundaboutExit}. Ausfahrt nehmen"
                } else {
                    "in den Kreisverkehr einfahren"
                }
            else -> "weiter geradeaus"
        }

        val distance = distance(announcement.distanceMeters)
        val tail = if (announcement.isImmediate) ", danach sofort noch einmal" else ""
        return if (distance == null) "Jetzt $turn$tail" else "In $distance $turn$tail"
    }

    private fun distance(meters: Int): String? = when {
        meters < 60 -> null
        meters < 400 -> "${(meters / 50.0).roundToInt() * 50} Metern"
        meters < 900 -> "${(meters / 100.0).roundToInt() * 100} Metern"
        meters < 1500 -> "einem Kilometer"
        else -> "${(meters / 1000.0).roundToInt()} Kilometern"
    }
}
