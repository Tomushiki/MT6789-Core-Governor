package com.example

import com.example.governor.AfterburnLogic
import com.example.model.BoostStrength
import com.example.model.AfterburnConfig
import com.example.model.AfterburnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterburnTest {
    private val cfg = AfterburnConfig(enabled = true, strength = BoostStrength.MAX)

    private fun decide(
        c: AfterburnConfig = cfg,
        selected: Boolean = true,
        temp: Float = 30f,
        start: Float = 42f,
        batt: Int = 80,
        charging: Boolean = false,
        was: Boolean = false
    ) = AfterburnLogic.decide(c, selected, temp, start, batt, charging, was)

    @Test fun offWhenDisabled() {
        assertEquals(AfterburnState.OFF, decide(c = cfg.copy(enabled = false)).state)
    }

    @Test fun idleWhenAppNotSelected() {
        val d = decide(selected = false)
        assertEquals(AfterburnState.IDLE, d.state)
        assertFalse(d.active)
    }

    @Test fun fullBoostWithHeadroomAndBattery() {
        val d = decide()
        assertEquals(AfterburnState.BOOSTING, d.state)
        assertEquals(1f, d.level, 0.001f)
    }

    @Test fun blockedWhenMarginNotMet() {
        val d = decide(temp = 40f) // margin 2 < 5
        assertEquals(AfterburnState.BLOCKED_THERMAL, d.state)
        assertFalse(d.active)
    }

    @Test fun thermalHysteresisKeepsBoostJustBelowMargin() {
        assertFalse(decide(temp = 37.5f, was = false).active) // margin 4.5 < 5
        assertTrue(decide(temp = 37.5f, was = true).active)   // needs only 3.5
    }

    @Test fun lowBatteryReducesBoost() {
        val d = decide(batt = 30)
        assertEquals(AfterburnState.REDUCED, d.state)
        assertTrue(d.level < 1f && d.level > 0f)
    }

    @Test fun lowBatteryNeedsMoreThermalMargin() {
        // margin 6: enough at 80% battery, not enough at 30% (needs 5 + 2 = 7)
        assertTrue(decide(temp = 36f, batt = 80).active)
        assertEquals(AfterburnState.BLOCKED_THERMAL, decide(temp = 36f, batt = 30).state)
    }

    @Test fun veryLowBatteryBlocks() {
        assertEquals(AfterburnState.BLOCKED_BATTERY, decide(batt = 5).state)
    }

    @Test fun chargingIgnoresBatteryCondition() {
        assertEquals(AfterburnState.BOOSTING, decide(batt = 5, charging = true).state)
    }

    @Test fun conditionsAreOptional() {
        val noThermal = cfg.copy(thermalConditionEnabled = false)
        assertTrue(decide(c = noThermal, temp = 41f).active)
        val noBattery = cfg.copy(batteryConditionEnabled = false)
        assertEquals(AfterburnState.BOOSTING, decide(c = noBattery, batt = 5).state)
    }

    @Test fun relieveCapMovesTowardHundred() {
        assertEquals(100f, AfterburnLogic.relieveCap(60f, 1f), 0.001f)
        assertEquals(80f, AfterburnLogic.relieveCap(60f, 0.5f), 0.001f)
        assertEquals(60f, AfterburnLogic.relieveCap(60f, 0f), 0.001f)
    }
}

class BatteryTruthTest {
    @Test fun highVoltageCurveReadsLowerPercentThanStandard() {
        val hv = com.example.governor.BatteryTruthMath.socFromOcv(4.0f, true)
        val std = com.example.governor.BatteryTruthMath.socFromOcv(4.0f, false)
        assertTrue(hv < std)
    }

    @Test fun veryHighRestingVoltageLatchesHighVoltage() {
        val m = com.example.governor.BatteryTruthModel()
        var st = com.example.governor.BatteryTruthState()
        for (i in 0 until 600) st = m.update(i * 150L, 4340f, 300f, 95, true)
        assertEquals(com.example.governor.BatteryChemistry.HIGH_VOLTAGE, st.chemistry)
        assertTrue(st.valid)
    }

    @Test fun sagIsAddedBackAndCappedNearGauge() {
        val m = com.example.governor.BatteryTruthModel()
        var st = com.example.governor.BatteryTruthState()
        for (i in 0 until 600) st = m.update(i * 150L, 3900f, 1500f, 55, true)
        assertTrue(st.sagMv > 100f)
        assertTrue(st.truePct in 35f..75f)
    }

    @Test fun chargingIsNotGraded() {
        val m = com.example.governor.BatteryTruthModel()
        val st = m.update(0L, 4100f, -2000f, 80, false)
        assertFalse(st.valid)
    }
}

class BatteryCalibrationTest {
    @Test fun fullChargeTaperSetsChemistryAndSerializes() {
        val m = com.example.governor.BatteryTruthModel()
        var t = 0L
        var st = com.example.governor.BatteryTruthState()
        for (i in 0 until 1500) { t += 100L; st = m.update(t, 4360f, -2000f, 60, false, true) }
        for (i in 0 until 800) { t += 100L; st = m.update(t, 4380f, -50f, 100, false, true) }
        assertTrue(st.calChargeDone)
        val saved = m.takeDirtySerialized()!!
        val m2 = com.example.governor.BatteryTruthModel()
        m2.load(saved)
        val st2 = m2.update(0L, 4000f, 300f, 70, true)
        assertEquals(com.example.governor.BatteryChemistry.HIGH_VOLTAGE, st2.chemistry)
    }

    @Test fun plugInDiscardsDischargeRun() {
        val m = com.example.governor.BatteryTruthModel()
        var t = 0L
        for (i in 0 until 100) { t += 100L; m.update(t, 4300f, 300f, 97, true, false) }
        m.update(t + 100L, 4300f, -1000f, 90, false, true)
        assertEquals(null, m.takeDirtySerialized())
    }
}
