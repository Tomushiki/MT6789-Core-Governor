package com.example

import com.example.governor.GovernorThrottleCause
import com.example.governor.StutterLogic
import com.example.model.AfterburnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorThrottleClassificationTest {

    /** Feeds three dipping ticks (the consecutive-dip trigger) and returns the last tick's result. */
    private fun dip(
        d: StutterLogic.StutterDetector,
        startMs: Long = 100_000L,
        temp: Boolean = false,
        power: Boolean = false,
        ab: AfterburnState = AfterburnState.OFF,
        gpu: Float = 10f
    ): Boolean {
        var r = false
        for (i in 0 until StutterLogic.CONSECUTIVE_DIP_TRIGGER) {
            r = d.recordTick(
                nowMs = startMs + i * 1_000L, fps = 20f, baselineFps = 60f, baselineReady = true, dipPct = 75,
                tempThrottleActive = temp, powerThrottleActive = power, gpuLoadPct = gpu, afterburnState = ab
            )
        }
        return r
    }

    @Test fun thermalThrottleIsClassifiedAndSuppressesInvestigation() {
        val d = StutterLogic.StutterDetector()
        assertFalse(dip(d, temp = true))
        assertEquals(GovernorThrottleCause.THERMAL, d.lastSuppressedCause)
    }

    @Test fun afterburnBlockedByHeatIsThermal() {
        val d = StutterLogic.StutterDetector()
        assertFalse(dip(d, ab = AfterburnState.BLOCKED_THERMAL))
        assertEquals(GovernorThrottleCause.THERMAL, d.lastSuppressedCause)
    }

    @Test fun batteryPolicyThrottleIsClassifiedAndSuppressesInvestigation() {
        val d = StutterLogic.StutterDetector()
        assertFalse(dip(d, power = true))
        assertEquals(GovernorThrottleCause.BATTERY, d.lastSuppressedCause)
    }

    @Test fun afterburnBlockedByBatteryIsBattery() {
        val d = StutterLogic.StutterDetector()
        assertFalse(dip(d, ab = AfterburnState.BLOCKED_BATTERY))
        assertEquals(GovernorThrottleCause.BATTERY, d.lastSuppressedCause)
    }

    @Test fun lowBatteryWithoutActiveBatteryThrottleIsNotClassified() {
        // Nothing but Afterburn states that do not cap performance: no governor cause, process hunt proceeds.
        for (ab in listOf(AfterburnState.OFF, AfterburnState.IDLE, AfterburnState.BOOSTING, AfterburnState.REDUCED)) {
            assertEquals(GovernorThrottleCause.NONE, StutterLogic.classifyThrottle(false, false, ab))
            val d = StutterLogic.StutterDetector()
            assertTrue(dip(d, ab = ab))
            assertEquals(GovernorThrottleCause.NONE, d.lastSuppressedCause)
        }
    }

    @Test fun culpritInvestigationStillRunsWithoutGovernorThrottling() {
        val d = StutterLogic.StutterDetector()
        assertTrue(dip(d))
        assertEquals(GovernorThrottleCause.NONE, d.lastSuppressedCause)
    }

    @Test fun throttleStillCoversTheCooldownWindowThenExpires() {
        val d = StutterLogic.StutterDetector()
        d.recordTick(100_000L, 60f, 60f, true, 75, true, false, 10f)
        assertEquals(GovernorThrottleCause.THERMAL, d.governorCause(100_000L + StutterLogic.THROTTLE_COOLDOWN_MS))
        assertEquals(GovernorThrottleCause.NONE, d.governorCause(100_000L + StutterLogic.THROTTLE_COOLDOWN_MS + 1))
    }

    @Test fun thermalWinsWhenBothAreActive() {
        assertEquals(GovernorThrottleCause.THERMAL, StutterLogic.classifyThrottle(true, true))
    }
}
