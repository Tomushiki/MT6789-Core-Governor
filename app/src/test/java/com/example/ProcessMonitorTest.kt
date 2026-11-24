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

import com.example.governor.CulpritTracker
import com.example.governor.ProcRow
import com.example.governor.ProcessMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessMonitorTest {
    private val out = """
PID UID RSS ARGS
1 0 3000 /system/bin/init
812 1000 90000 system_server
4001 u0_a123 150000 com.example.game
4002 u0_a123 20000 com.example.game:remote
5000 u0_a77 60000 com.heavy.app
===STAT===
1 (init) S 0 1 1 0 -1 4194560 100 0 0 0 5 5 0 0 20 0 1 0 1 1 1 1 1 1 1 1 1 1 1
812 (system_server) S 1 1 1 0 -1 4194560 100 0 0 0 100 50 0 0 20 0 1 0 1 1 1 1 1 1 1 1 1 1 1
4001 (m.example.game) S 1 1 1 0 -1 4194560 100 0 0 0 200 100 0 0 20 0 1 0 1 1 1 1 1 1 1 1 1 1 1
4002 (xample.game:rem) S 1 1 1 0 -1 4194560 100 0 0 0 10 10 0 0 20 0 1 0 1 1 1 1 1 1 1 1 1 1 1
5000 (com.heavy.app) S 1 1 1 0 -1 4194560 100 0 0 0 40 10 0 0 20 0 1 0 1 1 1 1 1 1 1 1 1 1 1
"""

    @Test
    fun parsesPsAndStat() {
        val p = ProcessMonitor.parse(out)
        assertEquals(5, p.size)
        val game = p.first { it.pid == 4001 }
        assertEquals(10123, game.uid)
        assertEquals(300L, game.ticks)
        assertEquals("com.example.game", game.name)
    }

    @Test
    fun groupsSubprocessesAndComputesCpu() {
        val first = ProcessMonitor.parse(out)
        val prev = first.associate { it.pid to it.ticks }
        val later = first.map { if (it.pid == 5000) it.copy(ticks = it.ticks + 500) else it }
        val rows = ProcessMonitor.group(later, prev, 5_000L, "com.example.game", setOf("com.launcher"))
        val heavy = rows.first { it.key == "com.heavy.app" }
        assertEquals(100f, heavy.cpuPct, 0.01f)
        val game = rows.first { it.key == "com.example.game" }
        assertEquals(2, game.procCount)
        assertTrue(game.foreground)
        assertFalse(game.killable)
        assertTrue(heavy.killable)
        assertEquals("com.heavy.app", rows.first().key)
    }

    @Test
    fun protectionRules() {
        assertNotNull(ProcessMonitor.protectedReason("init", 0, emptySet()))
        assertNotNull(ProcessMonitor.protectedReason("system_server", 1000, emptySet()))
        assertNotNull(ProcessMonitor.protectedReason("com.android.systemui", 10050, emptySet()))
        assertNotNull(ProcessMonitor.protectedReason("com.my.launcher", 10060, setOf("com.my.launcher")))
        assertNull(ProcessMonitor.protectedReason("com.some.app", 10070, emptySet()))
    }

    @Test
    fun culpritNeedsSustainedBackgroundUse() {
        val t = CulpritTracker(window = 3)
        fun row(cpu: Float, fg: Boolean = false) = ProcRow("com.heavy.app", true, 1, cpu, 50f, fg, null)
        assertFalse(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertFalse(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertTrue(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertFalse(t.update(listOf(row(30f, fg = true)), 15f)[0].culprit)
    }

    @Test
    fun defaultCulpritWindowIsFourSamples() {
        // v2.7.4: samples are 8 s apart, so 4 samples cover the same ~30 s as 6 samples at 4 s did.
        assertEquals(4, CulpritTracker.DEFAULT_WINDOW)
        val t = CulpritTracker()
        fun row(cpu: Float) = ProcRow("com.heavy.app", true, 1, cpu, 50f, false, null)
        assertFalse(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertFalse(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertFalse(t.update(listOf(row(30f)), 15f)[0].culprit)
        assertTrue(t.update(listOf(row(30f)), 15f)[0].culprit)
    }

    @Test
    fun culpritWindowForgetsOldSamples() {
        val t = CulpritTracker()
        fun row(cpu: Float) = ProcRow("com.heavy.app", true, 1, cpu, 50f, false, null)
        repeat(4) { t.update(listOf(row(30f)), 15f) }
        // Four quiet samples push the busy ones out of the window, so the flag clears.
        var last = t.update(listOf(row(0f)), 15f)[0]
        repeat(3) { last = t.update(listOf(row(0f)), 15f)[0] }
        assertFalse(last.culprit)
    }

    @Test
    fun badNamesAreNotKillable() {
        val r = ProcRow("com.x; reboot", true, 1, 50f, 1f, false, null)
        assertFalse(r.killable)
    }
}
