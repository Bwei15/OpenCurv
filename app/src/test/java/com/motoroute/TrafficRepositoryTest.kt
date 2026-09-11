package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.traffic.IncidentSeverity
import com.motoroute.data.traffic.IncidentType
import com.motoroute.data.traffic.TrafficIncident
import com.motoroute.data.traffic.TrafficRepository
import com.motoroute.data.traffic.TrafficSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficRepositoryTest {

    private fun closure(id: String, start: Long? = null, end: Long? = null) = TrafficIncident(
        id = id,
        title = "Vollsperrung $id",
        description = "",
        type = IncidentType.ROAD_CLOSURE,
        severity = IncidentSeverity.CRITICAL,
        location = GeoPoint(50.0, 10.0),
        startEpochMillis = start,
        endEpochMillis = end,
    )

    @Test
    fun refreshFromReplacesIncidentsAndSetsLastUpdatedOnSuccess() = runBlocking {
        val repo = TrafficRepository()
        assertNull(repo.lastUpdated.value)
        assertFalse(repo.isRefreshing.value)

        val source = TrafficSource { listOf(closure("a"), closure("b")) }
        val result = repo.refreshFrom(source)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        assertEquals(2, repo.incidents.value.size)
        assertTrue(repo.lastUpdated.value != null)
        assertFalse(repo.isRefreshing.value)
    }

    @Test
    fun refreshFromLeavesExistingIncidentsUntouchedOnFailure() = runBlocking {
        val repo = TrafficRepository()
        repo.refreshFrom(TrafficSource { listOf(closure("keep-me")) })
        val lastUpdatedBefore = repo.lastUpdated.value

        val failingSource = TrafficSource { throw java.io.IOException("offline") }
        val result = repo.refreshFrom(failingSource)

        assertTrue(result.isFailure)
        assertEquals(listOf("keep-me"), repo.incidents.value.map { it.id })
        assertEquals(lastUpdatedBefore, repo.lastUpdated.value)
        assertFalse(repo.isRefreshing.value)
    }

    @Test
    fun activeNoGoAreasFiltersByStartAndEndWindow() = runBlocking {
        val repo = TrafficRepository()
        val now = System.currentTimeMillis()
        repo.updateIncidents(
            listOf(
                closure("not-started-yet", start = now + 3_600_000L),
                closure("already-ended", end = now - 3_600_000L),
                closure("currently-active"),
                closure("open-ended-past-start", start = now - 1_000L),
            ),
        )

        val activeIds = repo.activeNoGoAreas().map { it.description }
        assertTrue(activeIds.any { it == "Vollsperrung currently-active" })
        assertTrue(activeIds.any { it == "Vollsperrung open-ended-past-start" })
        assertFalse(activeIds.any { it == "Vollsperrung not-started-yet" })
        assertFalse(activeIds.any { it == "Vollsperrung already-ended" })
    }

    @Test
    fun cachePersistsFetchedAtAndReloadsLastUpdatedOnStartup() = runBlocking {
        val cacheFile = File.createTempFile("traffic_cache", ".json")
        cacheFile.deleteOnExit()
        try {
            val repo = TrafficRepository(cacheFile = cacheFile)
            repo.updateIncidents(listOf(closure("cached")))
            val fetchedAt = repo.lastUpdated.value
            assertTrue(fetchedAt != null)

            // Simulate an app restart: a fresh repository reading the same cache file.
            val reloaded = TrafficRepository(cacheFile = cacheFile)
            assertEquals(1, reloaded.incidents.value.size)
            assertEquals(fetchedAt, reloaded.lastUpdated.value)
        } finally {
            cacheFile.delete()
        }
    }
}
