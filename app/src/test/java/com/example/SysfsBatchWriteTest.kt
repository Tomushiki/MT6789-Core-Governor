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

import com.example.root.SysfsBatchWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SysfsBatchWriteTest {
    private val pair = listOf(
        "/sys/p/scaling_max_freq" to "2000000",
        "/sys/p/scaling_min_freq" to "2000000"
    )

    @Test
    fun commandKeepsOrderAndReportsEachWrite() {
        val cmd = SysfsBatchWrite.buildCommand(pair) { false }
        val maxAt = cmd.indexOf("scaling_max_freq")
        val minAt = cmd.indexOf("scaling_min_freq")
        assertTrue(maxAt in 0 until minAt)
        assertTrue(cmd.contains("__CGW_0_$?"))
        assertTrue(cmd.contains("__CGW_1_$?"))
        assertTrue(!cmd.contains("chmod"))
    }

    @Test
    fun firstTimePathGetsChmod() {
        val cmd = SysfsBatchWrite.buildCommand(pair) { it.endsWith("max_freq") }
        assertEquals(1, Regex("chmod u\\+w").findAll(cmd).count())
    }

    @Test
    fun parseReadsPerWriteCodesAndIgnoresNoise() {
        val codes = SysfsBatchWrite.parseCodes("junk\n__CGW_0_0\n__CGW_1_1\n", 2)
        assertEquals(0, codes[0])
        assertEquals(1, codes[1])
    }

    @Test
    fun missingResultIsMinusOne() {
        val codes = SysfsBatchWrite.parseCodes("__CGW_0_0\n", 2)
        assertEquals(0, codes[0])
        assertEquals(-1, codes[1])
    }
}
