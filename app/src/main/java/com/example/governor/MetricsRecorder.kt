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

import com.example.model.BatteryState
import com.example.model.ClusterLiveState
import com.example.model.GovernorMode
import com.example.model.GpuLiveState
import com.example.model.LogLevel
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * One recorded point (about one per second).
 * [fps] is -1 when the app has no FPS reading. [work] is the "busy GHz" estimate, see [MetricsMath.workScore].
 */
data class MetricSample(
    val tMs: Long,
    val tempC: Float,
    val watts: Float,
    val fps: Float,
    val cpuLoadPct: Float,
    val gpuLoadPct: Float,
    val work: Float,
    val batteryState: BatteryState = BatteryState.DISCHARGING,
    val thermalThrottled: Boolean = false,
    val powerThrottled: Boolean = false
) {
    val isDischarge: Boolean
        get() = batteryState == BatteryState.DISCHARGING

    /** Frames per watt, or NaN when FPS or discharge power is not available. */
    val fpsPerWatt: Float
        get() = if (fps >= 0f && isDischarge) MetricsMath.ratio(fps, watts) else Float.NaN

    /** Estimated work per watt (busy GHz per watt), or NaN when discharge power is not available. */
    val workPerWatt: Float
        get() = if (isDischarge) MetricsMath.ratio(work, watts) else Float.NaN
}

data class MetricSummary(
    val durationMs: Long,
    val sampleCount: Int,
    val avgFps: Float,
    val minFps: Float,
    val p5Fps: Float,
    val avgTempC: Float,
    val maxTempC: Float,
    val avgWatts: Float,
    val maxWatts: Float,
    val avgFpsPerWatt: Float,
    val avgWorkPerWatt: Float,
    val energyWh: Float,
    val includesCharging: Boolean = false,
    /** Lowest valid (above 0 W) discharge power seen. NaN when there is none or the file predates 2.7.2. */
    val minWatts: Float = Float.NaN,
    // v4.0.0 extended figures (NaN / 0 for files saved before 4.0.0)
    val p1Fps: Float = Float.NaN,
    val p50Fps: Float = Float.NaN,
    val frameStabilityPct: Float = Float.NaN,
    val thermalThrottleSec: Int = 0,
    val powerThrottleSec: Int = 0,
    /** v4.0.7: seconds where the GAME held a flat cap (lobby/menu/pause); left out of the FPS figures. Not saved to file. */
    val gameLimitedSec: Int = 0,
    /** The cap level (FPS) those seconds sat on, or 0 when there were none. */
    val gameLimitedFps: Int = 0
)

/** A saved game session. The summary and tweak snapshot live in the file header so listing never has to read samples. */
data class SessionInfo(
    val fileName: String,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val summary: MetricSummary,
    val snapshot: SessionTweakSnapshot? = null,
    val midSessionChanges: Int = 0,
    val kept: Boolean = false
)

object MetricsMath {
    /** Below this power the ratio is meaningless (reading missing or near zero). */
    const val MIN_WATTS_FOR_RATIO = 0.3f

    /** Gaps longer than this are not counted as energy use (screen-off pauses, restarts). */
    const val MAX_ENERGY_GAP_MS = 5_000L

    /**
     * Rough "work done right now" in busy GHz: for every CPU cluster, frequency x cores x load,
     * plus GPU frequency x load. It is not a benchmark score, only a consistent number to compare
     * efficiency over time on the same phone.
     */
    fun workScore(clusters: List<ClusterLiveState>, gpu: GpuLiveState): Float {
        var total = 0.0
        for (c in clusters) {
            val khz = if (c.actualFreqKhz > 0L) c.actualFreqKhz else c.targetFreqKhz
            val cores = c.relatedCpus.size.coerceAtLeast(1)
            val load = c.smoothedLoadPct.coerceIn(0f, 100f) / 100.0
            total += (khz / 1_000_000.0) * cores * load
        }
        if (gpu.available) {
            val khz = if (gpu.currentFreqKhz > 0L) gpu.currentFreqKhz else gpu.targetFreqKhz
            val load = gpu.smoothedLoadPct.coerceIn(0f, 100f) / 100.0
            total += (khz / 1_000_000.0) * load
        }
        return total.toFloat()
    }

