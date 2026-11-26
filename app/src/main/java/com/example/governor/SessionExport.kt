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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text summary of a saved game session, for sharing (Telegram etc). Pure. */
object SessionExport {
    private fun ok(v: Float) = !v.isNaN() && v >= 0f

    fun duration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun summaryText(
        title: String,
        startMs: Long,
        s: MetricSummary,
        device: String,
        appVersion: String
    ): String {
        val l = Locale.US
        val sb = StringBuilder()
        sb.append("CoreGovernor session: ").append(title).append('\n')
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm", l).format(Date(startMs)))
            .append(" - ").append(duration(s.durationMs)).append('\n')
        if (ok(s.avgFps)) sb.append(String.format(l, "FPS: avg %.1f", s.avgFps))
            .also {
                if (ok(s.p5Fps)) sb.append(String.format(l, ", 5%% low %.1f", s.p5Fps))
                if (ok(s.minFps)) sb.append(String.format(l, ", min %.0f", s.minFps))
                sb.append('\n')
            }
        if (ok(s.avgTempC)) {
            sb.append(String.format(l, "Temp: avg %.1f C", s.avgTempC))
            if (ok(s.maxTempC)) sb.append(String.format(l, ", max %.1f C", s.maxTempC))
            sb.append('\n')
        }
        if (ok(s.avgWatts) && s.avgWatts > 0f) {
            sb.append(String.format(l, "Power: avg %.2f W", s.avgWatts))
            if (ok(s.maxWatts)) sb.append(String.format(l, ", peak %.2f W", s.maxWatts))
            if (ok(s.minWatts) && s.minWatts > 0f) sb.append(String.format(l, ", min %.2f W", s.minWatts))
            sb.append('\n')
        }
        if (ok(s.avgFpsPerWatt)) sb.append(String.format(l, "FPS per watt: %.1f\n", s.avgFpsPerWatt))
        if (ok(s.energyWh) && s.energyWh > 0f) sb.append(String.format(l, "Energy: %.2f Wh\n", s.energyWh))
        if (s.includesCharging) sb.append("(session included charging; power stats cover discharge only)\n")
        sb.append(device).append(" - CoreGovernor ").append(appVersion)
        return sb.toString()
    }

    private fun fieldDisplayName(key: String): String = when (key) {
        SessionTweakSnapshot.KEY_POWER_LIMIT -> "Power limit"
        SessionTweakSnapshot.KEY_POWER_TIMING -> "Power timing"
        SessionTweakSnapshot.KEY_SMOOTH_POWER -> "Smooth power limit"
        SessionTweakSnapshot.KEY_TEMP_THROTTLE -> "Temp throttle"
        SessionTweakSnapshot.KEY_TEMP_REDUCTION -> "Temp reduction"
        SessionTweakSnapshot.KEY_TEMP_CURVE -> "Temp curve"
        SessionTweakSnapshot.KEY_TEMP_REACTION -> "Temp reaction"
        SessionTweakSnapshot.KEY_AFTERBURN -> "Afterburn"
        SessionTweakSnapshot.KEY_CPU_HEADROOM -> "CPU headroom"
        SessionTweakSnapshot.KEY_CPU_RAMP_UP -> "CPU ramp up"
        SessionTweakSnapshot.KEY_CPU_RAMP_DOWN -> "CPU ramp down"
        SessionTweakSnapshot.KEY_GPU_HEADROOM -> "GPU headroom"
        SessionTweakSnapshot.KEY_GPU_RAMP_UP -> "GPU ramp up"
        SessionTweakSnapshot.KEY_GPU_RAMP_DOWN -> "GPU ramp down"
        SessionTweakSnapshot.KEY_CPU_RANGES -> "CPU ranges"
        SessionTweakSnapshot.KEY_GPU_RANGE -> "GPU range"
        SessionTweakSnapshot.KEY_RAM_PIN -> "RAM pin"
        SessionTweakSnapshot.KEY_STUTTER_HUNTER -> "Stutter Hunter"
        SessionTweakSnapshot.KEY_GAME_THREADS -> "Game Thread Controller"
        SessionTweakSnapshot.KEY_ADAPTIVE_REFRESH -> "Adaptive refresh"
        SessionTweakSnapshot.KEY_PER_GAME_OVERRIDE -> "Per-game override"
        SessionTweakSnapshot.KEY_AUTO_TUNE -> "Auto-tune"
        else -> key
    }

