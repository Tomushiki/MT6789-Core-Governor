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
import com.example.governor.MetricsMath
import com.example.governor.MetricsRecorder
import com.example.governor.SessionTweakSnapshot
import com.example.model.BatteryState
import com.example.model.BoostStrength
import com.example.model.FreqRange
import com.example.model.GameThreadControllerConfig
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.PerGameAutoTuneState
import com.example.model.PerGameProfile
import com.example.model.StutterHunterConfig
import com.example.model.StutterHunterMode
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SessionSnapshotTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "cg_snap_test_${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun snapshotCaptureEncodeAndDecodeRoundTrip() {
        val pkg = "com.example.game"
        val cfg = GovernorConfig(
            gamePackages = setOf(pkg),
            boostPackages = setOf(pkg),
            boostStrengthByPkg = mapOf(pkg to BoostStrength.MAX),
            ramPinMax = true,
            ramPinGameOnly = true,
            stutterHunter = StutterHunterConfig(enabled = true, mode = StutterHunterMode.STOP),
            gameThreadController = GameThreadControllerConfig(enabled = true),
            adaptiveRefreshEnabled = true,
            adaptiveIdleHz = 60,
            adaptiveActiveHz = 120,
            perGameProfiles = mapOf(
                pkg to PerGameProfile(
                    headroomPct = 28f,
                    powerLimitWatts = 6.5f,
                    tempStartC = 43.5f,
                    cpuRangesKhz = mapOf(0 to FreqRange(500_000L, 2_000_000L))
                )
            ),
            autoTuneByPkg = mapOf(pkg to PerGameAutoTuneState(enabled = true, pendingTrialId = 4))
        )
        val eff = AutoTuneLogic.resolveEffectiveForGame(cfg, pkg)
        val snap = SessionTweakSnapshot.capture(cfg, eff, pkg)
        val encoded = snap.encode()
        val decoded = SessionTweakSnapshot.decode(encoded)

        assertNotNull(decoded)
        assertEquals(28f, decoded!!.cpuHeadroomPct, 0.01f)
        assertEquals(6.5f, decoded.powerLimitWatts, 0.01f)
        assertEquals(43.5f, decoded.tempStartC, 0.01f)
        assertEquals("Max", decoded.afterburnStrength)
        assertTrue(decoded.ramPinMax)
        assertTrue(decoded.ramPinGameOnly)
        assertTrue(decoded.stutterHunterEnabled)
        assertEquals("STOP", decoded.stutterHunterMode)
        assertTrue(decoded.gameThreadControllerEnabled)
        assertTrue(decoded.adaptiveRefreshEnabled)
        assertEquals(60, decoded.adaptiveIdleHz)
        assertEquals(120, decoded.adaptiveActiveHz)
        assertTrue(decoded.perGameOverrideActive)
        assertTrue(decoded.autoTuneActive)
        assertEquals(4, decoded.autoTuneTrialId)
    }

    @Test
    fun legacyHeaderWithoutSnapshotStillLoadsSafely() {
        val legacyLine = "#CG1|1|com.old.game|1000|60000|60|58.0|45.0|52.0|39.5|42.0|4.20|5.80|13.8|2.10|0.07|0|3.10"
        val info = MetricsMath.parseHeader("sess_1000.csv", legacyLine)
        assertNotNull(info)
        assertEquals("com.old.game", info!!.packageName)
        assertEquals(58f, info.summary.avgFps, 0.01f)
        assertNull(info.snapshot)
        assertEquals(0, info.midSessionChanges)
        assertFalse(info.kept)
    }

    @Test
    fun recorderCapturesSnapshotMidSessionChangesAndProtectsKeptSessions() {
        val rec = MetricsRecorder(dir, CoroutineScope(Dispatchers.Default)) { _, _, _ -> }
        val cfg = GovernorConfig(ramPinMax = true)
        val eff = AutoTuneLogic.resolveEffectiveForGame(cfg, "com.game")
        rec.snapshotProvider = { pkg -> SessionTweakSnapshot.capture(cfg, eff, pkg) }

        val tel = TelemetryState(
            isRunning = true,
            activeMode = GovernorMode.GAME,
            foregroundPackage = "com.game",
            fps = 60f,
            watts = 4.5f,
            batteryState = BatteryState.DISCHARGING,
            temperatureC = 41f
        )
        for (i in 0 until 25) {
            rec.onTelemetry(tel, 10_000_000L + i * 1000L)
            if (i == 10 || i == 15) rec.recordMidSessionConfigChange()
        }
        rec.onStopped()

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && rec.sessions.value.isEmpty()) Thread.sleep(25)

        assertEquals(1, rec.sessions.value.size)
        val saved = rec.sessions.value[0]
        assertNotNull(saved.snapshot)
        assertEquals(2, saved.midSessionChanges)
        assertFalse(saved.kept)

        runBlocking {
            rec.setSessionKept(saved, true)
            assertTrue(rec.sessions.value[0].kept)
            // Default deleteAllSessions (includeKept = false) leaves kept sessions intact.
            rec.deleteAllSessions(includeKept = false)
            assertEquals(1, rec.sessions.value.size)
            // Explicit includeKept = true deletes kept sessions too.
            rec.deleteAllSessions(includeKept = true)
            assertEquals(0, rec.sessions.value.size)
        }
    }
}
