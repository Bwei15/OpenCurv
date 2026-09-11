package com.motoroute.domain.guidance

import com.motoroute.data.model.Maneuver
import com.motoroute.domain.AnnouncementKind
import com.motoroute.domain.VoiceAnnouncement
import kotlin.math.roundToInt

/**
 * What the app says out loud.
 *
 * Split out of the speech engine so the wording is testable without a device,
 * and so a second language is a second object rather than a fork of the voice
 * code. Announcements are built to be understood through a helmet at speed:
 * the manoeuvre first when it is imminent, distance first when it is not, no
 * filler, and numbers rounded to something a human would actually say.
 *
 * Every sentence is one clause. That is not a style choice - on a bike a
 * second clause is a second thing that can get lost to wind noise, and the
 * rider cannot ask the app to repeat itself.
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

    override fun announce(a: VoiceAnnouncement): String = when (a.kind) {
        AnnouncementKind.ARRIVAL -> "You have arrived"
        AnnouncementKind.OFF_ROUTE -> "Off route, recalculating"
        AnnouncementKind.FREE_RIDE -> "Follow the road for ${a.freeRideKm} kilometres"
        AnnouncementKind.CURVE_WARNING -> curveWarning(a)
        AnnouncementKind.MANEUVER -> maneuverSentence(a)
        AnnouncementKind.SPEED_CAMERA -> speedCameraPhrase(a)
    }

    private fun speedCameraPhrase(a: VoiceAnnouncement): String =
        a.speedCameraLimitKmh?.let { "Speed camera ahead, ${it}" } ?: "Speed camera ahead"

    private fun curveWarning(a: VoiceAnnouncement): String =
        if (a.comboCount >= 3) "Attention, sequence of bends" else "Attention, ${sharpName(a.maneuver)}"

    private fun sharpName(m: Maneuver): String = when (m) {
        Maneuver.HAIRPIN_LEFT -> "hairpin left"
        Maneuver.HAIRPIN_RIGHT -> "hairpin right"
        Maneuver.SHARP_LEFT -> "sharp left bend"
        Maneuver.SHARP_RIGHT -> "sharp right bend"
        else -> "sharp bend"
    }

    private fun maneuverSentence(a: VoiceAnnouncement): String {
        val turn = turnPhrase(a.maneuver, a.roundaboutExit)
        val tail = a.secondManeuver?.let { ", then immediately ${turnPhrase(it, 0)}" }.orEmpty()
        return if (a.isFinal) {
            "${turn.replaceFirstChar { it.uppercase() }} now$tail"
        } else {
            "In ${distanceWords(a.distanceMeters)}, $turn$tail"
        }
    }

    private fun turnPhrase(maneuver: Maneuver, roundaboutExit: Int): String = when (maneuver) {
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
            if (roundaboutExit > 0) "at the roundabout take exit $roundaboutExit" else "at the roundabout"
        else -> "continue"
    }

    private fun distanceWords(meters: Int): String = when {
        meters < 400 -> "${(meters / 50.0).roundToInt() * 50} metres"
        meters < 900 -> "${(meters / 100.0).roundToInt() * 100} metres"
        meters < 1500 -> "one kilometre"
        else -> "${(meters / 1000.0).roundToInt()} kilometres"
    }
}

/**
 * German guidance.
 *
 * Written the way a German navigation system speaks rather than translated
 * word for word: the manoeuvre is an infinitive clause after the distance
 * ("In dreihundert Metern rechts abbiegen"), which is both shorter and what a
 * rider here already has an ear for.
 */
object GermanPhrasebook : Phrasebook {

    override val testAnnouncement =
        "Sprachansage funktioniert. In dreihundert Metern rechts abbiegen."

    override fun announce(a: VoiceAnnouncement): String = when (a.kind) {
        AnnouncementKind.ARRIVAL -> "Sie haben Ihr Ziel erreicht"
        AnnouncementKind.OFF_ROUTE -> "Abseits der Route, neue Route wird berechnet"
        AnnouncementKind.FREE_RIDE -> "Dem Straßenverlauf ${a.freeRideKm} Kilometer folgen"
        AnnouncementKind.CURVE_WARNING -> curveWarning(a)
        AnnouncementKind.MANEUVER -> maneuverSentence(a)
        AnnouncementKind.SPEED_CAMERA -> speedCameraPhrase(a)
    }

    private fun speedCameraPhrase(a: VoiceAnnouncement): String =
        a.speedCameraLimitKmh?.let { "Achtung, Blitzer, $it" } ?: "Achtung, Blitzer"

    private fun curveWarning(a: VoiceAnnouncement): String =
        if (a.comboCount >= 3) "Achtung, mehrere Kurven" else "Achtung, ${sharpName(a.maneuver)}"

    private fun sharpName(m: Maneuver): String = when (m) {
        Maneuver.HAIRPIN_LEFT -> "scharfe Spitzkehre links"
        Maneuver.HAIRPIN_RIGHT -> "scharfe Spitzkehre rechts"
        Maneuver.SHARP_LEFT -> "scharfe Linkskurve"
        Maneuver.SHARP_RIGHT -> "scharfe Rechtskurve"
        else -> "scharfe Kurve"
    }

    private fun maneuverSentence(a: VoiceAnnouncement): String {
        val turn = turnPhrase(a.maneuver, a.roundaboutExit)
        val tail = a.secondManeuver?.let { ", dann sofort ${turnPhrase(it, 0)}" }.orEmpty()
        return if (a.isFinal) "Jetzt $turn$tail" else "In ${distanceWords(a.distanceMeters)} $turn$tail"
    }

    private fun turnPhrase(maneuver: Maneuver, roundaboutExit: Int): String = when (maneuver) {
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
            if (roundaboutExit > 0) "im Kreisverkehr die $roundaboutExit. Ausfahrt nehmen" else "in den Kreisverkehr einfahren"
        else -> "weiter geradeaus"
    }

    private fun distanceWords(meters: Int): String = when {
        meters < 400 -> "${(meters / 50.0).roundToInt() * 50} Metern"
        meters < 900 -> "${(meters / 100.0).roundToInt() * 100} Metern"
        meters < 1500 -> "einem Kilometer"
        else -> "${(meters / 1000.0).roundToInt()} Kilometern"
    }
}
