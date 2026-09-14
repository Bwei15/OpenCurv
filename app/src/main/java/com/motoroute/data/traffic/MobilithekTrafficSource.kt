package com.motoroute.data.traffic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traffic feed from the Mobilithek, the German federal ministry's national
 * access point for mobility data (`mobilithek.info`, formerly the MDM).
 *
 * This is the piece [AutobahnTrafficSource] cannot cover: the keyless Autobahn
 * GmbH API knows motorways and nothing else, while the interesting roads for a
 * motorcycle tour are exactly the Bundes- and Landesstraßen the Länder publish
 * through the Mobilithek.
 *
 * ## Why the rider has to supply a key
 *
 * Mobilithek data offers are not anonymous: you register, subscribe to an offer
 * and are issued credentials for it. There is no keyless endpoint to fall back
 * on, so this source stays inert until the rider pastes their own token into
 * settings - see `1.Doku/Verkehrsdaten.md`. With no key, [fetch] returns an
 * empty list rather than throwing, so the composite refresh keeps working off
 * the Autobahn source alone.
 *
 * ## Why the URL is configurable
 *
 * Each subscription has its own download URL, and different offers serve
 * different payloads (DATEX II XML for most Länder feeds, GeoJSON for a few).
 * Hard-coding one publisher's path would make the feature work for exactly one
 * account. So the rider supplies the URL their subscription shows them, the
 * token goes on the request, and [parse] decides from the payload itself
 * whether it is XML or GeoJSON.
 *
 * Android-free (`java.net` only), like the rest of `data/traffic` except
 * [TrafficUpdater].
 */
class MobilithekTrafficSource(
    /** The subscription token the rider copied out of their Mobilithek account. Blank disables the source. */
    private val apiKey: String,
    /** The subscription's download URL. Blank falls back to [DEFAULT_FEED_URL]. */
    feedUrl: String = "",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests: `(url, apiKey) -> raw response body`, throws on failure. */
    private val httpGet: (String, String) -> String = { url, key ->
        authorizedGet(url, key, connectTimeoutMillis = 15_000, readTimeoutMillis = 30_000)
    },
) : TrafficSource {

    private val url: String = feedUrl.ifBlank { DEFAULT_FEED_URL }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    override suspend fun fetch(): List<TrafficIncident> = withContext(dispatcher) {
        if (!isConfigured) return@withContext emptyList()
        parse(httpGet(url, apiKey))
    }

    /**
     * Turns one downloaded payload into incidents, picking the parser from the
     * payload rather than from configuration - a subscription that switches
     * from DATEX II to GeoJSON keeps working without the rider changing a
     * setting.
     */
    fun parse(body: String): List<TrafficIncident> = when {
        body.isBlank() -> emptyList()
        Datex2Parser.looksLikeXml(body) -> Datex2Parser.parse(body, idPrefix = ID_PREFIX)
        else -> MobilithekTrafficParser.parseGeoJson(body)
    }

    companion object {
        const val ID_PREFIX = "mobilithek:"

        /**
         * The Mobilithek's own subscription download path. A subscription id is
         * part of the URL the portal shows for the offer, so this default only
         * helps as a template - the rider is expected to paste theirs.
         */
        const val DEFAULT_FEED_URL = "https://mobilithek.info/mdp-api/broker/v2/subscriptions/download"
    }
}

/** GET with the Mobilithek token attached, accepting both DATEX II XML and GeoJSON. */
private fun authorizedGet(
    urlString: String,
    apiKey: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
): String {
    val connection = URL(urlString).openConnection() as HttpURLConnection
    connection.connectTimeout = connectTimeoutMillis
    connection.readTimeout = readTimeoutMillis
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/xml, application/json;q=0.9, */*;q=0.1")
    connection.setRequestProperty("User-Agent", "OpenCurv/1.0 (+https://github.com/Bwei15/OpenCurv)")
    // Portals in this space disagree on which header carries the token, and
    // sending both costs nothing: the one that is not understood is ignored.
    connection.setRequestProperty("Authorization", "Bearer $apiKey")
    connection.setRequestProperty("X-API-Key", apiKey)
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IOException("HTTP $code for $urlString")
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
