package com.motoroute.data.download

import android.content.Context
import com.motoroute.data.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The list of regions the rider can download.
 *
 * It is a plain JSON asset rather than something fetched from the server, for
 * two reasons: the app works the first time it is opened with no network, and
 * a rider who wants a region we did not list can add it to the file themselves
 * without touching any code. If a path ever goes stale, the download fails with
 * a clear message and manual import still works.
 */
class MapCatalog(private val context: Context) {

    private var cached: List<MapRegion>? = null

    suspend fun regions(): List<MapRegion> = withContext(Dispatchers.IO) {
        cached ?: load().also { cached = it }
    }

    /** Regions grouped by country, in catalog order. */
    suspend fun grouped(): Map<String, List<MapRegion>> =
        regions().groupBy { it.country }

    private fun load(): List<MapRegion> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("regions")
        buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                add(
                    MapRegion(
                        path = entry.getString("path"),
                        name = entry.getString("name"),
                        country = entry.optString("country", "Other"),
                        bounds = BoundingBox(
                            minLat = entry.getDouble("minLat"),
                            minLon = entry.getDouble("minLon"),
                            maxLat = entry.getDouble("maxLat"),
                            maxLon = entry.getDouble("maxLon"),
                        ),
                        approxSizeMb = entry.optInt("approxSizeMb", 0),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    private companion object {
        const val ASSET = "catalog/regions.json"
    }
}
