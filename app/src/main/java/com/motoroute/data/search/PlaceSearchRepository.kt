package com.motoroute.data.search

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** How far along the place index is. */
sealed interface IndexState {
    data object Idle : IndexState

    /**
     * Loaded and ready to search. [hasAddressIndex] is false when no
     * downloaded region has shipped a `.places.sqlite` yet (see
     * [SqlitePlaceIndex]) - towns and villages still search fine from the
     * bundled/TSV index, but streets and house numbers do not. A small hint
     * in the search box tells the rider why (`1.Doku/Ortssuche.md` §"App-Seite").
     */
    data class Ready(val places: Int, val hasAddressIndex: Boolean) : IndexState
}

/**
 * Offline destination search.
 *
 * Towns and villages come from a bundled TSV (`catalog/places_de.tsv`) plus
 * any `.places` file a map download drops next to its map. Streets and house
 * numbers come from [SqlitePlaceIndex], which reads the `<region-id>
 * .places.sqlite` file a region download adds once one exists for that region
 * (`tools/pipeline/bin/build_places.py`, wave 6.4a) - see `1.Doku/
 * Ortssuche.md`.
 *
 * There used to be a third source here: a live scan of the rider's Mapsforge
 * `.map` tiles for streets and POIs near the map centre. That reader
 * ([MapPlaceReader], now deleted) has been dead code since map rendering
 * moved to PMTiles - downloaded regions stopped shipping `.map` files, so
 * `mapFiles().filter { it.extension == "map" }` was always empty in practice.
 * [SqlitePlaceIndex] is its replacement, built for exactly the street/address
 * search that scan used to attempt live.
 */
