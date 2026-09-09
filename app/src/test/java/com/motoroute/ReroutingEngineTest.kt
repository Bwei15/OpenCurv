package com.motoroute

import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.RerouteResult
import com.motoroute.domain.ReroutingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReroutingEngineTest {

    private val here = GeoPoint(47.5, 11.5)
    private val there = GeoPoint(47.6, 11.6)
    private val route = RouteFixtures.lShapedRoute()

    @Test
    fun `a successful reroute reports the new route`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val engine = ReroutingEngine(scope, { _, _, _ -> Result.success(route) })

        var result: RerouteResult? = null
        engine.request(here, there) { result = it }

        assertTrue("got $result", result is RerouteResult.Success)
        assertEquals(route, (result as RerouteResult.Success).route)
    }

    @Test
    fun `a second request during the cooldown is skipped`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        var now = 0L
        val engine = ReroutingEngine(
            scope = scope,
            calculate = { _, _, _ -> calls++; Result.success(route) },
            cooldownMillis = 10_000L,
            nowMillis = { now },
        )

        engine.request(here, there) {}
        now = 3_000L
        var second: RerouteResult? = null
        engine.request(here, there) { second = it }

        assertEquals(1, calls)
        assertEquals(RerouteResult.Skipped, second)
    }

    @Test
    fun `a request after the cooldown goes through`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        var now = 0L
        val engine = ReroutingEngine(
            scope = scope,
            calculate = { _, _, _ -> calls++; Result.success(route) },
            cooldownMillis = 10_000L,
            nowMillis = { now },
        )

        engine.request(here, there) {}
        now = 20_000L
        engine.request(here, there) {}
        assertEquals(2, calls)
    }

    @Test
    fun `only one calculation runs at a time`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var now = 0L
        val engine = ReroutingEngine(
            scope = scope,
            calculate = { _, _, _ ->
                started++
                gate.await()
                Result.success(route)
            },
            cooldownMillis = 0L,
            nowMillis = { now },
        )

        engine.request(here, there) {}
        now = 100_000L
        var second: RerouteResult? = null
        engine.request(here, there) { second = it }

        assertEquals(1, started)
        assertEquals(RerouteResult.Skipped, second)
        gate.complete(Unit)
    }

    @Test
    fun `failures back off so a dead zone does not drain the battery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        var now = 0L
        val engine = ReroutingEngine(
            scope = scope,
            calculate = { _, _, _ -> calls++; Result.failure(IllegalStateException("no tiles")) },
            cooldownMillis = 10_000L,
            nowMillis = { now },
        )

        engine.request(here, there) {}
        assertEquals(1, calls)

        // One failure doubles the cooldown: 15 s is no longer enough.
        now = 15_000L
        engine.request(here, there) {}
        assertEquals(1, calls)

        now = 25_000L
        engine.request(here, there) {}
        assertEquals(2, calls)
    }

    @Test
    fun `an exception inside the calculation is reported, not thrown`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val engine = ReroutingEngine(scope, { _, _, _ -> throw IllegalStateException("boom") })

        var result: RerouteResult? = null
        engine.request(here, there) { result = it }
        assertTrue("got $result", result is RerouteResult.Failure)
    }
}
