package com.motoroute

import com.motoroute.data.search.ParsedQuery
import com.motoroute.data.search.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The forms `1.Doku/Ortssuche.md`'s "App-Seite" section promises the search
 * box understands. Each case mirrors one bullet there.
 */
class QueryParserTest {

    @Test
    fun `a plain place name is ambiguous with a street of the same text`() {
        // Nothing in the text says "Hannover" is a place and not a street -
        // both fields carry it, and SqlitePlaceIndex lets the data decide.
        assertEquals(
            ParsedQuery(place = "Hannover", street = "Hannover"),
            QueryParser.parse("Hannover"),
        )
    }

    @Test
    fun `a plain street name is ambiguous with a place of the same text`() {
        assertEquals(
            ParsedQuery(place = "Hauptstraße", street = "Hauptstraße"),
            QueryParser.parse("Hauptstraße"),
        )
    }

    @Test
    fun `street plus house number, no place`() {
        assertEquals(
            ParsedQuery(street = "Hauptstraße", houseNumber = "12"),
            QueryParser.parse("Hauptstraße 12"),
        )
    }

    @Test
    fun `a house number with a letter suffix`() {
        assertEquals(
            ParsedQuery(street = "Hauptstraße", houseNumber = "12a"),
            QueryParser.parse("Hauptstraße 12a"),
        )
    }

    @Test
    fun `abbreviated street, house number and a trailing place`() {
        assertEquals(
            ParsedQuery(place = "Hannover", street = "Hauptstraße", houseNumber = "12"),
            QueryParser.parse("Hauptstr. 12 Hannover"),
        )
    }

    @Test
    fun `place and street separated by a comma`() {
        assertEquals(
            ParsedQuery(place = "Hannover", street = "Hauptstraße", houseNumber = "12"),
            QueryParser.parse("Hannover, Hauptstraße 12"),
        )
    }

    @Test
    fun `postcode plus place, postcode is optional everywhere else`() {
        assertEquals(
            ParsedQuery(postcode = "30159", place = "Hannover"),
            QueryParser.parse("30159 Hannover"),
        )
        // No postcode anywhere in the other forms above - all still parse fine.
        assertEquals(null, QueryParser.parse("Hauptstraße 12").postcode)
    }

    @Test
    fun `a bare postcode with nothing after it`() {
        assertEquals(ParsedQuery(postcode = "30159"), QueryParser.parse("30159"))
    }

    @Test
    fun `Str dash str and capitalised Str all expand to strasse`() {
        assertEquals("Hauptstraße", QueryParser.expandStreetAbbreviation("Hauptstr."))
        assertEquals("Hauptstraße", QueryParser.expandStreetAbbreviation("HauptStr."))
        assertEquals("Hauptstraße", QueryParser.expandStreetAbbreviation("Haupt-str."))
        assertEquals("Bahnhofstraße", QueryParser.expandStreetAbbreviation("Bahnhof str."))
    }

    @Test
    fun `a hyphenated compound street name with a house number`() {
        val parsed = QueryParser.parse("Sankt-Florian-Weg 12a")
        assertEquals("Sankt-Florian-Weg", parsed.street)
        assertEquals("12a", parsed.houseNumber)
        assertEquals(null, parsed.place)
    }

    @Test
    fun `whitespace and commas alone parse to nothing`() {
        assertEquals(ParsedQuery(), QueryParser.parse("   "))
        assertEquals(ParsedQuery(), QueryParser.parse(" , , "))
    }

    @Test
    fun `house number normalisation matches the pipeline's hn_norm`() {
        assertEquals("12a", QueryParser.normaliseHouseNumber("12 A"))
        assertEquals("12", QueryParser.normaliseHouseNumber("12"))
    }
}
