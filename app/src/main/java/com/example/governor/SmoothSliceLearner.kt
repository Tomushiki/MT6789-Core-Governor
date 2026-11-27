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

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v4.0.9 smooth-limit slice learning (pure, no Android dependencies).
 *
 * The "slice" is the shortest time the smooth limiter stays on one of its two frequency steps
 * ([SmoothPowerLimiter.DutyScheduler.minPhaseMs]). A shorter slice gives a finer, steadier average
 * but needs more frequency writes through the root shell.
 *
 * Pieces:
 *  - [SmoothSlice]: the hard safe range, the ladder of allowed slices, the write-latency floor and the
 *    decision function [SmoothSlice.decide] (better / neutral / worse / inconclusive).
 *  - [WriteLatencyTracker]: how long frequency writes really take (ring buffer, percentiles).
 *  - [SliceLearner]: the state machine. One CPU cluster at a time: measure a baseline window, try the next
 *    shorter slice for one window, keep going only while it is better, otherwise go back to the last good slice.
 */
object SmoothSlice {
    /** Today's behaviour (the old fixed floor of the phase length). */
    const val DEFAULT_MS = 50
    /** Never shorter than this: the fast switcher itself ticks every 20 ms. */
    const val HARD_MIN_MS = 30
    /** Never longer than this (same as [SmoothPowerLimiter.MAX_PHASE_MS]). */
    const val HARD_MAX_MS = 400

    /** Slices the learner may use, longest first. */
    val LADDER: IntArray = intArrayOf(400, 300, 200, 150, 100, 70, 50, 40, 30)

    /** A slice must be at least this many times the p90 write time, or the writes cannot keep up. */
    const val WRITE_FLOOR_FACTOR = 3f

    /** Continuous eligible time (game, power limit active, discharging, no burst) before the first measurement. */
    const val WARMUP_MS = 120_000L
    /** Length of one measurement window. */
    const val WINDOW_MS = 45_000L
    const val MIN_WINDOW_TICKS = 60
    /** The candidate window is only comparable when mean power is within this fraction of the baseline. */
    const val COMPARABLE_WATTS_FRAC = 0.25f
    /** How often a stage is re-measured when the load changed too much to compare. */
    const val MAX_RETRIES = 2

    /** Candidate counts as WORSE beyond these margins (relative + absolute so tiny numbers do not flap). */
    const val WORSE_OVERSHOOT_REL = 1.15f
    const val WORSE_OVERSHOOT_ABS_W = 0.05f
    const val WORSE_FPS_CV_REL = 1.10f
    const val WORSE_FPS_CV_ABS = 0.01f
    const val WORSE_DRIFT_REL = 1.5f
    const val WORSE_DRIFT_ABS_PER_MIN = 2f
    const val WORSE_WRITE_REL = 1.5f
    const val WORSE_WRITE_ABS_MS = 5f

    /** Candidate counts as BETTER when overshoot or FPS steadiness improves beyond these margins. */
    const val BETTER_OVERSHOOT_REL = 0.90f
    const val BETTER_OVERSHOOT_ABS_W = 0.02f
    const val BETTER_FPS_CV_REL = 0.95f
    const val BETTER_FPS_CV_ABS = 0.003f

    fun clampMs(ms: Int): Int = ms.coerceIn(HARD_MIN_MS, HARD_MAX_MS)

    /** Shortest time between two forced re-writes of one cluster after the kernel overrode the fast switcher. */
    const val FIX_MIN_INTERVAL_MS = 400L

    /**
     * v4.1.0: should the fast switcher be told to write its value again? Only when the option is on, the kernel
     * really overrode the last write, no thermal cap is active (a thermal limit must win), and the last fix was
     * at least [FIX_MIN_INTERVAL_MS] ago (no write war).
     */
    fun shouldFixDrift(enabled: Boolean, thermalCapActive: Boolean, mismatch: Boolean, nowMs: Long, lastFixMs: Long): Boolean =
        enabled && mismatch && !thermalCapActive && nowMs - lastFixMs >= FIX_MIN_INTERVAL_MS

