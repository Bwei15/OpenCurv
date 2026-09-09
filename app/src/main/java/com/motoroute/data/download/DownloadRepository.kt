package com.motoroute.data.download

import com.motoroute.data.map.OfflineFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Everything the download screen needs to draw itself. */
data class DownloadQueueState(
    val items: List<DownloadProgress> = emptyList(),
    val isRunning: Boolean = false,
) {
    val active: DownloadProgress? get() = items.firstOrNull { it.state == DownloadState.RUNNING }
    val remaining: Int get() = items.count { it.state == DownloadState.QUEUED }
    val failed: List<DownloadProgress> get() = items.filter { it.state == DownloadState.FAILED }
    val allDone: Boolean
        get() = items.isNotEmpty() && items.none {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
        }
}

/**
 * A one-at-a-time download queue.
 *
 * Serial rather than parallel on purpose: these are hundred-megabyte files on
 * a phone, and three at once means three times the chance of running the
 * storage out with nothing usable to show for it. One file at a time also
 * makes progress honest.
 *
 * The repository knows nothing about Android - it is handed the directories to
 * write into - so the queue logic is unit testable.
 */
class DownloadRepository(
    private val scope: CoroutineScope,
    private val downloader: FileDownloader,
    private val directoryFor: (OfflineFileKind) -> File,
    /** Free bytes on the volume; the queue refuses to start without headroom. */
    private val freeSpaceBytes: () -> Long,
    private val onFileInstalled: (OfflineFileKind) -> Unit = {},
) {

    private val _state = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = _state.asStateFlow()

    private var worker: Job? = null

    /** Queues [targets], skipping anything already on disk. */
    fun enqueue(targets: List<DownloadTarget>) {
        val fresh = targets.filterNot { isAlreadyInstalled(it) }
        if (fresh.isEmpty()) return

        val existing = _state.value.items
        val known = existing.map { it.target.url }.toSet()
        val added = fresh
            .filterNot { it.url in known }
            .map { DownloadProgress(it, 0, 0, DownloadState.QUEUED) }
        if (added.isEmpty()) return

        _state.value = _state.value.copy(items = existing + added)
        startWorker()
    }

    fun isAlreadyInstalled(target: DownloadTarget): Boolean =
        File(directoryFor(target.kind), target.fileName).isFile

    /** Stops after the current file and clears everything still queued. */
    fun cancelAll() {
        worker?.cancel()
        worker = null
        _state.value = DownloadQueueState(
            items = _state.value.items.map {
                if (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) {
                    it.copy(state = DownloadState.CANCELLED)
                } else {
                    it
                }
            },
            isRunning = false,
        )
    }

    /** Forgets finished and failed entries so the list does not grow forever. */
    fun clearFinished() {
        _state.value = _state.value.copy(
            items = _state.value.items.filter {
                it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
            },
        )
    }

    fun retryFailed() {
        _state.value = _state.value.copy(
            items = _state.value.items.map {
                if (it.state == DownloadState.FAILED) {
                    it.copy(state = DownloadState.QUEUED, error = null)
                } else {
                    it
                }
            },
        )
        startWorker()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            _state.value = _state.value.copy(isRunning = true)
            try {
                while (true) {
                    val next = _state.value.items
                        .firstOrNull { it.state == DownloadState.QUEUED } ?: break
                    runOne(next)
                }
            } finally {
                _state.value = _state.value.copy(isRunning = false)
            }
        }
    }

    private suspend fun runOne(item: DownloadProgress) {
        update(item.target) { it.copy(state = DownloadState.RUNNING) }

        if (freeSpaceBytes() < MIN_FREE_BYTES) {
            update(item.target) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = "not enough free storage",
                )
            }
            return
        }

        try {
            val directory = directoryFor(item.target.kind)
            downloader.download(item.target, directory) { done, total ->
                update(item.target) { it.copy(bytesDone = done, bytesTotal = total) }
            }
            update(item.target) { it.copy(state = DownloadState.DONE) }
            onFileInstalled(item.target.kind)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Leave the .part file: the next attempt resumes from it.
            update(item.target) { it.copy(state = DownloadState.CANCELLED) }
            throw cancellation
        } catch (error: Throwable) {
            update(item.target) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = error.message ?: "download failed",
                )
            }
        }
    }

    private fun update(
        target: DownloadTarget,
        transform: (DownloadProgress) -> DownloadProgress,
    ) {
        _state.value = _state.value.copy(
            items = _state.value.items.map {
                if (it.target.url == target.url) transform(it) else it
            },
        )
    }

    private companion object {
        /** Refuse to start a download with less than this much room left. */
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
    }
}
