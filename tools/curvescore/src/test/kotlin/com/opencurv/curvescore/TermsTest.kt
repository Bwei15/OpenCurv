package com.opencurv.curvescore

import com.opencurv.curvescore.geom.TurnEvent
import com.opencurv.curvescore.score.ScoreConfig
import com.opencurv.curvescore.score.Terms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each scoring term on its own, against the shape its documentation claims. */
class TermsTest {

    private val cfg = ScoreConfig()

    // ------------------------------------------------------------ radius quality

    @Test
    fun `radius quality has the documented plateau and anchors`() {
        assertEquals("a corner has no value", 0.0, Terms.radiusQuality(0.0, cfg), 1e-9)
        assertEquals("40 m is the bottom of the sweet spot", 1.0, Terms.radiusQuality(40.0, cfg), 1e-9)
        assertEquals("130 m is the top of the sweet spot", 1.0, Terms.radiusQuality(130.0, cfg), 1e-9)
        assertEquals("mid-plateau", 1.0, Terms.radiusQuality(80.0, cfg), 1e-9)
        assertEquals("8 m keeps a quarter", 0.25, Terms.radiusQuality(8.0, cfg), 1e-9)
        // (130/260)^1.5 = 0.3536 - "twice the sweet spot is worth about a third"
        assertEquals(0.3536, Terms.radiusQuality(260.0, cfg), 0.005)
        // (130/520)^1.5 = 0.125 - "four times is worth an eighth"
        assertEquals(0.125, Terms.radiusQuality(520.0, cfg), 0.005)
        assertTrue("a motorway sweeper is worth ~nothing", Terms.radiusQuality(900.0, cfg) < 0.06)
        assertEquals("a straight has no curve value", 0.0, Terms.radiusQuality(Double.POSITIVE_INFINITY, cfg), 1e-9)
    }

    @Test
    fun `radius quality rises to the plateau and falls after it, without jumps`() {
        var prev = Terms.radiusQuality(0.5, cfg)
        var r = 1.0
        while (r <= 40.0) {
            val q = Terms.radiusQuality(r, cfg)
            assertTrue("must not fall below 40 m (at $r)", q >= prev - 1e-9)
            assertTrue("no jump at $r", q - prev < 0.05)
            prev = q; r += 0.5
        }
        prev = 1.0
        r = 130.0
        while (r <= 2000.0) {
            val q = Terms.radiusQuality(r, cfg)
            assertTrue("must not rise above 130 m (at $r)", q <= prev + 1e-9)
            prev = q; r += 5.0
        }
    }

    // ------------------------------------------------------------- S-curve term

    private fun ev(startS: Double, endS: Double, turnDeg: Double, radius: Double) =
        TurnEvent(startS, endS, Math.toRadians(turnDeg), radius, false, 4)

    @Test
    fun `alternating curves beat the same curve twice in the same direction`() {
        val sCurves = listOf(ev(0.0, 60.0, 60.0, 70.0), ev(70.0, 130.0, -60.0, 70.0), ev(140.0, 200.0, 60.0, 70.0))
        val sameWay = listOf(ev(0.0, 60.0, 60.0, 70.0), ev(70.0, 130.0, 60.0, 70.0), ev(140.0, 200.0, 60.0, 70.0))
        val s = Terms.alternation(sCurves, cfg)
        val same = Terms.alternation(sameWay, cfg)
        assertTrue("linked S-curves must be near 1, got $s", s > 0.9)
        assertEquals("same-direction curves earn nothing", 0.0, same, 1e-9)
    }

    @Test
    fun `the S-curve bonus decays with the straight between the two curves`() {
        fun pair(gap: Double) = Terms.alternation(
            listOf(ev(0.0, 60.0, 60.0, 70.0), ev(60.0 + gap, 120.0 + gap, -60.0, 70.0)), cfg
        )
        assertTrue(pair(0.0) > 0.98)
        // exp(-300/300) = 0.368
        assertEquals(0.368, pair(300.0), 0.02)
        assertTrue("1.5 km apart is not an S-curve", pair(1500.0) < 0.02)
        assertTrue("monotone in the gap", pair(0.0) > pair(150.0) && pair(150.0) > pair(600.0))
    }

    @Test
    fun `motorway sweepers earn no S-curve bonus`() {
        val sweepers = listOf(ev(0.0, 300.0, 10.0, 900.0), ev(320.0, 620.0, -10.0, 900.0))
        assertEquals(0.0, Terms.alternation(sweepers, cfg), 1e-9)
    }

    @Test
    fun `a single curve cannot alternate`() {
        assertEquals(0.0, Terms.alternation(listOf(ev(0.0, 60.0, 60.0, 70.0)), cfg), 1e-9)
    }

    // -------------------------------------------------------------- rhythm term

