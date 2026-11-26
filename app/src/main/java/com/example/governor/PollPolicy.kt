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

package com.example.governor

/**
 * v2.7.4 Adaptive polling decision (pure, no Android or coroutine dependencies).
 *
 * The configured poll interval is always the minimum. The control loop only sleeps longer
 * when the device has been calm for a couple of seconds, and snaps back to the configured
 * interval the moment anything stops being calm.
 */
object PollPolicy {
    /** Longest sleep used while calm. A configured interval above this is never shortened. */
    const val MAX_STRETCH_MS = 350L

    /** Total CPU load (smoothed) at or below this counts as low. */
    const val CALM_CPU_LOAD_PCT = 20f

    /** GPU load (smoothed) at or below this counts as low. */
    const val CALM_GPU_LOAD_PCT = 15f

    /** A cluster load rise bigger than this between two ticks is a spike and ends calm. */
    const val SPIKE_PCT = 12f

    /** All calm conditions must hold this long before the sleep is stretched. */
    const val CALM_HOLD_MS = 2_000L

    /** No touch for this long before the device can count as calm. */
    const val TOUCH_QUIET_MS = 1_500L

    /** Marker for "not currently calm". */
    private const val NOT_CALM = -1L

    /** Calm tracking state carried from tick to tick. */
    data class State(val calmSinceMs: Long = NOT_CALM)

    /** Everything the policy looks at for one tick. */
    data class Inputs(
        val nowMs: Long,
        val enabled: Boolean,
        val baseIntervalMs: Long,
        val gameMode: Boolean,
        val burstActive: Boolean,
        val tempThrottleActive: Boolean,
        val powerThrottleActive: Boolean,
        val cpuLoadPct: Float,
        val gpuLoadPct: Float,
        val maxLoadSpikePct: Float,
        val lastTouchMs: Long,
        val screenInteractive: Boolean,
        val foregroundChanged: Boolean,
        /** v3.8.1: the overlay is showing, so live watts need the loop at its base rate (never stretched). */
        val overlayLive: Boolean = false
    )

    /** Result for one tick: the sleep to use and the state to carry forward. */
    data class Decision(val intervalMs: Long, val stretched: Boolean, val state: State)

    /** True when this single tick looks calm, ignoring how long it has been that way. */
    fun isCalmNow(i: Inputs): Boolean {
        if (!i.enabled) return false
        if (!i.screenInteractive) return false
        if (i.gameMode || i.burstActive) return false
        if (i.tempThrottleActive || i.powerThrottleActive) return false
        if (i.foregroundChanged) return false
        if (i.overlayLive) return false
        if (i.cpuLoadPct > CALM_CPU_LOAD_PCT) return false
        if (i.gpuLoadPct > CALM_GPU_LOAD_PCT) return false
        if (i.maxLoadSpikePct >= SPIKE_PCT) return false
        // lastTouchMs of 0 means "never touched" (or touch detection is off): no touch to wait for.
        if (i.lastTouchMs > 0L && i.nowMs - i.lastTouchMs < TOUCH_QUIET_MS) return false
        return true
    }

    /**
     * Decide the next sleep. The result is never below [Inputs.baseIntervalMs] and never above
     * the larger of [Inputs.baseIntervalMs] and [MAX_STRETCH_MS].
     */
    fun decide(prev: State, i: Inputs): Decision {
        val base = i.baseIntervalMs
        if (!isCalmNow(i)) {
            return Decision(base, false, State(NOT_CALM))
        }
        val since = if (prev.calmSinceMs == NOT_CALM || prev.calmSinceMs > i.nowMs) i.nowMs else prev.calmSinceMs
        val state = State(since)
        val stretched = i.nowMs - since >= CALM_HOLD_MS && MAX_STRETCH_MS > base
        val interval = if (stretched) MAX_STRETCH_MS else base
        return Decision(interval, stretched, state)
    }
}
