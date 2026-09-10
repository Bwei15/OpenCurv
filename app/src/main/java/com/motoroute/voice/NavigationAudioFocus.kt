package com.motoroute.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Requests just enough audio focus to duck background music for one
 * announcement, instead of stopping it outright.
 *
 * Stopping music (`AUDIOFOCUS_GAIN_TRANSIENT`) is what makes a Bluetooth
 * intercom drop and re-open its music track on every single announcement -
 * exactly the reconnect churn `1.Doku/AI_README.md` §2.1 warns about.
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` asks Android for the softer version:
 * whatever is already playing turns down instead of stopping, so the
 * Bluetooth link and its music track stay open across the announcement.
 */
class NavigationAudioFocus(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val request = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // We are the transient guest here; the host stream should dim, not pause.
        .setWillPauseWhenDucked(false)
        .build()

    /** Call right before speaking. Failure here must never block the announcement. */
    fun duck() {
        runCatching { audioManager?.requestAudioFocus(request) }
    }

    /** Call once the announcement (chime + speech) has actually finished. */
    fun release() {
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }
}
