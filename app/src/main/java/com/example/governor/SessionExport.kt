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
}
