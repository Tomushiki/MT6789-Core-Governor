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

import com.example.governor.PowerModel
import com.example.model.BatteryState
import com.example.model.CpuPolicySpec
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
import com.example.model.GovernorConfig
import com.example.model.GpuOpp
import com.example.model.GpuSpec
import com.example.model.HardwareSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PowerModelTest {

    private lateinit var tempDir: File
    private lateinit var modelFile: File
    private lateinit var model: PowerModel

    private val sampleHw = HardwareSpec(
        discoveredAtMs = 1L,
        rootGranted = true,
        cpuPolicies = listOf(
            CpuPolicySpec(
                policyId = 0,
                name = "LITTLE",
                path = "/sys/devices/system/cpu/cpufreq/policy0",
                relatedCpus = listOf(0, 1, 2, 3, 4, 5),
                availableFreqsKhz = listOf(500_000L, 1_000_000L, 1_500_000L, 2_000_000L),
                origGovernor = "schedutil",
                origMinFreqKhz = 500_000L,
                origMaxFreqKhz = 2_000_000L
            )
        ),
        gpu = GpuSpec(
            discovered = true,
            oppTable = listOf(
                GpuOpp(oppIndex = 3, freqKhz = 400_000L),
                GpuOpp(oppIndex = 2, freqKhz = 600_000L),
                GpuOpp(oppIndex = 1, freqKhz = 800_000L),
                GpuOpp(oppIndex = 0, freqKhz = 1_000_000L)
            ),
            availableFreqsKhz = listOf(400_000L, 600_000L, 800_000L, 1_000_000L)
        )
    )

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cg_pm_test_${System.nanoTime()}").apply { mkdirs() }
        modelFile = File(tempDir, "power_model.json")
        model = PowerModel(modelFile)
        model.updateHardwareSpec(sampleHw)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun defaultConfigHasLearningOnAndEfficiencyGovernorOff() {
        val defaults = GovernorConfig()
        assertTrue(defaults.powerModelLearningEnabled)
        assertFalse(defaults.efficiencyLearningEnabled)
        assertEquals(EfficiencyMode.BALANCED, defaults.efficiencyMode)
        assertEquals(60, defaults.minConfidencePct)
        assertEquals(ExplorationRate.LOW, defaults.explorationRate)
    }

    @Test
    fun filtersChargingSleepInvalidWattsAndTransientSpikes() {
        val pids = intArrayOf(0)
        val freqs = intArrayOf(1500)
        val utils = floatArrayOf(40f)
        val cores = intArrayOf(6)

        model.trainStepInternal(
            timestampMs = 1000L, watts = 3.5f, isCharging = true, isInteractive = true,
            isSleepSaving = false, isGame = false, fgPkg = "com.app",
            learningEnabled = true, efficiencyEnabled = false,
            clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
            clusterUtilPct = utils, clusterCoreCounts = cores,
            gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f
        )
        model.trainStepInternal(
            timestampMs = 2000L, watts = 1.5f, isCharging = false, isInteractive = false,
            isSleepSaving = true, isGame = false, fgPkg = "com.app",
            learningEnabled = true, efficiencyEnabled = false,
            clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
            clusterUtilPct = utils, clusterCoreCounts = cores,
            gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f
        )
        model.trainStepInternal(
            timestampMs = 3000L, watts = 0f, isCharging = false, isInteractive = true,
            isSleepSaving = false, isGame = false, fgPkg = "com.app",
            learningEnabled = true, efficiencyEnabled = false,
            clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
            clusterUtilPct = utils, clusterCoreCounts = cores,
            gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f
        )

        model.refreshUiSnapshotNow()
        assertEquals(0L, model.uiSnapshot.value.totalSamples)
    }

    @Test
    fun learnsPowerAcrossScenariosWithLruCapAndCostPerPerf() {
        val pids = intArrayOf(0)
        val freqs = intArrayOf(1500)
        val utils = floatArrayOf(40f)
        val cores = intArrayOf(6)

        var t = 10_000L
        for (s in 0 until 30) {
            val pkg = "com.test.app$s"
            for (step in 0 until 2) {
                t += 1000L
                model.trainStepInternal(
                    timestampMs = t, watts = 3.2f, isCharging = false, isInteractive = true,
                    isSleepSaving = false, isGame = false, fgPkg = pkg,
                    learningEnabled = true, efficiencyEnabled = false,
                    clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
                    clusterUtilPct = utils, clusterCoreCounts = cores,
                    gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f
                )
            }
        }

        model.refreshUiSnapshotNow()
        val snap = model.uiSnapshot.value
        assertTrue(snap.totalSamples > 0L)
        assertTrue(snap.scenariosLearned <= PowerModel.MAX_SCENARIOS)

        val (predMw, conf) = model.predictPowerMw("cpu_0", 1500, 40f, "com.test.app29")
        assertTrue(predMw > 50f)
        assertTrue(conf > 10f)

        val cpp = model.costPerPerf("cpu_0", 1500, 40f, "com.test.app29", 3.6f)
        assertTrue(cpp > 0f)
    }

    @Test
    fun efficiencySelectorRespectsBoundsDisabledBypassAndBackoff() {
        val avail = listOf(500_000L, 1_000_000L, 1_500_000L, 2_000_000L)
        val disabledChoice = model.selectEfficientCpuFreqKhz(
            policyId = 0, availableFreqsKhz = avail, allowedMinKhz = 500_000L, cappedMaxKhz = 1_500_000L,
            standardTargetKhz = 1_500_000L, currentFreqKhz = 1_500_000L, rawLoadPct = 20f, coreCount = 6,
            scenarioKey = "com.app", isGame = false, fps = 60f, targetRefreshHz = 60f,
            efficiencyEnabled = false, efficiencyMode = EfficiencyMode.BALANCED,
            minConfidencePct = 60, explorationRate = ExplorationRate.LOW,
            burstActive = false, thermalThrottled = false, batteryPct = 80, nowMs = 10_000L
        )
        assertEquals(1_500_000L, disabledChoice)

        val pids = intArrayOf(0)
        val cores = intArrayOf(6)
        var t = 20_000L
        for (i in 0 until 45) {
            t += 1000L
            model.trainStepInternal(
                timestampMs = t, watts = 2.0f, isCharging = false, isInteractive = true,
                isSleepSaving = false, isGame = true, fgPkg = "com.game",
                learningEnabled = true, efficiencyEnabled = true,
                clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = intArrayOf(1000),
                clusterUtilPct = floatArrayOf(30f), clusterCoreCounts = cores,
                gpuAvailable = false, gpuFreqMhz = 0, gpuUtilPct = 0f
            )
            t += 1000L
            model.trainStepInternal(
                timestampMs = t, watts = 4.2f, isCharging = false, isInteractive = true,
                isSleepSaving = false, isGame = true, fgPkg = "com.game",
                learningEnabled = true, efficiencyEnabled = true,
                clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = intArrayOf(1500),
                clusterUtilPct = floatArrayOf(20f), clusterCoreCounts = cores,
                gpuAvailable = false, gpuFreqMhz = 0, gpuUtilPct = 0f
            )
        }

        val effChoice = model.selectEfficientCpuFreqKhz(
            policyId = 0, availableFreqsKhz = avail, allowedMinKhz = 500_000L, cappedMaxKhz = 1_500_000L,
            standardTargetKhz = 1_500_000L, currentFreqKhz = 1_500_000L, rawLoadPct = 20f, coreCount = 6,
            scenarioKey = "game:com.game", isGame = true, fps = 60f, targetRefreshHz = 60f,
            efficiencyEnabled = true, efficiencyMode = EfficiencyMode.BALANCED,
            minConfidencePct = 50, explorationRate = ExplorationRate.LOW,
            burstActive = false, thermalThrottled = false, batteryPct = 80, nowMs = t + 1000L
        )
        assertTrue(effChoice in 500_000L..1_500_000L)

        val backoffChoice = model.selectEfficientCpuFreqKhz(
            policyId = 0, availableFreqsKhz = avail, allowedMinKhz = 500_000L, cappedMaxKhz = 1_500_000L,
            standardTargetKhz = 1_500_000L, currentFreqKhz = effChoice, rawLoadPct = 40f, coreCount = 6,
            scenarioKey = "game:com.game", isGame = true, fps = 35f, targetRefreshHz = 60f,
            efficiencyEnabled = true, efficiencyMode = EfficiencyMode.BALANCED,
            minConfidencePct = 50, explorationRate = ExplorationRate.LOW,
            burstActive = false, thermalThrottled = false, batteryPct = 80, nowMs = t + 2000L
        )
        assertEquals(1_500_000L, backoffChoice)
    }

    @Test
    fun gpuEfficiencySelectorRespectsOppBounds() {
        val sortedOpps = sampleHw.gpu.oppTable
        val pos = model.selectEfficientGpuOppPos(
            sortedOpps = sortedOpps, allowedMinPos = 0, cappedMaxPos = 2,
            standardTargetPos = 2, currentFreqKhz = 800_000L, rawLoadPct = 20f,
            scenarioKey = "game:com.game", isGame = true, fps = 60f, targetRefreshHz = 60f,
            efficiencyEnabled = true, efficiencyMode = EfficiencyMode.BALANCED,
            minConfidencePct = 60, explorationRate = ExplorationRate.LOW,
            burstActive = false, thermalThrottled = false, batteryPct = 90, nowMs = 50_000L
        )
        assertTrue(pos in 0..2)
    }

    @Test
    fun settlesAfterFlipAndTracksChargingSkipAndBlocksChargingExploration() {
        val pids = intArrayOf(0)
        val freqs = intArrayOf(1500)
        val utils = floatArrayOf(35f)
        val cores = intArrayOf(6)

        // While charging: skips learning, counts samplesSkippedCharging, exposes learningPausedCharging
        for (i in 0 until 4) {
            model.trainStepInternal(
                timestampMs = 40_000L + i * 1000L, watts = 0f, isCharging = true, isInteractive = true,
                isSleepSaving = false, isGame = false, fgPkg = "com.app",
                learningEnabled = true, efficiencyEnabled = false,
                clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
                clusterUtilPct = utils, clusterCoreCounts = cores,
                gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f,
                batteryState = BatteryState.CHARGING
            )
        }
        model.refreshUiSnapshotNow()
        assertEquals(0L, model.uiSnapshot.value.totalSamples)
        assertEquals(4L, model.uiSnapshot.value.samplesSkippedCharging)
        assertTrue(model.uiSnapshot.value.learningPausedCharging)

        // Flip to DISCHARGING at t = 50_000: first 4.9 s must NOT learn
        for (dt in listOf(0L, 1000L, 2500L, 4500L)) {
            model.trainStepInternal(
                timestampMs = 50_000L + dt, watts = 3.0f, isCharging = false, isInteractive = true,
                isSleepSaving = false, isGame = false, fgPkg = "com.app",
                learningEnabled = true, efficiencyEnabled = false,
                clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
                clusterUtilPct = utils, clusterCoreCounts = cores,
                gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f,
                batteryState = BatteryState.DISCHARGING
            )
        }
        model.refreshUiSnapshotNow()
        assertEquals(0L, model.uiSnapshot.value.totalSamples)
        assertFalse(model.uiSnapshot.value.learningPausedCharging)

        // At t = 55_000 (5 s after flip) learning resumes
        model.trainStepInternal(
            timestampMs = 55_000L, watts = 3.0f, isCharging = false, isInteractive = true,
            isSleepSaving = false, isGame = false, fgPkg = "com.app",
            learningEnabled = true, efficiencyEnabled = false,
            clusterCount = 1, clusterPolicyIds = pids, clusterFreqMhz = freqs,
            clusterUtilPct = utils, clusterCoreCounts = cores,
            gpuAvailable = true, gpuFreqMhz = 600, gpuUtilPct = 30f,
            batteryState = BatteryState.DISCHARGING
        )
        model.refreshUiSnapshotNow()
        assertEquals(1L, model.uiSnapshot.value.totalSamples)

        // Exploration must never probe away from standard target while charging
        val avail = listOf(500_000L, 1_000_000L, 1_500_000L, 2_000_000L)
        for (i in 0 until 60) {
            val c = model.selectEfficientCpuFreqKhz(
                policyId = 0, availableFreqsKhz = avail, allowedMinKhz = 500_000L, cappedMaxKhz = 2_000_000L,
                standardTargetKhz = 1_500_000L, currentFreqKhz = 1_500_000L, rawLoadPct = 20f, coreCount = 6,
                scenarioKey = "com.app", isGame = false, fps = -1f, targetRefreshHz = 60f,
                efficiencyEnabled = true, efficiencyMode = EfficiencyMode.BALANCED,
                minConfidencePct = 60, explorationRate = ExplorationRate.HIGH,
                burstActive = false, thermalThrottled = false, batteryPct = 90,
                nowMs = 60_000L + i * 7_000L, batteryState = BatteryState.CHARGING
            )
            assertEquals(1_500_000L, c)
        }
    }
}
