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

package com.example

import com.example.governor.GameThreadLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameThreadFastEnumTest {

    @Test fun fastCommandGrepsAllCommFilesInOneGo() {
        val cmd = GameThreadLogic.buildFastThreadEnumCommand(19462)
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("grep -H '' /proc/19462/task/*/comm"))
        assertTrue(cmd.contains("PROC"))
        assertEquals(null, GameThreadLogic.buildFastThreadEnumCommand(0))
    }

    @Test fun parsesGrepOutputIncludingNamesWithColonsAndSpaces() {
        val out = "PROC\tcom.netease.newspike\n" +
            "/proc/19462/task/19462/comm:UnityMain\n" +
            "/proc/19462/task/19470/comm:Job.Worker 0\n" +
            "/proc/19462/task/19471/comm:Render:Thread\n" +
            "/proc/19462/task/abc/comm:bad\n" +
            "/proc/555/task/556/comm:other process\n" +
            "garbage line\n"
        val p = GameThreadLogic.parseFastThreadEnumOutput(out, 19462)
        assertEquals("com.netease.newspike", p.processName)
        assertEquals(listOf(19462, 19470, 19471), p.threads.map { it.tid })
        assertEquals("Job.Worker 0", p.threads[1].name)
        assertEquals("Render:Thread", p.threads[2].name)
    }

    @Test fun emptyOrInvalidOutputGivesNoThreads() {
        assertTrue(GameThreadLogic.parseFastThreadEnumOutput("", 100).threads.isEmpty())
        assertTrue(GameThreadLogic.parseFastThreadEnumOutput("/proc/100/task/101/comm:x", 0).threads.isEmpty())
    }

    @Test fun fastScanIsOnlyTrustedWhenItMatchesAndIsMuchFaster() {
        // same threads, 10x faster -> trust
        assertTrue(GameThreadLogic.fastEnumIsTrustworthy(100, 5000L, 100, 400L))
        // slightly different count is still fine
        assertTrue(GameThreadLogic.fastEnumIsTrustworthy(100, 5000L, 95, 400L))
        // missing too many threads -> reject
        assertFalse(GameThreadLogic.fastEnumIsTrustworthy(100, 5000L, 60, 400L))
        // wildly more threads -> reject (wrong glob / wrong process)
        assertFalse(GameThreadLogic.fastEnumIsTrustworthy(100, 5000L, 400, 400L))
        // not faster enough -> reject
        assertFalse(GameThreadLogic.fastEnumIsTrustworthy(100, 5000L, 100, 3000L))
        // nothing to compare against
        assertFalse(GameThreadLogic.fastEnumIsTrustworthy(0, 5000L, 0, 100L))
    }
}
