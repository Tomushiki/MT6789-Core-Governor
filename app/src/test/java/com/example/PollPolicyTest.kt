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
 */

package com.example

import com.example.governor.PollPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPolicyTest {

    private fun inputs(
        nowMs: Long,
        enabled: Boolean = true,
        base: Long = 150L,
        game: Boolean = false,
        burst: Boolean = false,
        temp: Boolean = false,
        power: Boolean = false,
        cpu: Float = 5f,
        gpu: Float = 2f,
        spike: Float = 0f,
        touch: Long = 0L,
        screen: Boolean = true,
        fgChanged: Boolean = false
    ) = PollPolicy.Inputs(
        nowMs = nowMs,
        enabled = enabled,
        baseIntervalMs = base,
        gameMode = game,
        burstActive = burst,
        tempThrottleActive = temp,
        powerThrottleActive = power,
        cpuLoadPct = cpu,
        gpuLoadPct = gpu,
        maxLoadSpikePct = spike,
        lastTouchMs = touch,
        screenInteractive = screen,
        foregroundChanged = fgChanged
    )

    /** Runs calm ticks every [stepMs] from [startMs] until [endMs] and returns the last decision. */
    private fun runCalm(startMs: Long, endMs: Long, stepMs: Long = 150L): PollPolicy.Decision {
        var d = PollPolicy.decide(PollPolicy.State(), inputs(startMs))
        var state = d.state
        var t = startMs
        while (t < endMs) {
            t += stepMs
            d = PollPolicy.decide(state, inputs(t))
            state = d.state
        }
        return d
    }

    @Test
    fun calmFromTheStartStillUsesBaseIntervalUntilHoldElapsed() {
        val d = PollPolicy.decide(PollPolicy.State(), inputs(10_000L))
        assertEquals(150L, d.intervalMs)
        assertFalse(d.stretched)
    }

    @Test
    fun stretchesAfterCalmHold() {
        val d = runCalm(10_000L, 10_000L + PollPolicy.CALM_HOLD_MS + 300L)
        assertTrue(d.stretched)
        assertEquals(PollPolicy.MAX_STRETCH_MS, d.intervalMs)
    }

    @Test
    fun stillBaseJustBeforeHoldElapses() {
        val d = runCalm(10_000L, 10_000L + PollPolicy.CALM_HOLD_MS - 300L)
        assertFalse(d.stretched)
        assertEquals(150L, d.intervalMs)
    }

    @Test
    fun disabledAlwaysUsesBase() {
        var state = PollPolicy.State()
        var d = PollPolicy.decide(state, inputs(1_000L, enabled = false))
        for (i in 1..40) {
            state = d.state
            d = PollPolicy.decide(state, inputs(1_000L + i * 150L, enabled = false))
        }
        assertFalse(d.stretched)
        assertEquals(150L, d.intervalMs)
    }

    @Test
    fun everyNonCalmConditionSnapsBackImmediately() {
        val calm = runCalm(0L, 5_000L)
        assertTrue(calm.stretched)
        val now = 6_000L
        val cases = listOf(
            "game" to inputs(now, game = true),
            "burst" to inputs(now, burst = true),
            "temp throttle" to inputs(now, temp = true),
            "power throttle" to inputs(now, power = true),
            "cpu load" to inputs(now, cpu = PollPolicy.CALM_CPU_LOAD_PCT + 1f),
            "gpu load" to inputs(now, gpu = PollPolicy.CALM_GPU_LOAD_PCT + 1f),
            "spike" to inputs(now, spike = PollPolicy.SPIKE_PCT),
            "touch" to inputs(now, touch = now - 200L),
            "screen off" to inputs(now, screen = false),
            "foreground change" to inputs(now, fgChanged = true)
        )
        for ((name, i) in cases) {
            val d = PollPolicy.decide(calm.state, i)
            assertEquals("case $name", 150L, d.intervalMs)
            assertFalse("case $name", d.stretched)
        }
    }

    @Test
    fun calmHoldRestartsAfterActivity() {
        val calm = runCalm(0L, 5_000L)
        val busy = PollPolicy.decide(calm.state, inputs(5_150L, spike = 30f))
        assertFalse(busy.stretched)
        // Calm again, but the 2 s hold starts over.
        val again = PollPolicy.decide(busy.state, inputs(5_300L))
        assertFalse(again.stretched)
        assertEquals(150L, again.intervalMs)
        var state = again.state
        var t = 5_300L
        var d = again
        while (t < 5_300L + PollPolicy.CALM_HOLD_MS + 150L) {
            t += 150L
            d = PollPolicy.decide(state, inputs(t))
            state = d.state
        }
        assertTrue(d.stretched)
    }

    @Test
    fun oldTouchDoesNotBlockCalm() {
        val calm = runCalm(0L, 5_000L)
        val d = PollPolicy.decide(calm.state, inputs(6_000L, touch = 6_000L - PollPolicy.TOUCH_QUIET_MS - 1L))
        assertTrue(d.stretched)
    }

    @Test
    fun neverBelowBaseOrAboveMaxForLargeBase() {
        // A configured interval above the stretch limit is never shortened.
        var state = PollPolicy.State()
        var d = PollPolicy.decide(state, inputs(0L, base = 400L))
        for (i in 1..40) {
            state = d.state
            d = PollPolicy.decide(state, inputs(i * 400L, base = 400L))
            assertTrue(d.intervalMs >= 400L)
        }
        assertEquals(400L, d.intervalMs)
        assertFalse(d.stretched)
    }

    @Test
    fun intervalStaysWithinBounds() {
        for (base in listOf(50L, 80L, 150L, 350L, 600L)) {
            var state = PollPolicy.State()
            var t = 0L
            for (i in 0..60) {
                val d = PollPolicy.decide(state, inputs(t, base = base, cpu = (i % 7).toFloat()))
                assertTrue(d.intervalMs >= base)
                assertTrue(d.intervalMs <= maxOf(base, PollPolicy.MAX_STRETCH_MS))
                state = d.state
                t += base
            }
        }
    }

    @Test
    fun clockGoingBackwardsDoesNotStretch() {
        val calm = runCalm(10_000L, 15_000L)
        // nowMs earlier than the recorded calm start: treated as a fresh calm period.
        val d = PollPolicy.decide(calm.state, inputs(1_000L))
        assertFalse(d.stretched)
        assertEquals(150L, d.intervalMs)
    }

    @Test
    fun visibleOverlayKeepsBaseIntervalEvenWhenCalm() {
        var state = PollPolicy.State()
        var t = 10_000L
        var d = PollPolicy.decide(state, inputs(t).copy(overlayLive = true))
        while (t < 10_000L + PollPolicy.CALM_HOLD_MS + 600L) {
            state = d.state
            t += 150L
            d = PollPolicy.decide(state, inputs(t).copy(overlayLive = true))
        }
        assertFalse(d.stretched)
        assertEquals(150L, d.intervalMs)
    }
}
