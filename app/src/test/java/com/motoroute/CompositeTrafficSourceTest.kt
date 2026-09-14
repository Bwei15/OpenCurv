package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.CompositeTrafficSource
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import com.motoroute.data.traffic.TrafficIncident
import com.motoroute.data.traffic.TrafficSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompositeTrafficSourceTest {

    private fun incident(id: String) = TrafficIncident(
        id = id,
        title = id,
        description = "",
        type = IncidentType.CONSTRUCTION,
        severity = IncidentSeverity.INFO,
        location = GeoPoint(52.0, 9.7),
    )

    private fun source(vararg ids: String) = TrafficSource { ids.map(::incident) }

    private fun failing() = TrafficSource { throw IOException("offline") }

    @Test
    fun `both feeds' incidents end up in one list`() = runBlocking {
        val merged = CompositeTrafficSource(listOf(source("a", "b"), source("c"))).fetch()
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `the same closure published by both feeds appears once`() = runBlocking {
        val merged = CompositeTrafficSource(listOf(source("a", "b"), source("b", "c"))).fetch()
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `one failing feed does not throw away the other's data`() = runBlocking {
        // The point of the whole class: a rider's Mobilithek token expiring must
        // not wipe out the motorway closures too.
        val merged = CompositeTrafficSource(listOf(source("motorway"), failing())).fetch()
        assertEquals(listOf("motorway"), merged.map { it.id })
    }

    @Test
    fun `when every feed fails the error propagates so the cache survives`() {
        // TrafficRepository.refreshFrom only keeps the old cache on a failure -
        // returning an empty list here would silently erase every known closure.
        val thrown = runCatching {
            runBlocking { CompositeTrafficSource(listOf(failing(), failing())).fetch() }
        }.exceptionOrNull()
        assertTrue("expected the failure to propagate, got $thrown", thrown is IOException)
    }

    @Test
    fun `no sources at all is empty, not an error`() = runBlocking {
        assertEquals(emptyList<Any>(), CompositeTrafficSource(emptyList()).fetch())
    }
}
