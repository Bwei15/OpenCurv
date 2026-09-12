package com.motoroute.data.search

import com.motoroute.data.model.GeoPoint

/**
 * What [PlaceSearchRepository] needs from a street/address index - exactly
 * [SqlitePlaceIndex]'s query surface, pulled out as an interface so the
 * ranking and merging logic in [PlaceSearchRepository] can be exercised with
 * a plain in-memory fake instead of a real SQLite file. `android.database`
 * only exists on a device (or under Robolectric, which this project does not
 * use - see `tools/verifier/build.gradle.kts`'s exclude list), so this is
 * also what keeps [PlaceSearchRepository]'s own ranking tests possible at
 * all.
 */
interface PlaceIndexSource {
    /** True once at least one address index file is open and readable. */
    val hasIndex: Boolean

    fun places(prefixNorm: String, limit: Int = DEFAULT_LIMIT): List<Place>

    fun streets(
        prefixNorm: String,
        placeNorm: String? = null,
        near: GeoPoint? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<Place>

    fun addresses(
        streetNorm: String,
        hnNorm: String,
        placeNorm: String? = null,
        near: GeoPoint? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<Place>

    /** Call after a region download or delete changes what backs this index. */
    fun invalidate()

    companion object {
        const val DEFAULT_LIMIT = 30
    }
}