    /**
     * Shortest slice the write path can support: [WRITE_FLOOR_FACTOR] x p90 write time, rounded UP to a ladder
     * value, never below [HARD_MIN_MS]. An unknown latency (<= 0) gives [HARD_MIN_MS].
     */
    fun floorFor(writeP90Ms: Float): Int {
        if (!(writeP90Ms > 0f)) return HARD_MIN_MS
        val need = ceil(writeP90Ms * WRITE_FLOOR_FACTOR).toInt().coerceAtLeast(HARD_MIN_MS)
        var best = HARD_MAX_MS
        for (v in LADDER) if (v >= need && v < best) best = v
        return best
    }

    /** Largest ladder value that is shorter than [current] and not below [floorMs], or null when there is none. */
    fun nextShorter(current: Int, floorMs: Int): Int? {
        var best: Int? = null
        for (v in LADDER) {
            if (v < current && v >= floorMs.coerceAtLeast(HARD_MIN_MS)) {
                if (best == null || v > best) best = v
            }
        }
        return best
    }

    /** One control tick's worth of measurements, for the cluster currently being trialled. */
    data class Sample(
        val watts: Float,
        val limitWatts: Float,
        /** <= 0 when there is no FPS reading. */
        val fps: Float,
        /** Cumulative count of kernel limit overrides seen for the cluster (only its change matters). */
        val driftTotal: Int,
        /** p90 of recent frequency write times in ms, 0 when unknown. */
        val writeP90Ms: Float,
        /** True when the engine had to lengthen the phase because of too many switches. */
        val backedOff: Boolean
    )

    /** What a window measured. fpsCv < 0 means "not enough FPS data". */
    data class Stats(
        val ticks: Int,
        val meanWatts: Float,
        /** Mean of max(0, watts - limit). */
        val overshootW: Float,
        /** Coefficient of variation of FPS (std / mean), e.g. 0.08 = 8 %. */
        val fpsCv: Float,
        val driftPerMin: Float,
        val writeP90Ms: Float,
        val backedOff: Boolean
    )

    enum class Kind { BETTER, NEUTRAL, WORSE, INCONCLUSIVE }

    data class Verdict(val kind: Kind, val reason: String)

    /**
     * Pure decision: is the candidate slice (measured in [cand]) better than the baseline ([base])?
     * Any single metric getting clearly worse makes it WORSE; it is BETTER only when nothing got worse and
     * overshoot or FPS steadiness clearly improved.
     */
    fun decide(base: Stats, cand: Stats, candSliceMs: Int): Verdict {
        if (cand.backedOff) return Verdict(Kind.WORSE, "too many switches (engine backed off)")
        if (cand.writeP90Ms * WRITE_FLOOR_FACTOR > candSliceMs && cand.writeP90Ms > 0f) {
            return Verdict(Kind.WORSE, "writes too slow for ${candSliceMs}ms (p90 ${fmt1(cand.writeP90Ms)}ms)")
        }
        if (base.meanWatts > 0.05f && abs(cand.meanWatts - base.meanWatts) / base.meanWatts > COMPARABLE_WATTS_FRAC) {
            return Verdict(Kind.INCONCLUSIVE, "load changed (${fmt2(base.meanWatts)}W -> ${fmt2(cand.meanWatts)}W)")
        }
        if (cand.overshootW > base.overshootW * WORSE_OVERSHOOT_REL + WORSE_OVERSHOOT_ABS_W) {
            return Verdict(Kind.WORSE, "overshoot ${fmt2(base.overshootW)}W -> ${fmt2(cand.overshootW)}W")
        }
        if (base.fpsCv >= 0f && cand.fpsCv >= 0f &&
            cand.fpsCv > base.fpsCv * WORSE_FPS_CV_REL + WORSE_FPS_CV_ABS
        ) {
            return Verdict(Kind.WORSE, "FPS less steady (${fmtPct(base.fpsCv)} -> ${fmtPct(cand.fpsCv)})")
        }
        if (cand.driftPerMin > base.driftPerMin * WORSE_DRIFT_REL + WORSE_DRIFT_ABS_PER_MIN) {
            return Verdict(Kind.WORSE, "more CpuDrift re-asserts (${fmt1(base.driftPerMin)} -> ${fmt1(cand.driftPerMin)}/min)")
        }
        if (base.writeP90Ms > 0f &&
            cand.writeP90Ms > max(base.writeP90Ms * WORSE_WRITE_REL, base.writeP90Ms + WORSE_WRITE_ABS_MS)
        ) {
            return Verdict(Kind.WORSE, "writes slower (p90 ${fmt1(base.writeP90Ms)} -> ${fmt1(cand.writeP90Ms)}ms)")
        }
        val overBetter = cand.overshootW < base.overshootW * BETTER_OVERSHOOT_REL - BETTER_OVERSHOOT_ABS_W
        val fpsBetter = base.fpsCv >= 0f && cand.fpsCv >= 0f &&
            cand.fpsCv < base.fpsCv * BETTER_FPS_CV_REL - BETTER_FPS_CV_ABS
        return when {
            overBetter && fpsBetter -> Verdict(Kind.BETTER, "overshoot and FPS steadiness improved")
            overBetter -> Verdict(Kind.BETTER, "overshoot smaller")
            fpsBetter -> Verdict(Kind.BETTER, "FPS steadier")
            else -> Verdict(Kind.NEUTRAL, "no clear gain")
        }
    }

