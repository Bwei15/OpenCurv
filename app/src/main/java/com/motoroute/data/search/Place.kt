package com.motoroute.data.search

import com.motoroute.data.model.GeoPoint

/**
 * Something a rider can search for and ride to.
 *
 * Everything here comes out of the `.map` file already on the phone: Mapsforge
 * maps carry the place nodes and the street names they draw with, which is
 * exactly the index an offline geocoder needs. No network, no separate
 * database, no extra download.
 */
data class Place(
    val name: String,
    val kind: PlaceKind,
    val latitude: Double,
    val longitude: Double,
    /** Extra line under the name: a road number, a town, a POI type. */
    val detail: String? = null,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)

    /** Stable enough to deduplicate the same village found in two tiles. */
    val dedupeKey: String
        get() = "${PlaceQuery.normalise(name)}@${round(latitude)},${round(longitude)}"

    private fun round(value: Double): Long = Math.round(value * 500.0)
}

/**
 * Ranked by how likely a rider is to mean it. A rider typing "Ha" wants
 * Hannover before Hamelspringe, and a town before a street of the same name.
 */
enum class PlaceKind(val weight: Int) {
    CITY(60),
    TOWN(50),
    VILLAGE(35),
    SUBURB(25),
    HAMLET(20),
    STREET(30),
    FUEL(15),
    POI(10),
    ;

    companion object {
        fun ofPlaceTag(value: String): PlaceKind? = when (value) {
            "city" -> CITY
            "town" -> TOWN
            "village" -> VILLAGE
            "suburb", "borough", "quarter", "city_block" -> SUBURB
            "hamlet", "isolated_dwelling", "neighbourhood", "locality" -> HAMLET
            else -> null
        }
    }
}

/**
 * Text matching for place names.
 *
 * German is the case that shapes this: "Göttingen" has to be findable by typing
 * "gottingen" or "goettingen" on a phone bouncing on a handlebar, and
 * "Bad Münder" by typing "munder". So names are folded to plain ASCII and
 * matched per word, not only from the front.
 */
object PlaceQuery {

    /** Lower case, umlauts folded, everything else reduced to single spaces. */
    fun normalise(text: String): String {
        val builder = StringBuilder(text.length)
        for (raw in text.lowercase()) {
            when (raw) {
                'ä' -> builder.append("ae")
                'ö' -> builder.append("oe")
                'ü' -> builder.append("ue")
                'ß' -> builder.append("ss")
                'á', 'à', 'â', 'å' -> builder.append('a')
                'é', 'è', 'ê' -> builder.append('e')
                'í', 'ì', 'î' -> builder.append('i')
                'ó', 'ò', 'ô', 'ø' -> builder.append('o')
                'ú', 'ù', 'û' -> builder.append('u')
                'ç' -> builder.append('c')
                'ñ' -> builder.append('n')
                else -> if (raw.isLetterOrDigit()) builder.append(raw) else builder.append(' ')
            }
        }
        return builder.toString().trim().replace(MULTI_SPACE, " ")
    }

    /**
     * How well [name] answers [normalisedQuery]; 0 means it does not.
     *
     * A prefix beats a word start beats a substring, because that is the order
     * in which a rider's own guess narrows down.
     */
    fun score(name: String, normalisedQuery: String): Int {
        if (normalisedQuery.isEmpty()) return 0
        val candidate = normalise(name)
        return when {
            candidate == normalisedQuery -> 100
            candidate.startsWith(normalisedQuery) -> 80
            candidate.split(' ').any { it.startsWith(normalisedQuery) } -> 60
            candidate.contains(normalisedQuery) -> 30
            else -> 0
        }
    }

    /**
     * Final ordering: how well the name matches, what kind of thing it is, and
     * how far away it is. Distance only breaks ties - a rider searching for
     * "Hannover" from Hamburg still means Hannover.
     */
    fun rank(place: Place, normalisedQuery: String, distanceMeters: Double?): Int {
        val match = score(place.name, normalisedQuery)
        if (match == 0) return 0
        val proximity = when {
            distanceMeters == null -> 0
            distanceMeters < 20_000 -> 25
            distanceMeters < 60_000 -> 18
            distanceMeters < 150_000 -> 10
            distanceMeters < 400_000 -> 4
            else -> 0
        }
        return match * 4 + place.kind.weight + proximity
    }

    private val MULTI_SPACE = Regex(" +")
}
