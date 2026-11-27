package com.example

import com.example.governor.SmoothPowerLimiter
import com.example.governor.SmoothPowerLimiter.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothPowerLimiterTest {
    // Owner's example: 2.4 GHz = 3.5 W, 2.5 GHz = 5 W. Ladder 2.3 / 2.4 / 2.5 with 2.0 W at the bottom.
    private val ladder = listOf(
        Step(2_300_000L, 2000f, 90f),
        Step(2_400_000L, 3500f, 90f),
        Step(2_500_000L, 5000f, 90f)
    )

    @Test fun exampleFromOwnerGivesAboutOneThirdOnTheHigherStep() {
        // Power range 2000..5000; cap fraction for a 4 W target = (4000-2000)/3000.
        val p = SmoothPowerLimiter.plan(ladder, 2000f / 3000f, 60f, 0f)
        assertTrue(p.smooth)
        assertEquals(1, p.lowIdx)
        assertEquals(2, p.highIdx)
        assertEquals(1f / 3f, p.duty, 0.01f)
    }

    @Test fun targetExactlyOnAStepHasNoDuty() {
        val p = SmoothPowerLimiter.plan(ladder, 0.5f, 60f, 0f) // 3.5 W
        assertEquals(1, p.lowIdx)
        assertEquals(0f, p.duty, 0.001f)
    }

    @Test fun noCapMeansPlainTopStep() {
        val p = SmoothPowerLimiter.plan(ladder, 1f, 60f, 0f)
        assertFalse(p.smooth)
        assertEquals(2, p.lowIdx)
        assertEquals(0f, p.duty, 0f)
    }

    @Test fun lowConfidenceFallsBackToPlainFloorStep() {
        val weak = ladder.toMutableList().also { it[1] = Step(2_400_000L, 3500f, 20f) }
        val p = SmoothPowerLimiter.plan(weak, 0.7f, 60f, 0f)
        assertFalse(p.smooth)
        assertEquals(0f, p.duty, 0f)
        // plain: threshold = 2.3 + 0.2*0.7 = 2.44 GHz -> 2.4 GHz step.
        assertEquals(1, p.lowIdx)
        assertTrue(p.reason.startsWith("low model confidence"))
    }

    @Test fun oneWeakFarStepDoesNotVetoWhenAverageIsHighEnough() {
        // Cap 0.7 lands between steps 1 and 2 (both strong); only the bottom step is weak. Average 70 >= 60.
        val weakBottom = ladder.toMutableList().also { it[0] = Step(2_300_000L, 2000f, 10f) }
            .also { it[1] = it[1].copy(confidencePct = 90f); it[2] = it[2].copy(confidencePct = 90f) }
        val p = SmoothPowerLimiter.plan(weakBottom, 0.7f, 60f, 0f)
        assertTrue(p.smooth)
    }

    @Test fun lowerThresholdLetsAWeakerModelSmooth() {
        val mid = ladder.map { it.copy(confidencePct = 45f) }
        assertFalse(SmoothPowerLimiter.plan(mid, 0.7f, 60f, 0f).smooth)
        assertTrue(SmoothPowerLimiter.plan(mid, 0.7f, 40f, 0f).smooth)
    }

    @Test fun flatOrBrokenModelFallsBack() {
        val flat = ladder.map { it.copy(powerMw = 3000f) }
        assertFalse(SmoothPowerLimiter.plan(flat, 0.5f, 60f, 0f).smooth)
        val nan = ladder.toMutableList().also { it[0] = Step(2_300_000L, Float.NaN, 90f) }
        assertFalse(SmoothPowerLimiter.plan(nan, 0.5f, 60f, 0f).smooth)
    }

    @Test fun noisyModelStaysMonotone() {
        val noisy = listOf(
            Step(1_000_000L, 1000f, 90f), Step(1_100_000L, 900f, 90f),
            Step(1_200_000L, 2000f, 90f), Step(1_300_000L, 3000f, 90f)
        )
        val p = SmoothPowerLimiter.plan(noisy, 0.4f, 60f, 0f) // target 1800
        assertTrue(p.smooth)
        assertTrue(p.highIdx >= p.lowIdx)
        assertTrue(p.duty in 0f..1f)
    }

    @Test fun trimMovesTheTargetAndIsClamped() {
        val up = SmoothPowerLimiter.plan(ladder, 0.5f, 60f, 0.15f)
        val down = SmoothPowerLimiter.plan(ladder, 0.5f, 60f, -0.15f)
        assertTrue(up.lowIdx > down.lowIdx || up.duty > down.duty)
        val huge = SmoothPowerLimiter.plan(ladder, 0.9f, 60f, 5f)
        assertEquals(2, huge.lowIdx) // clamped to full cap fraction
    }

    @Test fun singleStepAndEmptyAreSafe() {
        assertEquals(0f, SmoothPowerLimiter.plan(emptyList(), 0.5f, 60f, 0f).duty, 0f)
        assertEquals(0f, SmoothPowerLimiter.plan(listOf(ladder[0]), 0.5f, 60f, 0f).duty, 0f)
    }

    private fun simulate(duty: Float, tickMs: Long, totalMs: Long, minPhase: Long = SmoothPowerLimiter.MIN_PHASE_MS): Triple<Float, Float, Long> {
        val s = SmoothPowerLimiter.DutyScheduler()
        s.minPhaseMs = minPhase
        var t = 0L
        var highMs = 0L
        var maxPhase = 0L
        var phaseStart = 0L
        var prev = false
        s.update(t, duty)
        while (t < totalMs) {
            t += tickMs
            val h = s.update(t, duty)
            if (h) highMs += tickMs
            if (h != prev) { maxPhase = maxOf(maxPhase, t - phaseStart); phaseStart = t; prev = h }
        }
        return Triple(highMs.toFloat() / totalMs, s.switchesPerSec(t), maxPhase)
    }

    @Test fun schedulerAveragesToTheDuty() {
        for (duty in listOf(0.1f, 0.25f, 1f / 3f, 0.5f, 0.75f, 0.9f)) {
            val (frac, _, _) = simulate(duty, 50L, 20_000L)
            assertEquals("duty $duty", duty, frac, 0.04f)
        }
    }

    @Test fun schedulerAveragesToTheDutyWithSlowTicks() {
        val (frac, _, _) = simulate(1f / 3f, 100L, 30_000L)
        assertEquals(1f / 3f, frac, 0.05f)
    }

    @Test fun schedulerKeepsPhasesAtLeastMinimum() {
        val s = SmoothPowerLimiter.DutyScheduler()
        var t = 0L
        s.update(t, 0.5f)
        var last = false
        var lastFlip = 0L
        while (t < 10_000L) {
            t += 20L
            val h = s.update(t, 0.5f)
            if (h != last) {
                assertTrue("phase ${t - lastFlip}ms", t - lastFlip >= SmoothPowerLimiter.MIN_PHASE_MS)
                lastFlip = t
                last = h
            }
        }
    }

    @Test fun schedulerSnapsNearZeroAndOne() {
        val s = SmoothPowerLimiter.DutyScheduler()
        s.update(0L, 0.01f)
        var t = 0L
        repeat(50) { t += 50L; assertFalse(s.update(t, 0.01f)) }
        assertTrue(run { var h = false; repeat(5) { t += 50L; h = s.update(t, 0.99f) }; h })
    }

    @Test fun switchRateIsReported() {
        val (_, rate, _) = simulate(0.5f, 40L, 10_000L)
        assertTrue(rate > 0f)
        assertTrue(rate <= 1000f / SmoothPowerLimiter.MIN_PHASE_MS + 0.1f)
    }

    @Test fun minPhaseFollowsThePollWithinBounds() {
        assertEquals(60L, SmoothPowerLimiter.minPhaseFor(50L))
        assertEquals(120L, SmoothPowerLimiter.minPhaseFor(150L))
        assertEquals(120L, SmoothPowerLimiter.minPhaseFor(1000L))
        assertEquals(50L, SmoothPowerLimiter.minPhaseFor(10L))
    }

    @Test fun fastPollGivesAccurateAverage() {
        val (frac, _, _) = simulate(1f / 3f, 50L, 30_000L, SmoothPowerLimiter.minPhaseFor(50L))
        assertEquals(1f / 3f, frac, 0.04f)
    }

    @Test fun gateBlocksSlowPollSlowLoopAndBackoff() {
        assertNull(SmoothPowerLimiter.gate(100L, 110f, 3f, 1000L, 0L))
        assertNull(SmoothPowerLimiter.gate(500L, 110f, 3f, 1000L, 0L))
        assertNotNull(SmoothPowerLimiter.gate(1000L, 110f, 3f, 1000L, 0L))
        assertNull(SmoothPowerLimiter.gate(100L, 450f, 3f, 1000L, 0L)) // slow loop is fine now: the fast switcher runs on its own timer
        assertNotNull(SmoothPowerLimiter.gate(100L, 900f, 3f, 1000L, 0L))
        assertNotNull(SmoothPowerLimiter.gate(100L, 110f, 60f, 1000L, 0L))
        assertNotNull(SmoothPowerLimiter.gate(100L, 110f, 3f, 1000L, 5000L))
    }

    @Test fun trimIgnoresDeadbandAndNotSettled() {
        val t = SmoothPowerLimiter.Trim()
        t.update(0L, 6f, 6f, true, true)
        assertEquals(0f, t.update(1500L, 6.1f, 6f, true, true), 0f)      // 1.7% off: inside deadband
        assertEquals(0f, t.update(3000L, 8f, 6f, true, false), 0f)       // controller still moving
    }

    @Test fun trimLowersWhenOverAndRaisesWhenUnderAndClamps() {
        val t = SmoothPowerLimiter.Trim()
        t.update(0L, 6f, 6f, true, true)
        val v1 = t.update(1500L, 7f, 6f, true, true)
        assertTrue(v1 < 0f)
        var now = 1500L
        repeat(40) { now += 1500L; t.update(now, 9f, 6f, true, true) }
        assertEquals(-SmoothPowerLimiter.TRIM_LIMIT, t.value, 0.0001f)
        repeat(80) { now += 1500L; t.update(now, 4f, 6f, true, true) }
        assertEquals(SmoothPowerLimiter.TRIM_LIMIT, t.value, 0.0001f)
    }

    @Test fun trimResetsWhenNotEngaged() {
        val t = SmoothPowerLimiter.Trim()
        t.update(0L, 6f, 6f, true, true)
        t.update(1500L, 8f, 6f, true, true)
        assertTrue(t.value != 0f)
        assertEquals(0f, t.update(3000L, 8f, 6f, false, true), 0f)
    }
}