    /** "cpu_0:40,cpu_3:50" for storage in the config. */
    fun encode(map: Map<String, Int>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    /** Inverse of [encode]; ignores anything malformed and clamps to the hard range. */
    fun decode(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (part in text.split(',')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val id = part.substring(0, i).trim()
            val v = part.substring(i + 1).trim().toIntOrNull() ?: continue
            if (id.isNotEmpty()) out[id] = clampMs(v)
        }
        return out
    }

    fun summary(base: Stats, cand: Stats): String =
        "over ${fmt2(base.overshootW)}->${fmt2(cand.overshootW)}W, " +
            "fps cv ${if (base.fpsCv >= 0f) fmtPct(base.fpsCv) else "n/a"}->${if (cand.fpsCv >= 0f) fmtPct(cand.fpsCv) else "n/a"}, " +
            "drift ${fmt1(base.driftPerMin)}->${fmt1(cand.driftPerMin)}/min, " +
            "write p90 ${fmt1(base.writeP90Ms)}->${fmt1(cand.writeP90Ms)}ms"

    private fun fmt1(v: Float) = "%.1f".format(java.util.Locale.US, v)
    private fun fmt2(v: Float) = "%.2f".format(java.util.Locale.US, v)
    private fun fmtPct(v: Float) = "%.1f%%".format(java.util.Locale.US, v * 100f)

    /** Collects samples for one measurement window. */
    class WindowAcc {
        private var ticks = 0
        private var sumW = 0.0
        private var sumOver = 0.0
        private var fpsN = 0
        private var fpsSum = 0.0
        private var fpsSumSq = 0.0
        private var firstMs = -1L
        private var lastMs = 0L
        private var driftStart = 0
        private var driftEnd = 0
        private var p90Sum = 0.0
        private var p90N = 0
        private var backedOff = false

        fun reset() {
            ticks = 0; sumW = 0.0; sumOver = 0.0
            fpsN = 0; fpsSum = 0.0; fpsSumSq = 0.0
            firstMs = -1L; lastMs = 0L
            driftStart = 0; driftEnd = 0
            p90Sum = 0.0; p90N = 0
            backedOff = false
        }

        fun add(nowMs: Long, s: Sample) {
            if (firstMs < 0L) {
                firstMs = nowMs
                driftStart = s.driftTotal
            }
            lastMs = nowMs
            driftEnd = s.driftTotal
            ticks++
            sumW += s.watts
            sumOver += max(0f, s.watts - s.limitWatts)
            if (s.fps in 5f..240f) {
                fpsN++
                fpsSum += s.fps
                fpsSumSq += s.fps.toDouble() * s.fps
            }
            if (s.writeP90Ms > 0f) {
                p90Sum += s.writeP90Ms
                p90N++
            }
            if (s.backedOff) backedOff = true
        }

        val ticksSoFar: Int get() = ticks
        val durationMs: Long get() = if (firstMs < 0L) 0L else lastMs - firstMs

        fun stats(): Stats {
            val n = max(ticks, 1)
            var cv = -1f
            if (fpsN >= 20) {
                val mean = fpsSum / fpsN
                val varr = max(0.0, fpsSumSq / fpsN - mean * mean)
                if (mean > 1.0) cv = (sqrt(varr) / mean).toFloat()
            }
            val minutes = max(durationMs, 1L) / 60_000f
            val drift = max(0, driftEnd - driftStart)
            return Stats(
                ticks = ticks,
                meanWatts = (sumW / n).toFloat(),
                overshootW = (sumOver / n).toFloat(),
                fpsCv = cv,
                driftPerMin = drift / minutes,
                writeP90Ms = if (p90N > 0) (p90Sum / p90N).toFloat() else 0f,
                backedOff = backedOff
            )
        }
    }
}

/** How long writes really take. Thread-safe (the control loop and the fast switcher both write). */
class WriteLatencyTracker(private val capacity: Int = 256) {
    data class Summary(val count: Int, val avgMs: Float, val p50Ms: Float, val p90Ms: Float, val maxMs: Float)

