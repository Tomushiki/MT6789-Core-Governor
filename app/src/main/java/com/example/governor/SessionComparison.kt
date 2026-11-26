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

import com.example.model.CompositeScoreWeights
import java.util.Locale
import kotlin.math.abs

object SessionScoring {
    /**
     * Pure composite game score (0..100). Re-normalizes across whichever metrics are valid
     * so sessions missing FPS or discharge power still produce a consistent number.
     */
    fun compositeScore(summary: MetricSummary, weights: CompositeScoreWeights = CompositeScoreWeights()): Float {
        if (summary.sampleCount <= 0) return Float.NaN
        var weightedSum = 0.0
        var totalWeight = 0.0

        fun addComponent(weight: Int, normalized0to100: Float) {
            if (weight <= 0 || !normalized0to100.isFinite()) return
            weightedSum += weight * normalized0to100.coerceIn(0f, 100f)
            totalWeight += weight
        }

        if (summary.p5Fps.isFinite() && summary.p5Fps >= 0f) {
            addComponent(weights.p5FpsPct, (summary.p5Fps / 90f).coerceIn(0f, 1f) * 100f)
        }
        if (summary.avgFps.isFinite() && summary.avgFps >= 0f) {
            addComponent(weights.avgFpsPct, (summary.avgFps / 90f).coerceIn(0f, 1f) * 100f)
        }
        if (summary.avgFpsPerWatt.isFinite() && summary.avgFpsPerWatt >= 0f) {
            addComponent(weights.fpsPerWattPct, (summary.avgFpsPerWatt / 25f).coerceIn(0f, 1f) * 100f)
        }
        if (summary.avgTempC.isFinite()) {
            val tempScore = ((58f - summary.avgTempC) / (58f - 30f)).coerceIn(0f, 1f) * 100f
            addComponent(weights.tempPct, tempScore)
        }
        if (summary.frameStabilityPct.isFinite() && summary.frameStabilityPct >= 0f) {
            addComponent(weights.stabilityPct, summary.frameStabilityPct.coerceIn(0f, 100f))
        }

        if (totalWeight <= 0.0) return Float.NaN
        return (weightedSum / totalWeight).toFloat()
    }
}

enum class MetricDirection {
    BETTER,
    WORSE,
    NEUTRAL,
    NA
}

enum class ComparisonMetricKind(val higherIsBetter: Boolean, val decimals: Int, val unit: String) {
    SCORE(true, 1, ""),
    AVG_FPS(true, 1, ""),
    P5_FPS(true, 1, ""),
    MIN_FPS(true, 0, ""),
    FPS_PER_WATT(true, 1, ""),
    WORK_PER_WATT(true, 2, ""),
    AVG_TEMP(false, 1, " C"),
    MAX_TEMP(false, 1, " C"),
    AVG_WATTS(false, 2, " W"),
    PEAK_WATTS(false, 2, " W"),
    ENERGY_WH(false, 2, " Wh"),
    STABILITY_PCT(true, 0, "%")
}

data class ComparisonCell(
    val value: Float,
    val formatted: String,
    val deltaValue: Float,
    val deltaText: String,
    val direction: MetricDirection
)

data class ComparisonMetricRow(
    val kind: ComparisonMetricKind,
    val cells: List<ComparisonCell>
)

data class SettingsDiffRow(
    val fieldKey: String,
    val values: List<String>
)

enum class ComparisonWarning {
    DIFFERENT_GAMES,
    INCLUDES_CHARGING,
    DURATION_MISMATCH,
    SHORT_SESSION,
    MID_SESSION_CHANGES,
    MISSING_SNAPSHOT
}

data class VerdictEntry(
    val sessionIndex: Int,
    val label: String,
    val fpsDelta: Float,
    val wattsDelta: Float,
    val tempDelta: Float,
    val scoreDelta: Float,
    val changedFields: List<Triple<String, String, String>>
)

data class SessionComparisonResult(
    val sessions: List<SessionInfo>,
    val metricRows: List<ComparisonMetricRow>,
    val settingsDiffs: List<SettingsDiffRow>,
    val warnings: Set<ComparisonWarning>,
    val verdicts: List<VerdictEntry>
)

