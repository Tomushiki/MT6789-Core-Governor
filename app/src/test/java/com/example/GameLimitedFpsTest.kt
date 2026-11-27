/*
 * CoreGovernor - custom CPU/GPU throttling governor
 * Copyright (C) 2026 Azrael
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

package com.example

import com.example.governor.GameThreadLogic
import com.example.governor.MetricSample
import com.example.governor.MetricsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLimitedFpsTest {

    private fun sample(i: Int, fps: Float) = MetricSample(
        tMs = i * 1000L, tempC = 40f, watts = 2f, fps = fps, cpuLoadPct = 50f, gpuLoadPct = 50f, work = 1f
    )

    private fun series(vararg fps: Float) = fps.mapIndexed { i, f -> sample(i, f) }

    @Test fun flatThirtyInTheMiddleOfAHighFpsSessionIsGameLimited() {
        val s = series(88f, 90f, 87f, 89f, 90f, 88f, 30f, 30f, 30f, 30f, 89f, 90f)
        val mask = MetricsMath.gameLimitedMask(s)
        assertEquals(4, mask.count { it })
        assertTrue(mask[6] && mask[9])
        assertFalse(mask[5] || mask[10])
    }

    @Test fun twoSecondsOfFlatThirtyCounts() {
        val s = series(90f, 90f, 90f, 90f, 30f, 31f, 90f, 90f)
        assertEquals(2, MetricsMath.gameLimitedMask(s).count { it })
    }

    @Test fun aSingleThirtyDipIsNotAGameCap() {
        val s = series(90f, 90f, 90f, 30f, 90f, 90f)
        assertEquals(0, MetricsMath.gameLimitedMask(s).count { it })
    }

    @Test fun aWanderingRunIsNotFlat() {
        // 28 -> 32.4 spreads more than 2.5 FPS: that is struggling, not a cap.
        val s = series(90f, 90f, 90f, 28f, 32f, 28.4f, 32.4f, 90f, 90f)
        assertEquals(0, MetricsMath.gameLimitedMask(s).count { it })
    }

    @Test fun aGameThatAlwaysRunsAtThirtyIsLeftAlone() {
        val s = series(30f, 30f, 30f, 30f, 30f, 30f, 30f, 30f)
        assertEquals(0, MetricsMath.gameLimitedMask(s).count { it })
    }

    @Test fun summaryIgnoresTheLobbyFramesAndReportsThem() {
        val fps = FloatArray(40) { 85f } + FloatArray(10) { 30f }
        val sum = MetricsMath.summarize(fps.mapIndexed { i, f -> sample(i, f) })
        assertEquals(10, sum.gameLimitedSec)
        assertEquals(30, sum.gameLimitedFps)
        assertTrue(sum.p5Fps >= 85f)
        assertTrue(sum.minFps >= 85f)
    }

    @Test fun noSessionWithoutACapReportsNothing() {
        val sum = MetricsMath.summarize(series(80f, 85f, 90f, 70f, 88f, 60f))
        assertEquals(0, sum.gameLimitedSec)
    }

    @Test fun gameThreadScansSlowDownOnceSettled() {
        val fast = GameThreadLogic.nextPollDelayMs(nowMs = 10_000L, settleUntilMs = 30_000L, appliedCount = 0)
        val active = GameThreadLogic.nextPollDelayMs(nowMs = 40_000L, settleUntilMs = 30_000L, appliedCount = 3)
        val steady = GameThreadLogic.nextPollDelayMs(nowMs = 40_000L, settleUntilMs = 30_000L, appliedCount = 0)
        assertEquals(GameThreadLogic.MIN_POLL_INTERVAL_MS, fast)
        assertEquals(GameThreadLogic.ACTIVE_POLL_INTERVAL_MS, active)
        assertEquals(GameThreadLogic.STEADY_POLL_INTERVAL_MS, steady)
        assertTrue(steady >= fast * 5)
    }
}
