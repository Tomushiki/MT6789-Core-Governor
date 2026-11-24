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

import com.example.governor.ProtectionCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessProtectionTest {

    @Test
    fun parseOriginalSettingTreatsNullAndNoneAsUnset() {
        assertNull(ProtectionCommands.parseOriginalSetting(null))
        assertNull(ProtectionCommands.parseOriginalSetting(""))
        assertNull(ProtectionCommands.parseOriginalSetting("   \n"))
        assertNull(ProtectionCommands.parseOriginalSetting("null"))
        assertNull(ProtectionCommands.parseOriginalSetting("NULL"))
        assertNull(ProtectionCommands.parseOriginalSetting("None"))
        assertEquals("32", ProtectionCommands.parseOriginalSetting(" 32\n"))
        assertEquals("true", ProtectionCommands.parseOriginalSetting("true"))
    }

    @Test
    fun needsOomReapplyChecksNumericEquality() {
        assertFalse(ProtectionCommands.needsOomReapply("-900"))
        assertFalse(ProtectionCommands.needsOomReapply("  -900 \n"))
        assertTrue(ProtectionCommands.needsOomReapply("0"))
        assertTrue(ProtectionCommands.needsOomReapply("-800"))
        assertTrue(ProtectionCommands.needsOomReapply(""))
        assertTrue(ProtectionCommands.needsOomReapply(null))
    }

    @Test
    fun parseOomVerifyOutputValidatesAllPids() {
        val okOut = """
            __CG_OOM__
            1234:-900
            5678:-900
        """.trimIndent()
        assertTrue(ProtectionCommands.parseOomVerifyOutput(okOut))

        val partialFail = """
            __CG_OOM__
            1234:-900
            5678:0
        """.trimIndent()
        assertFalse(ProtectionCommands.parseOomVerifyOutput(partialFail))

        assertFalse(ProtectionCommands.parseOomVerifyOutput("__CG_OOM__\n"))
    }

    @Test
    fun parseWhitelistAndStandbyDetectsBoth() {
        val pkg = "com.aistudio.coregovernor.mt6789"
        val out = """
            __CG_DOZE__
            user,com.aistudio.coregovernor.mt6789,10234
            __CG_BUCKET__
            active
        """.trimIndent()
        val (doze, standby) = ProtectionCommands.parseWhitelistAndStandby(out, pkg)
        assertTrue(doze)
        assertTrue(standby)

        val numericBucketOut = """
            __CG_DOZE__
            __CG_BUCKET__
            10
        """.trimIndent()
        val (doze2, standby2) = ProtectionCommands.parseWhitelistAndStandby(numericBucketOut, pkg)
        assertFalse(doze2)
        assertTrue(standby2)
    }

    @Test
    fun phantomRestoreUsesDeleteWhenUnsetAndPutWhenSaved() {
        val deleteCmd = ProtectionCommands.buildRestorePhantomCommand(null, null)
        assertTrue(deleteCmd.contains("device_config delete activity_manager max_phantom_processes"))
        assertTrue(deleteCmd.contains("settings delete global settings_enable_monitor_phantom_procs"))

        val restoreCmd = ProtectionCommands.buildRestorePhantomCommand("32", "true")
        assertTrue(restoreCmd.contains("device_config put activity_manager max_phantom_processes '32'"))
        assertTrue(restoreCmd.contains("settings put global settings_enable_monitor_phantom_procs 'true'"))
    }

    @Test
    fun phantomDisableAndVerifyParsesCorrectly() {
        val out = """
            __CG_PMAX__
            2147483647
            __CG_PMON__
            false
        """.trimIndent()
        assertTrue(ProtectionCommands.isPhantomDisabled(out))

        val resetOut = """
            __CG_PMAX__
            32
            __CG_PMON__
            true
        """.trimIndent()
        assertFalse(ProtectionCommands.isPhantomDisabled(resetOut))
    }

    @Test
    fun watchdogScriptIncludesFlagPidGuardAndRateLimit() {
        val script = ProtectionCommands.buildWatchdogScript(
            packageName = "com.aistudio.coregovernor.mt6789",
            flagPath = "/data/user/0/com.aistudio.coregovernor.mt6789/files/protect.run",
            pidFile = "/data/user/0/com.aistudio.coregovernor.mt6789/files/protect.pid"
        )
        assertTrue(script.contains("kill -0"))
        assertTrue(script.contains("protect.run"))
        assertTrue(script.contains("am start-foreground-service -n 'com.example/.service.GovernorService' -a 'com.example.action.START_GOVERNOR' --ez 'com.example.extra.FROM_BOOT' true"))
        assertTrue(script.contains("-ge 3"))
        assertTrue(script.contains("-lt 120"))
    }
}
