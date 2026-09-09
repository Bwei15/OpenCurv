package com.motoroute.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import com.motoroute.domain.VoiceAnnouncement
import com.motoroute.domain.guidance.Phrasebook
import java.util.Locale

/**
 * Turn-by-turn speech using the platform TTS engine.
 *
 * The audio attributes matter more than they look: marking the stream as
 * ASSISTANCE_NAVIGATION_GUIDANCE is what makes a Bluetooth intercom duck the
 * music instead of talking over it, and what stops the announcement from being
 * routed to the phone speaker inside a helmet.
 *
 * The wording lives in [Phrasebook], picked from the language the engine
 * actually ended up speaking - so a German phone gets German announcements, and
 * a phone whose TTS has no German voice gets English rather than German words
 * read out by an English voice.
 */
class VoiceGuidance(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled: Boolean = true

    /** Set once the engine has told us which language it will speak. */
    var phrasebook: Phrasebook = Phrasebook.forLanguage(Locale.getDefault().language)
        private set

    /** True when the engine is initialised and can actually say something. */
    val isReady: Boolean get() = ready

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
                val spoken = if (
                    result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.ENGLISH)
                    Locale.ENGLISH
                } else {
                    locale
                }
                phrasebook = Phrasebook.forLanguage(spoken.language)
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

    /**
     * Says a sample announcement, so the rider can check volume, intercom
     * pairing and language without leaving the driveway.
     *
     * @return false when the engine is not up yet, so the UI can say so instead
     *   of leaving the rider wondering whether the phone is mute.
     */
    fun speakTest(): Boolean {
        if (!ready) return false
        tts?.speak(phrasebook.testAnnouncement, QUEUE_FLUSH, null, "opencurv-test")
        return true
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

    /** The sentence that would be spoken. Kept public so it can be inspected in tests. */
    fun phrase(announcement: VoiceAnnouncement): String = phrasebook.announce(announcement)
}
