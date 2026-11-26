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

import com.example.governor.AutoTuneLogic
import com.example.governor.MetricSummary
import com.example.governor.SessionInfo
import com.example.model.AutoTuneMode
import com.example.model.AutoTuneParam
import com.example.model.AutoTuneTrialStatus
import com.example.model.GovernorConfig
import com.example.model.PerGameProfile
import com.example.model.PowerThrottleConfig
import com.example.model.TempThrottleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTuneTest {

    private val pkg = "com.test.game"

    private fun makeSession(
        file: String,
        durMin: Int = 10,
        samples: Int = 600,
        avgFps: Float = 55f,
        p5Fps: Float = 48f,
        avgTemp: Float = 40f,
        maxTemp: Float = 42f,
        avgWatts: Float = 4.2f,
        fpw: Float = 13.1f,
        stability: Float = 86f,
        charging: Boolean = false,
        midChanges: Int = 0,
        thermalThrottleSec: Int = 0,
        powerThrottleSec: Int = 0
    ) = SessionInfo(
        fileName = file,
        packageName = pkg,
        startMs = 1_000_000L,
        endMs = 1_000_000L + durMin * 60_000L,
        summary = MetricSummary(
            durationMs = durMin * 60_000L,
            sampleCount = samples,
            avgFps = avgFps,
            minFps = p5Fps - 3f,
            p5Fps = p5Fps,
            avgTempC = avgTemp,
            maxTempC = maxTemp,
            avgWatts = avgWatts,
            maxWatts = avgWatts + 0.8f,
            avgFpsPerWatt = fpw,
            avgWorkPerWatt = 2.1f,
            energyWh = 0.5f,
            includesCharging = charging,
            p1Fps = p5Fps - 1f,
            p50Fps = avgFps,
            frameStabilityPct = stability,
            thermalThrottleSec = thermalThrottleSec,
            powerThrottleSec = powerThrottleSec
        ),
        midSessionChanges = midChanges
    )

    @Test
    fun perGameOverrideIsClampedByGlobalThermalAndPowerCaps() {
        val cfg = GovernorConfig(
            powerThrottle = PowerThrottleConfig(limitWatts = 5.5f),
            tempThrottle = TempThrottleConfig(startTempC = 42f, fullTempC = 52f),
            perGameProfiles = mapOf(
                pkg to PerGameProfile(
                    powerLimitWatts = 8.0f,
                    tempStartC = 49f,
                    tempFullC = 60f,
                    headroomPct = 30f
                )
            )
        )
        val eff = AutoTuneLogic.resolveEffectiveForGame(cfg, pkg)
        assertEquals(5.5f, eff.powerThrottle.limitWatts, 0.01f)
        assertEquals(42f, eff.tempThrottle.startTempC, 0.01f)
        assertEquals(52f, eff.tempThrottle.fullTempC, 0.01f)
        assertEquals(30f, eff.gameMode.headroomPct, 0.01f)
    }

    @Test
    fun invalidSessionsAreIgnoredCleanly() {
        var cfg = AutoTuneLogic.setEnabled(GovernorConfig(), pkg, true)
        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("s1.csv", charging = true))
        assertFalse(cfg.autoTuneByPkg[pkg]!!.baselineCaptured)
        assertEquals(AutoTuneTrialStatus.IGNORED, cfg.autoTuneByPkg[pkg]!!.trials.last().status)

        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("s2.csv", durMin = 2, samples = 120))
        assertFalse(cfg.autoTuneByPkg[pkg]!!.baselineCaptured)

        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("s3.csv", midChanges = 1))
        assertFalse(cfg.autoTuneByPkg[pkg]!!.baselineCaptured)

        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("s4.csv", avgFps = Float.NaN, p5Fps = Float.NaN, fpw = Float.NaN, stability = Float.NaN))
        assertFalse(cfg.autoTuneByPkg[pkg]!!.baselineCaptured)
    }

    @Test
    fun autoApplyBaselineImprovementAndSafetyRevertFlow() {
        var cfg = GovernorConfig()
        cfg = AutoTuneLogic.setEnabled(cfg, pkg, true)
        cfg = AutoTuneLogic.setMode(cfg, pkg, AutoTuneMode.AUTO_APPLY)

        // Clean baseline session
        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("base.csv", avgFps = 50f, p5Fps = 45f, fpw = 12f, avgTemp = 41f, stability = 82f))
        val stBase = cfg.autoTuneByPkg[pkg]!!
        assertTrue(stBase.baselineCaptured)
        assertEquals(AutoTuneTrialStatus.BASELINE, stBase.trials.last().status)

        // Prepare candidate ONLY at next session start
        cfg = AutoTuneLogic.prepareSessionStart(cfg, pkg)
        val stPend = cfg.autoTuneByPkg[pkg]!!
        assertNotNull(stPend.pendingCandidate)
        assertNotNull(cfg.perGameProfiles[pkg])

        // Trial improves score well past 3% margin -> KEPT
        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("t1.csv", avgFps = 60f, p5Fps = 56f, fpw = 16f, avgTemp = 39f, stability = 95f))
        val stKept = cfg.autoTuneByPkg[pkg]!!
        assertEquals(AutoTuneTrialStatus.KEPT, stKept.trials.last().status)
        assertEquals(stKept.bestProfile, cfg.perGameProfiles[pkg])

        // Next trial starts and triggers thermal breach -> REJECTED_SAFETY, blacklisted, reverted immediately
        cfg = AutoTuneLogic.prepareSessionStart(cfg, pkg)
        val badKey = cfg.autoTuneByPkg[pkg]!!.pendingCandidate!!.key
        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("t2.csv", maxTemp = 60f, thermalThrottleSec = 300))
        val stSafe = cfg.autoTuneByPkg[pkg]!!
        assertEquals(AutoTuneTrialStatus.REJECTED_SAFETY, stSafe.trials.last().status)
        assertTrue(badKey in stSafe.badCandidateKeys)
        assertEquals(stSafe.bestProfile, cfg.perGameProfiles[pkg] ?: PerGameProfile())
    }

    @Test
    fun suggestModeAndRecoveryActionsWorkAsExpected() {
        var cfg = GovernorConfig()
        cfg = AutoTuneLogic.setEnabled(cfg, pkg, true)
        cfg = AutoTuneLogic.setMode(cfg, pkg, AutoTuneMode.SUGGEST_ONLY)
        cfg = AutoTuneLogic.evaluateCompletedSession(cfg, makeSession("base.csv"))
        assertNotNull(cfg.autoTuneByPkg[pkg]!!.pendingSuggestion)

        cfg = AutoTuneLogic.applySuggestion(cfg, pkg)
        assertNull(cfg.autoTuneByPkg[pkg]!!.pendingSuggestion)
        assertNotNull(cfg.autoTuneByPkg[pkg]!!.pendingCandidate)

        // Locking parameter excludes it cleanly
        cfg = AutoTuneLogic.toggleLockParam(cfg, pkg, AutoTuneParam.POWER_LIMIT_WATTS)
        assertTrue(AutoTuneParam.POWER_LIMIT_WATTS in cfg.autoTuneByPkg[pkg]!!.lockedParams)

        // Revert to original user settings disables auto-tune and restores null/original profile
        cfg = AutoTuneLogic.revertToUserSettings(cfg, pkg)
        assertFalse(cfg.autoTuneByPkg[pkg]!!.enabled)
        assertNull(cfg.perGameProfiles[pkg])
    }
}
