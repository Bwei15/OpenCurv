package com.motoroute.data.search

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import java.io.File

/**
 * Reads streets and addresses straight out of the `<region-id>.places.sqlite`
 * files a region download writes to `OfflineDataRepository.placesDir` (see
 * `tools/pipeline/bin/build_places.py` and `1.Doku/Ortssuche.md` §5 for the
 * schema and the query shapes this follows).
 *
 * A rider can have more than one of these open at once (Bremen and
 * Niedersachsen, say, or two neighbouring federal states) - every query fans
 * out over all of them and merges the rows, so a street search near a state
 * border still finds the neighbour's streets.
 *
 * Every database opens [SQLiteDatabase.OPEN_READONLY] - nothing here ever
 * writes, and read-only skips SQLite's write-ahead-log setup, which matters
 * once a rider has Niedersachsen's file (tens of megabytes) open on a phone.
 *
 * Kept out of `tools/verifier` for the same reason `OfflineDataRepository` and
 * `SpeedCameraRepository` are: `android.database` is Android-only.
 * [QueryParser], the text-handling half of the address search, has no such
 * dependency and stays in the JVM build.
 */
class SqlitePlaceIndex(private val filesDir: () -> File) : PlaceIndexSource, AutoCloseable {

    private var databases: List<SQLiteDatabase>? = null
    private val lock = Any()

    private fun open(): List<SQLiteDatabase> = databases ?: synchronized(lock) {
        databases ?: openAll().also { databases = it }
    }

