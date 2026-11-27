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
 * v3.8.0 smooth power limit (pure, no Android dependencies).
 *
 * The plain power cap parks a component on one frequency step. This spreads the cap between two
 * ADJACENT steps instead: if 2.5 GHz draws 5 W, 2.4 GHz draws 3.5 W and the target is 4 W, it sits
 * on 2.5 GHz about 1/3 of the time and on 2.4 GHz about 2/3 of the time.
 *
 * Pieces (all pure so they can be unit tested):
 *  - [plan]: picks the two bracketing steps and the duty (share of time on the higher one) from
 *    model-predicted power. The cap percentage is read as a share of the component's POWER range
 *    (not of its frequency range). Falls back to "no smoothing" when the model is not confident.
 *  - [DutyScheduler]: turns a duty into high/low phases over time (error accumulator, minimum
 *    phase length, counts switches).
 *  - [Trim]: slow feedback from measured watts that corrects model error.
 *  - [gate]: says when smoothing must not run (poll too slow, loop too slow, too many switches).
 */
object SmoothPowerLimiter {
    /** The fast switcher only needs a fresh plan from the control loop about once a second. */
    const val MAX_POLL_MS = 500L
    /** Shortest phase ever used (about 3 frames at 60 fps). */
    const val MIN_PHASE_MS = 50L
    const val MAX_FOLLOW_PHASE_MS = 120L
    const val MAX_PHASE_MS = 400L
    /** Duty closer than this to 0 or 1 is not worth a switch. */
    const val DUTY_SNAP = 0.04f
    /** Control-loop period above this means the plan would be stale (the fast switcher itself runs on its own 20 ms timer). */
    const val MAX_TICK_GAP_MS = 700L
    const val MAX_SWITCHES_PER_SEC = 14f
    const val BACKOFF_MS = 30_000L
    const val SWITCH_WINDOW_MS = 2_000L

    /** The two steps being alternated must reach at least this share of the minimum confidence. */
    const val BRACKET_CONF_FRAC = 0.5f

    const val TRIM_LIMIT = 0.15f
    const val TRIM_DEADBAND = 0.04f
    const val TRIM_INTERVAL_MS = 1_000L
    const val TRIM_GAIN = 0.4f

    /** Shortest phase for a given poll interval: a little over one tick, so phases follow the poll. */
    fun minPhaseFor(pollMs: Long): Long =
        (pollMs * 1.2f).toLong().coerceIn(MIN_PHASE_MS, MAX_FOLLOW_PHASE_MS)

    data class Step(val freqKhz: Long, val powerMw: Float, val confidencePct: Float)

    /**
     * lowIdx/highIdx index into the step list passed to [plan]. duty = share of time on highIdx.
     * [smooth] is false when the plain behaviour applies (duty 0, stay on lowIdx).
     */
    data class Plan(val lowIdx: Int, val highIdx: Int, val duty: Float, val smooth: Boolean, val reason: String)

    /**
     * @param steps ascending by frequency, already limited to the allowed range.
     * @param capFrac 0..1, the current cap for this component (1 = no cap).
     * @param trim added to [capFrac] (see [Trim]).
     */
    fun plan(steps: List<Step>, capFrac: Float, minConfidencePct: Float, trim: Float): Plan {
        val n = steps.size
        if (n == 0) return Plan(0, 0, 0f, false, "no steps")
        val cap = (capFrac + trim).coerceIn(0f, 1f)
        val f0 = steps.first().freqKhz
        val fn = steps.last().freqKhz
        // Plain behaviour: highest step at or under the frequency threshold (same as GovernorMath).
        val thresholdKhz = f0 + ((fn - f0).toDouble() * cap).toLong()
        var plainIdx = 0
        for (i in 0 until n) if (steps[i].freqKhz <= thresholdKhz) plainIdx = i
        val plain = Plan(plainIdx, plainIdx, 0f, false, "")
        if (n < 2) return plain.copy(reason = "single step")
        if (cap >= 0.995f) return plain.copy(reason = "no cap")

        var confSum = 0f
        for (s in steps) {
            if (!(s.powerMw >= 0f)) return plain.copy(reason = "bad model value")
            confSum += s.confidencePct
        }
        // v4.0.6: judge the model by its AVERAGE confidence over the ladder (one rarely-visited step no longer
        // vetoes smoothing). The two steps actually used are checked below with a lower floor.
        val avgConf = confSum / n
        if (avgConf < minConfidencePct) {
            return plain.copy(reason = "low model confidence (avg ${avgConf.toInt()}% < ${minConfidencePct.toInt()}%)")
        }
        // Power must rise with frequency; smooth out noise with a running maximum.
        val p = FloatArray(n)
        var run = 0f
        for (i in 0 until n) {
            run = maxOf(run, steps[i].powerMw)
            p[i] = run
        }
        if (p[n - 1] < p[0] * 1.05f + 1f) return plain.copy(reason = "flat model")

        val target = p[0] + cap * (p[n - 1] - p[0])
        var lo = 0
        for (i in 0 until n) if (p[i] <= target) lo = i
        if (lo >= n - 1) return Plan(n - 1, n - 1, 0f, true, "")
        // Next step that actually draws more (skips equal-power neighbours).
        var hi = lo + 1
        while (hi < n - 1 && p[hi] <= p[lo]) hi++
        val floor = minConfidencePct * BRACKET_CONF_FRAC
        if (steps[lo].confidencePct < floor || steps[hi].confidencePct < floor) {
            return plain.copy(
                reason = "low model confidence (steps in use ${steps[lo].confidencePct.toInt()}%/${steps[hi].confidencePct.toInt()}% < ${floor.toInt()}%)"
            )
        }
        val span = p[hi] - p[lo]
        if (span <= 0.5f) return Plan(lo, lo, 0f, true, "")
        val duty = ((target - p[lo]) / span).coerceIn(0f, 1f)
        return Plan(lo, hi, duty, true, "")
    }

