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

import com.example.service.BootGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootGuardTest {
    @Test
    fun firstStartIsAllowed() {
        val d = BootGuard.evaluate(previousAttemptUnstable = false, fails = 0)
        assertTrue(d.allowStart)
        assertEquals(0, d.fails)
    }

    @Test
    fun stableRunResetsTheCounter() {
        val d = BootGuard.evaluate(previousAttemptUnstable = false, fails = 2)
        assertTrue(d.allowStart)
        assertEquals(0, d.fails)
    }

    @Test
    fun skipsAfterThreeUnstableStartsThenRecovers() {
        var fails = 0
        var d = BootGuard.evaluate(true, fails); fails = d.fails
        assertTrue(d.allowStart)
        d = BootGuard.evaluate(true, fails); fails = d.fails
        assertTrue(d.allowStart)
        d = BootGuard.evaluate(true, fails); fails = d.fails
        assertFalse(d.allowStart)
        assertTrue(d.tripped)
        assertEquals(0, fails)
        d = BootGuard.evaluate(false, fails)
        assertTrue(d.allowStart)
    }
}