data class DownsampledPoint(
    val elapsedSec: Float,
    val fps: Float,
    val watts: Float,
    val tempC: Float
)

object ChartDownsampler {
    fun downsample(samples: List<MetricSample>, maxPoints: Int = 90): List<DownsampledPoint> {
        if (samples.isEmpty()) return emptyList()
        val firstMs = samples.first().tMs
        if (samples.size <= maxPoints) {
            return samples.map { s ->
                DownsampledPoint(
                    elapsedSec = ((s.tMs - firstMs) / 1000f).coerceAtLeast(0f),
                    fps = if (s.fps >= 0f) s.fps else Float.NaN,
                    watts = if (s.isDischarge) s.watts else Float.NaN,
                    tempC = s.tempC
                )
            }
        }
        val bucketCount = maxPoints.coerceAtLeast(10)
        val out = ArrayList<DownsampledPoint>(bucketCount)
        val n = samples.size
        for (b in 0 until bucketCount) {
            val start = (b * n) / bucketCount
            val end = (((b + 1) * n) / bucketCount).coerceAtLeast(start + 1).coerceAtMost(n)
            var sumSec = 0.0
            var sumFps = 0.0
            var fpsN = 0
            var sumW = 0.0
            var wN = 0
            var sumT = 0.0
            var tN = 0
            for (i in start until end) {
                val s = samples[i]
                sumSec += ((s.tMs - firstMs) / 1000.0).coerceAtLeast(0.0)
                if (s.fps >= 0f && s.fps.isFinite()) {
                    sumFps += s.fps
                    fpsN++
                }
                if (s.isDischarge && s.watts.isFinite()) {
                    sumW += s.watts
                    wN++
                }
                if (s.tempC.isFinite()) {
                    sumT += s.tempC
                    tN++
                }
            }
            val count = (end - start).coerceAtLeast(1)
            out.add(
                DownsampledPoint(
                    elapsedSec = (sumSec / count).toFloat(),
                    fps = if (fpsN > 0) (sumFps / fpsN).toFloat() else Float.NaN,
                    watts = if (wN > 0) (sumW / wN).toFloat() else Float.NaN,
                    tempC = if (tN > 0) (sumT / tN).toFloat() else Float.NaN
                )
            )
        }
        return out
    }
}

object SessionComparisonLogic {

    private val FIELD_ORDER = listOf(
        SessionTweakSnapshot.KEY_POWER_LIMIT,
        SessionTweakSnapshot.KEY_POWER_TIMING,
        SessionTweakSnapshot.KEY_SMOOTH_POWER,
        SessionTweakSnapshot.KEY_TEMP_THROTTLE,
        SessionTweakSnapshot.KEY_TEMP_REDUCTION,
        SessionTweakSnapshot.KEY_TEMP_CURVE,
        SessionTweakSnapshot.KEY_TEMP_REACTION,
        SessionTweakSnapshot.KEY_AFTERBURN,
        SessionTweakSnapshot.KEY_CPU_HEADROOM,
        SessionTweakSnapshot.KEY_CPU_RAMP_UP,
        SessionTweakSnapshot.KEY_CPU_RAMP_DOWN,
        SessionTweakSnapshot.KEY_GPU_HEADROOM,
        SessionTweakSnapshot.KEY_GPU_RAMP_UP,
        SessionTweakSnapshot.KEY_GPU_RAMP_DOWN,
        SessionTweakSnapshot.KEY_CPU_RANGES,
        SessionTweakSnapshot.KEY_GPU_RANGE,
        SessionTweakSnapshot.KEY_RAM_PIN,
        SessionTweakSnapshot.KEY_STUTTER_HUNTER,
        SessionTweakSnapshot.KEY_GAME_THREADS,
        SessionTweakSnapshot.KEY_ADAPTIVE_REFRESH,
        SessionTweakSnapshot.KEY_PER_GAME_OVERRIDE,
        SessionTweakSnapshot.KEY_AUTO_TUNE
    )

    private fun fmtVal(v: Float, decimals: Int, unit: String): String {
        if (!v.isFinite()) return "-"
        return String.format(Locale.US, "%.${decimals}f%s", v, unit)
    }

