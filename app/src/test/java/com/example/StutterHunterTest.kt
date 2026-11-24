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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigStorage
import com.example.governor.ProcRow
import com.example.governor.StutterLogic
import com.example.model.GovernorConfig
import com.example.model.StutterHunterConfig
import com.example.model.StutterHunterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class StutterHunterTest {

    @Test
    fun percentile75ReturnsExpectedInterpolatedValue() {
        val values = listOf(10f, 20f, 30f, 40f, 50f)
        assertEquals(40f, StutterLogic.percentile75(values), 0.01f)
        assertEquals(0f, StutterLogic.percentile75(emptyList()), 0.01f)
    }

    @Test
    fun baselineRequires120SamplesAndIgnoresWarmupAndZeroFps() {
        val tracker = StutterLogic.BaselineTracker()

        // Ignored during first 20 s warmup and fps <= 0
        for (i in 0 until 25) {
            tracker.onSample(fps = 60f, dipPct = 75, elapsedSinceForegroundMs = 19_999L)
        }
        tracker.onSample(fps = 0f, dipPct = 75, elapsedSinceForegroundMs = 25_000L)
        tracker.onSample(fps = -5f, dipPct = 75, elapsedSinceForegroundMs = 25_000L)
        assertEquals(0, tracker.sampleCount)
        assertFalse(tracker.isReady)

        // 119 valid samples: still learning
        repeat(119) { idx ->
            tracker.onSample(
                fps = if (idx % 4 == 0) 60f else 56f,
                dipPct = 75,
                elapsedSinceForegroundMs = 20_000L + idx * 1_000L
            )
        }
        assertEquals(119, tracker.sampleCount)
        assertFalse(tracker.isReady)
        assertEquals(99, tracker.learningPct)

        // 120th sample completes baseline
        tracker.onSample(fps = 60f, dipPct = 75, elapsedSinceForegroundMs = 140_000L)
        assertEquals(120, tracker.sampleCount)
        assertTrue(tracker.isReady)
        assertTrue(tracker.baselineFps >= 56f && tracker.baselineFps <= 60f)
    }

    @Test
    fun baselineIgnoresDipsWithoutLoweringLearnedFps() {
        val tracker = StutterLogic.BaselineTracker()
        repeat(120) {
            tracker.onSample(fps = 60f, dipPct = 75, elapsedSinceForegroundMs = 30_000L)
        }
        assertTrue(tracker.isReady)
        val initialBaseline = tracker.baselineFps

        // Feed 40 stutter frames (30 FPS < 60 * 0.75 = 45 FPS)
        repeat(40) {
            tracker.onSample(fps = 30f, dipPct = 75, elapsedSinceForegroundMs = 200_000L)
        }
        assertEquals(120, tracker.sampleCount)
        assertEquals(initialBaseline, tracker.baselineFps, 0.001f)
    }

    @Test
    fun stutterDetectorTriggersOnThreeConsecutiveOrFiveInTenTicks() {
        val detector = StutterLogic.StutterDetector()
        var t = 1_000L

        // Not ready yet -> false
        assertFalse(
            detector.recordTick(
                nowMs = t,
                fps = 20f,
                baselineFps = 60f,
                baselineReady = false,
                dipPct = 75,
                tempThrottleActive = false,
                powerThrottleActive = false,
                gpuLoadPct = 40f
            )
        )

        // Two consecutive dips -> false; one healthy tick resets streak
        repeat(2) {
            t += 1_000L
            assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))
        }
        t += 1_000L
        assertFalse(detector.recordTick(t, 60f, 60f, true, 75, false, false, 40f))

        // 3 consecutive dips -> true
        t += 1_000L
        assertFalse(detector.recordTick(t, 40f, 60f, true, 75, false, false, 40f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 40f, 60f, true, 75, false, false, 40f))
        t += 1_000L
        assertTrue(detector.recordTick(t, 40f, 60f, true, 75, false, false, 40f))

        // 5 dips spread over 9 ticks (never 3 consecutive) -> true on 5th dip
        val pattern = listOf(40f, 60f, 40f, 60f, 40f, 60f, 40f, 60f, 40f)
        var triggeredAt = -1
        pattern.forEachIndexed { idx, fps ->
            t += 1_000L
            if (detector.recordTick(t, fps, 60f, true, 75, false, false, 40f)) {
                triggeredAt = idx
            }
        }
        assertEquals(8, triggeredAt)
    }

    @Test
    fun stutterDetectorSkipsWhenThrottledOrGpuSaturated() {
        val detector = StutterLogic.StutterDetector()
        var t = 10_000L

        // Throttle active at t = 11_000 -> suppresses trigger within 10 s window
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, true, false, 40f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))

        // GPU >= 90% suppresses trigger even when no throttle was active
        detector.reset()
        t = 50_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 92f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 92f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 92f))

        // Power throttle active then wait > 10 s -> resumes triggering
        detector.reset()
        t = 100_000L
        assertFalse(detector.recordTick(t, 60f, 60f, true, 75, false, true, 40f))
        t += 11_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))
        t += 1_000L
        assertFalse(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))
        t += 1_000L
        assertTrue(detector.recordTick(t, 30f, 60f, true, 75, false, false, 40f))
    }

    @Test
    fun pickCulpritEnforcesExclusionsThresholdAndAveraging() {
        fun row(
            key: String,
            cpuPct: Float,
            isApp: Boolean = true,
            foreground: Boolean = false,
            protectedReason: String? = null
        ) = ProcRow(
            key = key,
            isApp = isApp,
            procCount = 1,
            cpuPct = cpuPct,
            ramMb = 120f,
            foreground = foreground,
            protectedReason = protectedReason
        )

        val snap1 = listOf(
            row("com.game.play", 95f, foreground = true),
            row("com.example", 30f),
            row("com.android.launcher", 45f),
            row("com.sys.pkg", 50f),
            row("com.whitelisted.app", 60f),
            row("com.ignored.app", 70f),
            row("com.prot.app", 55f, protectedReason = "Core system"),
            row("surfaceflinger", 80f, isApp = false),
            row("com.hog.second", 18f),
            row("com.hog.winner", 26f)
        )
        val snap2 = listOf(
            row("com.hog.second", 16f),
            row("com.hog.winner", 22f) // avg = (26 + 22) / 2 = 24
        )

        val culprit = StutterLogic.pickCulprit(
            deltaSnapshots = listOf(snap1, snap2),
            foregroundGame = "com.game.play",
            ownPackage = "com.example",
            protectedPackages = setOf("com.android.launcher"),
            systemPackages = setOf("com.sys.pkg"),
            whitelist = setOf("com.whitelisted.app"),
            cpuThresholdPct = 15,
            excludedInSession = setOf("com.ignored.app")
        )
        assertNotNull(culprit)
        assertEquals("com.hog.winner", culprit!!.packageName)
        assertEquals(24f, culprit.avgCpuPct, 0.01f)

        // Below threshold returns null
        val below = StutterLogic.pickCulprit(
            deltaSnapshots = listOf(listOf(row("com.low.app", 14f))),
            foregroundGame = "com.game.play",
            ownPackage = "com.example",
            protectedPackages = emptySet(),
            systemPackages = emptySet(),
            whitelist = emptySet(),
            cpuThresholdPct = 15
        )
        assertNull(below)
    }

    @Test
    fun sessionBrakesEnforceCooldownPerPkgCapSessionCapAndRecoveryIgnore() {
        val brakes = StutterLogic.SessionBrakes()
        var t = 1_000L

        assertTrue(brakes.canStop("com.a", t))
        brakes.recordStop("com.a", t)

        // 30 s cooldown applies to all packages
        assertFalse(brakes.canStop("com.a", t + 29_999L))
        assertFalse(brakes.canStop("com.b", t + 29_999L))

        // After 30 s, com.a can be stopped a second time
        t += 30_000L
        assertTrue(brakes.canStop("com.a", t))
        brakes.recordStop("com.a", t)

        // Max 2 per package reached for com.a
        t += 30_000L
        assertFalse(brakes.canStop("com.a", t))
        assertTrue("com.a" in brakes.excludedForStop())

        // Recovery check & markDidNotHelp
        assertTrue(StutterLogic.didRecover(baselineFps = 60f, dipPct = 75, fpsAfter = 46f))
        assertFalse(StutterLogic.didRecover(baselineFps = 60f, dipPct = 75, fpsAfter = 40f))
        brakes.markDidNotHelp("com.b")
        assertFalse(brakes.canStop("com.b", t))
        assertTrue("com.b" in brakes.excludedForStop())

        // Max 6 stops per session
        val others = listOf("com.c", "com.d", "com.e", "com.f")
        others.forEach { pkg ->
            t += 30_000L
            assertTrue(brakes.canStop(pkg, t))
            brakes.recordStop(pkg, t)
        }
        t += 30_000L
        assertFalse(brakes.canStop("com.g", t))

        // Reset clears everything on game exit
        brakes.reset()
        assertTrue(brakes.canStop("com.a", t))
        assertTrue(brakes.excludedForStop().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StutterHunterConfigStorageTest {

    @Test
    fun saveAndLoadRoundTripPreservesAndClampsStutterHunterConfig() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfgFile = File(ctx.filesDir, "core_governor_config.json")
        try {
            cfgFile.delete()
            val storage = ConfigStorage(ctx)
            val expected = StutterHunterConfig(
                enabled = true,
                mode = StutterHunterMode.STOP,
                dipPct = 82,
                cpuThresholdPct = 22,
                whitelist = setOf("com.foo.bar", "com.baz.qux")
            )
            storage.saveConfig(GovernorConfig(stutterHunter = expected))
            val loaded = storage.loadConfig()
            assertEquals(expected, loaded.stutterHunter)

            // Out-of-range values are clamped on load
            cfgFile.writeText(
                """
                {
                  "stutterHunter": {
                    "enabled": true,
                    "mode": "NOTIFY",
                    "dipPct": 99,
                    "cpuThresholdPct": 2,
                    "whitelist": ["com.keep.me", ""]
                  }
                }
                """.trimIndent()
            )
            val clamped = storage.loadConfig().stutterHunter
            assertTrue(clamped.enabled)
            assertEquals(StutterHunterMode.NOTIFY, clamped.mode)
            assertEquals(90, clamped.dipPct)
            assertEquals(10, clamped.cpuThresholdPct)
            assertEquals(setOf("com.keep.me"), clamped.whitelist)
        } finally {
            cfgFile.delete()
        }
    }

    @Test
    fun missingStutterHunterKeyLoadsDefaultsCleanly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfgFile = File(ctx.filesDir, "core_governor_config.json")
        try {
            cfgFile.writeText("""{"pollIntervalMs": 120}""")
            val loaded = ConfigStorage(ctx).loadConfig()
            assertEquals(120L, loaded.pollIntervalMs)
            assertEquals(StutterHunterConfig(), loaded.stutterHunter)
        } finally {
            cfgFile.delete()
        }
    }
}
