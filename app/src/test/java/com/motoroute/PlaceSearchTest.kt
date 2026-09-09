package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.search.Place
import com.motoroute.data.search.PlaceKind
import com.motoroute.data.search.PlaceQuery
import com.motoroute.domain.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
