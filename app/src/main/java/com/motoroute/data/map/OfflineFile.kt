package com.motoroute.data.map

import java.io.File

/** One offline file the rider has imported. */
data class OfflineFile(
    val file: File,
    val sizeBytes: Long,
    val kind: OfflineFileKind,
) {
    val name: String get() = file.name
}

enum class OfflineFileKind(val extension: String, val directory: String) {
    MAP("map", "maps"),
    SEGMENT("rd5", "segments"),
    PROFILE("brf", "profiles"),
    /**
     * A PMTiles archive: the vector tiles MapLibre draws the map from.
     *
     * Separate from [MAP] on purpose - [MAP] is still what the offline place
     * search reads (Mapsforge), while this is what the renderer reads. The two
     * will likely arrive as one downloaded "region" bundle once the data layer
     * is wired to the cloud pipeline's catalog.json (kind "maptiles" there);
     * until then this is also the extension the Storage Access Framework
     * import in [OfflineDataRepository.import] recognises, which is how a
     * .pmtiles file built by tools/pipeline gets onto a device for testing.
     */
    MAPTILES("pmtiles", "maptiles"),

    /**
     * A `<region-id>.cameras.tsv` speed-camera file - see
     * `tools/pipeline/bin/build_cameras.py` and `1.Doku/Blitzer.md`. The
     * catalog kind is `"cameras"`; [com.motoroute.data.cameras.SpeedCameraRepository]
     * reads every file matching this extension out of this directory.
     */
    CAMERAS("tsv", "cameras"),

    /**
     * A `<region-id>.places.sqlite` offline place/street/address index - see
     * `tools/pipeline/bin/build_places.py` and `1.Doku/Ortssuche.md`. The
     * catalog kind is `"places"`; [com.motoroute.data.search.SqlitePlaceIndex]
     * opens every file matching this extension out of this directory.
     * `"sqlite"` is unambiguous as a bare extension today - nothing else in
     * the download pipeline produces a `.sqlite` file (see `make_catalog.py`).
     */
    PLACES("sqlite", "places"),
    ;

    companion object {
        fun of(fileName: String): OfflineFileKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}