    private val buf = FloatArray(capacity)
    private var size = 0
    private var next = 0
    private var total = 0L

    @Synchronized
    fun add(ms: Float) {
        if (!(ms >= 0f)) return
        buf[next] = ms
        next = (next + 1) % capacity
        if (size < capacity) size++
        total++
    }

    @Synchronized
    fun totalCount(): Long = total

    @Synchronized
    fun clear() {
        size = 0; next = 0
    }

    @Synchronized
    fun summary(): Summary? {
        if (size == 0) return null
        val a = buf.copyOf(size)
        a.sort()
        var sum = 0.0
        for (v in a) sum += v
        return Summary(size, (sum / size).toFloat(), pct(a, 0.5f), pct(a, 0.9f), a[size - 1])
    }

    /** p90 over the recent writes; 0 when there are fewer than 5 samples. */
    @Synchronized
    fun p90(): Float {
        if (size < 5) return 0f
        val a = buf.copyOf(size)
        a.sort()
        return pct(a, 0.9f)
    }

    private fun pct(sorted: FloatArray, p: Float): Float {
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

/**
 * One CPU cluster at a time: baseline window, then a window with the next shorter slice, and so on.
 * Time is passed in, so it is fully testable.
 */
class SliceLearner {
    enum class Phase { BASELINE, CANDIDATE }
    enum class ChangeKind { TRIAL, KEPT_TRYING, REVERTED, SETTLED, INTERRUPTED }

    /** [fromMs] == [toMs] for pure "settled" notes. */
    data class Change(val id: String, val fromMs: Int, val toMs: Int, val kind: ChangeKind, val text: String)

    private class Cluster(var slice: Int, var settled: Boolean)

    private val clusters = LinkedHashMap<String, Cluster>()
    var currentId: String? = null
        private set
    var phase: Phase = Phase.BASELINE
        private set
    private var candidate = 0
    private var base: SmoothSlice.Stats? = null
    private val acc = SmoothSlice.WindowAcc()
    private var eligibleSince = -1L
    private var retries = 0

    /**
     * Makes sure every cluster in [ids] is known. New clusters start at [startMs]; those in [learned] are already
     * settled. Unsettled clusters that are not mid-trial follow a changed [startMs].
     */
    fun configure(ids: List<String>, startMs: Int, learned: Map<String, Int>) {
        for (id in ids) {
            val c = clusters[id]
            if (c == null) {
                val l = learned[id]
                clusters[id] = if (l != null) Cluster(SmoothSlice.clampMs(l), true) else Cluster(SmoothSlice.clampMs(startMs), false)
            } else if (!c.settled && !(id == currentId && phase == Phase.CANDIDATE) && c.slice != SmoothSlice.clampMs(startMs)) {
                c.slice = SmoothSlice.clampMs(startMs)
                if (id == currentId) { base = null; acc.reset(); phase = Phase.BASELINE; retries = 0 }
            }
        }
        if (currentId == null) currentId = clusters.entries.firstOrNull { !it.value.settled }?.key
    }

    /** Slice to use for [id] while learning, or null when the learner knows nothing about it. */
    fun sliceFor(id: String): Int? {
        val c = clusters[id] ?: return null
        return if (id == currentId && phase == Phase.CANDIDATE) candidate else c.slice
    }

    fun learnedMap(): Map<String, Int> =
        clusters.filter { it.value.settled }.mapValues { it.value.slice }

    val allSettled: Boolean get() = clusters.isNotEmpty() && clusters.values.all { it.settled }

    /** Forget a running trial without a change event (learning switched off). */
    fun abortTrial() {
        phase = Phase.BASELINE
        base = null
        acc.reset()
        eligibleSince = -1L
        retries = 0
    }

    /** Forget everything (learning switched on again). */
    fun reset() {
        clusters.clear()
        currentId = null
        abortTrial()
    }

    /**
     * Call once per control tick while learning is on. [eligible] = a long steady game session with the power
     * limit engaged (see the engine). Returns a [Change] when the slice changes or a cluster settles.
     */
    fun onTick(nowMs: Long, eligible: Boolean, sample: SmoothSlice.Sample): Change? {
        val id = currentId ?: return null
        val c = clusters[id] ?: return null
        if (!eligible) {
            eligibleSince = -1L
            acc.reset()
            if (phase == Phase.CANDIDATE) {
                val from = candidate
                phase = Phase.BASELINE
                base = null
                return Change(id, from, c.slice, ChangeKind.INTERRUPTED, "$id slice $from -> ${c.slice} ms (trial interrupted, back to the last good slice)")
            }
            base = null
            return null
        }
        if (eligibleSince < 0L) eligibleSince = nowMs
        if (nowMs - eligibleSince < SmoothSlice.WARMUP_MS) return null
        acc.add(nowMs, sample)
        if (acc.durationMs < SmoothSlice.WINDOW_MS || acc.ticksSoFar < SmoothSlice.MIN_WINDOW_TICKS) return null
        val stats = acc.stats()
        acc.reset()

        if (phase == Phase.BASELINE) {
            base = stats
            val floor = SmoothSlice.floorFor(stats.writeP90Ms)
            val next = SmoothSlice.nextShorter(c.slice, floor)
            if (next == null) {
                return settle(id, c, "shortest allowed (floor ${floor}ms from p90 write ${"%.1f".format(java.util.Locale.US, stats.writeP90Ms)}ms)")
            }
            candidate = next
            phase = Phase.CANDIDATE
            return Change(id, c.slice, next, ChangeKind.TRIAL, "$id slice ${c.slice} -> $next ms (trial)")
        }

        val b = base ?: run {
            phase = Phase.BASELINE
            return null
        }
        val tried = candidate
        val verdict = SmoothSlice.decide(b, stats, tried)
        val sum = SmoothSlice.summary(b, stats)
        when (verdict.kind) {
            SmoothSlice.Kind.BETTER -> {
                c.slice = tried
                base = stats
                retries = 0
                val floor = SmoothSlice.floorFor(stats.writeP90Ms)
                val next = SmoothSlice.nextShorter(tried, floor)
                if (next == null) {
                    phase = Phase.BASELINE
                    return settle(id, c, "kept ${tried}ms: ${verdict.reason} ($sum); shortest allowed")
                }
                candidate = next
                return Change(id, tried, next, ChangeKind.KEPT_TRYING, "$id slice $tried ms kept (${verdict.reason}), trying $next ms ($sum)")
            }
            SmoothSlice.Kind.INCONCLUSIVE -> {
                phase = Phase.BASELINE
                base = null
                retries++
                if (retries > SmoothSlice.MAX_RETRIES) {
                    return settle(id, c, "kept ${c.slice}ms: trial of ${tried}ms inconclusive ${retries} times (${verdict.reason})")
                }
                return Change(id, tried, c.slice, ChangeKind.REVERTED, "$id slice $tried -> ${c.slice} ms (${verdict.reason}; measuring again)")
            }
            else -> {
                phase = Phase.BASELINE
                val why = if (verdict.kind == SmoothSlice.Kind.WORSE) "worse: ${verdict.reason}" else "no gain"
                return settle(id, c, "kept ${c.slice}ms: ${tried}ms was $why ($sum)", revertFrom = tried)
            }
        }
    }

    private fun settle(id: String, c: Cluster, text: String, revertFrom: Int = c.slice): Change {
        c.settled = true
        base = null
        retries = 0
        acc.reset()
        phase = Phase.BASELINE
        currentId = clusters.entries.firstOrNull { !it.value.settled }?.key
        return Change(id, revertFrom, c.slice, ChangeKind.SETTLED, "$id slice settled at ${c.slice} ms ($text)")
    }
}
