package com.example

import com.example.governor.PowerCapController
import com.example.model.PowerThrottleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerCapControllerTest {
    private val cfg = PowerThrottleConfig(limitWatts = 6f, sustainMs = 2000L, stepPct = 3f, releaseStepPct = 1f)

    private fun step(s: PowerCapController.State, t: Long, avg: Float, fast: Float = avg, bypass: Boolean = false) =
        PowerCapController.step(s, t, avg, fast, cfg, 20f, bypass)

    @Test fun waitsForSustainBeforeEngaging() {
        var s = PowerCapController.State()
        s = step(s, 1000L, 6.5f)
        s = step(s, 2500L, 6.5f)
        assertEquals(100f, s.capPct, 0.001f)
        s = step(s, 3200L, 6.5f)
        assertTrue(s.capPct < 100f)
    }

    @Test fun bigSpikeEngagesAfterHalfTheSustain() {
        var s = PowerCapController.State()
        s = step(s, 1000L, 9f)
        s = step(s, 2100L, 9f)
        assertTrue(s.capPct < 100f)
    }

    @Test fun engagedCapKeepsSteppingWithoutNewSustainWait() {
        var s = PowerCapController.State(capPct = 90f, overSinceMs = 0L, lastStepMs = 0L, lastOverMs = 0L)
        val first = step(s, 1000L, 6.5f)
        val second = step(first, 1400L, 6.5f)
        assertTrue(second.capPct < first.capPct)
    }

    @Test fun biggerOvershootCutsHarder() {
        val s = PowerCapController.State(capPct = 90f, overSinceMs = 0L, lastStepMs = 0L, lastOverMs = 0L)
        val small = step(s, 1000L, 6.2f)
        val big = step(s, 1000L, 8f)
        assertTrue(big.capPct < small.capPct)
    }

    @Test fun neverGoesBelowFloor() {
        var s = PowerCapController.State(capPct = 21f, overSinceMs = 0L, lastStepMs = 0L, lastOverMs = 0L)
        s = step(s, 1000L, 12f)
        assertEquals(20f, s.capPct, 0.001f)
    }

    @Test fun releaseWaitsAfterBeingOver() {
        val s = PowerCapController.State(capPct = 80f, overSinceMs = -1L, lastStepMs = 0L, lastOverMs = 10_000L)
        assertEquals(80f, step(s, 10_500L, 4f).capPct, 0.001f)
        assertTrue(step(s, 12_000L, 4f).capPct > 80f)
    }

    @Test fun holdsInsideTheDeadBand() {
        val s = PowerCapController.State(capPct = 80f, overSinceMs = -1L, lastStepMs = 0L, lastOverMs = 0L)
        assertEquals(80f, step(s, 20_000L, 5.8f).capPct, 0.001f)
    }

    @Test fun afterburnBypassHoldsTheCap() {
        val s = PowerCapController.State(capPct = 80f, overSinceMs = 0L, lastStepMs = 0L, lastOverMs = 0L)
        val r = step(s, 5000L, 9f, bypass = true)
        assertEquals(80f, r.capPct, 0.001f)
        assertEquals(-1L, r.overSinceMs)
    }
}

class ThreadEnumCommandTest {
    @Test fun perThreadLoopForksNothing() {
        val cmd = com.example.governor.GameThreadLogic.buildThreadEnumCommand(1234)!!
        val loop = cmd.substringAfter("for t in").substringBefore("done;")
        assertTrue(loop.contains("read -r name"))
        assertTrue(!loop.contains("cat ") && !loop.contains("| tr"))
    }
}
