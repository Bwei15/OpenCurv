package com.motoroute.domain.cameras

import com.motoroute.data.cameras.SpeedCamera
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the HUD overlay and the voice layer need about an active warning. */
data class SpeedCameraWarning(
    val camera: SpeedCamera,
    val distanceMeters: Double,
    val maxSpeedKmh: Int?,
)

/**
 * Decides when a stationary speed camera is worth warning the rider about.
 *
 * Android-free by design, like [com.motoroute.domain.NavigationManager] - it
 * runs in `tools/verifier` and is driven by a synthetic GPS track in
 * [SpeedCameraWarnerTest] rather than a device. Feed it every filtered fix,
 * navigating or not: `data/location/LocationProvider.kt` fixes reach
 * `domain/NavigationController.kt` whenever the map screen is open, active
 * ride or none, and a camera 300 m from home is exactly the case a rider
 * wants a warning for even when going nowhere in particular.
 *
 * Rules (`1.Doku/Blitzer.md` has the numbers with their reasoning):
 *  - a new warning starts when a camera is within [WARN_RADIUS_M], the
 *    bearing from the rider to it is within [APPROACH_BEARING_DIFF_DEG] of
 *    the current heading, speed is at least [MIN_SPEED_MPS] (below that the
 *    heading is not trustworthy - see `1.Doku/Sprachausgabe.md` §4 for the
 *    same caveat elsewhere), and - if the camera carries OSM's `direction`
 *    tag - the heading also matches that direction within
 *    [DIRECTION_TOLERANCE_DEG] (otherwise the camera watches the opposite
 *    carriageway and is ignored);
 *  - once active, the warning stays up (hysteresis, not re-evaluated against
 *    the start conditions) until the camera is more than
 *    [RELEASE_RADIUS_M] away or has been passed (bearing to it now differs
 *    from heading by more than [PASSED_BEARING_DIFF_DEG]);
 *  - the same camera announces at most once per [COOLDOWN_MILLIS] - the
 *    persistent [warning] state is not subject to this, only the one-shot
 *    [announcements] signal the voice layer speaks from, so a rider who loops
 *    back past the same camera twice inside three minutes still sees the red
 *    screen both times but only hears it once.
 */
class SpeedCameraWarner(private var grid: SpeedCameraGrid = SpeedCameraGrid(emptyList())) {

    private val _warning = MutableStateFlow<SpeedCameraWarning?>(null)

    /** The currently active warning, for the HUD overlay. Null when there is none. */
    val warning: StateFlow<SpeedCameraWarning?> = _warning.asStateFlow()

    private val _announcements = MutableSharedFlow<SpeedCameraWarning>(extraBufferCapacity = 2)

    /** Fires exactly once per approach, subject to the per-camera cooldown - the voice layer's cue. */
    val announcements: SharedFlow<SpeedCameraWarning> = _announcements.asSharedFlow()

    private var activeCamera: SpeedCamera? = null
    private val lastAnnouncedAtMillis = HashMap<String, Long>()

    /** Swaps in a freshly loaded camera set (e.g. after a region download completes). */
    fun updateCameras(grid: SpeedCameraGrid) {
        this.grid = grid
    }

    /**
     * Processes one fix. Cheap to call on every fix: with [enabled] false (the
     * default - see the opt-in switch in `data/settings/SettingsRepository.kt`
     * and the legal note in `1.Doku/Blitzer.md`) it does nothing but clear any
     * stale warning.
     */
    fun onFix(
        point: GeoPoint,
        headingDegrees: Double,
        speedMps: Double,
        enabled: Boolean,
        nowMillis: Long,
    ) {
        if (!enabled) {
            clear()
            return
        }

        val active = activeCamera
        if (active != null) {
            val distance = Geo.distanceMeters(point, active.point)
            val bearingToCamera = Geo.bearingDegrees(point, active.point)
            // Heading is unreliable below MIN_SPEED_MPS, so a stopped rider
            // (traffic light, junction) never gets falsely "passed" - only the
            // distance release still applies while stationary.
            val passed = speedMps >= MIN_SPEED_MPS &&
                Geo.bearingDifference(headingDegrees, bearingToCamera) > PASSED_BEARING_DIFF_DEG
            if (distance > RELEASE_RADIUS_M || passed) {
                activeCamera = null
                _warning.value = null
            } else {
                _warning.value = SpeedCameraWarning(active, distance, active.maxSpeedKmh)
                return
            }
        }

        // No active warning (or it just released this fix): look for a new one.
        if (speedMps < MIN_SPEED_MPS) return

        val camera = bestCandidate(point, headingDegrees) ?: return
        val distance = Geo.distanceMeters(point, camera.point)
        activeCamera = camera
        _warning.value = SpeedCameraWarning(camera, distance, camera.maxSpeedKmh)

        val lastAnnounced = lastAnnouncedAtMillis[camera.id]
        if (lastAnnounced == null || nowMillis - lastAnnounced >= COOLDOWN_MILLIS) {
            lastAnnouncedAtMillis[camera.id] = nowMillis
            _announcements.tryEmit(SpeedCameraWarning(camera, distance, camera.maxSpeedKmh))
        }
    }

    /** Nearest camera within range whose approach and direction conditions all hold. */
    private fun bestCandidate(point: GeoPoint, headingDegrees: Double): SpeedCamera? {
        var best: SpeedCamera? = null
        var bestDistance = Double.MAX_VALUE
        for (camera in grid.nearby(point, WARN_RADIUS_M)) {
            val distance = Geo.distanceMeters(point, camera.point)
            if (distance >= bestDistance) continue
            val bearingToCamera = Geo.bearingDegrees(point, camera.point)
            if (Geo.bearingDifference(headingDegrees, bearingToCamera) > APPROACH_BEARING_DIFF_DEG) continue
            val direction = camera.directionDeg
            if (direction != null &&
                Geo.bearingDifference(headingDegrees, direction.toDouble()) > DIRECTION_TOLERANCE_DEG
            ) {
                continue
            }
            best = camera
            bestDistance = distance
        }
        return best
    }

    private fun clear() {
        activeCamera = null
        _warning.value = null
    }

    companion object {
        /** A camera further than this is not worth warning about yet. */
        const val WARN_RADIUS_M = 1000.0

        /** Hysteresis: an active warning survives until the camera is this far away. */
        const val RELEASE_RADIUS_M = 1200.0

        /** Max bearing error, rider -> camera, to count as "heading towards it". */
        const val APPROACH_BEARING_DIFF_DEG = 35.0

        /** Max heading error against the camera's own `direction` tag. */
        const val DIRECTION_TOLERANCE_DEG = 60.0

        /** Bearing error, rider -> camera, past which the camera counts as "behind". */
        const val PASSED_BEARING_DIFF_DEG = 100.0

        /** Below this, GPS heading is too noisy to trust (see Sprachausgabe.md §4). */
        const val MIN_SPEED_MPS = 3.0

        /** One announcement per camera per approach; re-arms after this long. */
        const val COOLDOWN_MILLIS = 3 * 60 * 1000L
    }
}
