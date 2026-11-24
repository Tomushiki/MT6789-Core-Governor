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

import com.example.governor.D8300UserPreset
import com.example.model.CpuPolicySpec
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.GpuSpec
import com.example.model.HardwareSpec
import com.example.model.ThermalSensorSpec
import com.example.model.ThrottleCurveShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePresetsTest {

    private fun policy(id: Int, freqs: List<Long>) = CpuPolicySpec(
        policyId = id,
        name = "policy$id",
        path = "/sys/devices/system/cpu/cpufreq/policy$id",
        relatedCpus = listOf(id),
        availableFreqsKhz = freqs,
        origGovernor = "schedutil",
        origMinFreqKhz = freqs.first(),
        origMaxFreqKhz = freqs.last()
    )

    private val little = listOf(500_000L, 1_000_000L, 1_500_000L, 1_800_000L, 2_200_000L)
    private val big = listOf(600_000L, 1_200_000L, 1_800_000L, 2_300_000L, 2_500_000L, 3_200_000L)
    private val prime = listOf(700_000L, 1_500_000L, 2_400_000L, 2_900_000L, 3_350_000L)
    private val gpu = listOf(300_000L, 600_000L, 900_000L, 1_100_000L, 1_400_000L)

    private fun matchingHw(withGpu: Boolean = true, sensor: Boolean = true) = HardwareSpec(
        cpuPolicies = listOf(policy(0, little), policy(4, big), policy(7, prime)),
        gpu = if (withGpu) GpuSpec(discovered = true, availableFreqsKhz = gpu) else GpuSpec(),
        thermalSensors = if (sensor) listOf(
            ThermalSensorSpec("z40", "gpu", "/sys/class/thermal/thermal_zone40/temp", "GPU", 1000f)
        ) else emptyList()
    )

    @Test fun matchesOnlyThreeClusterPrimePhone() {
        assertTrue(D8300UserPreset.matches(matchingHw()))
        val mt6789 = HardwareSpec(cpuPolicies = listOf(policy(0, little), policy(6, big)))
        assertFalse(D8300UserPreset.matches(mt6789))
        val slowPrime = HardwareSpec(
            cpuPolicies = listOf(policy(0, little), policy(4, big), policy(7, listOf(500_000L, 2_000_000L)))
        )
        assertFalse(D8300UserPreset.matches(slowPrime))
    }

    @Test fun applyReturnsNullOnOtherDevices() {
        val mt6789 = HardwareSpec(cpuPolicies = listOf(policy(0, little), policy(6, big)))
        assertNull(D8300UserPreset.apply(GovernorConfig(), mt6789))
    }

    @Test fun capsSnapDownToRealFrequencies() {
        val out = D8300UserPreset.apply(GovernorConfig(), matchingHw())!!
        assertEquals(1_800_000L, out.normalMode.cpuRangesKhz.getValue(0).maxKhz)
        assertEquals(2_300_000L, out.normalMode.cpuRangesKhz.getValue(4).maxKhz)
        assertEquals(2_400_000L, out.normalMode.cpuRangesKhz.getValue(7).maxKhz)
        assertEquals(2_500_000L, out.gameMode.cpuRangesKhz.getValue(4).maxKhz)
        assertEquals(3_350_000L, out.gameMode.cpuRangesKhz.getValue(7).maxKhz)
        assertEquals(900_000L, out.normalMode.gpuRangeKhz!!.maxKhz)
        assertEquals(1_400_000L, out.gameMode.gpuRangeKhz!!.maxKhz)
    }

    @Test fun minNeverExceedsMax() {
        val cfg = GovernorConfig().let {
            it.copy(normalMode = it.normalMode.copy(cpuRangesKhz = mapOf(0 to FreqRange(2_200_000L, 2_200_000L))))
        }
        val out = D8300UserPreset.apply(cfg, matchingHw())!!
        for (mode in listOf(out.normalMode, out.gameMode)) {
            mode.cpuRangesKhz.values.forEach { assertTrue(it.minKhz <= it.maxKhz) }
            mode.gpuRangeKhz?.let { assertTrue(it.minKhz <= it.maxKhz) }
        }
    }

    @Test fun temperatureThrottleAndSensorSet() {
        val out = D8300UserPreset.apply(GovernorConfig(), matchingHw())!!
        assertEquals(60f, out.tempThrottle.startTempC, 0f)
        assertEquals(72f, out.tempThrottle.fullTempC, 0f)
        assertEquals(ThrottleCurveShape.LINEAR, out.tempThrottle.curveShape)
        assertEquals("/sys/class/thermal/thermal_zone40/temp", out.selectedTempSensorPath)
    }

    @Test fun sensorKeptWhenZone40Missing() {
        val cfg = GovernorConfig(selectedTempSensorPath = "/sys/class/thermal/thermal_zone1/temp")
        val out = D8300UserPreset.apply(cfg, matchingHw(sensor = false))!!
        assertEquals("/sys/class/thermal/thermal_zone1/temp", out.selectedTempSensorPath)
    }

    @Test fun gpuUntouchedWhenNotDiscovered() {
        val out = D8300UserPreset.apply(GovernorConfig(), matchingHw(withGpu = false))!!
        assertEquals(GovernorConfig().normalMode.gpuRangeKhz, out.normalMode.gpuRangeKhz)
        assertEquals(GovernorConfig().gameMode.gpuRangeKhz, out.gameMode.gpuRangeKhz)
    }

    @Test fun onlyIntendedFieldsChange() {
        val cfg = GovernorConfig(pollIntervalMs = 123L, overlayEnabled = true, startOnBoot = true)
        val out = D8300UserPreset.apply(cfg, matchingHw())!!
        val restored = out.copy(
            normalMode = cfg.normalMode,
            gameMode = cfg.gameMode,
            tempThrottle = cfg.tempThrottle,
            selectedTempSensorPath = cfg.selectedTempSensorPath
        )
        assertEquals(cfg, restored)
        assertEquals(cfg.normalMode.headroomPct, out.normalMode.headroomPct, 0f)
        assertEquals(cfg.gameMode.rampUpSpeed, out.gameMode.rampUpSpeed, 0f)
    }
}