    private fun openAll(): List<SQLiteDatabase> {
        val dir = filesDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        return files.mapNotNull { file ->
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            }.onFailure { e ->
                Log.w(TAG, "could not open ${file.name}: ${e.message}")
            }.getOrNull()
        }
    }

    /** Call after a region download or delete changes what is under [filesDir]. */
    override fun invalidate() {
        synchronized(lock) {
            databases?.forEach { runCatching { it.close() } }
            databases = null
        }
    }

    override fun close() = invalidate()

    /** True once at least one `.places.sqlite` file is open and readable. */
    override val hasIndex: Boolean get() = open().isNotEmpty()

    // ---- a) places ----------------------------------------------------

    /** Places whose `norm` starts with [prefixNorm] - `1.Doku/Ortssuche.md` §5a. */
    override fun places(prefixNorm: String, limit: Int): List<Place> {
        if (prefixNorm.isEmpty()) return emptyList()
        val (lower, upper) = prefixBounds(prefixNorm)
        return open().flatMap { db -> placesByNormRange(db, lower, upper, limit) }.take(limit)
    }

    // ---- b) streets -----------------------------------------------------

    /**
     * Streets whose `norm` starts with [prefixNorm].
     *
     * [placeNorm], when given, scopes the search to that one place (`1.Doku/
     * Ortssuche.md` §5b) - a fast, exact lookup. Otherwise [near], when given,
     * scopes it to a bounding box around a point and ranks by distance -
     * useful for "which street am I thinking of" without a named place. With
     * neither, every matching street comes back, ordered by name.
     */
    override fun streets(
        prefixNorm: String,
        placeNorm: String?,
        near: GeoPoint?,
        limit: Int,
    ): List<Place> {
        if (prefixNorm.isEmpty()) return emptyList()
        val (lower, upper) = prefixBounds(prefixNorm)
        return open().flatMap { db ->
            when {
                placeNorm != null -> streetsInPlace(db, lower, upper, placeNorm, limit)
                near != null -> streetsNear(db, lower, upper, near, limit)
                else -> streetsPlain(db, lower, upper, limit)
            }
        }
    }

    // ---- c) addresses -----------------------------------------------------

    /**
     * Addresses on a street matching [streetNorm] (a prefix - the rider may
     * not have finished typing the street name) whose house number matches
     * [hnNorm]. An exact house-number match wins when there is one; only a
     * housenumber genuinely not on file falls back to a prefix match on it
     * (typing "1" before "12" gets progressively narrower results instead of
     * nothing until the last digit lands).
     *
     * [placeNorm] scopes the candidate streets the same way [streets] does;
     * [near] is currently unused here (a full house-number query already
     * narrows to a handful of streets, so a bounding box buys nothing) but
     * kept in the signature to match `1.Doku/Ortssuche.md` §5c and leave room
     * for it once distance-based street disambiguation needs it.
     */
    override fun addresses(
        streetNorm: String,
        hnNorm: String,
        placeNorm: String?,
        near: GeoPoint?,
        limit: Int,
    ): List<Place> {
        if (streetNorm.isEmpty() || hnNorm.isEmpty()) return emptyList()
        return open().flatMap { db -> addressesIn(db, streetNorm, hnNorm, placeNorm, near, limit) }.take(limit)
    }

    // ---- places -------------------------------------------------------------

    private fun placesByNormRange(db: SQLiteDatabase, lower: String, upper: String, limit: Int): List<Place> {
        val sql = "SELECT name, kind, lat, lon FROM places WHERE norm >= ? AND norm < ? ORDER BY kind LIMIT ?"
        return db.rawQuery(sql, arrayOf(lower, upper, limit.toString())).use { c ->
            val out = ArrayList<Place>(c.count)
            while (c.moveToNext()) {
                out += Place(
                    name = c.getString(0),
                    kind = PlaceKind.ofPlaceTag(c.getString(1)) ?: PlaceKind.TOWN,
                    latitude = c.getLong(2) / COORD_SCALE,
                    longitude = c.getLong(3) / COORD_SCALE,
                )
            }
            out
        }
    }

    private data class PlaceRef(val id: Long, val name: String)

    private fun placesByNorm(db: SQLiteDatabase, norm: String): List<PlaceRef> =
        db.rawQuery("SELECT id, name FROM places WHERE norm = ?", arrayOf(norm)).use { c ->
            val out = ArrayList<PlaceRef>(c.count)
            while (c.moveToNext()) out += PlaceRef(c.getLong(0), c.getString(1))
            out
        }

    // ---- streets --------------------------------------------------------

    private fun streetsPlain(db: SQLiteDatabase, lower: String, upper: String, limit: Int): List<Place> {
        val sql = "SELECT name, lat, lon FROM streets WHERE norm >= ? AND norm < ? ORDER BY name LIMIT ?"
        return db.rawQuery(sql, arrayOf(lower, upper, limit.toString())).use { c ->
            val out = ArrayList<Place>(c.count)
            while (c.moveToNext()) {
                out += Place(
                    name = c.getString(0),
                    kind = PlaceKind.STREET,
                    latitude = c.getLong(1) / COORD_SCALE,
                    longitude = c.getLong(2) / COORD_SCALE,
                )
            }
            out
        }
    }

    private fun streetsInPlace(
        db: SQLiteDatabase,
        lower: String,
        upper: String,
        placeNorm: String,
        limit: Int,
    ): List<Place> {
        val places = placesByNorm(db, placeNorm)
        if (places.isEmpty()) return emptyList()
        val placeById = places.associateBy { it.id }
        val placeholders = places.joinToString(",") { "?" }
        val args = arrayOf(lower, upper, *places.map { it.id.toString() }.toTypedArray(), limit.toString())
        val sql = "SELECT name, lat, lon, place_id FROM streets " +
            "WHERE norm >= ? AND norm < ? AND place_id IN ($placeholders) ORDER BY name LIMIT ?"
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Place>(c.count)
            while (c.moveToNext()) {
                out += Place(
                    name = c.getString(0),
                    kind = PlaceKind.STREET,
                    latitude = c.getLong(1) / COORD_SCALE,
                    longitude = c.getLong(2) / COORD_SCALE,
                    detail = placeById[c.getLong(3)]?.name,
                )
            }
            out
        }
    }

    private fun streetsNear(db: SQLiteDatabase, lower: String, upper: String, near: GeoPoint, limit: Int): List<Place> {
        val box = boundingBox(near, NEARBY_RADIUS_METERS)
        val sql = "SELECT s.name, s.lat, s.lon, p.name FROM streets s LEFT JOIN places p ON p.id = s.place_id " +
            "WHERE s.norm >= ? AND s.norm < ? AND s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? LIMIT ?"
        val args = arrayOf(
            lower, upper,
            box.minLat.toString(), box.maxLat.toString(),
            box.minLon.toString(), box.maxLon.toString(),
            (limit * NEARBY_OVERFETCH).toString(),
        )
        val candidates = db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Place>(c.count)
            while (c.moveToNext()) {
                out += Place(
                    name = c.getString(0),
                    kind = PlaceKind.STREET,
                    latitude = c.getLong(1) / COORD_SCALE,
                    longitude = c.getLong(2) / COORD_SCALE,
                    detail = if (c.isNull(3)) null else c.getString(3),
                )
            }
            out
        }
        return candidates.sortedBy { Geo.distanceMeters(near, it.point) }.take(limit)
    }

    // ---- addresses ------------------------------------------------------

    private data class StreetCandidate(val id: Long, val name: String, val placeId: Long?)

    private fun addressesIn(
        db: SQLiteDatabase,
        streetNorm: String,
        hnNorm: String,
        placeNorm: String?,
        @Suppress("UNUSED_PARAMETER") near: GeoPoint?,
        limit: Int,
    ): List<Place> {
        val (lower, upper) = prefixBounds(streetNorm)
        val candidates = streetCandidates(db, lower, upper, placeNorm)
        if (candidates.isEmpty()) return emptyList()

        val placeNameCache = HashMap<Long, String?>()
        fun placeName(id: Long): String? =
            placeNameCache.getOrPut(id) {
                db.rawQuery("SELECT name FROM places WHERE id = ?", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }

        val out = ArrayList<Place>()
        for (candidate in candidates) {
            if (out.size >= limit) break
            addressRows(db, candidate.id, hnNorm, limit - out.size).forEach { row ->
                val placeName = candidate.placeId?.let(::placeName)
                out += Place(
                    name = "${candidate.name} ${row.displayHouseNumber}",
                    kind = PlaceKind.ADDRESS,
                    latitude = row.lat / COORD_SCALE,
                    longitude = row.lon / COORD_SCALE,
                    detail = listOfNotNull(row.postcode, placeName).joinToString(" ").ifBlank { null },
                )
            }
        }
        return out
    }

    private fun streetCandidates(
        db: SQLiteDatabase,
        lower: String,
        upper: String,
        placeNorm: String?,
    ): List<StreetCandidate> {
        val (sql, args) = if (placeNorm != null) {
            val places = placesByNorm(db, placeNorm)
            if (places.isEmpty()) return emptyList()
            val placeholders = places.joinToString(",") { "?" }
            val queryArgs = arrayOf(lower, upper, *places.map { it.id.toString() }.toTypedArray(), STREET_CANDIDATE_CAP.toString())
            "SELECT id, name, place_id FROM streets WHERE norm >= ? AND norm < ? AND place_id IN ($placeholders) LIMIT ?" to queryArgs
        } else {
            "SELECT id, name, place_id FROM streets WHERE norm >= ? AND norm < ? LIMIT ?" to
                arrayOf(lower, upper, STREET_CANDIDATE_CAP.toString())
        }
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<StreetCandidate>(c.count)
            while (c.moveToNext()) {
                out += StreetCandidate(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))
            }
            out
        }
    }

    private data class AddressRow(val displayHouseNumber: String, val lat: Long, val lon: Long, val postcode: String?)

    /**
     * [hn_norm] exact match first; a prefix match only when nothing matches
     * exactly. `addresses` has no primary key on `(street_id, hn_norm)` - two
     * OSM address objects can legitimately share one (a building split into
     * several entries in the source data) - so more than one row can come
     * back for what looks like a single house number; that is real data, not
     * a bug, and each becomes its own [Place] with its own coordinates.
     */
    private fun addressRows(db: SQLiteDatabase, streetId: Long, hnNorm: String, limit: Int): List<AddressRow> {
        if (limit <= 0) return emptyList()
        val exact = queryAddressRows(
            db,
            where = "street_id = ? AND hn_norm = ?",
            args = arrayOf(streetId.toString(), hnNorm),
            orderBy = "housenumber",
            limit = limit,
        )
        if (exact.isNotEmpty()) return exact
        val (lower, upper) = prefixBounds(hnNorm)
        return queryAddressRows(
            db,
            where = "street_id = ? AND hn_norm >= ? AND hn_norm < ?",
            args = arrayOf(streetId.toString(), lower, upper),
            orderBy = "hn_norm, housenumber",
            limit = limit,
        )
    }

    private fun queryAddressRows(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
        orderBy: String,
        limit: Int,
    ): List<AddressRow> {
        val sql = "SELECT hn_norm, housenumber, lat, lon, postcode FROM addresses WHERE $where ORDER BY $orderBy LIMIT ?"
        return db.rawQuery(sql, args + limit.toString()).use { c ->
            val out = ArrayList<AddressRow>(c.count)
            while (c.moveToNext()) {
                // housenumber is NOT NULL in the current schema (always the
                // original-casing display text - see build_places.py's
                // write_sqlite); the null check is a defensive fallback to
                // hn_norm only, never expected to trigger against a file this
                // pipeline actually produced.
                val housenumber = if (c.isNull(1)) null else c.getString(1)
                out += AddressRow(
                    displayHouseNumber = housenumber ?: c.getString(0),
                    lat = c.getLong(2),
                    lon = c.getLong(3),
                    postcode = if (c.isNull(4)) null else c.getString(4),
                )
            }
            out
        }
    }

    // ---- shared -----------------------------------------------------------

    private data class IntBounds(val minLat: Long, val maxLat: Long, val minLon: Long, val maxLon: Long)

    private fun boundingBox(near: GeoPoint, radiusMeters: Double): IntBounds {
        val latDelta = radiusMeters / Geo.METERS_PER_DEG_LAT
        val lonDelta = radiusMeters / Geo.metersPerDegLon(near.latitude)
        return IntBounds(
            minLat = ((near.latitude - latDelta) * COORD_SCALE).toLong(),
            maxLat = ((near.latitude + latDelta) * COORD_SCALE).toLong(),
            minLon = ((near.longitude - lonDelta) * COORD_SCALE).toLong(),
            maxLon = ((near.longitude + lonDelta) * COORD_SCALE).toLong(),
        )
    }

    /**
     * `norm >= lower AND norm < upper` for a prefix scan over a `TEXT`
     * BINARY-collation index - `1.Doku/Ortssuche.md` §2's "Präfix-Suche"
     * note. Appending `￿` (encoded as the 3-byte UTF-8 sequence the
     * doc's `X'ffff'` hex literal also names) puts the upper bound above any
     * string sharing the prefix, without a `LIKE`.
     */
    private fun prefixBounds(prefix: String): Pair<String, String> = prefix to (prefix + '￿')

    companion object {
        private const val TAG = "PlaceSearch"
        const val SUFFIX = ".places.sqlite"
        private const val COORD_SCALE = 1_000_000.0
        private const val NEARBY_RADIUS_METERS = 25_000.0
        private const val NEARBY_OVERFETCH = 3
        private const val STREET_CANDIDATE_CAP = 5
    }
}
