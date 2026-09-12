package com.motoroute

import com.motoroute.data.search.PlaceQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the one contract the offline address index depends on: the `norm`
 * column tools/pipeline/bin/build_places.py writes into `<region>.places.sqlite`
 * MUST be produced by the exact same rule as [PlaceQuery.normalise] here,
 * because the app queries that column with a range scan
 * (`WHERE norm >= ? AND norm < ?`) built from typed text run through this
 * same function. If the two ever drift, prefix search silently stops
 * finding rows instead of failing loudly - hence a parity test on both
 * sides instead of just one.
 *
 * These 5 examples are mirrored byte-for-byte as `_PARITY_EXAMPLES` in
 * build_places.py. Add to both lists together, never just one.
 */
class PlaceNormaliseParityTest {

    @Test
    fun `normalise matches build_places py, example by example`() {
        assertEquals("goettingen", PlaceQuery.normalise("Göttingen"))
        assertEquals("muenster", PlaceQuery.normalise("Münster"))
        assertEquals("strasse", PlaceQuery.normalise("Straße"))
        assertEquals(
            "bad muender am deister",
            PlaceQuery.normalise("Bad Münder am Deister"),
        )
        assertEquals(
            "sankt florian weg 12a",
            PlaceQuery.normalise("Sankt-Florian-Weg 12a"),
        )
    }
}
