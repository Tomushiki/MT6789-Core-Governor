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

import com.example.governor.BatteryEstimator
import com.example.governor.BatteryEstimatorMath
import com.example.governor.BatterySegmentRecord
import com.example.model.BatteryState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BatteryEstimatorTest {

    private lateinit var tempDir: File
    private lateinit var modelFile: File
    private lateinit var est: BatteryEstimator

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cg_batt_test_${System.nanoTime()}").apply { mkdirs() }
        modelFile = File(tempDir, "battery_model.json")
        est = BatteryEstimator(modelFile)
        est.setUiVisible(true)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun runTicks(
        startMs: Long,
        count: Int,
        dtMs: Long,
        state: BatteryState,
        currentMa: Float,
        voltageMv: Float = 4000f,
        pctProvider: (Int) -> Int
    ): Long {
        var t = startMs
        val watts = (currentMa / 1000f) * (voltageMv / 1000f)
        for (i in 0 until count) {
            t += dtMs
            est.recordTick(
                nowMs = t,
                batteryState = state,
                absCurrentMa = currentMa,
                voltageMv = voltageMv,
                dischargeWatts = if (state == BatteryState.DISCHARGING) watts else 0f,
                chargeWatts = if (state == BatteryState.CHARGING) watts else 0f,
                batteryPct = pctProvider(i),
                pollIntervalMs = dtMs,
                estimationEnabled = true
            )
        }
        return t
    }

    @Test
    fun rejectsShortDurationSmallDeltaAndInvalidatesSegmentOnGaps() {
        var t = 10_000L
        // Initial baseline at 80%
        est.recordTick(t, BatteryState.DISCHARGING, 1000f, 4000f, 4f, 0f, 80, 150L, true)
        // Transition to 79% starts collecting
        t += 150L
        est.recordTick(t, BatteryState.DISCHARGING, 1000f, 4000f, 4f, 0f, 79, 150L, true)
        // Drop 10% in only 60 seconds (< 180 s min duration) -> NOT accepted
        t = runTicks(t, 400, 150L, BatteryState.DISCHARGING, 1000f) { idx ->
            if (idx == 399) 69 else 79
        }
        assertEquals(0, est.snapshot.value.totalValidSegments)

        // Start collecting again, run 100 ticks, then trigger a 1500ms gap (> max(600, 3*150))
        t += 150L
        est.recordTick(t, BatteryState.DISCHARGING, 1000f, 4000f, 4f, 0f, 68, 150L, true)
        t = runTicks(t, 50, 150L, BatteryState.DISCHARGING, 1000f) { 68 }
        t += 1_500L // gap
        est.recordTick(t, BatteryState.DISCHARGING, 1000f, 4000f, 4f, 0f, 55, 150L, true)
        assertEquals(0, est.snapshot.value.totalValidSegments)
    }

    @Test
    fun collectsValidDischargeAndChargeSegmentsRejectsOutliersAndCombinesDirections() {
        // Complete a 10% discharge segment (79 -> 69) over 240s at 1200 mA => 80 mAh/240s => wait:
        // 1200 mA for 45 min = 900 mAh / 10% = 9000 mAh? Pick realistic numbers: 5000 mAh * 10% = 500 mAh.
        // At 2000 mA -> 500 mAh takes 0.25h = 900 s (1800 ticks at 500 ms).
        var t = 100_000L
        est.recordTick(t, BatteryState.DISCHARGING, 2000f, 4000f, 8f, 0f, 80, 500L, true)
        t += 500L
        est.recordTick(t, BatteryState.DISCHARGING, 2000f, 4000f, 8f, 0f, 79, 500L, true)
        t = runTicks(t, 1800, 500L, BatteryState.DISCHARGING, 2000f) { idx ->
            if (idx == 1799) 69 else 79
        }

        val s1 = est.snapshot.value
        assertEquals(1, s1.dischargeSegmentCount)
        assertEquals(5000f, s1.dischargeCapacityMah, 10f)
        assertEquals(20.0f, s1.dischargeCapacityWh, 0.2f)
        assertEquals(5000f, s1.combinedCapacityMah, 10f)
        assertTrue(s1.confidencePct > 0)

        // Now complete a matching 10% charge segment (69 -> 70 boundary, then 70 -> 80) at 2000 mA for 900s => ~5000 mAh
        t += 500L
        est.recordTick(t, BatteryState.CHARGING, 2000f, 4000f, 0f, 8f, 69, 500L, true)
        t += 500L
        est.recordTick(t, BatteryState.CHARGING, 2000f, 4000f, 0f, 8f, 70, 500L, true)
        t = runTicks(t, 1800, 500L, BatteryState.CHARGING, 2000f) { idx ->
            if (idx == 1799) 80 else 70
        }

        val s2 = est.snapshot.value
        assertEquals(1, s2.dischargeSegmentCount)
        assertEquals(1, s2.chargeSegmentCount)
        assertEquals(2, s2.totalValidSegments)
        assertEquals(5000f, s2.chargeCapacityMah, 10f)
        assertFalse(s2.hasDirectionDisagreement)

        // Verify MAD outlier rejection directly on segments list
        val disList = listOf(
            BatterySegmentRecord(1L, BatteryState.DISCHARGING, 90, 80, 10, 600_000L, 480f, 1.92f, 1000f, 4800f, 19.2f),
            BatterySegmentRecord(2L, BatteryState.DISCHARGING, 80, 70, 10, 600_000L, 490f, 1.96f, 1000f, 4900f, 19.6f),
            BatterySegmentRecord(3L, BatteryState.DISCHARGING, 70, 60, 10, 600_000L, 485f, 1.94f, 1000f, 4850f, 19.4f),
            BatterySegmentRecord(4L, BatteryState.DISCHARGING, 60, 50, 10, 600_000L, 1400f, 5.60f, 1000f, 14000f, 56.0f)
        )
        val filtered = BatteryEstimatorMath.estimateDirection(disList)
        assertTrue(filtered.capacityMah in 4750f..4950f)
    }

    @Test
    fun flagsLargeDisagreementAndChargeCounterNeverAltersModel() {
        val disList = listOf(
            BatterySegmentRecord(1L, BatteryState.DISCHARGING, 90, 75, 15, 900_000L, 675f, 2.7f, 1000f, 4500f, 18.0f)
        )
        val chgList = listOf(
            BatterySegmentRecord(2L, BatteryState.CHARGING, 30, 50, 20, 900_000L, 1200f, 4.8f, 1500f, 6000f, 24.0f)
        )
        val comb = BatteryEstimatorMath.combineDirections(disList, chgList)
        assertTrue(comb.hasDisagreement)
        assertTrue(comb.disagreementPct > 12f)

        // Charge counter acts purely as second opinion
        est.updateBatteryProperties(
            batteryPct = 50,
            tempC = 32f,
            voltageMv = 3900f,
            healthLabel = "Good",
            chargeCounterMah = 2000f,
            cycleCount = 12
        )
        est.pushSnapshotNow()
        val snap = est.snapshot.value
        assertEquals(4000f, snap.chargeCounterEstimateMah, 1f)
        assertEquals(0f, snap.combinedCapacityMah, 0.01f)
        assertEquals(12, snap.cycleCount)
    }

    @Test
    fun tracksCountersSohEtaAndAtomicPersistenceWithoutResumingPartialSegment() {
        est.updateConfig(estimationEnabled = true, designCapacityOverrideMah = 5000)
        var t = 1_000_000L
        est.recordTick(t, BatteryState.DISCHARGING, 2000f, 4000f, 8f, 0f, 80, 500L, true)
        t += 500L
        est.recordTick(t, BatteryState.DISCHARGING, 2000f, 4000f, 8f, 0f, 79, 500L, true)
        t = runTicks(t, 1800, 500L, BatteryState.DISCHARGING, 2000f) { idx ->
            if (idx == 1799) 69 else 79
        }
        // Start an incomplete partial segment (69 -> 65), NOT enough for >= 8%
        t = runTicks(t, 100, 500L, BatteryState.DISCHARGING, 2000f) { 65 }

        val snap = est.snapshot.value
        assertEquals(1, snap.totalValidSegments)
        assertEquals(5000f, snap.combinedCapacityMah, 10f)
        assertEquals(100f, snap.sohPct, 1f)
        assertTrue(snap.remainingWh > 12f)
        assertTrue(snap.lifetimeWhOut > 2f)
        assertEquals(snap.lifetimeWhOut, snap.sessionWhOut, 0.01f)

        // Atomic flush writes battery_model.json with no leftover .tmp
        est.flush()
        assertTrue(modelFile.exists())
        assertFalse(File(tempDir, "battery_model.json.tmp").exists())

        // Reload in a fresh instance: segments & lifetime persist, session resets to 0, partial segment dropped
        val reloaded = BatteryEstimator(modelFile)
        reloaded.updateConfig(estimationEnabled = true, designCapacityOverrideMah = 5000)
        reloaded.load()
        val rs = reloaded.snapshot.value
        assertEquals(1, rs.totalValidSegments)
        assertEquals(5000f, rs.combinedCapacityMah, 10f)
        assertEquals(snap.lifetimeWhOut, rs.lifetimeWhOut, 0.01f)
        assertEquals(0f, rs.sessionWhOut, 0.001f)
        assertEquals(0, rs.activeSegmentDeltaPct)

        // Reset session keeps learned model; reset estimate clears learned model
        est.resetSession()
        assertEquals(0f, est.snapshot.value.sessionWhOut, 0.001f)
        assertEquals(1, est.snapshot.value.totalValidSegments)
        est.resetEstimate()
        assertEquals(0, est.snapshot.value.totalValidSegments)

        // Corrupt JSON does not crash load()
        modelFile.writeText("{not valid json")
        val corruptLoader = BatteryEstimator(modelFile)
        corruptLoader.load()
        assertEquals(0, corruptLoader.snapshot.value.totalValidSegments)
    }
}
