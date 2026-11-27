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

import com.example.model.PowerThrottleConfig

/**
 * v3.7.0 power-cap controller (pure, no Android dependencies).
 *
 * The old loop lowered the cap by a fixed step once per second and raised it every tick, so the
 * draw sat above the limit and sawtoothed. This one:
 *  - steps down in proportion to how far over the limit the draw is (1x..4x the step),
 *  - once the cap is engaged keeps stepping down every [STEP_INTERVAL_MS] instead of waiting for
 *    the sustain time again,
 *  - judges the draw on a short window while engaged, so it reacts to what the last step did
 *    instead of a long average that still holds the old, higher readings,
 *  - skips the sustain wait for a big spike (more than 35% over),
 *  - only releases after the draw has stayed under the limit for [HOLD_AFTER_OVER_MS], so it
 *    settles on the limit instead of bouncing across it.
 */
object PowerCapController {
    const val STEP_INTERVAL_MS = 350L
    const val HOLD_AFTER_OVER_MS = 1500L
    /** v4.0.6: was 4x. A noisy 3-sample reading used to cut up to 12% per 350 ms while release crept up at 1-2%. */
    const val MAX_STEP_MULT = 2.5f
    /** v4.0.6: how much faster the cap lifts when the draw is far under the limit (headroom x this, up to [MAX_RELEASE_MULT]). */
    const val RELEASE_HEADROOM_GAIN = 10f
    const val MAX_RELEASE_MULT = 8f
    const val SPIKE_RATIO = 1.35f
    const val RELEASE_BELOW_RATIO = 0.95f
    const val ENGAGED_BELOW_PCT = 99.5f

    data class State(
        val capPct: Float = 100f,
        /** When the draw first went over the limit (this run), or -1 if it is not over. */
        val overSinceMs: Long = -1L,
        val lastStepMs: Long = 0L,
        val lastOverMs: Long = 0L
    )

    fun step(
        prev: State,
        nowMs: Long,
        avgWatts: Float,
        fastWatts: Float,
        cfg: PowerThrottleConfig,
        floorPct: Float,
        bypassed: Boolean
    ): State {
        val limit = cfg.limitWatts.coerceAtLeast(0.1f)
        val engaged = prev.capPct < ENGAGED_BELOW_PCT
        // v4.0.6: once engaged, blend the fast and slow readings (fast alone chased single noisy samples and over-cut).
        val overW = if (engaged) (fastWatts + avgWatts) * 0.5f else avgWatts
        val isOver = overW > limit

        if (isOver && bypassed) {
            // Afterburn is bypassing the cap: hold the stored cap so it does not sink while boosting.
            return prev.copy(overSinceMs = -1L)
        }

        if (isOver) {
            val since = if (prev.overSinceMs >= 0L) prev.overSinceMs else nowMs
            val sustain = if (overW > limit * SPIKE_RATIO) cfg.sustainMs / 2L else cfg.sustainMs
            val waiting = !engaged && nowMs - since < sustain
            val tooSoon = nowMs - prev.lastStepMs < STEP_INTERVAL_MS
            if (waiting || tooSoon) return prev.copy(overSinceMs = since, lastOverMs = nowMs)
            val overFrac = overW / limit - 1f
            val mult = (1f + overFrac * 6f).coerceIn(1f, MAX_STEP_MULT)
            val next = (prev.capPct - cfg.stepPct * mult).coerceIn(floorPct, 100f)
            return State(capPct = next, overSinceMs = since, lastStepMs = nowMs, lastOverMs = nowMs)
        }

        var next = prev.capPct
        var lastStep = prev.lastStepMs
        val releaseW = (avgWatts + fastWatts) * 0.5f
        if (releaseW < limit * RELEASE_BELOW_RATIO && prev.capPct < 100f &&
            nowMs - prev.lastOverMs >= HOLD_AFTER_OVER_MS &&
            nowMs - prev.lastStepMs >= STEP_INTERVAL_MS
        ) {
            val headroom = RELEASE_BELOW_RATIO - releaseW / limit
            val mult = (1f + headroom * RELEASE_HEADROOM_GAIN).coerceIn(1f, MAX_RELEASE_MULT)
            next = (prev.capPct + cfg.releaseStepPct * mult).coerceIn(floorPct, 100f)
            lastStep = nowMs
        }
        return State(capPct = next, overSinceMs = -1L, lastStepMs = lastStep, lastOverMs = prev.lastOverMs)
    }
}
