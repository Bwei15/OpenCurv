package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.NoGoArea
import com.motoroute.domain.NoGoFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class NoGoFilterTest {
    private val hannover = GeoPoint(52.37, 9.73)
    private val hameln = GeoPoint(52.10, 9.36)

    @Test
    fun `keeps closures near the trip and drops the rest of the country`() {
        val near = NoGoArea(GeoPoint(52.25, 9.55))
        val edge = NoGoArea(GeoPoint(52.55, 9.73)) // ~20 km north of the box
        val munich = NoGoArea(GeoPoint(48.14, 11.58))
        val kept = NoGoFilter.near(listOf(near, edge, munich), listOf(hannover, hameln))
        assertEquals(listOf(near, edge), kept)
    }

    @Test
    fun `no waypoints means no nogos`() {
        assertEquals(emptyList<NoGoArea>(), NoGoFilter.near(listOf(NoGoArea(hannover)), emptyList()))
    }
}