    private fun metricValue(info: SessionInfo, kind: ComparisonMetricKind, weights: CompositeScoreWeights): Float {
        val s = info.summary
        return when (kind) {
            ComparisonMetricKind.SCORE -> SessionScoring.compositeScore(s, weights)
            ComparisonMetricKind.AVG_FPS -> s.avgFps
            ComparisonMetricKind.P5_FPS -> s.p5Fps
            ComparisonMetricKind.MIN_FPS -> s.minFps
            ComparisonMetricKind.FPS_PER_WATT -> s.avgFpsPerWatt
            ComparisonMetricKind.WORK_PER_WATT -> s.avgWorkPerWatt
            ComparisonMetricKind.AVG_TEMP -> s.avgTempC
            ComparisonMetricKind.MAX_TEMP -> s.maxTempC
            ComparisonMetricKind.AVG_WATTS -> s.avgWatts
            ComparisonMetricKind.PEAK_WATTS -> s.maxWatts
            ComparisonMetricKind.ENERGY_WH -> s.energyWh
            ComparisonMetricKind.STABILITY_PCT -> s.frameStabilityPct
        }
    }

    fun compare(
        sessions: List<SessionInfo>,
        weights: CompositeScoreWeights = CompositeScoreWeights()
    ): SessionComparisonResult {
        if (sessions.isEmpty()) {
            return SessionComparisonResult(emptyList(), emptyList(), emptyList(), emptySet(), emptyList())
        }
        val metricRows = ComparisonMetricKind.values().map { kind ->
            val baseVal = metricValue(sessions[0], kind, weights)
            val cells = sessions.mapIndexed { idx, info ->
                val v = metricValue(info, kind, weights)
                val formatted = fmtVal(v, kind.decimals, kind.unit)
                if (idx == 0 || !v.isFinite() || !baseVal.isFinite()) {
                    ComparisonCell(v, formatted, Float.NaN, if (idx == 0) "" else "-", MetricDirection.NA)
                } else {
                    val delta = v - baseVal
                    val eps = when (kind.decimals) {
                        0 -> 0.5f
                        1 -> 0.05f
                        else -> 0.005f
                    }
                    val dir = when {
                        abs(delta) < eps -> MetricDirection.NEUTRAL
                        (delta > 0f && kind.higherIsBetter) || (delta < 0f && !kind.higherIsBetter) -> MetricDirection.BETTER
                        else -> MetricDirection.WORSE
                    }
                    val arrow = if (delta > 0f) "↑" else if (delta < 0f) "↓" else ""
                    val sign = if (delta >= 0f) "+" else ""
                    val deltaStr = "$arrow $sign${String.format(Locale.US, "%.${kind.decimals}f%s", delta, kind.unit)}".trim()
                    ComparisonCell(v, formatted, delta, deltaStr, dir)
                }
            }
            ComparisonMetricRow(kind, cells)
        }

        val maps = sessions.map { it.snapshot?.toDisplayMap() }
        val diffRows = mutableListOf<SettingsDiffRow>()
        for (key in FIELD_ORDER) {
            val vals = maps.map { m -> m?.get(key) ?: "Not recorded" }
            if (vals.distinct().size > 1) {
                diffRows.add(SettingsDiffRow(key, vals))
            }
        }

        val warnings = LinkedHashSet<ComparisonWarning>()
        if (sessions.map { it.packageName }.distinct().size > 1) {
            warnings.add(ComparisonWarning.DIFFERENT_GAMES)
        }
        if (sessions.any { it.summary.includesCharging }) {
            warnings.add(ComparisonWarning.INCLUDES_CHARGING)
        }
        if (sessions.any { it.summary.sampleCount < MetricsRecorder.MIN_SESSION_SAMPLES }) {
            warnings.add(ComparisonWarning.SHORT_SESSION)
        }
        val durations = sessions.map { it.summary.durationMs.coerceAtLeast(1L) }
        val minDur = durations.minOrNull() ?: 1L
        val maxDur = durations.maxOrNull() ?: 1L
        if (sessions.size >= 2 && maxDur.toDouble() / minDur.toDouble() > 1.8) {
            warnings.add(ComparisonWarning.DURATION_MISMATCH)
        }
        if (sessions.any { it.midSessionChanges > 0 }) {
            warnings.add(ComparisonWarning.MID_SESSION_CHANGES)
        }
        if (sessions.any { it.snapshot == null }) {
            warnings.add(ComparisonWarning.MISSING_SNAPSHOT)
        }

        val base = sessions[0]
        val baseScore = SessionScoring.compositeScore(base.summary, weights)
        val baseMap = maps[0]
        val verdicts = (1 until sessions.size).map { idx ->
            val cur = sessions[idx]
            val curScore = SessionScoring.compositeScore(cur.summary, weights)
            val fpsDelta = if (cur.summary.avgFps.isFinite() && base.summary.avgFps.isFinite()) cur.summary.avgFps - base.summary.avgFps else Float.NaN
            val wDelta = if (cur.summary.avgWatts.isFinite() && base.summary.avgWatts.isFinite()) cur.summary.avgWatts - base.summary.avgWatts else Float.NaN
            val tDelta = if (cur.summary.avgTempC.isFinite() && base.summary.avgTempC.isFinite()) cur.summary.avgTempC - base.summary.avgTempC else Float.NaN
            val sDelta = if (curScore.isFinite() && baseScore.isFinite()) curScore - baseScore else Float.NaN
            val curMap = maps[idx]
            val changes = if (baseMap != null && curMap != null) {
                FIELD_ORDER.mapNotNull { k ->
                    val a = baseMap[k]
                    val b = curMap[k]
                    if (a != null && b != null && a != b) Triple(k, a, b) else null
                }
            } else emptyList()
            val letter = ('A' + idx).toString()
            VerdictEntry(idx, letter, fpsDelta, wDelta, tDelta, sDelta, changes)
        }

        return SessionComparisonResult(
            sessions = sessions,
            metricRows = metricRows,
            settingsDiffs = diffRows,
            warnings = warnings,
            verdicts = verdicts
        )
    }
}

