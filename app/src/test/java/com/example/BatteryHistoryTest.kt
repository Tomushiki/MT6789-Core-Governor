package com.example

import com.example.governor.BatteryHistoryMath
import com.example.governor.BatteryHistoryPoint
import com.example.governor.BatteryRange
import com.example.model.BatteryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHistoryTest {
    private val min = 60_000L

    private fun pt(tMin: Long, pct: Int, st: BatteryState, w: Float = 0f, screen: Boolean = false, temp: Float = 30f) =
        BatteryHistoryPoint(tMin * min, pct, st, w, temp, 3900f, screen)

    @Test fun recordsOnlyEveryMinuteUnlessLevelChanges() {
        val a = pt(0, 50, BatteryState.DISCHARGING)
        assertTrue(BatteryHistoryMath.shouldRecord(null, a))
        assertFalse(BatteryHistoryMath.shouldRecord(a, a.copy(timeMs = a.timeMs + 20_000L)))
        assertTrue(BatteryHistoryMath.shouldRecord(a, a.copy(timeMs = a.timeMs + 60_000L)))
        assertTrue(BatteryHistoryMath.shouldRecord(a, a.copy(timeMs = a.timeMs + 6_000L, pct = 49)))
        assertFalse(BatteryHistoryMath.shouldRecord(a, a.copy(timeMs = a.timeMs + 1_000L, pct = 49)))
    }

    @Test fun rejectsBadLevelAndBackwardsTime() {
        val a = pt(10, 50, BatteryState.DISCHARGING)
        assertFalse(BatteryHistoryMath.shouldRecord(a, pt(11, -1, BatteryState.DISCHARGING)))
        assertFalse(BatteryHistoryMath.shouldRecord(a, pt(5, 50, BatteryState.DISCHARGING)))
    }

    @Test fun pruneDropsOldPoints() {
        val now = BatteryHistoryMath.MAX_AGE_MS + 10 * min
        val kept = BatteryHistoryMath.prune(listOf(pt(0, 50, BatteryState.DISCHARGING), BatteryHistoryPoint(now - min, 40, BatteryState.DISCHARGING, 0f, 30f, 3900f, false)), now)
        assertEquals(1, kept.size)
    }

    @Test fun windowKeepsOnlyRange() {
        val pts = (0..10).map { pt(it * 60L, 90 - it, BatteryState.DISCHARGING) }
        val now = 10 * 60 * min
        assertEquals(7, BatteryHistoryMath.window(pts, now, BatteryRange.H6).size)
    }

    @Test fun thinKeepsLastPointAndLimitsSize() {
        val pts = (0 until 1000).map { pt(it.toLong(), 50, BatteryState.DISCHARGING) }
        val t = BatteryHistoryMath.thin(pts, 100)
        assertTrue(t.size in 100..102)
        assertEquals(pts.last(), t.last())
        assertEquals(pts, BatteryHistoryMath.thin(pts, 2000))
    }

    @Test fun sessionsSplitChargeAndUse() {
        val pts = listOf(
            pt(0, 52, BatteryState.DISCHARGING, -1.5f, true),
            pt(10, 50, BatteryState.DISCHARGING, -1.5f, true),
            pt(20, 47, BatteryState.DISCHARGING, -1.5f, false),
            pt(21, 47, BatteryState.CHARGING, 10f),
            pt(30, 50, BatteryState.CHARGING, 10f),
            pt(40, 52, BatteryState.CHARGING, 10f)
        )
        val s = BatteryHistoryMath.sessions(pts)
        assertEquals(2, s.size)
        assertEquals(BatteryState.DISCHARGING, s[0].state)
        assertEquals(52, s[0].startPct)
        assertEquals(-5, s[0].deltaPct)
        assertTrue(s[0].screenOnShare > 0.4f && s[0].screenOnShare < 0.6f)
        assertEquals(BatteryState.CHARGING, s[1].state)
        assertEquals(5, s[1].deltaPct)
        assertTrue(s[1].avgWatts > 9f)
    }

    @Test fun longSilenceStartsNewSession() {
        val pts = listOf(
            pt(0, 80, BatteryState.DISCHARGING), pt(30, 78, BatteryState.DISCHARGING),
            pt(300, 60, BatteryState.DISCHARGING), pt(330, 58, BatteryState.DISCHARGING)
        )
        assertEquals(2, BatteryHistoryMath.sessions(pts).size)
    }

    @Test fun tinyBlipIsDropped() {
        val pts = listOf(pt(0, 50, BatteryState.DISCHARGING), pt(1, 50, BatteryState.DISCHARGING))
        assertTrue(BatteryHistoryMath.sessions(pts).isEmpty())
    }

    @Test fun summaryComputesDrainPerHourSplitByScreen() {
        // 2h on battery: 1h screen-on losing 10%, 1h screen-off losing 2%
        val pts = ArrayList<BatteryHistoryPoint>()
        var pct = 80.0
        for (i in 0..120) {
            val on = i < 60
            pts.add(pt(i.toLong(), Math.round(pct).toInt(), BatteryState.DISCHARGING, -2f, on))
            pct -= if (on) 10.0 / 60 else 2.0 / 60
        }
        val s = BatteryHistoryMath.summary(pts)
        assertEquals(6.0f, s.drainPctPerHour, 0.6f)
        assertEquals(10.0f, s.screenOnDrainPctPerHour, 1.0f)
        assertEquals(2.0f, s.screenOffDrainPctPerHour, 1.0f)
        assertEquals(2.0f, s.avgDrawWatts, 0.05f)
    }

    @Test fun summaryNotEnoughDataGivesMinusOne() {
        val s = BatteryHistoryMath.summary(listOf(pt(0, 50, BatteryState.DISCHARGING), pt(2, 49, BatteryState.DISCHARGING)))
        assertEquals(-1f, s.drainPctPerHour, 0f)
    }

    @Test fun encodeDecodeRoundTrip() {
        val p = pt(123, 77, BatteryState.CHARGING, 11.7f, true, 36.5f)
        val d = BatteryHistoryMath.decode(BatteryHistoryMath.encode(p))
        assertNotNull(d)
        assertEquals(p.timeMs, d!!.timeMs)
        assertEquals(77, d.pct)
        assertEquals(BatteryState.CHARGING, d.state)
        assertEquals(11.7f, d.watts, 0.01f)
        assertTrue(d.screenOn)
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(BatteryHistoryMath.decode("hello"))
        assertNull(BatteryHistoryMath.decode("1,2,99,0,0,0,0"))
        assertNull(BatteryHistoryMath.decode("a,b,c,d,e,f,g"))
    }
}
