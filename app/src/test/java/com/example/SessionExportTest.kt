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

import com.example.governor.MetricSummary
import com.example.governor.SessionExport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExportTest {
    private fun summary(fps: Float = 58.2f, minW: Float = Float.NaN) = MetricSummary(
        durationMs = 905_000L, sampleCount = 900, avgFps = fps, minFps = 40f, p5Fps = 47f,
        avgTempC = 39.5f, maxTempC = 42f, avgWatts = 3.2f, maxWatts = 5.1f,
        avgFpsPerWatt = 18.1f, avgWorkPerWatt = 2f, energyWh = 0.8f, includesCharging = false, minWatts = minW
    )

    @Test
    fun textHasKeyNumbersAndDevice() {
        val t = SessionExport.summaryText("Game", 0L, summary(), "Spark 40 Pro", "2.7.3")
        assertTrue(t.contains("avg 58.2"))
        assertTrue(t.contains("5% low 47.0"))
        assertTrue(t.contains("FPS per watt: 18.1"))
        assertTrue(t.contains("Spark 40 Pro - CoreGovernor 2.7.3"))
        assertTrue(t.contains("15m 5s"))
    }

    @Test
    fun blankValuesAreSkipped() {
        val t = SessionExport.summaryText("Game", 0L, summary(fps = Float.NaN), "X", "1")
        assertFalse(t.contains("FPS: avg"))
        assertFalse(t.contains(", min 0."))
    }
}
