package com.motoroute.data.cameras

import com.motoroute.data.model.GeoPoint
import java.util.Locale

/**
 * A stationary speed camera.
 *
 * Sourced from OSM `highway=speed_camera` nodes, and from the `role=device`
 * node of `enforcement=maxspeed` relations - the pipeline
 * (`tools/pipeline/bin/build_cameras.py`) flattens both to the same TSV row,
 * see `1.Doku/Blitzer.md`.
 *
 * [id] is derived from the coordinate rather than an OSM node id: the TSV
 * format the pipeline emits does not carry node ids at all (they are not
 * needed on a phone that only ever reads the file forwards), and a
 * coordinate-derived id is exactly the key the loader already needs to merge
 * duplicate nodes - the same camera can appear both as a plain
 * `highway=speed_camera` node and as the device node of an
 * `enforcement=maxspeed` relation in the same extract. It doubles as the
 * stable per-camera key [com.motoroute.domain.cameras.SpeedCameraWarner] uses
 * for its announce-once-per-pass cooldown: stable across app restarts and
 * catalog re-downloads, which an index into a mutable list would not be.
 */
data class SpeedCamera(
    val id: String,
    val point: GeoPoint,
    /** OSM `direction` tag in degrees: which direction of travel the camera watches. Null = unknown/both. */
    val directionDeg: Int? = null,
    val maxSpeedKmh: Int? = null,
    val name: String? = null,
) {
    companion object {
        /** Coordinate rounded to 5 decimals (~1.1 m) - the dedupe/cooldown key. */
        fun idFor(latitude: Double, longitude: Double): String =
            String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude)
    }
}
