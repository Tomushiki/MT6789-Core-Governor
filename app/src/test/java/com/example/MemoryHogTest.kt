package com.example

import com.example.governor.ProcRow
import com.example.governor.ProcessMonitor
import com.example.governor.StutterLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryHogTest {
    private fun row(key: String, mb: Float, cpu: Float = 0f, prot: String? = null) =
        ProcRow(key, true, 1, cpu, mb, false, prot)

    private val low = StutterLogic.MemInfo(totalMb = 8000f, availableMb = 600f)
    private val fine = StutterLogic.MemInfo(totalMb = 8000f, availableMb = 4000f)

    private fun pick(mem: StutterLogic.MemInfo?, snaps: List<List<ProcRow>>, wl: Set<String> = emptySet()) =
        StutterLogic.pickMemoryHog(snaps, mem, "com.game", "com.me", emptySet(), emptySet(), wl)

    private val snaps = listOf(
        listOf(row("com.big", 900f), row("com.small", 120f), row("com.game", 2500f)),
        listOf(row("com.big", 950f), row("com.small", 130f), row("com.game", 2500f))
    )

    @Test fun picksBiggestHolderUnderPressure() {
        val c = pick(low, snaps)
        assertNotNull(c)
        assertEquals("com.big", c!!.packageName)
        assertEquals(900f, c.ramMb, 0.1f)
    }

    @Test fun nothingWithoutPressure() = assertNull(pick(fine, snaps))
    @Test fun nothingWithoutMemoryInfo() = assertNull(pick(null, snaps))

    @Test fun smallHoldersAreIgnored() {
        assertNull(pick(low, listOf(listOf(row("com.small", 200f)), listOf(row("com.small", 210f)))))
    }

    @Test fun whitelistProtectedAndGameAreSkipped() {
        assertNull(pick(low, snaps, wl = setOf("com.big")))
        assertNull(pick(low, listOf(listOf(row("com.big", 900f, prot = "Core system")), listOf(row("com.big", 900f, prot = "Core system")))))
    }

    @Test fun appMissingFromOneSampleIsNotSteady() {
        assertNull(pick(low, listOf(listOf(row("com.big", 900f)), listOf(row("com.other", 100f)))))
    }

    @Test fun psiAloneCountsAsPressure() {
        assertNotNull(pick(StutterLogic.MemInfo(8000f, 4000f, psiSomeAvg10 = 12f), snaps))
    }

    @Test fun parsesMemoryTail() {
        val out = "1 0 10 init\n${ProcessMonitor.STAT_MARK}\n${ProcessMonitor.MEM_MARK}\n" +
            "MemTotal:        8000000 kB\nMemFree:          100000 kB\nMemAvailable:     600000 kB\n" +
            "some avg10=9.50 avg60=3.00 avg300=1.00 total=12345\n"
        val m = ProcessMonitor.parseMem(out)!!
        assertEquals(7812f, m.totalMb, 1f)
        assertEquals(9.5f, m.psiSomeAvg10, 0.01f)
    }
}
