package com.motoroute.data.search

/**
 * What a rider typed, split into the pieces an address lookup needs.
 *
 * Android-free on purpose (see `tools/verifier/build.gradle.kts`): parsing
 * "Hauptstr. 12 Hannover" into its parts is plain text handling, so it is
 * compiled and tested on the JVM alongside the rest of the routing core, not
 * only inside an instrumented Android build.
 *
 * Fields are independently nullable, not a tagged union - a plain, un-numbered
 * word like "Hannover" or "Hauptstraße" could name either a place or a street,
 * and this parser does not guess which from the text alone (no gazetteer, no
 * heuristics on word endings). It sets **both** [place] and [street] to the
 * same text in that case and leaves the decision to the data: [SqlitePlaceIndex]
 * queries both tables, and whichever one actually has a matching row wins.
 * Once a house number appears, the ambiguity is gone - a house number can only
 * follow a street - so [place] and [street] separate cleanly.
 */
data class ParsedQuery(
    val place: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val postcode: String? = null,
) {
    val isEmpty: Boolean
        get() = place == null && street == null && houseNumber == null && postcode == null
}

/**
 * Splits a search box query into [ParsedQuery]'s pieces.
 *
 * Recognised shapes (see `1.Doku/Ortssuche.md` §"App-Seite" and
 * `QueryParserTest`):
 *  - `Hannover` - a plain place-or-street name.
 *  - `Hauptstraße` - ditto, no house number.
 *  - `Hauptstraße 12` / `Hauptstraße 12a` - street plus house number.
 *  - `Hauptstr. 12 Hannover` - street (abbreviated), house number, place.
 *  - `Hannover, Hauptstraße 12` - place and street separated by a comma.
 *  - `30159 Hannover` - postcode plus place (the postcode is optional
 *    everywhere else, but when a bare 4-5 digit token opens the query it can
 *    only be a postcode - German street numbering never starts a query).
 *
 * Abbreviations `str.` / `Str.` / `-str.` expand to `straße` before anything
 * is normalised - [PlaceQuery.normalise] turns the `.` into a space rather
 * than understanding it as an abbreviation, so "Hauptstr." would otherwise
 * normalise to "hauptstr" and never match a `streets.norm` of "hauptstrasse".
 */
object QueryParser {

    private val WHITESPACE = Regex("\\s+")
    private val LEADING_POSTCODE = Regex("^(\\d{4,5})(\\s+(.*))?$")
    private val HOUSE_NUMBER = Regex("(?i)^\\d+[a-zäöüß]?$")
    private val STREET_ABBREVIATION = Regex("(?i)[- ]?str\\.$")

    fun parse(raw: String): ParsedQuery {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }) return ParsedQuery()

        val commaParts = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (commaParts.size >= 2) {
            val (placePostcode, placeText) = splitLeadingPostcode(commaParts[0])
            val rest = splitStreetHouseNumberPlace(commaParts.drop(1).joinToString(" "))
            return ParsedQuery(
                place = placeText.ifBlank { null } ?: rest.trailingPlace,
                street = rest.street,
                houseNumber = rest.houseNumber,
                postcode = placePostcode,
            )
        }

        val (postcode, remainder) = splitLeadingPostcode(trimmed)
        if (postcode != null) {
            return ParsedQuery(postcode = postcode, place = remainder.ifBlank { null })
        }

        val split = splitStreetHouseNumberPlace(trimmed)
        return if (split.houseNumber != null) {
            ParsedQuery(place = split.trailingPlace, street = split.street, houseNumber = split.houseNumber)
        } else {
            // Ambiguous plain text - see the class doc on [ParsedQuery.place]/[street].
            ParsedQuery(place = trimmed, street = split.street)
        }
    }

    /** `"30159 Hannover"` -> `"30159" to "Hannover"`; anything else -> `null to text`. */
    private fun splitLeadingPostcode(text: String): Pair<String?, String> {
        val match = LEADING_POSTCODE.matchEntire(text.trim()) ?: return null to text
        val postcode = match.groupValues[1]
        val remainder = match.groupValues.getOrNull(3).orEmpty().trim()
        return postcode to remainder
    }

    private data class StreetSplit(val street: String?, val houseNumber: String?, val trailingPlace: String?)

    /**
     * Finds the first token shaped like a house number and splits around it:
     * everything before is the street, everything after is a trailing place
     * name. With no such token the whole text is handed back as [street]
     * (abbreviation-expanded) and [houseNumber] is null.
     */
    private fun splitStreetHouseNumberPlace(text: String): StreetSplit {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return StreetSplit(null, null, null)

        val hnIndex = tokens.indexOfFirst { HOUSE_NUMBER.matches(it) }
        if (hnIndex < 0) {
            return StreetSplit(street = expandStreetAbbreviation(text.trim()), houseNumber = null, trailingPlace = null)
        }

        val streetTokens = tokens.subList(0, hnIndex)
        val placeTokens = tokens.subList(hnIndex + 1, tokens.size)
        return StreetSplit(
            street = streetTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let(::expandStreetAbbreviation),
            houseNumber = tokens[hnIndex],
            trailingPlace = placeTokens.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    /** `"Hauptstr."` / `"Haupt-str."` -> `"Hauptstraße"`; anything else unchanged. */
    fun expandStreetAbbreviation(text: String): String =
        if (STREET_ABBREVIATION.containsMatchIn(text)) STREET_ABBREVIATION.replace(text, "straße") else text

    /** The app-side equivalent of the pipeline's `hn_norm`: lower case, no spaces. */
    fun normaliseHouseNumber(houseNumber: String): String =
        houseNumber.trim().lowercase().replace(" ", "")
}
