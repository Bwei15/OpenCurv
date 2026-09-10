package com.motoroute.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.speech.tts.UtteranceProgressListener
import com.motoroute.domain.VoiceAnnouncement
import com.motoroute.domain.guidance.Phrasebook
import java.util.Locale

/**
 * Turn-by-turn speech using the platform TTS engine.
 *
 * This class owns *how* an announcement reaches the headset, nothing about
 * *when* to speak (that's [com.motoroute.domain.NavigationManager]'s state
 * machine) or *what* to say (that's [Phrasebook]). Three things happen around
 * every utterance, in order, all aimed at the same problem - a Bluetooth
 * helmet headset that has gone to sleep and needs 0.5-1.5 s to open its audio
 * track again (`1.Doku/AI_README.md` §2.1):
 *
 *  1. [NavigationAudioFocus] asks for transient-duck focus, so any music
 *     already playing gets quieter instead of stopping - stopping it is what
 *     causes the constant Bluetooth reconnect churn that eats the start of
 *     the next sentence.
 *  2. [PrerollChime] plays a short tone first, opening the Bluetooth track
 *     before the words start so the distance at the front of the sentence
 *     survives instead of being swallowed by the wake-up delay.
 *  3. [BluetoothRoute] can (opt-in, see its own doc comment) switch the
 *     announcement to the SCO/HFP profile for a rider running a second
 *     intercom or radio mesh; everyone else stays on A2DP.
 *
 * The audio attributes still matter on their own: marking the stream as
 * ASSISTANCE_NAVIGATION_GUIDANCE is what makes a Bluetooth intercom duck the
 * music instead of talking over it, and what stops the announcement from
 * being routed to the phone speaker inside a helmet.
 *
 * The wording lives in [Phrasebook], picked from the language the engine
 * actually ended up speaking - so a German phone gets German announcements, and
 * a phone whose TTS has no German voice gets English rather than German words
 * read out by an English voice.
 */
class VoiceGuidance(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled: Boolean = true

    /** Set once the engine has told us which language it will speak. */
    var phrasebook: Phrasebook = Phrasebook.forLanguage(Locale.getDefault().language)
        private set

    /** True when the engine is initialised and can actually say something. */
    val isReady: Boolean get() = ready

    private val chime = PrerollChime(appContext)
    private val audioFocus = NavigationAudioFocus(appContext)
    private val bluetoothRoute = BluetoothRoute(appContext)

    /**
     * Opt-in fallback to the SCO/HFP profile for a rider running a separate
     * intercom or radio mesh alongside OpenCurv's own announcements - off by
     * default, see [BluetoothRoute]. There is currently no UI switch for this;
     * see `1.Doku/Sprachausgabe.md` for where one should go.
     */
    var useIntercomVoiceProfile: Boolean
        get() = bluetoothRoute.preferScoForAnnouncements
        set(value) {
            bluetoothRoute.preferScoForAnnouncements = value
        }

    init {
        tts = TextToSpeech(appContext) { status ->
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
                tts?.setOnUtteranceProgressListener(EndOfUtteranceListener(::onAnnouncementFinished))
            }
        }
    }

    private fun onAnnouncementFinished() {
        bluetoothRoute.stopIfNeeded()
        audioFocus.release()
    }

    fun speak(announcement: VoiceAnnouncement) {
        if (!enabled || !ready) return
        val text = phrase(announcement)
        if (text.isBlank()) return
        // The final ("jetzt"/"now") call and the rare priority events (arrival,
        // off-route) are the only ones allowed to cut off something already
        // playing - a stale "in three hundred metres" arriving after the turn
        // is worse than silence, but a queued-up backlog of early calls is not
        // worth losing either.
        val mode = if (announcement.isFinal) QUEUE_FLUSH else QUEUE_ADD
        audioFocus.duck()
        bluetoothRoute.startIfNeeded()
        chime.playThenSpeak {
            tts?.speak(text, mode, null, "opencurv-${System.nanoTime()}")
        }
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
        audioFocus.duck()
        bluetoothRoute.startIfNeeded()
        chime.playThenSpeak {
            tts?.speak(phrasebook.testAnnouncement, QUEUE_FLUSH, null, "opencurv-test")
        }
        return true
    }

    fun stop() {
        tts?.stop()
        onAnnouncementFinished()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        chime.release()
        onAnnouncementFinished()
    }

    /** The sentence that would be spoken. Kept public so it can be inspected in tests. */
    fun phrase(announcement: VoiceAnnouncement): String = phrasebook.announce(announcement)
}

/** Releases the audio focus/SCO grabbed for one utterance once it truly ends, however it ends. */
private class EndOfUtteranceListener(private val onFinished: () -> Unit) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = Unit
    override fun onDone(utteranceId: String?) = onFinished()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onError(utteranceId: String?) = onFinished()

    override fun onError(utteranceId: String?, errorCode: Int) = onFinished()
    override fun onStop(utteranceId: String?, interrupted: Boolean) = onFinished()
}
