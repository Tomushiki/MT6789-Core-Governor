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

import android.os.BatteryManager
import com.example.governor.GovernorMath
import com.example.governor.SysfsParsers
import com.example.model.BatteryState
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.TempThrottleConfig
import com.example.model.ThrottleCurveShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysfsParsersTest {

    @Test
    fun procStatClusterLoadCalculatesCorrectly() {
        val prevRaw = """
            cpu  100 0 100 800 0 0 0 0 0 0
            cpu0 100 0 100 800 0 0 0 0 0 0
            cpu1 100 0 100 800 0 0 0 0 0 0
        """.trimIndent()
        val currRaw = """
            cpu  150 0 150 900 0 0 0 0 0 0
            cpu0 150 0 150 900 0 0 0 0 0 0
            cpu1 120 0 130 950 0 0 0 0 0 0
        """.trimIndent()

        val prev = SysfsParsers.parseProcStat(prevRaw)
        val curr = SysfsParsers.parseProcStat(currRaw)
        val load = SysfsParsers.computeClusterLoadPct(listOf(0, 1), prev, curr)
        assertEquals(37.5f, load, 0.2f)
        val busiestLoad = SysfsParsers.computeClusterLoadPct(listOf(0, 1), prev, curr, useBusiestCore = true)
        assertEquals(50f, busiestLoad, 0.2f)
    }

    @Test
    fun gpufreqWorkingOppTableSortsLowestToHighestPreservingRealIndex() {
        // Simulate vendor-overclocked table where index 0 is highest frequency
        val workingTable = """
            [00*] freq: 1120000, volt: 92500, vsram: 95000
            [01] freq: 1000000, volt: 85000, vsram: 87500
            [02] freq: 850000, volt: 80000, vsram: 85000
            [03] freq: 650000, volt: 75000, vsram: 80000
            [04] freq: 400000, volt: 70000, vsram: 75000
        """.trimIndent()

        val parsed = SysfsParsers.parseGpufreqOppTable(workingTable)
        assertEquals(5, parsed.sortedOpps.size)
        assertTrue(parsed.orderDescription.contains("Highest-first"))

        // Sorted lowest -> highest, while preserving real kernel oppIndex
        assertEquals(400_000L, parsed.sortedOpps[0].freqKhz)
        assertEquals(4, parsed.sortedOpps[0].oppIndex)
        assertEquals(1_120_000L, parsed.sortedOpps[4].freqKhz)
        assertEquals(0, parsed.sortedOpps[4].oppIndex)

        // Check gpufreq_status robust parsing + cross-lookup
        val status = SysfsParsers.parseGpufreqStatus(
            "[GPUFREQ-STATUS] Current OPP: [1], Freq: 1000000 KHz, Volt: 85000",
            parsed.sortedOpps
        )
        assertEquals(1_000_000L, status.freqKhz)
        assertEquals(1, status.oppIndex)
    }

    @Test
    fun gpuOppPositionScalingAndThrottlingConvertsToRealOppIndex() {
        val workingTable = """
            [00] freq: 1100000, volt: 90000
            [01] freq: 950000, volt: 85000
            [02] freq: 800000, volt: 80000
            [03] freq: 600000, volt: 75000
            [04] freq: 400000, volt: 70000
        """.trimIndent()
        val opps = SysfsParsers.parseGpufreqOppTable(workingTable).sortedOpps

        // Full range 400M..1100M (positions 0..4), cap 50% -> cappedMaxPos = 2 (800MHz, real oppIndex = 2)
        val selAtFullLoad = GovernorMath.selectGpuOppPosition(
            sortedOpps = opps,
            allowedMinKhz = 400_000L,
            allowedMaxKhz = 1_100_000L,
            capPct = 50f,
            loadPct = 100f
        )
        assertNotNull(selAtFullLoad)
        assertEquals(2, selAtFullLoad!!.cappedMaxPos)
        assertEquals(2, selAtFullLoad.targetPos)
        assertEquals(800_000L, selAtFullLoad.targetOpp.freqKhz)
        assertEquals(2, selAtFullLoad.targetOpp.oppIndex)

        // At 0% load -> position 0 (400 MHz -> real oppIndex 4)
        val selAtIdle = GovernorMath.selectGpuOppPosition(
            sortedOpps = opps,
            allowedMinKhz = 400_000L,
            allowedMaxKhz = 1_100_000L,
            capPct = 100f,
            loadPct = 0f
        )
        assertNotNull(selAtIdle)
        assertEquals(0, selAtIdle!!.targetPos)
        assertEquals(4, selAtIdle.targetOpp.oppIndex)
    }

    @Test
    fun powerCalibrationHandlesUnitsAndPolarity() {
        val reading = SysfsParsers.computePowerReading(
            rawCurrent = -1_500_000L,
            rawVoltage = 4_000_000L,
            unitScale = PowerUnitScale.AUTO,
            polarity = PowerPolarity.DISCHARGE_NEGATIVE
        )
        assertEquals(6.0f, reading.watts, 0.05f)
        assertEquals(-1500f, reading.currentMa, 0.5f)
        assertEquals(4000f, reading.voltageMv, 0.5f)
    }

    @Test
    fun tempThrottleCurveAndHysteresisWorkSmoothly() {
        val cfg = TempThrottleConfig(
            enabled = true,
            startTempC = 40f,
            fullTempC = 60f,
            cpuMaxReductionPct = 50f,
            gpuMaxReductionPct = 60f,
            curveShape = ThrottleCurveShape.LINEAR,
            reactionSpeed = 1.0f,
            hysteresisC = 3.0f
        )
        val (cpuCap, gpuCap, peak) = GovernorMath.stepTempCap(50f, 40f, 100f, 100f, cfg, 20f)
        assertEquals(75f, cpuCap, 0.5f)
        assertEquals(70f, gpuCap, 0.5f)
        assertEquals(50f, peak, 0.1f)

        val (cpuLatched, _, nextPeak) = GovernorMath.stepTempCap(48.5f, peak, cpuCap, gpuCap, cfg, 20f)
        assertEquals(75f, cpuLatched, 0.5f)
        assertEquals(50f, nextPeak, 0.1f)
    }

    @Test
    fun powerReadingDistinguishesDischargingChargingFullPluggedAndUnknown() {
        // Discharging with learned negative current polarity
        val dis = SysfsParsers.computePowerReading(
            rawCurrent = -1_500_000L,
            rawVoltage = 4_000_000L,
            unitScale = PowerUnitScale.AUTO,
            polarity = PowerPolarity.AUTO_ABS,
            bmStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            bmPlugged = 0,
            learnedDischargeSign = -1
        )
        assertEquals(BatteryState.DISCHARGING, dis.state)
        assertEquals(6.0f, dis.dischargeWatts, 0.05f)
        assertEquals(0f, dis.chargeWatts, 0.001f)
        assertEquals(-6.0f, dis.signedWatts, 0.05f)

        // Charging with positive current
        val chg = SysfsParsers.computePowerReading(
            rawCurrent = 2_500_000L,
            rawVoltage = 4_000_000L,
            unitScale = PowerUnitScale.AUTO,
            polarity = PowerPolarity.AUTO_ABS,
            bmStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            bmPlugged = 2,
            learnedDischargeSign = -1
        )
        assertEquals(BatteryState.CHARGING, chg.state)
        assertEquals(10.0f, chg.chargeWatts, 0.05f)
        assertEquals(0f, chg.dischargeWatts, 0.001f)
        assertEquals(10.0f, chg.signedWatts, 0.05f)

        // Full & plugged within deadband (<= 0.15 W and <= 50 mA) -> FULL_PLUGGED
        val full = SysfsParsers.computePowerReading(
            rawCurrent = 20L,
            rawVoltage = 4_350_000L,
            unitScale = PowerUnitScale.AUTO,
            polarity = PowerPolarity.AUTO_ABS,
            bmStatus = BatteryManager.BATTERY_STATUS_FULL,
            bmPlugged = 2,
            learnedDischargeSign = -1
        )
        assertEquals(BatteryState.FULL_PLUGGED, full.state)
        assertEquals(0f, full.dischargeWatts, 0.001f)
        assertEquals(0f, full.chargeWatts, 0.001f)

        // Invalid voltage -> UNKNOWN
        val unk = SysfsParsers.computePowerReading(
            rawCurrent = 0L,
            rawVoltage = 0L,
            unitScale = PowerUnitScale.AUTO,
            polarity = PowerPolarity.AUTO_ABS
        )
        assertEquals(BatteryState.UNKNOWN, unk.state)
    }

    @Test
    fun chargingAwareTempThrottleAppliesClampedOffset() {
        val cfg = TempThrottleConfig(
            enabled = true,
            startTempC = 42f,
            fullTempC = 52f,
            cpuMaxReductionPct = 50f,
            gpuMaxReductionPct = 50f,
            curveShape = ThrottleCurveShape.LINEAR,
            reactionSpeed = 1.0f,
            hysteresisC = 2f,
            chargingAware = true,
            chargingStartOffsetC = 8f // Clamped to max 5 C -> effective start 37 C
        )
        val (cpuDis, _, _) = GovernorMath.stepTempCap(40f, 30f, 100f, 100f, cfg, 20f, isChargingOrPlugged = false)
        assertEquals(100f, cpuDis, 0.01f)

        val (cpuChg, _, _) = GovernorMath.stepTempCap(40f, 30f, 100f, 100f, cfg, 20f, isChargingOrPlugged = true)
        assertEquals(90f, cpuChg, 0.2f)
    }

    @Test
    fun sysfsCapacityAndCycleParsersHandleUnitsAndInvalidInput() {
        assertEquals(5000, SysfsParsers.parseCapacityMah("5000000"))
        assertEquals(4500, SysfsParsers.parseCapacityMah("4500"))
        assertNull(SysfsParsers.parseCapacityMah("0"))
        assertNull(SysfsParsers.parseCapacityMah("abc"))
        assertEquals(42, SysfsParsers.parseCycleCount("42"))
        assertNull(SysfsParsers.parseCycleCount("-1"))
    }
}
