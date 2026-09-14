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
    /**
     * Keep the route off motorways. Feeds the .brf `avoid_motorways`
     * parameter, which prices an Autobahn as a last resort rather than
     * forbidding it outright (see `motorcycle_curvy.brf`). On by default: a
     * motorcycle tour has nothing to gain from an Autobahn, and the ride report
     * that prompted this was a Rundtour that ran down one for a third of its
     * length.
     */
    val avoidMotorways: Boolean = true,
    /**
     * Token for a Mobilithek subscription, pasted in by the rider.
     *
     * The Mobilithek is the federal ministry's national access point and the
     * only way to get closures and roadworks for Bundes-/Landesstraßen; unlike
     * the Autobahn API it needs an account, so this cannot ship with a key.
     * Blank means the feature is simply off and only the keyless motorway feed
     * is used - see `1.Doku/Verkehrsdaten.md`.
     */
    val trafficApiKey: String = "",
    /** The subscription's own download URL; blank uses [com.motoroute.data.traffic.MobilithekTrafficSource.DEFAULT_FEED_URL]. */
    val trafficFeedUrl: String = "",
    /**
     * Where the map was last centred, so a cold start can show the right place
     * before the GPS has a fix.
     *
     * The ride report: up to five seconds of looking at the wrong part of the
     * country after opening the app. A cold GNSS fix takes that long and more -
     * nothing in the app can speed it up - but the map does not have to wait for
     * it to show where the rider was standing when they closed it. 0.0/0.0 means
     * unset (it is in the Atlantic, so it is not a position anyone loses).
     */
    val lastLatitude: Double = 0.0,
    val lastLongitude: Double = 0.0,
) {
    /** The remembered position, or null when there is none yet. */
    val lastPosition: Pair<Double, Double>?
        get() = if (lastLatitude == 0.0 && lastLongitude == 0.0) null else lastLatitude to lastLongitude
}

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
        avoidMotorways = prefs.getBoolean(KEY_AVOID_MOTORWAYS, true),
        trafficApiKey = prefs.getString(KEY_TRAFFIC_API_KEY, null).orEmpty(),
        trafficFeedUrl = prefs.getString(KEY_TRAFFIC_FEED_URL, null).orEmpty(),
        // Stored as bits because SharedPreferences has no putDouble.
        lastLatitude = Double.fromBits(prefs.getLong(KEY_LAST_LAT, 0L)),
        lastLongitude = Double.fromBits(prefs.getLong(KEY_LAST_LON, 0L)),
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
            .putBoolean(KEY_AVOID_MOTORWAYS, updated.avoidMotorways)
            .putString(KEY_TRAFFIC_API_KEY, updated.trafficApiKey)
            .putString(KEY_TRAFFIC_FEED_URL, updated.trafficFeedUrl)
            .putLong(KEY_LAST_LAT, updated.lastLatitude.toRawBits())
            .putLong(KEY_LAST_LON, updated.lastLongitude.toRawBits())
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
        const val KEY_AVOID_MOTORWAYS = "avoid_motorways"
        const val KEY_TRAFFIC_API_KEY = "traffic_api_key"
        const val KEY_TRAFFIC_FEED_URL = "traffic_feed_url"
        const val KEY_LAST_LAT = "last_latitude_bits"
        const val KEY_LAST_LON = "last_longitude_bits"
    }
}