    fun ratio(value: Float, watts: Float): Float =
        if (watts >= MIN_WATTS_FOR_RATIO && value.isFinite()) value / watts else Float.NaN

    /** NaN-aware moving average; NaN inputs stay NaN. */
    fun movingAverage(values: List<Float>, window: Int): List<Float> {
        if (window <= 1 || values.isEmpty()) return values
        val out = ArrayList<Float>(values.size)
        for (i in values.indices) {
            if (values[i].isNaN()) {
                out.add(Float.NaN)
                continue
            }
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - window + 1)..i) {
                val v = values[j]
                if (!v.isNaN()) {
                    sum += v
                    n++
                }
            }
            out.add(if (n == 0) Float.NaN else (sum / n).toFloat())
        }
        return out
    }

    /** Seconds spent strictly above [thresholdC] across samples. */
    fun timeAboveTempSec(samples: List<MetricSample>, thresholdC: Float): Int =
        samples.count { !it.tempC.isNaN() && it.tempC >= thresholdC }

    /** FPS levels games commonly hold in lobbies, menus and pause screens to save power. */
    val GAME_CAP_LEVELS = floatArrayOf(30f)
    /** A sample counts as "on the cap" when within this many FPS of it. */
    const val GAME_CAP_TOLERANCE_FPS = 2.5f
    /** A cap plateau must be at least this many consecutive samples (about 1 s each) to be called a game cap. */
    const val GAME_CAP_MIN_RUN = 2
    /** ...and it must stay flat: the highest and lowest reading in the run differ by at most this much. */
    const val GAME_CAP_MAX_SPREAD_FPS = 2.5f
    /** The session must otherwise run clearly above the cap (more than 20% over it) for this share of its FPS samples. */
    const val GAME_CAP_MIN_ABOVE_PCT = 25

    /**
     * Marks samples where the game itself held a flat FPS cap (typically 30 FPS in a lobby, menu or pause) so they are not
     * mistaken for the governor's throttling. Only a flat run of at least [GAME_CAP_MIN_RUN] samples counts, and only when
     * the session mostly runs well above the cap; a game that simply runs at 30 all the time is left alone.
     */
    fun gameLimitedMask(samples: List<MetricSample>): BooleanArray {
        val n = samples.size
        val mask = BooleanArray(n)
        if (n < GAME_CAP_MIN_RUN) return mask
        val validTotal = samples.count { it.fps >= 0f }
        if (validTotal == 0) return mask
        for (cap in GAME_CAP_LEVELS) {
            val above = samples.count { it.fps > cap * 1.2f }
            if (above * 100 < validTotal * GAME_CAP_MIN_ABOVE_PCT) continue
            var i = 0
            while (i < n) {
                if (!nearCap(samples[i].fps, cap)) { i++; continue }
                var j = i
                var lo = samples[i].fps
                var hi = lo
                while (j + 1 < n && nearCap(samples[j + 1].fps, cap)) {
                    j++
                    val v = samples[j].fps
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                if (j - i + 1 >= GAME_CAP_MIN_RUN && hi - lo <= GAME_CAP_MAX_SPREAD_FPS) {
                    for (k in i..j) mask[k] = true
                }
                i = j + 1
            }
        }
        return mask
    }

    private fun nearCap(fps: Float, cap: Float): Boolean =
        fps >= 0f && kotlin.math.abs(fps - cap) <= GAME_CAP_TOLERANCE_FPS

    fun summarize(samples: List<MetricSample>): MetricSummary {
        if (samples.isEmpty()) {
            return MetricSummary(0L, 0, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0f, false)
        }
        var sumTemp = 0.0
        var maxTemp = Float.NEGATIVE_INFINITY
        var sumW = 0.0
        var wattN = 0
        var maxW = 0f
        var minW = Float.POSITIVE_INFINITY
        var sumFps = 0.0
        var fpsN = 0
        var minFps = Float.POSITIVE_INFINITY
        val fpsValues = ArrayList<Float>()
        // For the efficiency averages only discharging samples with a usable power reading count, and the
        // average is total-output / total-power, which is the honest "frames per joule" figure.
        var ratioFps = 0.0
        var ratioWattsForFps = 0.0
        var ratioWork = 0.0
        var ratioWattsForWork = 0.0
        var energyWs = 0.0
        var includesCharging = false
        var thermalSec = 0
        var powerSec = 0
        val limitedMask = gameLimitedMask(samples)
        val gameLimitedSec = limitedMask.count { it }

        for ((i, s) in samples.withIndex()) {
            val gameLimited = limitedMask[i]
            sumTemp += s.tempC
            if (s.tempC > maxTemp) maxTemp = s.tempC
            if (s.thermalThrottled) thermalSec++
            if (s.powerThrottled) powerSec++
            if (s.batteryState == BatteryState.CHARGING || s.batteryState == BatteryState.FULL_PLUGGED) {
                includesCharging = true
            }
            if (s.isDischarge) {
                sumW += s.watts
                wattN++
                if (s.watts > maxW) maxW = s.watts
                // A 0 W reading means the sensor gave nothing, so it must not become the "minimum".
                if (s.watts > 0f && s.watts < minW) minW = s.watts
                if (s.watts >= MIN_WATTS_FOR_RATIO) {
                    ratioWork += s.work
                    ratioWattsForWork += s.watts
                    if (s.fps >= 0f && !gameLimited) {
                        ratioFps += s.fps
                        ratioWattsForFps += s.watts
                    }
                }
                if (i > 0 && samples[i - 1].isDischarge) {
                    val dt = (s.tMs - samples[i - 1].tMs).coerceIn(0L, MAX_ENERGY_GAP_MS)
                    energyWs += s.watts * (dt / 1000.0)
                }
            }
            if (s.fps >= 0f && !gameLimited) {
                sumFps += s.fps
                fpsN++
                fpsValues.add(s.fps)
                if (s.fps < minFps) minFps = s.fps
            }
        }
        fpsValues.sort()
        val avgFps = if (fpsN == 0) Float.NaN else (sumFps / fpsN).toFloat()
        val p1 = if (fpsValues.isEmpty()) Float.NaN else fpsValues[((fpsValues.size - 1) * 0.01).toInt()]
        val p5 = if (fpsValues.isEmpty()) Float.NaN else fpsValues[((fpsValues.size - 1) * 0.05).toInt()]
        val p50 = if (fpsValues.isEmpty()) Float.NaN else fpsValues[((fpsValues.size - 1) * 0.50).toInt()]
        val stabilityPct = if (fpsValues.isNotEmpty() && !avgFps.isNaN() && avgFps > 0f) {
            val lo = avgFps * 0.90f
            val hi = avgFps * 1.10f
            val stableCount = fpsValues.count { it in lo..hi }
            (stableCount * 100f) / fpsValues.size
        } else Float.NaN
        val n = samples.size
        return MetricSummary(
            durationMs = samples.last().tMs - samples.first().tMs,
            sampleCount = n,
            avgFps = avgFps,
            minFps = if (fpsN == 0) Float.NaN else minFps,
            p5Fps = p5,
            avgTempC = (sumTemp / n).toFloat(),
            maxTempC = maxTemp,
            avgWatts = if (wattN == 0) Float.NaN else (sumW / wattN).toFloat(),
            maxWatts = if (wattN == 0) Float.NaN else maxW,
            avgFpsPerWatt = if (ratioWattsForFps > 0.0) (ratioFps / ratioWattsForFps).toFloat() else Float.NaN,
            avgWorkPerWatt = if (ratioWattsForWork > 0.0) (ratioWork / ratioWattsForWork).toFloat() else Float.NaN,
            energyWh = (energyWs / 3600.0).toFloat(),
            includesCharging = includesCharging,
            minWatts = if (minW.isInfinite()) Float.NaN else minW,
            p1Fps = p1,
            p50Fps = p50,
            frameStabilityPct = stabilityPct,
            thermalThrottleSec = thermalSec,
            powerThrottleSec = powerSec,
            gameLimitedSec = gameLimitedSec,
            gameLimitedFps = if (gameLimitedSec > 0) GAME_CAP_LEVELS.first().toInt() else 0
        )
    }

    // ---- File format: one header line, then one CSV line per sample ----

    private fun f(v: Float): String =
        if (v.isNaN()) "NaN" else String.format(Locale.ROOT, "%.2f", v)

    private fun stateToCode(s: BatteryState): String = when (s) {
        BatteryState.DISCHARGING -> "D"
        BatteryState.CHARGING -> "C"
        BatteryState.FULL_PLUGGED -> "F"
        BatteryState.UNKNOWN -> "U"
    }

    private fun codeToState(code: String?): BatteryState = when (code?.trim()) {
        "C" -> BatteryState.CHARGING
        "F" -> BatteryState.FULL_PLUGGED
        "U" -> BatteryState.UNKNOWN
        else -> BatteryState.DISCHARGING
    }

    fun encodeSample(s: MetricSample): String {
        val flags = (if (s.thermalThrottled) 1 else 0) or (if (s.powerThrottled) 2 else 0)
        val base = "${s.tMs},${f(s.tempC)},${f(s.watts)},${f(s.fps)},${f(s.cpuLoadPct)},${f(s.gpuLoadPct)},${f(s.work)},${stateToCode(s.batteryState)}"
        return if (flags == 0) base else "$base,$flags"
    }

    fun decodeSample(line: String): MetricSample? {
        val p = line.split(',')
        if (p.size < 7) return null
        val t = p[0].toLongOrNull() ?: return null
        val v = FloatArray(6)
        for (i in 0 until 6) v[i] = p[i + 1].toFloatOrNull() ?: return null
        val state = if (p.size >= 8) codeToState(p[7]) else BatteryState.DISCHARGING
        val flags = if (p.size >= 9) p[8].trim().toIntOrNull() ?: 0 else 0
        return MetricSample(
            tMs = t,
            tempC = v[0],
            watts = v[1],
            fps = v[2],
            cpuLoadPct = v[3],
            gpuLoadPct = v[4],
            work = v[5],
            batteryState = state,
            thermalThrottled = (flags and 1) != 0,
            powerThrottled = (flags and 2) != 0
        )
    }

    fun encodeHeader(
        pkg: String,
        startMs: Long,
        endMs: Long,
        s: MetricSummary,
        snapshot: SessionTweakSnapshot? = null,
        midSessionChanges: Int = 0,
        kept: Boolean = false
    ): String =
        listOf(
            "#CG1", pkg.replace('|', '_').replace('\n', ' '), startMs, endMs, s.sampleCount,
            f(s.avgFps), f(s.minFps), f(s.p5Fps), f(s.avgTempC), f(s.maxTempC),
            f(s.avgWatts), f(s.maxWatts), f(s.avgFpsPerWatt), f(s.avgWorkPerWatt), f(s.energyWh),
            if (s.includesCharging) "1" else "0",
            f(s.minWatts),
            f(s.p1Fps),
            f(s.p50Fps),
            f(s.frameStabilityPct),
            s.thermalThrottleSec.coerceAtLeast(0),
            s.powerThrottleSec.coerceAtLeast(0),
            midSessionChanges.coerceAtLeast(0),
            if (kept) "1" else "0",
            snapshot?.encode().orEmpty()
        ).joinToString("|")

    fun decodeHeader(fileName: String, line: String): SessionInfo? {
        val p = line.split('|')
        if (p.size < 15 || p[0] != "#CG1") return null
        val start = p[2].toLongOrNull() ?: return null
        val end = p[3].toLongOrNull() ?: return null
        val n = p[4].toIntOrNull() ?: return null
        fun g(i: Int) = p[i].toFloatOrNull() ?: Float.NaN
        fun gOpt(i: Int) = p.getOrNull(i)?.toFloatOrNull() ?: Float.NaN
        val hasCharging = p.getOrNull(15) == "1"
        // Field 16 (minimum power) was added in 2.7.2; older files do not have it.
        val minW = gOpt(16)
        // Fields 17..24 were added in 4.0.0; older files return NaN / 0 / null snapshot.
        val p1 = gOpt(17)
        val p50 = gOpt(18)
        val stability = gOpt(19)
        val thermalSec = p.getOrNull(20)?.toIntOrNull() ?: 0
        val powerSec = p.getOrNull(21)?.toIntOrNull() ?: 0
        val midChanges = p.getOrNull(22)?.toIntOrNull() ?: 0
        val kept = p.getOrNull(23) == "1"
        val snap = SessionTweakSnapshot.decode(p.getOrNull(24))
        return SessionInfo(
            fileName = fileName,
            packageName = p[1],
            startMs = start,
            endMs = end,
            summary = MetricSummary(
                durationMs = end - start,
                sampleCount = n,
                avgFps = g(5), minFps = g(6), p5Fps = g(7),
                avgTempC = g(8), maxTempC = g(9),
                avgWatts = g(10), maxWatts = g(11),
                avgFpsPerWatt = g(12), avgWorkPerWatt = g(13),
                energyWh = g(14),
                includesCharging = hasCharging,
                minWatts = minW,
                p1Fps = p1,
                p50Fps = p50,
                frameStabilityPct = stability,
                thermalThrottleSec = thermalSec,
                powerThrottleSec = powerSec
            ),
            snapshot = snap,
            midSessionChanges = midChanges,
            kept = kept
        )
    }
}

