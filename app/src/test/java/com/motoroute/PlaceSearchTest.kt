package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.search.Place
import com.motoroute.data.search.PlaceIndexSource
import com.motoroute.data.search.PlaceKind
import com.motoroute.data.search.PlaceQuery
import com.motoroute.data.search.PlaceSearchRepository
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline search has to survive being typed at by someone in a hurry: no
 * umlauts, no capitals, half the name.
 */
class PlaceSearchTest {

    @Test
    fun `umlauts fold to what a phone keyboard produces in a hurry`() {
        assertEquals("goettingen", PlaceQuery.normalise("Göttingen"))
        assertEquals("muenster", PlaceQuery.normalise("Münster"))
        assertEquals("strasse", PlaceQuery.normalise("Straße"))
        assertEquals("bad muender am deister", PlaceQuery.normalise("Bad Münder am Deister"))
    }

    @Test
    fun `a prefix beats a word start beats a substring`() {
        val exact = PlaceQuery.score("Hannover", "hannover")
        val prefix = PlaceQuery.score("Hannoversch Münden", "hannover")
        val word = PlaceQuery.score("Bad Hannover", "hannover")
        val inside = PlaceQuery.score("Schannoverweg", "hannover")

        assertTrue(exact > prefix)
        assertTrue(prefix > word)
        assertTrue(word > inside)
        assertEquals(0, PlaceQuery.score("Bremen", "hannover"))
    }

    @Test
    fun `a town outranks a street of the same name`() {
        val query = "lindenberg"
        val town = Place("Lindenberg", PlaceKind.TOWN, 52.0, 9.0)
        val street = Place("Lindenberg", PlaceKind.STREET, 52.0, 9.0)

        assertTrue(
            PlaceQuery.rank(town, query, 40_000.0) >
                PlaceQuery.rank(street, query, 40_000.0),
        )
    }

    @Test
    fun `distance only breaks ties, it does not overrule the name`() {
        val query = "hannover"
        val far = Place("Hannover", PlaceKind.CITY, 52.37, 9.73)
        val near = Place("Hannoversch Münden", PlaceKind.TOWN, 51.41, 9.65)

        // The far city still wins over the near town whose name only starts the same.
        assertTrue(
            PlaceQuery.rank(far, query, 300_000.0) > PlaceQuery.rank(near, query, 5_000.0),
        )
    }

    @Test
    fun `a place that does not match ranks zero whatever its kind`() {
        val place = Place("Celle", PlaceKind.CITY, 52.6, 10.1)
        assertEquals(0, PlaceQuery.rank(place, "hannover", 1_000.0))
    }

    @Test
    fun `the same village found in two tiles deduplicates`() {
        val a = Place("Eldagsen", PlaceKind.VILLAGE, 52.1801, 9.6402)
        val b = Place("Eldagsen", PlaceKind.VILLAGE, 52.1802, 9.6403)
        val elsewhere = Place("Eldagsen", PlaceKind.VILLAGE, 51.0, 9.0)

        assertEquals(a.dedupeKey, b.dedupeKey)
        assertNotEquals(a.dedupeKey, elsewhere.dedupeKey)
    }

    @Test
    fun `a place carries a usable route destination`() {
        val place = Place("Bad Pyrmont", PlaceKind.TOWN, 51.9861, 9.2536)
        val point = GeoPoint(51.9861, 9.2536)
        assertTrue(Geo.distanceMeters(place.point, point) < 1.0)
    }

