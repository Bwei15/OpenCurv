package com.motoroute

import com.motoroute.domain.RecalcTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecalcTriggerTest {

    @Test
    fun `fires once after the debounce window`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val trigger = RecalcTrigger(scope, debounceMillis = 400L)
        var fires = 0

        trigger.request { fires++ }
        testScheduler.advanceTimeBy(399L)
        testScheduler.runCurrent()
        assertEquals(0, fires)

        testScheduler.advanceTimeBy(2L)
        testScheduler.runCurrent()
        assertEquals(1, fires)
    }

    @Test
    fun `a later request within the window replaces the earlier one`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val trigger = RecalcTrigger(scope, debounceMillis = 400L)
        var fires = 0

        trigger.request { fires++ }
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()
        // The slider moved again before the first request was due - it must
        // not fire on the original schedule any more.
        trigger.request { fires++ }

        testScheduler.advanceTimeBy(300L) // 600ms since request 1, only 300ms since request 2
        testScheduler.runCurrent()
        assertEquals(0, fires)

        testScheduler.advanceTimeBy(100L)
        testScheduler.runCurrent()
        assertEquals(1, fires)
    }

    @Test
    fun `cancel suppresses a pending request`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val trigger = RecalcTrigger(scope, debounceMillis = 400L)
        var fires = 0

        trigger.request { fires++ }
        trigger.cancel()

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(0, fires)
    }

    @Test
    fun `repeated requests after firing can fire again`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val trigger = RecalcTrigger(scope, debounceMillis = 400L)
        var fires = 0

        trigger.request { fires++ }
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, fires)

        trigger.request { fires++ }
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(2, fires)
    }
}
