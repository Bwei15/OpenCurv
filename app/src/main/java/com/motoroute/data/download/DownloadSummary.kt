package com.motoroute.data.download

/**
 * The queue, summarised the way the rider thinks about it.
 *
 * A region is five to nine files, and a progress list of five file names with
 * five progress bars is noise. What the rider wants to know is "Niedersachsen,
 * file 3 of 5, 62 %" - one row per region, one bar, one cancel button.
 */
data class RegionDownloadProgress(
    val regionPath: String,
    val regionName: String,
    val filesTotal: Int,
    val filesDone: Int,
    val filesFailed: Int,
    val isRunning: Boolean,
    val activeLabel: String?,
    val activeFraction: Float?,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String?,
) {
    val isFinished: Boolean get() = filesDone + filesFailed >= filesTotal

    /**
     * Whole-package progress in 0..1.
     *
     * Finished files count as whole, the running one contributes its own
     * fraction. That is honest without needing the sizes of files the server
     * has not been asked about yet: a queued 150 MB tile and a queued 400 MB map
     * are both simply "not started".
     */
    val fraction: Float
        get() {
            if (filesTotal == 0) return 0f
            val active = activeFraction ?: 0f
            return ((filesDone + filesFailed + active) / filesTotal).coerceIn(0f, 1f)
        }
}

/** Groups the queue by region, in the order the regions were queued. */
fun DownloadQueueState.byRegion(): List<RegionDownloadProgress> {
    val grouped = LinkedHashMap<String, MutableList<DownloadProgress>>()
    items.forEach { item ->
        val key = item.target.regionPath ?: OTHER_FILES
        grouped.getOrPut(key) { mutableListOf() }.add(item)
    }
    return grouped.map { (key, list) ->
        val running = list.firstOrNull { it.state == DownloadState.RUNNING }
        val failed = list.filter { it.state == DownloadState.FAILED }
        RegionDownloadProgress(
            regionPath = key,
            regionName = list.firstNotNullOfOrNull { it.target.regionName }
                ?: list.first().target.label,
            filesTotal = list.size,
            filesDone = list.count { it.state == DownloadState.DONE },
            filesFailed = failed.size,
            isRunning = running != null,
            activeLabel = running?.target?.fileName,
            activeFraction = running?.fraction,
            bytesDone = list.sumOf { it.bytesDone },
            bytesTotal = list.sumOf { it.bytesTotal },
            error = failed.firstNotNullOfOrNull { it.error },
        )
    }
}

private const val OTHER_FILES = "other"
