package com.motoroute.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces profile/curviness changes into a single automatic recalculation.
 *
 * The curviness slider fires a change on every pixel of drag. Without this,
 * each one would cancel whatever BRouter is chewing on and start over, which
 * on this app's 30-80 s routes would mean a recalculation never finishes
 * while a finger is still moving. [request] replaces whatever is pending, so
 * only the change still current after [debounceMillis] of quiet actually
 * recalculates.
 *
 * Android-free and driven by an injected [CoroutineScope] - the same shape as
 * [ReroutingEngine] - so the debounce window is a plain unit test instead of
 * something only provable by dragging a slider on a device.
 */
class RecalcTrigger(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 400L,
) {

    private var pending: Job? = null

    /** Schedules [onDue] after the debounce, cancelling any request still waiting. */
    fun request(onDue: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            onDue()
        }
    }

    /** Drops a pending request without firing it, e.g. when the plan is cleared. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
