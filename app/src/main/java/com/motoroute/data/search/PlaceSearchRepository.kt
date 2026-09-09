package com.motoroute.data.search

import com.motoroute.data.model.GeoPoint
import com.motoroute.domain.geo.Geo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** How far along the place index is. */
sealed interface IndexState {
    data object NoMaps : IndexState
    data object Idle : IndexState
    data class Building(val fraction: Float, val places: Int) : IndexState
    data class Ready(val places: Int) : IndexState
}

/**
 * Offline destination search.
 *
 * The index is built from the maps the rider already downloaded, in two passes.
 * The coarse pass reads the low zoom levels and has every city and town in a
 * couple of seconds, so the search box is useful almost immediately. The
 * thorough pass then walks the detailed levels for villages, hamlets and
 * suburbs; it takes a while on a whole federal state, runs in the background,
 * and its result is written next to the map so it only ever happens once.
 *
 * Streets are not indexed at all - they are scanned live around wherever the
 * rider is looking, because a street name is only a useful destination when it
 * is a nearby one, and indexing every street in Niedersachsen would cost more
 * than it is worth.
 */
class PlaceSearchRepository(
    private val mapFiles: () -> List<File>,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow<IndexState>(IndexState.Idle)
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private val places = ArrayList<Place>()
    private val seen = HashSet<String>()
    private var job: Job? = null
    private var indexedFiles: Set<String> = emptySet()

    val size: Int get() = synchronized(places) { places.size }

    /** Builds the index if it is not already there for exactly these maps. */
    fun ensureIndex() {
        val files = mapFiles()
        if (files.isEmpty()) {
            _state.value = IndexState.NoMaps
            return
        }
        val fingerprint = files.map(::fingerprintOf).toSet()
        if (fingerprint == indexedFiles && job?.isActive != true) return
        if (job?.isActive == true) return

        indexedFiles = fingerprint
        synchronized(places) {
            places.clear()
            seen.clear()
        }
        job = scope.launch(dispatcher) { build(files) }
    }

    /** Called when maps are added or removed. */
    fun invalidate() {
        job?.cancel()
        job = null
        indexedFiles = emptySet()
        synchronized(places) {
            places.clear()
            seen.clear()
        }
        _state.value = IndexState.Idle
    }

    /**
     * Ranked matches for [query].
     *
     * Indexed places answer instantly; nearby streets and points of interest
     * are scanned from the map on the spot, under a timeout, so a slow scan
     * degrades into "no streets found" rather than a frozen search box.
     */
    suspend fun search(query: String, near: GeoPoint?): List<Place> {
        val normalised = PlaceQuery.normalise(query)
        if (normalised.isEmpty()) return emptyList()

        val fromIndex = synchronized(places) { places.toList() }
            .mapNotNull { place ->
                val rank = PlaceQuery.rank(place, normalised, near?.let { distance(it, place) })
                if (rank == 0) null else place to rank
            }

        val nearby = if (near != null) scanNearby(normalised, near) else emptyList()

        val combined = LinkedHashMap<String, Pair<Place, Int>>()
        (fromIndex + nearby).forEach { (place, rank) ->
            val existing = combined[place.dedupeKey]
            if (existing == null || existing.second < rank) {
                combined[place.dedupeKey] = place to rank
            }
        }

        return combined.values
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { it.first }
    }

    /** Where the map data sits, so the map can open somewhere useful with no GPS. */
    suspend fun mapStartPosition(): GeoPoint? = withContext(dispatcher) {
        mapFiles().firstNotNullOfOrNull { file ->
            MapPlaceReader(file).use { reader ->
                reader.startPosition()?.let { GeoPoint(it.latitude, it.longitude) }
            }
        }
    }

    private suspend fun scanNearby(
        normalised: String,
        near: GeoPoint,
    ): List<Pair<Place, Int>> = withTimeoutOrNull(NEARBY_TIMEOUT_MS) {
        withContext(dispatcher) {
            val found = ArrayList<Pair<Place, Int>>()
            mapFiles().forEach { file ->
                MapPlaceReader(file).use { reader ->
                    if (!reader.isUsable) return@use
                    reader.searchNearby(
                        normalisedQuery = normalised,
                        centreLat = near.latitude,
                        centreLon = near.longitude,
                        radiusMeters = NEARBY_RADIUS_METERS,
                        limit = NEARBY_LIMIT,
                    ) { place ->
                        val rank = PlaceQuery.rank(place, normalised, distance(near, place))
                        if (rank > 0) found += place to rank
                    }
                }
            }
            found
        }
    } ?: emptyList()

    private suspend fun build(files: List<File>) {
        var restoredAll = true
        files.forEach { file ->
            val cached = readCache(file)
            if (cached == null) {
                restoredAll = false
            } else {
                addAll(cached)
            }
        }
        if (restoredAll) {
            _state.value = IndexState.Ready(size)
            return
        }

        _state.value = IndexState.Building(0f, size)

        // Each map's own places are kept apart from the rest, so the file
        // written next to a map is that map's index and nothing else - a
        // deleted region must not leave its towns behind in a neighbour's
        // cache.
        val perFile = HashMap<String, MutableList<Place>>()

        // Coarse pass: cities and towns, over in seconds, so the search box
        // starts answering while the thorough pass is still running.
        files.forEach { file ->
            val found = perFile.getOrPut(file.name) { mutableListOf() }
            MapPlaceReader(file).use { reader ->
                reader.scanPlaces(COARSE_ZOOM) {
                    found += it
                    add(it)
                }
            }
            _state.value = IndexState.Building(COARSE_SHARE, size)
        }

        // Thorough pass: villages, hamlets, suburbs.
        files.forEachIndexed { index, file ->
            val share = 1f / files.size
            val found = perFile.getOrPut(file.name) { mutableListOf() }
            MapPlaceReader(file).use { reader ->
                reader.scanPlaces(
                    zoom = FINE_ZOOM,
                    onProgress = { done, total ->
                        val fraction = COARSE_SHARE +
                            (1f - COARSE_SHARE) * share * (index + done.toFloat() / total)
                        _state.value = IndexState.Building(fraction.coerceIn(0f, 1f), size)
                    },
                ) {
                    found += it
                    add(it)
                }
            }
            writeCache(file, found)
        }

        _state.value = IndexState.Ready(size)
    }

    private fun add(place: Place) {
        synchronized(places) {
            if (seen.add(place.dedupeKey)) places += place
        }
    }

    private fun addAll(list: List<Place>) {
        synchronized(places) {
            list.forEach { if (seen.add(it.dedupeKey)) places += it }
        }
    }

    private fun distance(from: GeoPoint, place: Place): Double =
        Geo.distanceMeters(from, place.point)

    // ---- cache -----------------------------------------------------------

    private fun fingerprintOf(file: File): String =
        "${file.name}-${file.length()}-${file.lastModified()}"

    private fun cacheFileOf(file: File): File =
        File(cacheDir, fingerprintOf(file).replace(Regex("[^A-Za-z0-9._-]"), "_") + ".places")

    private fun readCache(file: File): List<Place>? = runCatching {
        val cache = cacheFileOf(file)
        if (!cache.isFile) return null
        cache.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val kind = runCatching { PlaceKind.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            Place(
                name = parts[0],
                kind = kind,
                latitude = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                longitude = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
            )
        }
    }.getOrNull()

    private fun writeCache(file: File, places: List<Place>) {
        runCatching {
            cacheDir.mkdirs()
            // One index per map file version; drop the previous one so a
            // re-downloaded map does not leave a stale index behind.
            cacheDir.listFiles()
                .orEmpty()
                .filter { it.name.startsWith(file.name.substringBeforeLast('.')) }
                .filterNot { it.name == cacheFileOf(file).name }
                .forEach { it.delete() }

            val text = places.joinToString("\n") {
                "${it.name.replace('\t', ' ')}\t${it.kind.name}\t${it.latitude}\t${it.longitude}"
            }
            cacheFileOf(file).writeText(text)
        }
    }

    private companion object {
        /** Cities and towns live in the lowest zoom interval of the map. */
        const val COARSE_ZOOM: Byte = 8

        /** Villages, hamlets and suburbs only appear in the detailed one. */
        const val FINE_ZOOM: Byte = 12

        const val COARSE_SHARE = 0.05f

        const val NEARBY_RADIUS_METERS = 25_000.0
        const val NEARBY_LIMIT = 40
        const val NEARBY_TIMEOUT_MS = 4_000L
        const val MAX_RESULTS = 30
    }
}
