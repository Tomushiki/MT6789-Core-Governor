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

    private fun tel(mode: GovernorMode, pkg: String = "com.game", fps: Float = 60f, running: Boolean = true) =
        TelemetryState(
            isRunning = running, activeMode = mode, foregroundPackage = pkg,
            fps = fps, watts = 4f, temperatureC = 40f
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
        for (i in 0 until 40) rec.onTelemetry(tel(GovernorMode.NORMAL), 3_000_000L + i * 250L)
        assertEquals(10, rec.live.value.size)
        assertFalse(rec.recording.value)
    }

    @Test
    fun stoppingTheGovernorSavesAndLongGapSplits() {
        feed(4_000_000L, 25, tel(GovernorMode.GAME))
        // 60 s pause (screen-off saving), then the game continues: first part is saved on its own.
        feed(4_000_000L + 85_000L, 25, tel(GovernorMode.GAME))
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
}
