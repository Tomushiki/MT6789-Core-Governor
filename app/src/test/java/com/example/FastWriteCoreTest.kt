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

import com.example.root.FastWriteCore
import com.example.root.FastWriteProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FastWriteCoreTest {
    private fun tmp(): File = File.createTempFile("cgfast", ".txt").also { it.deleteOnExit() }

    @Test
    fun writesAndKeepsTheFileOpen() {
        val f = tmp()
        val core = FastWriteCore()
        assertEquals(0, core.write(f.path, "2000000"))
        assertEquals(1, core.openCount)
        assertEquals("2000000", f.readText())
        assertEquals(0, core.write(f.path, "850000"))
        assertEquals(1, core.openCount)
        assertEquals("850000", f.readText().take(6))
        core.closeAll()
        assertEquals(0, core.openCount)
    }

    @Test
    fun sequentialWritesReachTheNodeEachTime() {
        val f = tmp()
        val core = FastWriteCore()
        for (v in listOf("1", "22", "333", "4")) assertEquals(0, core.write(f.path, v))
        assertTrue(f.readText().startsWith("4"))
        core.closeAll()
    }

    @Test
    fun missingPathFailsWithoutThrowing() {
        val core = FastWriteCore()
        assertEquals(1, core.write("/nonexistent-dir/for/test/scaling_max_freq", "1"))
        assertEquals(0, core.openCount)
    }

    @Test
    fun protocolRoundTrip() {
        assertEquals("W\t/p\t5\n", FastWriteProtocol.encodeRequest("/p", "5"))
        assertEquals(0, FastWriteProtocol.parseReply("CGW:0"))
        assertEquals(1, FastWriteProtocol.parseReply(" CGW:1 "))
        assertNull(FastWriteProtocol.parseReply("CGW:READY"))
        assertNull(FastWriteProtocol.parseReply("some su banner"))
        assertTrue(FastWriteProtocol.isEncodable("/sys/a", "123"))
        assertFalse(FastWriteProtocol.isEncodable("/sys/a\tb", "123"))
        assertFalse(FastWriteProtocol.isEncodable("/sys/a", "1\n2"))
    }
}
