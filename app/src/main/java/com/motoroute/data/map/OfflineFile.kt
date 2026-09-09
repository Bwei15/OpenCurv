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
    ;

    companion object {
        fun of(fileName: String): OfflineFileKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}
