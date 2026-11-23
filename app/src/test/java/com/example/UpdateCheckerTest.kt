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

import com.example.governor.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(UpdateChecker.isNewer("v2.1.2", "2.1.1"))
        assertTrue(UpdateChecker.isNewer("2.2.0", "2.1.9"))
        assertTrue(UpdateChecker.isNewer("v3.0", "2.9.9"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v2.1.1", "2.1.1"))
        assertFalse(UpdateChecker.isNewer("2.1", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("1.12.8", "2.1.1"))
    }

    @Test fun numericNotLexicographic() {
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.0"))
    }

    @Test fun suffixesIgnored() {
        assertEquals(listOf(2, 1, 1), UpdateChecker.parseVersion("v2.1.1-beta+5"))
    }
}