    @Test
    fun `readPlaces parses tab-separated place lines correctly`() {
        val tsv = """
            # Kommentarzeile
            Berlin	CITY	52.5200	13.4050	Hauptstadt
            München	city	48.1351	11.5820
            Nürburgring	POI	50.3342	6.9427	Eifel / Nordschleife
            
            Bad Pyrmont	town	51.9861	9.2536
        """.trimIndent()

        val parsed = com.motoroute.data.search.PlaceSearchRepository.readPlaces(tsv.byteInputStream())
        assertEquals(4, parsed.size)

        assertEquals("Berlin", parsed[0].name)
        assertEquals(PlaceKind.CITY, parsed[0].kind)
        assertEquals(52.5200, parsed[0].latitude, 0.0001)
        assertEquals("Hauptstadt", parsed[0].detail)

        assertEquals("München", parsed[1].name)
        assertEquals(PlaceKind.CITY, parsed[1].kind)

        assertEquals("Nürburgring", parsed[2].name)
        assertEquals(PlaceKind.POI, parsed[2].kind)
        assertEquals("Eifel / Nordschleife", parsed[2].detail)

        assertEquals("Bad Pyrmont", parsed[3].name)
        assertEquals(PlaceKind.TOWN, parsed[3].kind)
    }

    @Test
    fun `bundled base index enables immediate search without any downloaded map`() = kotlinx.coroutines.test.runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("place-search-test").toFile()
        val basePlaces = listOf(
            Place("Berlin", PlaceKind.CITY, 52.52, 13.40, "Hauptstadt"),
            Place("München", PlaceKind.CITY, 48.14, 11.58, "Bayern"),
            Place("Hamburg", PlaceKind.CITY, 53.55, 9.99, "Hansestadt"),
        )

        val repo = com.motoroute.data.search.PlaceSearchRepository(
            mapFiles = { emptyList() },
            cacheDir = tempDir,
            scope = this,
            basePlacesProvider = { basePlaces },
        )

        repo.ensureIndex()

        val state = repo.state.value
        assertTrue("State should be Ready even without maps", state is com.motoroute.data.search.IndexState.Ready)
        assertEquals(3, (state as com.motoroute.data.search.IndexState.Ready).places)

        val results = repo.search("muenchen", near = null)
        assertEquals(1, results.size)
        assertEquals("München", results.single().name)

