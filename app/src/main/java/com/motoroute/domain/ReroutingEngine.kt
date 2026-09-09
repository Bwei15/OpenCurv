package com.motoroute.domain

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a reroute attempt produced. */
sealed interface RerouteResult {
    data class Success(val route: Route) : RerouteResult
    data class Failure(val message: String) : RerouteResult
    data object Skipped : RerouteResult
}

/**
 * Recalculates the route when the rider has left it.
 *
 * Everything here is about *not* getting in the way:
 *
 *  - the calculation runs on a background coroutine, never on the UI thread;
 *  - only one reroute runs at a time, and a request arriving while one is in
 *    flight is dropped rather than queued;
 *  - after a completed reroute there is a cooldown, so a rider threading
 *    through a car park does not trigger a recalculation every second;
 *  - the destination is kept, so a reroute always heads for the original goal
 *    from wherever the rider actually is.
 */
class ReroutingEngine(
    private val scope: CoroutineScope,
    private val calculate: suspend (from: GeoPoint, to: GeoPoint, via: List<GeoPoint>) -> Result<Route>,
    private val cooldownMillis: Long = 12_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val mutex = Mutex()
    private var inFlight: Job? = null
    private var lastAttemptMillis: Long? = null
    private var failureCount = 0

    val isRerouting: Boolean get() = inFlight?.isActive == true

    /**
     * Requests a recalculation from [from] to [destination].
     *
     * @param remainingVia via points still ahead of the rider; passing these
     *   keeps a planned loop a loop instead of collapsing it into a straight
     *   run home the moment the rider misses one turn.
     */
    fun request(
        from: GeoPoint,
        destination: GeoPoint,
        remainingVia: List<GeoPoint> = emptyList(),
        onResult: (RerouteResult) -> Unit,
    ) {
        val now = nowMillis()
        if (isRerouting) {
            onResult(RerouteResult.Skipped)
            return
        }
        // A null last attempt means "never tried": the first request after a
        // reset must always go through, whatever the clock happens to read.
        val since = lastAttemptMillis?.let { now - it }
        if (since != null && since < effectiveCooldown()) {
            onResult(RerouteResult.Skipped)
            return
        }
        lastAttemptMillis = now

        inFlight = scope.launch {
            mutex.withLock {
                val result = runCatching { calculate(from, destination, remainingVia) }
                    .getOrElse { Result.failure(it) }
                result.fold(
                    onSuccess = {
                        failureCount = 0
                        onResult(RerouteResult.Success(it))
                    },
                    onFailure = {
                        failureCount++
                        onResult(RerouteResult.Failure(it.message ?: "rerouting failed"))
                    },
                )
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
        inFlight = null
    }

    fun reset() {
        cancel()
        lastAttemptMillis = null
        failureCount = 0
    }

    /**
     * Backs off after repeated failures. Off the edge of the imported routing
     * tiles every attempt will fail, and hammering BRouter there just eats
     * battery for nothing.
     */
    private fun effectiveCooldown(): Long {
        val factor = when {
            failureCount <= 0 -> 1L
            failureCount == 1 -> 2L
            failureCount == 2 -> 4L
            else -> 8L
        }
        return cooldownMillis * factor
    }
}