enum class ImpactConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class TweakImpactEntry(
    val fieldKey: String,
    val fromValue: String,
    val toValue: String,
    val deltaScore: Float,
    val deltaFps: Float,
    val deltaP5Fps: Float,
    val deltaWatts: Float,
    val deltaTempC: Float,
    val fromCount: Int,
    val toCount: Int,
    val confidence: ImpactConfidence
) {
    val totalSessions: Int
        get() = fromCount + toCount
}

data class TweakImpactReport(
    val packageName: String,
    val analyzedSessionCount: Int,
    val bestTweaks: List<TweakImpactEntry>,
    val worstTweaks: List<TweakImpactEntry>
)

object TweakImpactAnalyzer {

    private data class GroupMeans(
        val value: String,
        val count: Int,
        val avgScore: Float,
        val avgFps: Float,
        val avgP5Fps: Float,
        val avgWatts: Float,
        val avgTempC: Float
    )

    private val RANKED_KEYS = listOf(
        SessionTweakSnapshot.KEY_POWER_LIMIT,
        SessionTweakSnapshot.KEY_POWER_TIMING,
        SessionTweakSnapshot.KEY_SMOOTH_POWER,
        SessionTweakSnapshot.KEY_TEMP_THROTTLE,
        SessionTweakSnapshot.KEY_TEMP_CURVE,
        SessionTweakSnapshot.KEY_AFTERBURN,
        SessionTweakSnapshot.KEY_CPU_HEADROOM,
        SessionTweakSnapshot.KEY_CPU_RAMP_UP,
        SessionTweakSnapshot.KEY_CPU_RAMP_DOWN,
        SessionTweakSnapshot.KEY_GPU_HEADROOM,
        SessionTweakSnapshot.KEY_GPU_RAMP_UP,
        SessionTweakSnapshot.KEY_GPU_RAMP_DOWN,
        SessionTweakSnapshot.KEY_CPU_RANGES,
        SessionTweakSnapshot.KEY_GPU_RANGE,
        SessionTweakSnapshot.KEY_RAM_PIN,
        SessionTweakSnapshot.KEY_STUTTER_HUNTER,
        SessionTweakSnapshot.KEY_GAME_THREADS,
        SessionTweakSnapshot.KEY_ADAPTIVE_REFRESH
    )

