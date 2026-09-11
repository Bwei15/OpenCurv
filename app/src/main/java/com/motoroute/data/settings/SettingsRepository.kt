package com.motoroute.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapTheme { AUTO, DAY, NIGHT }

/**
 * Which cartography the map is drawn with.
 *
 * [COLOUR] is the everyday style: coloured road classes, green woodland, blue
 * water - close enough to what every rider already reads on a phone that no
 * learning is needed. [CONTRAST] throws the colour away for maximum legibility
 * in direct sun, which is worth having when the sun is low and the visor is
 * scratched.
 */
enum class MapStyle { COLOUR, CONTRAST }

data class Settings(
    val profileId: String = "motorcycle_curvy",
    /** 0.0 = direct, 2.0 = maximum curves. Feeds the .brf `curviness` parameter. */
    val curviness: Float = 1.0f,
    val voiceEnabled: Boolean = true,
    val mapTheme: MapTheme = MapTheme.AUTO,
    /** Fake 3D perspective while navigating (see MapScreen for the caveat). */
    val perspectiveEnabled: Boolean = true,
    val headingUp: Boolean = true,
    val volumeKeyZoom: Boolean = true,
    val keepScreenOn: Boolean = true,
    val searchAlternatives: Boolean = true,
    val mapStyle: MapStyle = MapStyle.COLOUR,
    /** False until the rider has been walked through getting their first map. */
    val onboardingDone: Boolean = false,
    /**
     * Opt-in for the on-screen/spoken stationary speed-camera warning
     * (`domain/cameras/SpeedCameraWarner.kt`). Defaults to **off**: in
     * Germany, using a device to warn of speed camera locations while driving
     * is prohibited under StVO §23 Abs. 1c, so this has to be a choice the
     * rider makes, not a default - see `1.Doku/Blitzer.md`.
     */
    val speedCameraWarnings: Boolean = false,
)

/**
 * Settings live in SharedPreferences: a handful of scalars, read on every
 * start, written when the rider flips a switch. DataStore would add a
 * dependency and a coroutine round trip for no benefit at this size.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("opencurv", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun read() = Settings(
        profileId = prefs.getString(KEY_PROFILE, null) ?: "motorcycle_curvy",
        curviness = prefs.getFloat(KEY_CURVINESS, 1.0f),
        voiceEnabled = prefs.getBoolean(KEY_VOICE, true),
        mapTheme = runCatching { MapTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "AUTO") }
            .getOrDefault(MapTheme.AUTO),
        perspectiveEnabled = prefs.getBoolean(KEY_PERSPECTIVE, true),
        headingUp = prefs.getBoolean(KEY_HEADING_UP, true),
        volumeKeyZoom = prefs.getBoolean(KEY_VOLUME_ZOOM, true),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, true),
        searchAlternatives = prefs.getBoolean(KEY_ALTERNATIVES, true),
        mapStyle = runCatching { MapStyle.valueOf(prefs.getString(KEY_MAP_STYLE, null) ?: "COLOUR") }
            .getOrDefault(MapStyle.COLOUR),
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
        speedCameraWarnings = prefs.getBoolean(KEY_SPEED_CAMERA_WARNINGS, false),
    )

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString(KEY_PROFILE, updated.profileId)
            .putFloat(KEY_CURVINESS, updated.curviness)
            .putBoolean(KEY_VOICE, updated.voiceEnabled)
            .putString(KEY_THEME, updated.mapTheme.name)
            .putBoolean(KEY_PERSPECTIVE, updated.perspectiveEnabled)
            .putBoolean(KEY_HEADING_UP, updated.headingUp)
            .putBoolean(KEY_VOLUME_ZOOM, updated.volumeKeyZoom)
            .putBoolean(KEY_KEEP_SCREEN, updated.keepScreenOn)
            .putBoolean(KEY_ALTERNATIVES, updated.searchAlternatives)
            .putString(KEY_MAP_STYLE, updated.mapStyle.name)
            .putBoolean(KEY_ONBOARDING, updated.onboardingDone)
            .putBoolean(KEY_SPEED_CAMERA_WARNINGS, updated.speedCameraWarnings)
            .apply()
        _settings.value = updated
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_CURVINESS = "curviness"
        const val KEY_VOICE = "voice"
        const val KEY_THEME = "theme"
        const val KEY_PERSPECTIVE = "perspective"
        const val KEY_HEADING_UP = "heading_up"
        const val KEY_VOLUME_ZOOM = "volume_zoom"
        const val KEY_KEEP_SCREEN = "keep_screen"
        const val KEY_ALTERNATIVES = "alternatives"
        const val KEY_MAP_STYLE = "map_style"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_SPEED_CAMERA_WARNINGS = "speed_camera_warnings"
    }
}