    @Test
    fun `evenly spaced curves have rhythm, bunched ones do not`() {
        val even = (0 until 6).map { ev(it * 300.0, it * 300.0 + 60.0, if (it % 2 == 0) 50.0 else -50.0, 80.0) }
        val bunched = listOf(
            ev(0.0, 60.0, 50.0, 80.0), ev(70.0, 130.0, -50.0, 80.0), ev(140.0, 200.0, 50.0, 80.0),
            ev(2500.0, 2560.0, -50.0, 80.0),
        )
        assertTrue("even spacing -> rhythm", Terms.rhythm(even, cfg) > 0.9)
        assertTrue("bunch + long gap -> no rhythm", Terms.rhythm(bunched, cfg) < 0.3)
    }

    @Test
    fun `fewer than three curves is no rhythm at all`() {
        assertEquals(0.0, Terms.rhythm(listOf(ev(0.0, 60.0, 50.0, 80.0), ev(300.0, 360.0, -50.0, 80.0)), cfg), 1e-9)
    }

    // ------------------------------------------------------------ gradient term

    @Test
    fun `gradient term peaks at alpine pass gradients and dies on a wall`() {
        assertEquals("flat earns nothing", 0.0, Terms.gradient(0.0, cfg), 1e-9)
        assertEquals("2 % is halfway up the ramp", 0.5, Terms.gradient(0.02, cfg), 1e-9)
        assertEquals("4 % reaches the plateau", 1.0, Terms.gradient(0.04, cfg), 1e-9)
        assertEquals("6 % - the classic pass", 1.0, Terms.gradient(0.06, cfg), 1e-9)
        assertEquals("8 % still on the plateau", 1.0, Terms.gradient(0.08, cfg), 1e-9)
        assertEquals("15 % is a ramp, not a road", 0.0, Terms.gradient(0.15, cfg), 1e-9)
        assertEquals("30 % even more so", 0.0, Terms.gradient(0.30, cfg), 1e-9)
        assertEquals("downhill counts the same", Terms.gradient(0.06, cfg), Terms.gradient(-0.06, cfg), 1e-9)
    }

    @Test
    fun `missing elevation is neutral, never a penalty`() {
        assertEquals(0.0, Terms.gradient(null, cfg), 1e-9)
    }

    // ----------------------------------------------------------------- penalties

    @Test
    fun `corner penalty halves the score at the documented rate`() {
        val km = 1000.0
        val oneRightAngle = Math.PI / 2.0
        // 0.8 right angles per km must halve it.
        assertEquals(0.5, Terms.cornerPenalty(oneRightAngle * 0.8, km, cfg), 1e-6)
        assertTrue("one corner in 4 km is nearly free", Terms.cornerPenalty(oneRightAngle * 0.25, km, cfg) > 0.85)
        assertTrue("a grid at 1.8/km is crushed", Terms.cornerPenalty(oneRightAngle * 1.8, km, cfg) < 0.17)
        assertEquals("no corners, no penalty", 1.0, Terms.cornerPenalty(0.0, km, cfg), 1e-9)
    }

    @Test
    fun `settlement penalty is linear and bottoms out at a quarter`() {
        assertEquals(1.0, Terms.settlementPenalty(0.0, cfg), 1e-9)
        assertEquals(0.625, Terms.settlementPenalty(0.5, cfg), 1e-9)
        assertEquals(0.25, Terms.settlementPenalty(1.0, cfg), 1e-9)
    }

    @Test
    fun `traffic penalty matches its documented anchors`() {
        assertEquals("three stops per km halves it", 0.5, Terms.trafficPenalty(3.0, 1000.0, cfg), 1e-6)
        // One traffic light every 2 km = 0.5/km -> 1/(1+1/6) = 0.857
        assertEquals(0.857, Terms.trafficPenalty(0.5, 1000.0, cfg), 0.002)
    }

    // -------------------------------------------------------------- quantisation

    @Test
    fun `quantisation honours the configured level count`() {
        assertEquals(0, Terms.quantise(0.0, 16))
        assertEquals(0, Terms.quantise(0.0624, 16))
        assertEquals(1, Terms.quantise(0.0626, 16))
        assertEquals(15, Terms.quantise(1.0, 16))
        assertEquals(15, Terms.quantise(2.0, 16))
        assertEquals(0, Terms.quantise(-1.0, 16))
        assertEquals("configurable: 4 levels", 3, Terms.quantise(0.99, 4))
        assertEquals(1, Terms.quantise(0.3, 4))
        assertEquals("configurable: 8 levels", 7, Terms.quantise(0.95, 8))
    }

    @Test
    fun `quantisation is monotone for every level count`() {
        for (levels in listOf(2, 4, 8, 16, 32)) {
            var prev = -1
            var v = 0.0
            while (v <= 1.0) {
                val l = Terms.quantise(v, levels)
                assertTrue("monotone at $v with $levels levels", l >= prev)
                assertTrue(l in 0 until levels)
                prev = l; v += 0.001
            }
        }
    }
}