    private fun meanOf(values: List<Float>): Float {
        val valid = values.filter { it.isFinite() }
        return if (valid.isEmpty()) Float.NaN else (valid.sum() / valid.size)
    }

    private fun diffOrNaN(a: Float, b: Float): Float =
        if (a.isFinite() && b.isFinite()) a - b else Float.NaN

    fun analyze(
        packageName: String,
        allSessions: List<SessionInfo>,
        weights: CompositeScoreWeights = CompositeScoreWeights()
    ): TweakImpactReport {
        val gameSessions = allSessions.filter {
            it.packageName == packageName &&
                it.snapshot != null &&
                it.summary.sampleCount >= MetricsRecorder.MIN_SESSION_SAMPLES
        }
        if (gameSessions.size < 2) {
            return TweakImpactReport(packageName, gameSessions.size, emptyList(), emptyList())
        }

        // Prefer discharge-only clean sessions when at least 2 exist; otherwise use all valid snapshot sessions.
        val cleanSessions = gameSessions.filter { !it.summary.includesCharging && it.midSessionChanges == 0 }
        val pool = if (cleanSessions.size >= 2) cleanSessions else gameSessions

        val scoreByFile = pool.associate { it.fileName to SessionScoring.compositeScore(it.summary, weights) }
        val mapByFile = pool.associate { it.fileName to it.snapshot!!.toDisplayMap() }

        val best = mutableListOf<TweakImpactEntry>()
        val worst = mutableListOf<TweakImpactEntry>()

        for (key in RANKED_KEYS) {
            val grouped = LinkedHashMap<String, MutableList<SessionInfo>>()
            for (s in pool) {
                val v = mapByFile[s.fileName]?.get(key) ?: continue
                grouped.getOrPut(v) { mutableListOf() }.add(s)
            }
            if (grouped.size < 2) continue

            val means = grouped.entries.map { (valStr, list) ->
                GroupMeans(
                    value = valStr,
                    count = list.size,
                    avgScore = meanOf(list.map { scoreByFile[it.fileName] ?: Float.NaN }),
                    avgFps = meanOf(list.map { it.summary.avgFps }),
                    avgP5Fps = meanOf(list.map { it.summary.p5Fps }),
                    avgWatts = meanOf(list.map { it.summary.avgWatts }),
                    avgTempC = meanOf(list.map { it.summary.avgTempC })
                )
            }.filter { it.avgScore.isFinite() }

            if (means.size < 2) continue

            // Reference value: the most common value (tie-breaker: earliest tested).
            val ref = means.maxByOrNull { it.count } ?: continue
            for (alt in means) {
                if (alt.value == ref.value) continue
                val dScore = alt.avgScore - ref.avgScore
                if (abs(dScore) < 0.2f) continue
                val minCount = minOf(ref.count, alt.count)
                val confidence = when {
                    minCount < 3 -> ImpactConfidence.LOW
                    minCount < 6 -> ImpactConfidence.MEDIUM
                    else -> ImpactConfidence.HIGH
                }
                val entry = TweakImpactEntry(
                    fieldKey = key,
                    fromValue = ref.value,
                    toValue = alt.value,
                    deltaScore = dScore,
                    deltaFps = diffOrNaN(alt.avgFps, ref.avgFps),
                    deltaP5Fps = diffOrNaN(alt.avgP5Fps, ref.avgP5Fps),
                    deltaWatts = diffOrNaN(alt.avgWatts, ref.avgWatts),
                    deltaTempC = diffOrNaN(alt.avgTempC, ref.avgTempC),
                    fromCount = ref.count,
                    toCount = alt.count,
                    confidence = confidence
                )
                if (dScore > 0f) {
                    best.add(entry)
                } else {
                    worst.add(entry)
                }
            }
        }

        return TweakImpactReport(
            packageName = packageName,
            analyzedSessionCount = pool.size,
            bestTweaks = best.sortedByDescending { it.deltaScore },
            worstTweaks = worst.sortedBy { it.deltaScore }
        )
    }
}