/**
 * Keeps the live history (about 30 minutes at 1 sample per second, in memory only) and records
 * every Game-mode session to its own small file so runs can be compared later.
 *
 * Cost: one cheap check per control tick; a sample is built at most once per second and files are
 * written on an IO thread only when a session ends. No extra polling of the hardware.
 */
class MetricsRecorder(
    private val dir: File,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String, String) -> Unit
) {
    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val LIVE_CAPACITY = 1_800
        const val MAX_SESSION_SAMPLES = 7_200
        const val MIN_SESSION_SAMPLES = 20
        const val MAX_SAVED_SESSIONS = 40
        const val SESSION_GAP_MS = 15_000L
    }

    private val _live = MutableStateFlow<List<MetricSample>>(emptyList())
    val live: StateFlow<List<MetricSample>> = _live.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    /** True while a game session is being recorded. */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Called for every recorded sample (about 1/s) with whether a game is in the foreground. */
    @Volatile
    var onSample: ((MetricSample, Boolean) -> Unit)? = null

    /** Called once when a game session begins to capture the active tweak snapshot. */
    @Volatile
    var snapshotProvider: ((String) -> SessionTweakSnapshot?)? = null

    /** Called on IO thread after a game session is saved cleanly. */
    @Volatile
    var onSessionSaved: ((SessionInfo) -> Unit)? = null

    fun sessionFile(info: SessionInfo): File = File(dir, info.fileName)

    private val liveBuf = ArrayDeque<MetricSample>()
    private var lastSampleMs = 0L

    /** v2.7.4: the live list is only published while the Metrics tab is on screen. Recording is unaffected. */
    private var liveVisible = false

    /**
     * Tells the recorder whether anyone is looking at the live list. Becoming visible publishes the
     * buffer once; hiding releases the published copy. The buffer itself keeps filling either way.
     */
    @Synchronized
    fun setLiveVisible(visible: Boolean) {
        if (visible == liveVisible) return
        liveVisible = visible
        _live.value = if (visible) liveBuf.toList() else emptyList()
    }

    private var sessionSamples = ArrayList<MetricSample>()
    private var sessionPkg = ""
    private var sessionActive = false
    private var sessionStartSnapshot: SessionTweakSnapshot? = null
    private var sessionMidChanges = 0
    private val saveLock = Any()

    init {
        scope.launch(Dispatchers.IO) { refreshSessions() }
    }

    /** Marks that user settings were modified while a game session was already running. */
    @Synchronized
    fun recordMidSessionConfigChange() {
        if (sessionActive) {
            sessionMidChanges++
        }
    }

    /** Called from the control loop after each telemetry push. Cheap unless a sample is due. */
    @Synchronized
    fun onTelemetry(t: TelemetryState, nowMs: Long) {
        if (!t.isRunning) {
            endSessionLocked()
            return
        }
        if (nowMs - lastSampleMs < SAMPLE_INTERVAL_MS) return

        // A long pause (screen-off saving, restart) closes the current session.
        if (sessionActive && lastSampleMs > 0L && nowMs - lastSampleMs > SESSION_GAP_MS) {
            endSessionLocked()
        }
        lastSampleMs = nowMs

        val recordedWatts = when (t.batteryState) {
            BatteryState.CHARGING -> t.chargeWatts
            BatteryState.FULL_PLUGGED -> 0f
            BatteryState.DISCHARGING, BatteryState.UNKNOWN -> t.watts
        }
        val sample = MetricSample(
            tMs = nowMs,
            tempC = t.temperatureC,
            watts = recordedWatts,
            fps = t.fps,
            cpuLoadPct = t.totalCpuLoadPct,
            gpuLoadPct = t.gpu.smoothedLoadPct,
            work = MetricsMath.workScore(t.clusters, t.gpu),
            batteryState = t.batteryState,
            thermalThrottled = t.tempThrottleActive,
            powerThrottled = t.powerThrottleActive
        )

        liveBuf.addLast(sample)
        while (liveBuf.size > LIVE_CAPACITY) liveBuf.removeFirst()
        if (liveVisible) _live.value = liveBuf.toList()

        val inGame = t.activeMode == GovernorMode.GAME
        onSample?.invoke(sample, inGame)
        if (inGame) {
            if (sessionActive && sessionPkg != t.foregroundPackage && t.foregroundPackage.isNotBlank()) {
                endSessionLocked() // switched to a different game
            }
            if (!sessionActive) {
                sessionActive = true
                sessionPkg = t.foregroundPackage
                sessionSamples = ArrayList()
                sessionMidChanges = 0
                sessionStartSnapshot = snapshotProvider?.invoke(sessionPkg)
                _recording.value = true
            }
            sessionSamples.add(sample)
            if (sessionSamples.size >= MAX_SESSION_SAMPLES) endSessionLocked()
        } else if (sessionActive) {
            endSessionLocked()
        }
    }

    /** Called when the governor stops, so a running session is saved. */
    @Synchronized
    fun onStopped() = endSessionLocked()

    @Synchronized
    fun clearLive() {
        liveBuf.clear()
        _live.value = emptyList()
    }

    private fun endSessionLocked() {
        if (!sessionActive) return
        val samples = sessionSamples
        val pkg = sessionPkg
        val snap = sessionStartSnapshot
        val midChanges = sessionMidChanges
        sessionActive = false
        sessionSamples = ArrayList()
        sessionPkg = ""
        sessionStartSnapshot = null
        sessionMidChanges = 0
        _recording.value = false
        if (samples.size < MIN_SESSION_SAMPLES) return
        scope.launch(Dispatchers.IO) { saveSession(pkg, samples, snap, midChanges) }
    }

    private fun saveSession(
        pkg: String,
        samples: List<MetricSample>,
        snapshot: SessionTweakSnapshot?,
        midChanges: Int
    ) {
        synchronized(saveLock) {
            try {
                dir.mkdirs()
                val start = samples.first().tMs
                val end = samples.last().tMs
                val cleanPkg = pkg.ifBlank { "unknown" }
                val safePkg = cleanPkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val fileName = "${start}_$safePkg.csv"
                val file = File(dir, fileName)
                val summary = MetricsMath.summarize(samples)
                file.bufferedWriter().use { w ->
                    w.write(
                        MetricsMath.encodeHeader(
                            pkg = cleanPkg,
                            startMs = start,
                            endMs = end,
                            s = summary,
                            snapshot = snapshot,
                            midSessionChanges = midChanges,
                            kept = false
                        )
                    )
                    w.newLine()
                    for (s in samples) {
                        w.write(MetricsMath.encodeSample(s))
                        w.newLine()
                    }
                }
                pruneOld()
                refreshSessions()
                val savedInfo = SessionInfo(
                    fileName = fileName,
                    packageName = cleanPkg,
                    startMs = start,
                    endMs = end,
                    summary = summary,
                    snapshot = snapshot,
                    midSessionChanges = midChanges,
                    kept = false
                )
                if (summary.gameLimitedSec > 0) {
                    log(
                        LogLevel.INFO,
                        "Metrics",
                        "Game held a flat ~${summary.gameLimitedFps} FPS cap for ${summary.gameLimitedSec}s " +
                            "(lobby/menu/pause, set by the game, not by CoreGovernor); left out of the FPS figures and Auto-Tune."
                    )
                }
                log(LogLevel.INFO, "Metrics", "Saved session $safePkg (${samples.size}s).")
                onSessionSaved?.invoke(savedInfo)
            } catch (e: Exception) {
                log(LogLevel.WARN, "Metrics", "Could not save session: ${e.message}")
            }
        }
    }

    private fun pruneOld() {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name } ?: return
        if (files.size <= MAX_SAVED_SESSIONS) return
        var excess = files.size - MAX_SAVED_SESSIONS
        for (f in files) {
            if (excess <= 0) break
            val kept = try {
                val line = f.bufferedReader().use { it.readLine() }
                if (line != null) MetricsMath.decodeHeader(f.name, line)?.kept == true else false
            } catch (_: Exception) {
                false
            }
            if (!kept) {
                f.delete()
                excess--
            }
        }
    }

    private fun refreshSessions() {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") } ?: run {
            _sessions.value = emptyList()
            return
        }
        val list = files.mapNotNull { f ->
            try {
                val header = f.bufferedReader().use { it.readLine() } ?: return@mapNotNull null
                MetricsMath.decodeHeader(f.name, header)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startMs }
        _sessions.value = list
    }

    suspend fun setSessionKept(info: SessionInfo, kept: Boolean) = withContext(Dispatchers.IO) {
        synchronized(saveLock) {
            val file = File(dir, info.fileName)
            if (!file.exists()) return@withContext
            try {
                val lines = file.readLines()
                if (lines.isEmpty()) return@withContext
                val decoded = MetricsMath.decodeHeader(info.fileName, lines[0]) ?: info
                val newHeader = MetricsMath.encodeHeader(
                    pkg = decoded.packageName,
                    startMs = decoded.startMs,
                    endMs = decoded.endMs,
                    s = decoded.summary,
                    snapshot = decoded.snapshot,
                    midSessionChanges = decoded.midSessionChanges,
                    kept = kept
                )
                val tmp = File(dir, "${info.fileName}.tmp")
                tmp.bufferedWriter().use { w ->
                    w.write(newHeader)
                    for (i in 1 until lines.size) {
                        w.newLine()
                        w.write(lines[i])
                    }
                    w.newLine()
                }
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
                refreshSessions()
            } catch (e: Exception) {
                log(LogLevel.WARN, "Metrics", "Could not update keep flag: ${e.message}")
            }
        }
    }

    suspend fun loadSession(info: SessionInfo): List<MetricSample> = withContext(Dispatchers.IO) {
        try {
            File(dir, info.fileName).useLines { lines ->
                lines.drop(1).mapNotNull { MetricsMath.decodeSample(it) }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun deleteSession(info: SessionInfo) = withContext(Dispatchers.IO) {
        synchronized(saveLock) {
            File(dir, info.fileName).delete()
            refreshSessions()
        }
    }

    suspend fun deleteAllSessions(includeKept: Boolean = false) = withContext(Dispatchers.IO) {
        synchronized(saveLock) {
            dir.listFiles { f -> f.name.endsWith(".csv") }?.forEach { f ->
                if (includeKept) {
                    f.delete()
                } else {
                    val kept = try {
                        val line = f.bufferedReader().use { it.readLine() }
                        line != null && MetricsMath.decodeHeader(f.name, line)?.kept == true
                    } catch (_: Exception) {
                        false
                    }
                    if (!kept) f.delete()
                }
            }
            refreshSessions()
        }
    }
}
