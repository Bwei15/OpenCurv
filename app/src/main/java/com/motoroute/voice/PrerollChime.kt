package com.motoroute.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.motoroute.R

/**
 * The chime played a beat before every spoken announcement.
 *
 * Its job has almost nothing to do with being heard - it exists to open the
 * Bluetooth A2DP audio track before the words start. Helmet intercoms (Sena,
 * Cardo) drop their link to idle a few seconds into silence and take 0.5-1.5 s
 * to wake it back up; if speech starts the instant the TTS engine is asked to
 * speak, the beginning of the sentence is what pays for that wake-up -
 * "...links abbiegen" instead of "In 200 Metern links abbiegen". A short,
 * audible sound played first (rather than plain silence, per
 * `1.Doku/AI_README.md` §2.1) opens the same track a beat earlier, and
 * doubles as a "heads up, something is coming" cue for the rider.
 *
 * [SoundPool] gives no reliable "this stream finished" callback, so handing
 * off to the TTS engine uses the clip's own known length instead of a
 * callback - simple, and off by at most a few milliseconds, which does not
 * matter for a wake-up tone.
 */
class PrerollChime(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private var soundId = 0
    private var loaded = false

    init {
        runCatching {
            soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            soundId = soundPool.load(context, R.raw.nav_chime, 1)
        }
    }

    /**
     * Plays the chime, then runs [afterChime] once it has had time to open the
     * Bluetooth track. If the chime failed to load or play - a silent phone, a
     * broken audio stack, whatever - [afterChime] still runs immediately: the
     * announcement itself must never be held hostage by the wake-up tone.
     */
    fun playThenSpeak(afterChime: () -> Unit) {
        val played = loaded &&
            runCatching { soundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1.0f) != 0 }
                .getOrDefault(false)
        handler.postDelayed(afterChime, if (played) CHIME_DURATION_MILLIS else 0L)
    }

    fun release() {
        runCatching { soundPool.release() }
    }

    private companion object {
        const val VOLUME = 0.7f

        /** Matches the generated clip in res/raw/nav_chime.wav (~340 ms + margin). */
        const val CHIME_DURATION_MILLIS = 380L
    }
}
