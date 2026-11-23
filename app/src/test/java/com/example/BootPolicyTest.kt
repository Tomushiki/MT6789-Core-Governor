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

import com.example.service.BootPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPolicyTest {

    @Test
    fun startsAfterRebootWhenItWasRunningEvenIfStartOnBootIsOff() {
        assertTrue(BootPolicy.shouldStartAfterBoot(startOnBoot = false, restoreEnabled = true, lastRunning = true))
    }

    @Test
    fun doesNotStartAfterRebootWhenItWasStoppedByTheUser() {
        assertFalse(BootPolicy.shouldStartAfterBoot(startOnBoot = false, restoreEnabled = true, lastRunning = false))
    }

    @Test
    fun oldStartOnBootSwitchStillAlwaysStarts() {
        assertTrue(BootPolicy.shouldStartAfterBoot(startOnBoot = true, restoreEnabled = false, lastRunning = false))
        assertTrue(BootPolicy.shouldStartAfterBoot(startOnBoot = true, restoreEnabled = true, lastRunning = false))
    }

    @Test
    fun restoreSwitchOffMeansNoRestore() {
        assertFalse(BootPolicy.shouldStartAfterBoot(startOnBoot = false, restoreEnabled = false, lastRunning = true))
    }

    @Test
    fun updateRestartsOnlyWhenItWasRunningAndRestoreIsOn() {
        assertTrue(BootPolicy.shouldStartAfterUpdate(restoreEnabled = true, lastRunning = true))
        assertFalse(BootPolicy.shouldStartAfterUpdate(restoreEnabled = true, lastRunning = false))
        assertFalse(BootPolicy.shouldStartAfterUpdate(restoreEnabled = false, lastRunning = true))
        // Start-on-boot must not make an app update start a governor the user had stopped.
    }
}
