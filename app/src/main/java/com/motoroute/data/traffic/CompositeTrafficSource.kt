package com.motoroute.data.traffic

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Several feeds behind one [TrafficSource].
 *
 * The Autobahn API covers motorways, the Mobilithek covers the Bundes- and
 * Landesstraßen a tour actually uses; a rider wants both, and
 * [TrafficRepository.refreshFrom] takes exactly one source. This fetches them
 * concurrently and concatenates the results, de-duplicated by incident id in
 * case two feeds publish the same closure.
 *
 * Partial failure is survivable on purpose, mirroring how
 * [AutobahnTrafficSource] treats one failing road: if at least one source
 * answers, the refresh succeeds with what came back, because replacing 4000
 * known closures with an exception helps nobody. Only when *every* source
 * fails does the error propagate, so [TrafficRepository] keeps its cache
 * instead of overwriting it with an empty list.
 */
class CompositeTrafficSource(
    private val sources: List<TrafficSource>,
) : TrafficSource {

    override suspend fun fetch(): List<TrafficIncident> = coroutineScope {
        if (sources.isEmpty()) return@coroutineScope emptyList()

        val results = sources
            .map { source -> async { runCatching { source.fetch() } } }
            .map { it.await() }

        val failures = results.count { it.isFailure }
        if (failures == results.size) {
            throw results.first().exceptionOrNull() ?: IllegalStateException("all traffic sources failed")
        }

        val seen = HashSet<String>()
        val merged = ArrayList<TrafficIncident>()
        for (result in results) {
            for (incident in result.getOrDefault(emptyList())) {
                if (seen.add(incident.id)) merged += incident
            }
        }
        merged
    }
}