    /** Why smoothing must not run right now, or null when it may. */
    fun gate(
        pollMs: Long,
        avgTickGapMs: Float,
        switchesPerSec: Float,
        nowMs: Long,
        disabledUntilMs: Long
    ): String? {
        if (nowMs < disabledUntilMs) return "backing off after too many switches"
        if (pollMs > MAX_POLL_MS) return "poll interval ${pollMs}ms is above ${MAX_POLL_MS}ms"
        if (avgTickGapMs > MAX_TICK_GAP_MS) return "control loop too slow (${avgTickGapMs.toInt()}ms per tick)"
        if (switchesPerSec > MAX_SWITCHES_PER_SEC * 2f) return "too many frequency writes (${"%.1f".format(switchesPerSec)}/s)"
        return null
    }

    /** Turns a duty into high/low phases. One instance per component. */
    class DutyScheduler {
        var high = false
            private set
        var minPhaseMs = MIN_PHASE_MS
        private var phaseStartMs = 0L
        private var lastMs = -1L
        private var errMs = 0f
        private val switchTimes = ArrayDeque<Long>()

        @Synchronized
        fun reset() {
            high = false
            lastMs = -1L
            errMs = 0f
            switchTimes.clear()
        }

        private fun flip(nowMs: Long) {
            high = !high
            phaseStartMs = nowMs
            switchTimes.addLast(nowMs)
        }

        /** Call once per control tick. Returns true when the component should be on its high step. */
        @Synchronized
        fun update(nowMs: Long, duty: Float): Boolean {
            if (lastMs < 0L) {
                lastMs = nowMs
                phaseStartMs = nowMs
                high = false
                return false
            }
            val dt = (nowMs - lastMs).coerceIn(0L, 1000L)
            lastMs = nowMs
            while (switchTimes.isNotEmpty() && nowMs - switchTimes.first() > SWITCH_WINDOW_MS) switchTimes.removeFirst()
            if (duty <= DUTY_SNAP) {
                errMs = 0f
                if (high) flip(nowMs)
                return false
            }
            if (duty >= 1f - DUTY_SNAP) {
                errMs = 0f
                if (!high) flip(nowMs)
                return true
            }
            errMs = (errMs + duty * dt - (if (high) dt else 0L)).coerceIn(-MAX_PHASE_MS.toFloat(), MAX_PHASE_MS.toFloat())
            val hyst = minPhaseMs * 0.5f
            if (nowMs - phaseStartMs >= minPhaseMs) {
                if (high && errMs <= -hyst) flip(nowMs)
                else if (!high && errMs >= hyst) flip(nowMs)
            }
            return high
        }

        @Synchronized
        fun switchesPerSec(nowMs: Long): Float {
            var n = 0
            for (t in switchTimes) if (nowMs - t <= SWITCH_WINDOW_MS) n++
            return n * 1000f / SWITCH_WINDOW_MS
        }
    }

    /**
     * Slow correction of the model: when the measured draw stays off the limit while the plain
     * controller has settled, nudge the cap fraction a little (never more than [TRIM_LIMIT]).
     */
    class Trim {
        var value = 0f
            private set
        private var lastMs = -1L

        fun reset() {
            value = 0f
            lastMs = -1L
        }

        fun update(nowMs: Long, avgWatts: Float, limitWatts: Float, engaged: Boolean, controllerSettled: Boolean): Float {
            if (!engaged) {
                value = 0f
                lastMs = nowMs
                return value
            }
            if (lastMs < 0L) lastMs = nowMs
            if (nowMs - lastMs < TRIM_INTERVAL_MS) return value
            lastMs = nowMs
            if (!controllerSettled || limitWatts <= 0f) return value
            val err = (avgWatts - limitWatts) / limitWatts
            if (kotlin.math.abs(err) < TRIM_DEADBAND) return value
            value = (value - TRIM_GAIN * err.coerceIn(-0.5f, 0.5f)).coerceIn(-TRIM_LIMIT, TRIM_LIMIT)
            return value
        }
    }
}
