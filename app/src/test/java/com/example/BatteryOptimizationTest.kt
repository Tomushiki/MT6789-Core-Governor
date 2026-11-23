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

import com.example.governor.BatteryOptimization
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationTest {

    @Test
    fun rootCommandWhitelistsThePackageAndAlwaysEndsSuccessfully() {
        val cmd = BatteryOptimization.rootCommand("com.aistudio.coregovernor.mt6789")
        assertTrue(cmd.contains("deviceidle whitelist +com.aistudio.coregovernor.mt6789"))
        assertTrue(cmd.contains("RUN_ANY_IN_BACKGROUND allow"))
        // The real result is checked with PowerManager afterwards, so the command itself must not fail the shell.
        assertTrue(cmd.trimEnd().endsWith("true"))
    }
}
