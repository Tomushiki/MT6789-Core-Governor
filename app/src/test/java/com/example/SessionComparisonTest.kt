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

import com.example.governor.ChartDownsampler
import com.example.governor.ComparisonMetricKind
import com.example.governor.ComparisonWarning
import com.example.governor.ImpactConfidence
import com.example.governor.MetricDirection
import com.example.governor.MetricSample
import com.example.governor.MetricSummary
import com.example.governor.SessionComparisonLogic
import com.example.governor.SessionExport
import com.example.governor.SessionInfo
import com.example.governor.SessionScoring
import com.example.governor.SessionTweakSnapshot
import com.example.governor.TweakImpactAnalyzer
import com.example.model.CompositeScoreWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComparisonTest {

    private fun snap(
        powerWatts: Float = 6.0f,
        tempStart: Float = 42.0f,
        afterburn: String = "Medium"
    ) = SessionTweakSnapshot(
        cpuHeadroomPct = 22f,
        cpuRampUp = 0.92f,
        cpuRampDown = 0.04f,
        gpuHeadroomPct = 22f,
        gpuRampUp = 0.92f,
        gpuRampDown = 0.05f,
        cpuRangesSummary = "P0:500-2000M",
        gpuRangeSummary = "300-950M",
        tempThrottleEnabled = true,
        tempStartC = tempStart,
        tempFullC = 56f,
        tempCpuRedPct = 55f,
        tempGpuRedPct = 65f,
        tempCurve = "LINEAR",
        tempReaction = 0.35f,
        powerThrottleEnabled = true,
        powerLimitWatts = powerWatts,
        powerSustainMs = 2000L,
        powerStepPct = 3f,
        smoothPowerLimit = true,
        afterburnEnabled = true,
        afterburnPkgEnabled = true,
        afterburnStrength = afterburn,
        ramPinMax = false,
        ramPinGameOnly = true,
        stutterHunterEnabled = false,
        stutterHunterMode = "NOTIFY",
        gameThreadControllerEnabled = false,
        adaptiveRefreshEnabled = false,
        adaptiveIdleHz = 0,
        adaptiveActiveHz = 0
    )

    private fun session(
        file: String,
        pkg: String = "com.game",
        durMs: Long = 300_000L,
        samples: Int = 300,
        avgFps: Float = 55f,
        p5Fps: Float = 48f,
        avgTemp: Float = 41f,
        avgWatts: Float = 4.2f,
        fpw: Float = 13.1f,
        stability: Float = 85f,
        charging: Boolean = false,
        midChanges: Int = 0,
        snapshot: SessionTweakSnapshot? = snap()
    ) = SessionInfo(
        fileName = file,
        packageName = pkg,
        startMs = 1_000_000L,
        endMs = 1_000_000L + durMs,
        summary = MetricSummary(
            durationMs = durMs,
            sampleCount = samples,
            avgFps = avgFps,
            minFps = p5Fps - 5f,
            p5Fps = p5Fps,
            avgTempC = avgTemp,
            maxTempC = avgTemp + 2f,
            avgWatts = avgWatts,
            maxWatts = avgWatts + 1f,
            avgFpsPerWatt = fpw,
            avgWorkPerWatt = 2.0f,
            energyWh = 0.35f,
            includesCharging = charging,
            minWatts = avgWatts - 1f,
            p1Fps = p5Fps - 2f,
            p50Fps = avgFps,
            frameStabilityPct = stability
        ),
        snapshot = snapshot,
        midSessionChanges = midChanges
    )

    @Test
    fun compositeScoreRewardsFpsEfficiencyCoolnessAndStability() {
        val weights = CompositeScoreWeights()
        val fastCool = session("a.csv", avgFps = 60f, p5Fps = 56f, avgTemp = 38f, fpw = 16f, stability = 95f).summary
        val slowHot = session("b.csv", avgFps = 42f, p5Fps = 30f, avgTemp = 49f, fpw = 8f, stability = 60f).summary
        assertTrue(SessionScoring.compositeScore(fastCool, weights) > SessionScoring.compositeScore(slowHot, weights))

        // Works even when FPS was not readable.
        val noFps = fastCool.copy(avgFps = Float.NaN, p5Fps = Float.NaN, p1Fps = Float.NaN, avgFpsPerWatt = Float.NaN, frameStabilityPct = Float.NaN)
        assertTrue(SessionScoring.compositeScore(noFps, weights).isFinite())
    }

    @Test
    fun comparisonDetectsDeltasDiffsVerdictsAndWarnings() {
        val a = session("a.csv", pkg = "com.game1", durMs = 300_000L, avgFps = 52f, avgWatts = 4.6f, avgTemp = 43f, snapshot = snap(6.0f, afterburn = "Medium"))
        val b = session("b.csv", pkg = "com.game2", durMs = 700_000L, samples = 15, avgFps = 57f, avgWatts = 4.0f, avgTemp = 40.5f, charging = true, midChanges = 1, snapshot = snap(6.5f, afterburn = "High"))

        val res = SessionComparisonLogic.compare(listOf(a, b))

        val fpsRow = res.metricRows.first { it.kind == ComparisonMetricKind.AVG_FPS }
        assertEquals(MetricDirection.BETTER, fpsRow.cells[1].direction)
        assertTrue(fpsRow.cells[1].deltaText.startsWith("↑ +5.0"))

        val wattsRow = res.metricRows.first { it.kind == ComparisonMetricKind.AVG_WATTS }
        assertEquals(MetricDirection.BETTER, wattsRow.cells[1].direction)
        assertTrue(wattsRow.cells[1].deltaText.contains("-0.60 W"))

        val diffKeys = res.settingsDiffs.map { it.fieldKey }
        assertTrue(SessionTweakSnapshot.KEY_POWER_LIMIT in diffKeys)
        assertTrue(SessionTweakSnapshot.KEY_AFTERBURN in diffKeys)
        assertEquals(2, diffKeys.size)

        assertTrue(ComparisonWarning.DIFFERENT_GAMES in res.warnings)
        assertTrue(ComparisonWarning.INCLUDES_CHARGING in res.warnings)
        assertTrue(ComparisonWarning.DURATION_MISMATCH in res.warnings)
        assertTrue(ComparisonWarning.SHORT_SESSION in res.warnings)
        assertTrue(ComparisonWarning.MID_SESSION_CHANGES in res.warnings)

        val exported = SessionExport.comparisonText(res, mapOf("com.game1" to "Game 1", "com.game2" to "Game 2"), "TestPhone", "4.0.0")
        assertTrue(exported.contains("Power limit"))
        assertTrue(exported.contains("Session B"))
    }

    @Test
    fun tweakImpactAnalyzerRanksTweaksAndLabelsLowConfidence() {
        val list = listOf(
            session("1.csv", avgFps = 50f, p5Fps = 42f, avgTemp = 43f, avgWatts = 4.5f, fpw = 11f, stability = 80f, snapshot = snap(powerWatts = 6.0f)),
            session("2.csv", avgFps = 51f, p5Fps = 43f, avgTemp = 42f, avgWatts = 4.4f, fpw = 11.5f, stability = 82f, snapshot = snap(powerWatts = 6.0f)),
            session("3.csv", avgFps = 59f, p5Fps = 54f, avgTemp = 39f, avgWatts = 3.9f, fpw = 15.1f, stability = 92f, snapshot = snap(powerWatts = 5.0f))
        )
        val report = TweakImpactAnalyzer.analyze("com.game", list)
        assertEquals(3, report.analyzedSessionCount)
        assertTrue(report.bestTweaks.isNotEmpty())
        val top = report.bestTweaks.first()
        assertEquals(SessionTweakSnapshot.KEY_POWER_LIMIT, top.fieldKey)
        assertTrue(top.deltaScore > 0f)
        assertEquals(ImpactConfidence.LOW, top.confidence)
    }

    @Test
    fun chartDownsamplerCapsPointsSmoothly() {
        val raw = (0 until 300).map { i ->
            MetricSample(tMs = 1_000_000L + i * 1000L, tempC = 40f, watts = 4f, fps = 60f, cpuLoadPct = 50f, gpuLoadPct = 50f, work = 3f)
        }
        val pts = ChartDownsampler.downsample(raw, maxPoints = 60)
        assertEquals(60, pts.size)
        assertTrue(pts.first().elapsedSec < pts.last().elapsedSec)
    }
}
