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

import com.example.governor.MetricSample
import com.example.governor.MetricsMath
import com.example.model.BatteryState
import com.example.model.ClusterLiveState
import com.example.model.GpuLiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsMathTest {

    private fun sample(t: Long, fps: Float, watts: Float, temp: Float = 40f, work: Float = 4f) =
        MetricSample(t, temp, watts, fps, 50f, 50f, work)

    private fun cluster(cpus: List<Int>, khz: Long, load: Float) = ClusterLiveState(
        policyId = cpus.first(), name = "c", relatedCpus = cpus,
        loadPct = load, smoothedLoadPct = load,
        targetFreqKhz = khz, actualFreqKhz = khz, governor = "performance",
        allowedMinKhz = 0, allowedMaxKhz = khz, cappedMaxKhz = khz,
        minAvailableKhz = 0, maxAvailableKhz = khz
    )

    @Test
    fun fpsPerWattIsFpsOverWatts() {
        assertEquals(15f, sample(0, 60f, 4f).fpsPerWatt, 0.001f)
    }

    @Test
    fun ratioIsBlankWithoutFpsOrPower() {
        assertTrue(sample(0, -1f, 4f).fpsPerWatt.isNaN())
        assertTrue(sample(0, 60f, 0.1f).fpsPerWatt.isNaN())
        assertTrue(sample(0, 60f, 0.1f).workPerWatt.isNaN())
    }

    @Test
    fun workScoreCombinesCpuAndGpu() {
        // 4 cores at 2.0 GHz, 50% load = 4.0 busy GHz; GPU 1.0 GHz at 50% = 0.5.
        val cpu = cluster(listOf(4, 5, 6, 7), 2_000_000L, 50f)
        val gpu = GpuLiveState(available = true, smoothedLoadPct = 50f, currentFreqKhz = 1_000_000L)
        assertEquals(4.5f, MetricsMath.workScore(listOf(cpu), gpu), 0.001f)
    }

    @Test
    fun workScoreIgnoresUnavailableGpu() {
        val cpu = cluster(listOf(0, 1), 1_000_000L, 100f)
        assertEquals(2f, MetricsMath.workScore(listOf(cpu), GpuLiveState(available = false)), 0.001f)
    }

    @Test
    fun summaryAveragesAndLows() {
        val s = (0 until 100).map { sample(it * 1000L, fps = (it + 1).toFloat(), watts = 5f, temp = 40f + it * 0.1f) }
        val sum = MetricsMath.summarize(s)
        assertEquals(50.5f, sum.avgFps, 0.01f)
        assertEquals(1f, sum.minFps, 0.001f)
        assertEquals(5f, sum.p5Fps, 0.001f)
        assertEquals(5f, sum.avgWatts, 0.001f)
        assertEquals(49.9f, sum.maxTempC, 0.01f)
        assertEquals(99_000L, sum.durationMs)
        // Total frames over total watts = 50.5 / 5.
        assertEquals(10.1f, sum.avgFpsPerWatt, 0.01f)
        // 99 intervals x 5 W x 1 s = 495 Ws = 0.1375 Wh.
        assertEquals(0.1375f, sum.energyWh, 0.001f)
    }

    @Test
    fun summaryHandlesNoFps() {
        val sum = MetricsMath.summarize(listOf(sample(0, -1f, 3f), sample(1000, -1f, 3f)))
        assertTrue(sum.avgFps.isNaN())
        assertTrue(sum.avgFpsPerWatt.isNaN())
        assertEquals(1f, sum.avgWorkPerWatt / (4f / 3f), 0.001f)
    }

    @Test
    fun energyIgnoresLongGaps() {
        val sum = MetricsMath.summarize(listOf(sample(0, 60f, 6f), sample(600_000L, 60f, 6f)))
        // The 10-minute gap counts for at most 5 s: 6 W x 5 s = 30 Ws.
        assertEquals(30f / 3600f, sum.energyWh, 0.0001f)
    }

    @Test
    fun movingAverageSkipsGaps() {
        val out = MetricsMath.movingAverage(listOf(10f, Float.NaN, 20f, 30f), 2)
        assertEquals(10f, out[0], 0.001f)
        assertTrue(out[1].isNaN())
        assertEquals(20f, out[2], 0.001f)
        assertEquals(25f, out[3], 0.001f)
    }

    @Test
    fun sampleFileRoundTrip() {
        val s = MetricSample(1_700_000_000_000L, 41.25f, 4.5f, 59.5f, 62f, 71f, 5.5f)
        val back = MetricsMath.decodeSample(MetricsMath.encodeSample(s))
        assertNotNull(back)
        assertEquals(s.tMs, back!!.tMs)
        assertEquals(41.25f, back.tempC, 0.001f)
        assertEquals(59.5f, back.fps, 0.001f)
        assertNull(MetricsMath.decodeSample("garbage"))
        assertNull(MetricsMath.decodeSample("1,2,3"))
    }

    @Test
    fun headerRoundTripKeepsSummaryAndMissingFps() {
        val sum = MetricsMath.summarize(listOf(sample(0, -1f, 3f), sample(1000, -1f, 3f)))
        val header = MetricsMath.encodeHeader("com.game|x", 0L, 1000L, sum)
        val info = MetricsMath.decodeHeader("f.csv", header)
        assertNotNull(info)
        assertEquals("com.game_x", info!!.packageName)
        assertEquals(1000L, info.endMs)
        assertTrue(info.summary.avgFps.isNaN())
        assertEquals(sum.avgWatts, info.summary.avgWatts, 0.01f)
        assertNull(MetricsMath.decodeHeader("f.csv", "not a header"))
    }

    @Test
    fun chargingAndPluggedSamplesExcludedFromPowerStatsAndEnergy() {
        val list = listOf(
            MetricSample(0L, 40f, 5f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
            MetricSample(1_000L, 41f, 5f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
            MetricSample(2_000L, 42f, 15f, 60f, 50f, 50f, 4f, BatteryState.CHARGING),
            MetricSample(3_000L, 42f, 0f, 60f, 50f, 50f, 4f, BatteryState.FULL_PLUGGED)
        )
        assertTrue(list[2].fpsPerWatt.isNaN())
        assertTrue(list[2].workPerWatt.isNaN())
        assertTrue(list[3].fpsPerWatt.isNaN())

        val sum = MetricsMath.summarize(list)
        assertTrue(sum.includesCharging)
        assertEquals(5f, sum.avgWatts, 0.001f)
        assertEquals(5f, sum.maxWatts, 0.001f)
        assertEquals(12f, sum.avgFpsPerWatt, 0.01f)
        assertEquals(5f / 3600f, sum.energyWh, 0.0001f)
    }

    @Test
    fun backwardCompatibleWithLegacyCsvRowsAndHeaders() {
        val legacyRow = "1000,40.00,4.50,60.00,50.00,55.00,4.00"
        val decodedRow = MetricsMath.decodeSample(legacyRow)
        assertNotNull(decodedRow)
        assertEquals(BatteryState.DISCHARGING, decodedRow!!.batteryState)
        assertEquals(4.5f, decodedRow.watts, 0.01f)

        assertEquals(BatteryState.CHARGING, MetricsMath.decodeSample("2000,41.00,12.00,60.00,50.00,55.00,4.00,C")!!.batteryState)
        assertEquals(BatteryState.FULL_PLUGGED, MetricsMath.decodeSample("3000,41.00,0.00,60.00,50.00,55.00,4.00,F")!!.batteryState)

        val legacyHeader = "#CG1|com.game|0|1000|2|60.00|58.00|59.00|40.00|41.00|4.00|5.00|15.00|1.00|0.01"
        val legacyInfo = MetricsMath.decodeHeader("f.csv", legacyHeader)
        assertNotNull(legacyInfo)
        assertFalse(legacyInfo!!.summary.includesCharging)

        val sum = MetricsMath.summarize(
            listOf(
                MetricSample(0L, 40f, 4f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
                MetricSample(1000L, 41f, 10f, 60f, 50f, 50f, 4f, BatteryState.CHARGING)
            )
        )
        val encoded = MetricsMath.encodeHeader("com.game", 0L, 1000L, sum)
        val decoded = MetricsMath.decodeHeader("f.csv", encoded)
        assertTrue(decoded!!.summary.includesCharging)
    }

    @Test
    fun minimumPowerIgnoresZeroReadingsAndCharging() {
        val list = listOf(
            MetricSample(0L, 40f, 4f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
            MetricSample(1_000L, 40f, 0f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),   // sensor gave nothing
            MetricSample(2_000L, 40f, 1.5f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
            MetricSample(3_000L, 40f, 7f, 60f, 50f, 50f, 4f, BatteryState.DISCHARGING),
            MetricSample(4_000L, 40f, 0.2f, 60f, 50f, 50f, 4f, BatteryState.CHARGING)     // charging is excluded
        )
        val sum = MetricsMath.summarize(list)
        assertEquals(1.5f, sum.minWatts, 0.001f)
        assertEquals(7f, sum.maxWatts, 0.001f)
    }

    @Test
    fun minimumPowerBlankWithoutDischargeSamples() {
        val sum = MetricsMath.summarize(
            listOf(MetricSample(0L, 40f, 12f, 60f, 50f, 50f, 4f, BatteryState.CHARGING))
        )
        assertTrue(sum.minWatts.isNaN())
    }

    @Test
    fun minimumPowerSurvivesHeaderRoundTripAndOldHeadersHaveNone() {
        val sum = MetricsMath.summarize(listOf(sample(0, 60f, 4f), sample(1_000, 60f, 2.5f)))
        val back = MetricsMath.decodeHeader("f.csv", MetricsMath.encodeHeader("com.game", 0L, 1000L, sum))
        assertEquals(2.5f, back!!.summary.minWatts, 0.01f)

        // A 2.7.1 header has no minimum power field.
        val old = "#CG1|com.game|0|1000|2|60.00|58.00|59.00|40.00|41.00|4.00|5.00|15.00|1.00|0.01|0"
        val oldInfo = MetricsMath.decodeHeader("f.csv", old)
        assertNotNull(oldInfo)
        assertTrue(oldInfo!!.summary.minWatts.isNaN())
        assertEquals(5f, oldInfo.summary.maxWatts, 0.001f)
    }
}
