package com.motoroute.data.traffic

/**
 * A live feed of traffic incidents.
 *
 * [AutobahnTrafficSource] is the only implementation today, and it covers
 * motorways only (the BMDV/Autobahn GmbH open API has no data for
 * Landes-/Bundesstraßen). A Mobilithek/DATEX-II source for those would
 * implement this same interface and register itself in [TrafficUpdater] -
 * see 1.Doku/Verkehrsdaten.md for what that registration would need
 * (Mobilithek requires an account and an API key, so it could not be built
 * here).
 */
fun interface TrafficSource {
    suspend fun fetch(): List<TrafficIncident>
}