class PlaceSearchRepository(
    private val mapFiles: () -> List<File>,
    @Suppress("UNUSED_PARAMETER") cacheDir: File,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val basePlacesProvider: (() -> List<Place>)? = null,
    private val placesFiles: (() -> List<File>)? = null,
    /** Street/address lookups; null in tests that only care about the TSV/base index. */
    private val sqliteIndex: PlaceIndexSource? = null,
    /**
     * Where a search's timing goes - `android.util.Log.d("PlaceSearch", _)` in
     * production (wired in [com.motoroute.di.AppContainer]), a no-op by
     * default so this class stays Android-free and compiles under
     * `tools/verifier` like the rest of the routing core.
     */
    private val logger: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow<IndexState>(IndexState.Idle)
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private val places = ArrayList<Place>()
    private val seen = HashSet<String>()

    val size: Int get() = synchronized(places) { places.size }

    /** Adds places to the index, e.g. from an external source or test fixture. */
    fun loadPlaces(list: List<Place>) {
        addAll(list)
        if (size > 0 && _state.value is IndexState.Idle) updateState()
    }

    /** Loads the bundled/TSV town index and refreshes whether an address index exists. */
    fun ensureIndex() {
        if (_state.value !is IndexState.Idle) return
        loadBaseAndPlacesFiles()
        updateState()
    }

    private fun updateState() {
        // A repository built without a SqlitePlaceIndex at all (most unit
        // tests) has nothing to say about address coverage, so it does not
        // downgrade to "no address index" - only a repository that genuinely
        // has the capability but found no `.places.sqlite` file does.
        val hasAddressIndex = sqliteIndex?.hasIndex ?: true
        _state.value = IndexState.Ready(size, hasAddressIndex)
    }

    private fun loadBaseAndPlacesFiles() {
        basePlacesProvider?.invoke()?.let { addAll(it) }

        placesFiles?.invoke()?.forEach { file ->
            if (file.isFile) addAll(readPlaces(file))
        }

        // Sibling ".places" TSV files next to whatever mapFiles() points at
        // (map or pmtiles) - e.g. a hand-imported "de-by.places" alongside
        // "de-by.pmtiles". Downloaded regions supply the same thing through
        // [placesFiles] already; this catches a manual import.
        val candidateDirs = mapFiles().mapNotNull { it.parentFile }.distinct()
        for (dir in candidateDirs) {
            dir.listFiles { f -> f.isFile && f.extension.equals("places", ignoreCase = true) }
                ?.forEach { file -> addAll(readPlaces(file)) }
        }
    }

    /** Called when maps, regions or address-index files are added or removed. */
    fun invalidate() {
        sqliteIndex?.invalidate()
        synchronized(places) {
            places.clear()
            seen.clear()
        }
        _state.value = IndexState.Idle
    }

    /**
     * Ranked matches for [query]: towns/villages from the bundled index,
     * streets and house numbers from [sqliteIndex] once a region has one.
     *
     * Ranking (`1.Doku/Ortssuche.md` §"App-Seite"): an exact place match
     * outranks a street found within a named place, which outranks a street
     * found only by proximity to [near], which outranks a bare address match
     * - *unless* the query actually carried a house number, in which case the
     * address jumps to the very top: typing one is unambiguous intent.
     */
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(dispatcher) {
        val normalised = PlaceQuery.normalise(query)
        if (normalised.isEmpty()) return@withContext emptyList()
        val startedAt = System.currentTimeMillis()

        val parsed = QueryParser.parse(query)

        val fromIndex = synchronized(places) { places.toList() }
            .mapNotNull { place ->
                val rank = PlaceQuery.rank(place, normalised, near?.let { distance(it, place) })
                if (rank == 0) null else place to rank
            }

        val fromSqlite = sqliteIndex?.let { index ->
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { sqliteMatches(index, parsed, near) }
        }.orEmpty()

        val combined = LinkedHashMap<String, Pair<Place, Int>>()
        (fromIndex + fromSqlite).forEach { (place, rank) ->
            val existing = combined[place.dedupeKey]
            if (existing == null || existing.second < rank) {
                combined[place.dedupeKey] = place to rank
            }
        }

        val result = combined.values
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { it.first }

        logger(
            "search '$query' -> ${result.size} results (${fromIndex.size} base, ${fromSqlite.size} sqlite) " +
                "in ${System.currentTimeMillis() - startedAt} ms",
        )
        result
    }

    /** [sqliteIndex] candidates for one search, each tagged with its rank. */
    private fun sqliteMatches(
        index: PlaceIndexSource,
        parsed: ParsedQuery,
        near: GeoPoint?,
    ): List<Pair<Place, Int>> {
        val out = ArrayList<Pair<Place, Int>>()
        val streetText = parsed.street

        // a) places - an exact/prefix hit here already outranks everything
        // below through PlaceQuery.rank's own weighting (a city's kind weight
        // plus a full-text match dwarfs the street/address tiers), so no
        // extra boost is needed to satisfy "exact place beats the rest".
        parsed.place?.let(PlaceQuery::normalise)?.takeIf { it.isNotEmpty() }?.let { placeNorm ->
            index.places(placeNorm, MAX_RESULTS).forEach { place ->
                val rank = PlaceQuery.rank(place, placeNorm, near?.let { distance(it, place) })
                if (rank > 0) out += place to rank
            }
        }

        // b) streets - scoped to a named place when the query structurally
        // named one (not the ambiguous single-word case, where QueryParser
        // sets place == street and scoping "by itself" would be meaningless),
        // else ranked by proximity to [near] when there is one.
        if (streetText != null) {
            val streetNorm = PlaceQuery.normalise(streetText)
            if (streetNorm.isNotEmpty()) {
                val placeNorm = parsed.place
                    ?.takeIf { it != streetText }
                    ?.let(PlaceQuery::normalise)
                when {
                    placeNorm != null -> index.streets(streetNorm, placeNorm = placeNorm, limit = MAX_RESULTS)
                        .forEach { out += it to (RANK_STREET_IN_NAMED_PLACE + PlaceQuery.score(it.name, streetNorm)) }
                    near != null -> index.streets(streetNorm, near = near, limit = MAX_RESULTS)
                        .forEach { out += it to (RANK_STREET_NEAR_MAP + PlaceQuery.score(it.name, streetNorm)) }
                }
            }

            // c) addresses - only once the rider has actually typed a house
            // number; a bare street prefix is covered by (b) already, and an
            // address without a house number is not a more useful result than
            // the street it sits on.
            val houseNumber = parsed.houseNumber
            if (houseNumber != null && streetNorm.isNotEmpty()) {
                val hnNorm = QueryParser.normaliseHouseNumber(houseNumber)
                val placeNorm = parsed.place?.takeIf { it != streetText }?.let(PlaceQuery::normalise)
                if (hnNorm.isNotEmpty()) {
                    index.addresses(streetNorm, hnNorm, placeNorm = placeNorm, near = near, limit = MAX_RESULTS)
                        .forEach { out += it to RANK_ADDRESS_WITH_HOUSE_NUMBER }
                }
            }
        }

        return out
    }

    /** Where the map data sits, so the map can open somewhere useful with no GPS. */
    suspend fun mapStartPosition(): GeoPoint? = withContext(dispatcher) {
        synchronized(places) { places.firstOrNull()?.point }
    }

    private fun addAll(list: List<Place>) {
        synchronized(places) {
            list.forEach { if (seen.add(it.dedupeKey)) places += it }
        }
    }

    private fun distance(from: GeoPoint, place: Place): Double =
        Geo.distanceMeters(from, place.point)

    companion object {
        const val MAX_RESULTS = 30

        /** A search never blocks the UI longer than this, even against a huge/corrupt index. */
        const val SEARCH_TIMEOUT_MS = 3_000L

        private const val RANK_STREET_IN_NAMED_PLACE = 260
        private const val RANK_STREET_NEAR_MAP = 140

        /**
         * Above any base/TSV place match (which tops out around match(100)*4 +
         * CITY.weight(60) + proximity(25) = 485) - see the class doc: typing a
         * house number is unambiguous intent, so the address wins outright.
         */
        private const val RANK_ADDRESS_WITH_HOUSE_NUMBER = 900

        fun parsePlaceLine(line: String): Place? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val parts = trimmed.split('\t')
            if (parts.size < 4) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val kindStr = parts[1].trim()
            val kind = PlaceKind.entries.firstOrNull { it.name.equals(kindStr, ignoreCase = true) }
                ?: PlaceKind.ofPlaceTag(kindStr.lowercase())
                ?: PlaceKind.TOWN
            val lat = parts[2].trim().toDoubleOrNull() ?: return null
            val lon = parts[3].trim().toDoubleOrNull() ?: return null
            val detail = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
            return Place(name, kind, lat, lon, detail)
        }

        fun readPlaces(reader: java.io.BufferedReader): List<Place> =
            reader.lineSequence().mapNotNull(::parsePlaceLine).toList()

        fun readPlaces(file: File): List<Place> =
            if (!file.isFile) emptyList() else file.bufferedReader().use(::readPlaces)

        fun readPlaces(stream: java.io.InputStream): List<Place> =
            stream.bufferedReader().use(::readPlaces)
    }
}