        val startPos = repo.mapStartPosition()
        org.junit.Assert.assertNotNull(startPos)
        assertEquals(52.52, startPos!!.latitude, 0.01)
    }

    @Test
    fun `places file alongside pmtiles is read directly without mapsforge map`() = kotlinx.coroutines.test.runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("place-search-pmtiles").toFile()
        val pmtiles = File(tempDir, "de-by.pmtiles").apply { writeText("dummy pmtiles content") }
        val placesFile = File(tempDir, "de-by.places").apply {
            writeText("Garmisch-Partenkirchen\tTOWN\t47.4917\t11.0955\tAlpen\nFüssen\tTOWN\t47.5696\t10.7004\tAllgäu\n")
        }

        val repo = com.motoroute.data.search.PlaceSearchRepository(
            mapFiles = { listOf(pmtiles) },
            cacheDir = File(tempDir, "cache"),
            scope = this,
            placesFiles = { listOf(placesFile) },
        )

        repo.ensureIndex()

        val state = repo.state.value
        assertTrue(state is com.motoroute.data.search.IndexState.Ready)
        assertEquals(2, (state as com.motoroute.data.search.IndexState.Ready).places)

        val results = repo.search("garmisch", near = null)
        assertEquals(1, results.size)
        assertEquals("Garmisch-Partenkirchen", results.single().name)
    }

    // ---- SqlitePlaceIndex integration (via a fake, see PlaceIndexSource) --

    @Test
    fun `an address with a house number ranks above an exact place match`() = kotlinx.coroutines.test.runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("place-search-address").toFile()
        // A place happens to share its exact name with the query text - an
        // exact CITY match like this would otherwise win outright (see
        // PlaceQuery.rank), which is exactly what typing a house number
        // should override.
        val basePlaces = listOf(Place("Hauptstraße 12", PlaceKind.CITY, 52.0, 9.0))
        val address = Place(
            "Hauptstraße 12", PlaceKind.ADDRESS, 52.37, 9.73,
            detail = "30159 Hannover",
        )
        val fake = FakePlaceIndexSource(addressesResult = listOf(address))

        val repo = PlaceSearchRepository(
            mapFiles = { emptyList() },
            cacheDir = tempDir,
            scope = this,
            basePlacesProvider = { basePlaces },
            sqliteIndex = fake,
        )
        repo.ensureIndex()

        val results = repo.search("Hauptstraße 12", near = null)
        assertEquals("Hauptstraße 12", results.first().name)
        assertEquals(PlaceKind.ADDRESS, results.first().kind)
    }

    @Test
    fun `a comma-separated place scopes the street search to that place, not a bounding box`() =
        kotlinx.coroutines.test.runTest {
            val tempDir = java.nio.file.Files.createTempDirectory("place-search-street-scope").toFile()
            val fake = FakePlaceIndexSource(
                streetsResult = listOf(Place("Hauptstraße", PlaceKind.STREET, 52.37, 9.73, detail = "Hannover")),
            )
            val repo = PlaceSearchRepository(
                mapFiles = { emptyList() },
                cacheDir = tempDir,
                scope = this,
                sqliteIndex = fake,
            )
            repo.ensureIndex()

            repo.search("Hannover, Hauptstraße", near = GeoPoint(52.0, 9.0))

            assertEquals("hannover", fake.lastStreetsPlaceNorm)
            assertNull("a named place must not also fall back to a bounding box", fake.lastStreetsNear)
        }

    @Test
    fun `a single ambiguous word searches streets near the map, not within itself as a place`() =
        kotlinx.coroutines.test.runTest {
            val tempDir = java.nio.file.Files.createTempDirectory("place-search-ambiguous").toFile()
            val fake = FakePlaceIndexSource(
                streetsResult = listOf(Place("Hauptstraße", PlaceKind.STREET, 52.37, 9.73)),
            )
            val repo = PlaceSearchRepository(
                mapFiles = { emptyList() },
                cacheDir = tempDir,
                scope = this,
                sqliteIndex = fake,
            )
            repo.ensureIndex()
            val near = GeoPoint(52.37, 9.73)

            repo.search("Hauptstraße", near = near)

            // QueryParser sets place == street == "Hauptstraße" here (genuinely
            // ambiguous, see ParsedQuery's doc) - that must not be read as an
            // instruction to scope the street search to a place of the same name.
            assertNull(fake.lastStreetsPlaceNorm)
            assertEquals(near, fake.lastStreetsNear)
        }

    @Test
    fun `no places sqlite file yet still allows the base town search`() = kotlinx.coroutines.test.runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("place-search-no-address-index").toFile()
        val fake = FakePlaceIndexSource(hasIndex = false)
        val repo = PlaceSearchRepository(
            mapFiles = { emptyList() },
            cacheDir = tempDir,
            scope = this,
            basePlacesProvider = { listOf(Place("Hannover", PlaceKind.CITY, 52.37, 9.73)) },
            sqliteIndex = fake,
        )

        repo.ensureIndex()

        val state = repo.state.value
        assertTrue(state is com.motoroute.data.search.IndexState.Ready)
        assertEquals(false, (state as com.motoroute.data.search.IndexState.Ready).hasAddressIndex)

        val results = repo.search("hannover", near = null)
        assertEquals(1, results.size)
    }

    /** A minimal stand-in for [com.motoroute.data.search.SqlitePlaceIndex] - see [PlaceIndexSource]. */
    private class FakePlaceIndexSource(
        override val hasIndex: Boolean = true,
        private val placesResult: List<Place> = emptyList(),
        private val streetsResult: List<Place> = emptyList(),
        private val addressesResult: List<Place> = emptyList(),
    ) : PlaceIndexSource {
        var lastStreetsPlaceNorm: String? = null
        var lastStreetsNear: GeoPoint? = null

        override fun places(prefixNorm: String, limit: Int): List<Place> = placesResult

        override fun streets(prefixNorm: String, placeNorm: String?, near: GeoPoint?, limit: Int): List<Place> {
            lastStreetsPlaceNorm = placeNorm
            lastStreetsNear = near
            return streetsResult
        }

        override fun addresses(
            streetNorm: String,
            hnNorm: String,
            placeNorm: String?,
            near: GeoPoint?,
            limit: Int,
        ): List<Place> = addressesResult

        override fun invalidate() = Unit
    }
}
