package com.example

import com.example.governor.BatteryChemistry
import com.example.governor.BatteryTruthModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTruthChargingTest {
    private fun model(): BatteryTruthModel {
        val m = BatteryTruthModel()
        m.load("v1;4200.0;0.0;false;false;" + List(21) { "0.0" }.joinToString(",") + ";0.0;false")
        return m
    }

    @Test
    fun gradesWhileChargingAfterSettling() {
        val m = model()
        var s = m.update(0L, 3900f, -2000f, 60, discharging = false, plugged = true)
        assertFalse(s.valid) // not settled yet
        for (i in 1..80) s = m.update(i * 1000L, 3900f, -2000f, 60, discharging = false, plugged = true)
        assertTrue(s.valid)
        assertTrue(s.charging)
        assertEquals(BatteryChemistry.STANDARD, s.chemistry)
        assertTrue(s.truePct in 50f..70f) // stays within 10 points of the gauge
        assertTrue(s.confidencePct <= 70)
    }

    @Test
    fun doesNotGradeInTheTaperOrAtTrickleCurrent() {
        val m = model()
        var s = m.update(0L, 4180f, -2000f, 95, discharging = false, plugged = true)
        for (i in 1..80) s = m.update(i * 1000L, 4180f, -2000f, 95, discharging = false, plugged = true)
        assertFalse(s.valid)
        val m2 = model()
        for (i in 0..80) s = m2.update(i * 1000L, 3900f, -50f, 60, discharging = false, plugged = true)
        assertFalse(s.valid)
    }
}
