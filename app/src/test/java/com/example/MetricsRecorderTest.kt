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

import com.example.governor.MetricsRecorder
import com.example.model.GovernorMode
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MetricsRecorderTest {

    private lateinit var dir: File
    private lateinit var rec: MetricsRecorder

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "cg_metrics_test_${System.nanoTime()}")
        rec = MetricsRecorder(dir, CoroutineScope(Dispatchers.Default)) { _, _, _ -> }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun tel(
        mode: GovernorMode,
        pkg: String = "com.game",
        fps: Float = 60f,
        running: Boolean = true,
        watts: Float = 4f,
        chargeWatts: Float = 0f,
        batteryState: com.example.model.BatteryState = com.example.model.BatteryState.DISCHARGING
    ) =
        TelemetryState(
            isRunning = running, activeMode = mode, foregroundPackage = pkg,
            fps = fps, watts = watts, chargeWatts = chargeWatts,
            batteryState = batteryState, temperatureC = 40f
        )

    private fun feed(from: Long, count: Int, t: TelemetryState) {
        for (i in 0 until count) rec.onTelemetry(t, from + i * 1000L)
    }

    private fun waitForSessions(n: Int) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && rec.sessions.value.size < n) Thread.sleep(25)
    }

    @Test
    fun gameSessionIsSavedWhenItEnds() {
        feed(1_000_000L, 30, tel(GovernorMode.GAME))
        assertTrue(rec.recording.value)
        rec.onTelemetry(tel(GovernorMode.NORMAL), 1_000_000L + 30_000L)
        assertFalse(rec.recording.value)
        waitForSessions(1)
        assertEquals(1, rec.sessions.value.size)
        val info = rec.sessions.value[0]
        assertEquals("com.game", info.packageName)
        assertEquals(30, info.summary.sampleCount)
        assertEquals(60f, info.summary.avgFps, 0.01f)
        val samples = runBlocking { rec.loadSession(info) }
        assertEquals(30, samples.size)
        assertEquals(4f, samples[0].watts, 0.01f)
    }

    @Test
    fun shortSessionIsNotSaved() {
        feed(2_000_000L, 5, tel(GovernorMode.GAME))
        rec.onStopped()
        Thread.sleep(300)
        assertEquals(0, rec.sessions.value.size)
    }

    @Test
    fun liveHistoryRecordsAllModesAtOneHz() {
        // Ticks 250 ms apart should still give one sample per second.
        rec.setLiveVisible(true)
        for (i in 0 until 40) rec.onTelemetry(tel(GovernorMode.NORMAL), 3_000_000L + i * 250L)
        assertEquals(10, rec.live.value.size)
        assertFalse(rec.recording.value)
    }

    @Test
    fun liveListIsOnlyPublishedWhileVisibleButStillBuffered() {
        for (i in 0 until 40) rec.onTelemetry(tel(GovernorMode.NORMAL), 3_100_000L + i * 250L)
        assertEquals(0, rec.live.value.size)
        // Becoming visible publishes everything buffered so far, once.
        rec.setLiveVisible(true)
        assertEquals(10, rec.live.value.size)
        rec.onTelemetry(tel(GovernorMode.NORMAL), 3_100_000L + 40 * 250L)
        assertEquals(11, rec.live.value.size)
        // Hiding releases the published copy; the buffer keeps filling.
        rec.setLiveVisible(false)
        assertEquals(0, rec.live.value.size)
        rec.onTelemetry(tel(GovernorMode.NORMAL), 3_100_000L + 41 * 250L + 1_000L)
        assertEquals(0, rec.live.value.size)
        rec.setLiveVisible(true)
        assertEquals(12, rec.live.value.size)
    }

    @Test
    fun ratingFeedKeepsFlowingWhileLiveListIsHidden() {
        var count = 0
        rec.onSample = { _, _ -> count++ }
        for (i in 0 until 40) rec.onTelemetry(tel(GovernorMode.NORMAL), 3_200_000L + i * 250L)
        assertEquals(10, count)
        assertEquals(0, rec.live.value.size)
    }

    @Test
    fun stoppingTheGovernorSavesAndLongGapSplits() {
        feed(4_000_000L, 25, tel(GovernorMode.GAME))
        // 60 s pause (screen-off saving), then the game continues: first part is saved on its own.
        feed(4_000_000L + 85_000L, 1, tel(GovernorMode.GAME))
        // Let the first save finish before the second starts, so the two background saves cannot race.
        waitForSessions(1)
        feed(4_000_000L + 86_000L, 24, tel(GovernorMode.GAME))
        rec.onTelemetry(tel(GovernorMode.GAME, running = false), 4_000_000L + 120_000L)
        waitForSessions(2)
        assertEquals(2, rec.sessions.value.size)
    }

    @Test
    fun switchingGamesStartsANewSession() {
        feed(5_000_000L, 22, tel(GovernorMode.GAME, pkg = "com.a"))
        feed(5_000_000L + 22_000L, 22, tel(GovernorMode.GAME, pkg = "com.b"))
        rec.onStopped()
        waitForSessions(2)
        assertEquals(setOf("com.a", "com.b"), rec.sessions.value.map { it.packageName }.toSet())
    }

    @Test
    fun deleteRemovesTheFile() {
        feed(6_000_000L, 25, tel(GovernorMode.GAME))
        rec.onStopped()
        waitForSessions(1)
        runBlocking { rec.deleteSession(rec.sessions.value[0]) }
        assertEquals(0, rec.sessions.value.size)
    }

    @Test
    fun chargingSamplesAreRecordedForChartButExcludedFromStatsAndFlagged() {
        feed(
            7_000_000L, 15,
            tel(GovernorMode.GAME, watts = 4f, batteryState = com.example.model.BatteryState.DISCHARGING)
        )
        feed(
            7_000_000L + 15_000L, 15,
            tel(GovernorMode.GAME, watts = 0f, chargeWatts = 12f, batteryState = com.example.model.BatteryState.CHARGING)
        )
        rec.onStopped()
        waitForSessions(1)
        assertEquals(1, rec.sessions.value.size)
        val info = rec.sessions.value[0]
        assertEquals(30, info.summary.sampleCount)
        assertTrue(info.summary.includesCharging)
        assertEquals(4f, info.summary.avgWatts, 0.01f)
        assertEquals(4f, info.summary.maxWatts, 0.01f)
        // 14 consecutive 1s discharge intervals at 4 W = 56 Ws / 3600 (~0.0156 Wh; CSV header formats %.2f -> 0.02)
        assertEquals(56f / 3600f, info.summary.energyWh, 0.005f)
        val samples = runBlocking { rec.loadSession(info) }
        assertEquals(30, samples.size)
        assertEquals(56f / 3600f, com.example.governor.MetricsMath.summarize(samples).energyWh, 0.0001f)
        assertEquals(com.example.model.BatteryState.DISCHARGING, samples[0].batteryState)
        assertEquals(com.example.model.BatteryState.CHARGING, samples[15].batteryState)
        assertEquals(12f, samples[15].watts, 0.01f)
    }
}
