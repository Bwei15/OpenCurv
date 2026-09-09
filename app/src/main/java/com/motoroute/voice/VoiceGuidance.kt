package com.motoroute.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import com.motoroute.data.model.Maneuver
import com.motoroute.domain.VoiceAnnouncement
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turn-by-turn speech using the platform TTS engine.
 *
 * The audio attributes matter more than they look: marking the stream as
 * ASSISTANCE_NAVIGATION_GUIDANCE is what makes a Bluetooth intercom duck the
 * music instead of talking over it, and what stops the announcement from being
 * routed to the phone speaker inside a helmet.
 */
class VoiceGuidance(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled: Boolean = true

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                val locale = Locale.getDefault()
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
            }
        }
    }

    fun speak(announcement: VoiceAnnouncement) {
        if (!enabled || !ready) return
        val text = phrase(announcement)
        // Arrival and the final 50 m call flush the queue: a stale "in three
        // hundred metres" arriving after the turn is worse than silence.
        val mode = if (announcement.distanceMeters <= 60) QUEUE_FLUSH else QUEUE_ADD
        tts?.speak(text, mode, null, "opencurv-${announcement.hashCode()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    /** Builds the spoken sentence. Kept separate so it can be unit tested. */
    fun phrase(announcement: VoiceAnnouncement): String {
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

        val distance = formatDistance(announcement.distanceMeters)
        val tail = if (announcement.isImmediate) ", then immediately again" else ""
        return if (distance == null) "$turn now$tail" else "In $distance, $turn$tail"
    }

    /** Rounds to something a human would actually say. */
    private fun formatDistance(meters: Int): String? = when {
        meters < 60 -> null
        meters < 400 -> "${(meters / 50.0).roundToInt() * 50} metres"
        meters < 900 -> "${(meters / 100.0).roundToInt() * 100} metres"
        meters < 1500 -> "one kilometre"
        else -> "${(meters / 1000.0).roundToInt()} kilometres"
    }
}
