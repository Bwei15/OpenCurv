package com.motoroute.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Chooses which Bluetooth audio profile an announcement goes out on.
 *
 * By default everything stays on A2DP - the music-quality profile a helmet
 * headset's own speakers use, and the one [NavigationAudioFocus]'s ducking
 * actually works against. SCO/HFP (the phone-call profile) is only worth
 * switching to when the rider runs a separate intercom/radio mesh in the
 * helmet that shares the phone's call audio path - `1.Doku/AI_README.md` §2.1
 * calls this out as a fallback for that case, not the default for everyone.
 *
 * This is the one piece of the audio layer that is honestly unfinished:
 * `AudioManager.startBluetoothSco()` has needed the `BLUETOOTH_CONNECT`
 * runtime permission since Android 12, and that permission is not declared in
 * the manifest - adding it is outside the files this change is allowed to
 * touch. Without it [startIfNeeded] is a no-op and every announcement plays
 * over A2DP, which is the correct behaviour for the large majority of riders
 * who are not running a secondary intercom anyway. See `1.Doku/Sprachausgabe.md`.
 */
class BluetoothRoute(private val context: Context) {

    /** Opt-in: most riders should stay on A2DP and never touch this. */
    var preferScoForAnnouncements: Boolean = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val hasBluetoothConnectPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Switches to SCO for the duration of one announcement, if enabled and possible. */
    fun startIfNeeded() {
        if (!preferScoForAnnouncements || !hasBluetoothConnectPermission) return
        runCatching {
            val manager = audioManager ?: return
            if (!manager.isBluetoothScoAvailableOffCall) return
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
        }
    }

    /** Hands the link back to A2DP once the announcement is done. */
    fun stopIfNeeded() {
        if (!preferScoForAnnouncements || !hasBluetoothConnectPermission) return
        runCatching {
            audioManager?.isBluetoothScoOn = false
            audioManager?.stopBluetoothSco()
        }
    }
}