    private fun metricDisplayName(kind: ComparisonMetricKind): String = when (kind) {
        ComparisonMetricKind.SCORE -> "Score"
        ComparisonMetricKind.AVG_FPS -> "Avg FPS"
        ComparisonMetricKind.P5_FPS -> "5% low FPS"
        ComparisonMetricKind.MIN_FPS -> "Min FPS"
        ComparisonMetricKind.FPS_PER_WATT -> "FPS/W"
        ComparisonMetricKind.WORK_PER_WATT -> "Work/W"
        ComparisonMetricKind.AVG_TEMP -> "Avg temp"
        ComparisonMetricKind.MAX_TEMP -> "Max temp"
        ComparisonMetricKind.AVG_WATTS -> "Avg power"
        ComparisonMetricKind.PEAK_WATTS -> "Peak power"
        ComparisonMetricKind.ENERGY_WH -> "Energy"
        ComparisonMetricKind.STABILITY_PCT -> "Frame stability"
    }

    fun comparisonText(
        result: SessionComparisonResult,
        appLabels: Map<String, String>,
        device: String,
        appVersion: String
    ): String {
        val l = Locale.US
        val sb = StringBuilder()
        val sessions = result.sessions
        if (sessions.isEmpty()) return ""
        sb.append("CoreGovernor comparison (").append(sessions.size).append(" sessions)\n")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", l)
        sessions.forEachIndexed { idx, s ->
            val letter = ('A' + idx)
            val title = appLabels[s.packageName] ?: s.packageName
            sb.append(letter).append(": ").append(title)
                .append(" - ").append(df.format(Date(s.startMs)))
                .append(" (").append(duration(s.summary.durationMs)).append(")\n")
        }
        sb.append("\nMetrics:\n")
        for (row in result.metricRows) {
            val line = row.cells.mapIndexed { idx, c ->
                val letter = ('A' + idx)
                if (idx == 0 || c.deltaText.isBlank()) "$letter=${c.formatted}" else "$letter=${c.formatted} (${c.deltaText})"
            }.joinToString(" | ")
            sb.append("- ").append(metricDisplayName(row.kind)).append(": ").append(line).append('\n')
        }
        if (result.settingsDiffs.isNotEmpty()) {
            sb.append("\nSettings differences:\n")
            for (d in result.settingsDiffs) {
                val vals = d.values.mapIndexed { idx, v -> "${'A' + idx}=$v" }.joinToString(" | ")
                sb.append("- ").append(fieldDisplayName(d.fieldKey)).append(": ").append(vals).append('\n')
            }
        }
        if (result.verdicts.isNotEmpty()) {
            sb.append("\nVerdict:\n")
            for (v in result.verdicts) {
                val parts = mutableListOf<String>()
                if (v.fpsDelta.isFinite()) parts.add(String.format(l, "%+.1f FPS avg", v.fpsDelta))
                if (v.wattsDelta.isFinite()) parts.add(String.format(l, "%+.2f W", v.wattsDelta))
                if (v.tempDelta.isFinite()) parts.add(String.format(l, "%+.1f C", v.tempDelta))
                val chg = v.changedFields.take(4).joinToString(", ") { (k, a, b) -> "${fieldDisplayName(k)} $a -> $b" }
                sb.append("Session ").append(v.label).append(": ").append(parts.joinToString(", "))
                if (chg.isNotBlank()) sb.append(". Changes: ").append(chg)
                sb.append('\n')
            }
        }
        if (result.warnings.isNotEmpty()) {
            sb.append("\nWarnings:\n")
            for (w in result.warnings) {
                val msg = when (w) {
                    ComparisonWarning.DIFFERENT_GAMES -> "Different games selected"
                    ComparisonWarning.INCLUDES_CHARGING -> "At least one session included charging"
                    ComparisonWarning.DURATION_MISMATCH -> "Session durations differ substantially"
                    ComparisonWarning.SHORT_SESSION -> "At least one session has fewer than ${MetricsRecorder.MIN_SESSION_SAMPLES} samples"
                    ComparisonWarning.MID_SESSION_CHANGES -> "Settings changed mid-session in at least one run"
                    ComparisonWarning.MISSING_SNAPSHOT -> "Settings were not recorded for at least one session"
                }
                sb.append("- ").append(msg).append('\n')
            }
        }
        sb.append('\n').append(device).append(" - CoreGovernor ").append(appVersion)
        return sb.toString()
    }
}
