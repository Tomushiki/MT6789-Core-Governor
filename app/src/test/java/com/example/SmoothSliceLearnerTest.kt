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

import com.example.governor.SliceLearner
import com.example.governor.SmoothSlice
import com.example.governor.SmoothSlice.Kind
import com.example.governor.SmoothSlice.Sample
import com.example.governor.SmoothSlice.Stats
import com.example.governor.WriteLatencyTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothSliceLearnerTest {

    private fun stats(
        watts: Float = 2.0f,
        over: Float = 0.30f,
        cv: Float = 0.08f,
        drift: Float = 1f,
        p90: Float = 6f,
        backed: Boolean = false
    ) = Stats(100, watts, over, cv, drift, p90, backed)

    // ---- ladder, floor, range -------------------------------------------------------------------------

    @Test fun hardRangeIsEnforced() {
        assertEquals(30, SmoothSlice.clampMs(5))
        assertEquals(400, SmoothSlice.clampMs(9000))
        assertEquals(50, SmoothSlice.clampMs(50))
        assertTrue(SmoothSlice.LADDER.all { it in SmoothSlice.HARD_MIN_MS..SmoothSlice.HARD_MAX_MS })
        assertEquals(50, SmoothSlice.DEFAULT_MS)
    }

    @Test fun nextShorterWalksTheLadderAndStopsAtTheFloor() {
        assertEquals(200, SmoothSlice.nextShorter(300, 30))
        assertEquals(100, SmoothSlice.nextShorter(150, 30))
        assertEquals(40, SmoothSlice.nextShorter(50, 30))
        assertEquals(30, SmoothSlice.nextShorter(40, 30))
        assertNull(SmoothSlice.nextShorter(30, 30))
        assertNull(SmoothSlice.nextShorter(50, 50))
        assertEquals(50, SmoothSlice.nextShorter(70, 50))
        // an off-ladder starting value still steps down to the next ladder value below it
        assertEquals(50, SmoothSlice.nextShorter(60, 30))
        // the floor can never be below the hard minimum
        assertNull(SmoothSlice.nextShorter(30, 1))
    }

    @Test fun writeLatencySetsTheFloor() {
        assertEquals(30, SmoothSlice.floorFor(0f))
        assertEquals(30, SmoothSlice.floorFor(5f))     // 15 ms needed -> hard minimum
        assertEquals(40, SmoothSlice.floorFor(12f))    // 36 -> 40
        assertEquals(70, SmoothSlice.floorFor(21f))    // 63 -> 70
        assertEquals(200, SmoothSlice.floorFor(60f))   // 180 -> 200
        assertEquals(400, SmoothSlice.floorFor(500f))  // never above the hard maximum
    }

    @Test fun encodeDecodeRoundTripAndGarbage() {
        val m = mapOf("cpu_3" to 50, "cpu_0" to 40)
        assertEquals("cpu_0:40,cpu_3:50", SmoothSlice.encode(m))
        assertEquals(m, SmoothSlice.decode(SmoothSlice.encode(m)))
        assertEquals(mapOf("cpu_6" to 30, "cpu_0" to 400), SmoothSlice.decode("cpu_6:1,junk,:5,cpu_0:9999,cpu_x:abc"))
        assertTrue(SmoothSlice.decode("").isEmpty())
    }

    // ---- decide() -------------------------------------------------------------------------------------

    @Test fun smallerOvershootIsBetter() {
        val v = SmoothSlice.decide(stats(), stats(over = 0.15f), 40)
        assertEquals(Kind.BETTER, v.kind)
    }

    @Test fun steadierFpsIsBetter() {
        val v = SmoothSlice.decide(stats(), stats(cv = 0.05f), 40)
        assertEquals(Kind.BETTER, v.kind)
    }

    @Test fun noClearChangeIsNeutral() {
        val v = SmoothSlice.decide(stats(), stats(over = 0.29f, cv = 0.079f), 40)
        assertEquals(Kind.NEUTRAL, v.kind)
    }

    @Test fun anyMetricGettingWorseWins_evenIfAnotherImproved() {
        // overshoot much better, but more drift
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(drift = 1f), stats(over = 0.1f, drift = 9f), 40).kind)
        // overshoot much better, but FPS less steady
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(), stats(over = 0.1f, cv = 0.14f), 40).kind)
        // FPS better, but overshoot larger
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(), stats(cv = 0.04f, over = 0.5f), 40).kind)
        // writes got much slower
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(p90 = 6f), stats(over = 0.1f, p90 = 13f), 70).kind)
    }

    @Test fun backoffOrSlowWritesAreAlwaysWorse() {
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(), stats(over = 0.0f, backed = true), 40).kind)
        // p90 20 ms x 3 = 60 ms > 50 ms slice
        assertEquals(Kind.WORSE, SmoothSlice.decide(stats(p90 = 20f), stats(over = 0.0f, p90 = 20f), 50).kind)
    }

    @Test fun differentLoadIsInconclusiveNotWorse() {
        val v = SmoothSlice.decide(stats(watts = 2.0f), stats(watts = 3.2f, over = 0.9f), 40)
        assertEquals(Kind.INCONCLUSIVE, v.kind)
    }

    @Test fun missingFpsDataDoesNotBlockOrHelp() {
        assertEquals(Kind.NEUTRAL, SmoothSlice.decide(stats(cv = -1f), stats(cv = 0.5f), 40).kind)
        assertEquals(Kind.BETTER, SmoothSlice.decide(stats(cv = -1f), stats(cv = -1f, over = 0.1f), 40).kind)
    }

    @Test fun windowStatsMeasureOvershootCvAndDrift() {
        val acc = SmoothSlice.WindowAcc()
        var t = 0L
        for (i in 0 until 120) {
            val fps = if (i % 2 == 0) 50f else 60f
            acc.add(t, Sample(watts = if (i % 2 == 0) 2.4f else 1.8f, limitWatts = 2.0f, fps = fps, driftTotal = 10 + i / 20, writeP90Ms = 8f, backedOff = false))
            t += 500L
        }
        val s = acc.stats()
        assertEquals(120, s.ticks)
        assertEquals(2.1f, s.meanWatts, 0.01f)
        assertEquals(0.2f, s.overshootW, 0.01f)        // half the ticks are 0.4 W over
        assertEquals(0.0909f, s.fpsCv, 0.005f)         // std 5 / mean 55
        assertEquals(5f / (59_500f / 60_000f), s.driftPerMin, 0.1f)
        assertEquals(8f, s.writeP90Ms, 0.01f)
        assertFalse(s.backedOff)
    }

    @Test fun fewFpsSamplesGiveUnknownCv() {
        val acc = SmoothSlice.WindowAcc()
        for (i in 0 until 10) acc.add(i * 500L, Sample(2f, 2f, 60f, 0, 0f, false))
        assertTrue(acc.stats().fpsCv < 0f)
    }

    // ---- write latency tracker ------------------------------------------------------------------------

    @Test fun latencyTrackerPercentilesAndRing() {
        val t = WriteLatencyTracker(capacity = 10)
        assertNull(t.summary())
        assertEquals(0f, t.p90(), 0f)
        for (i in 1..10) t.add(i.toFloat())
        val s = t.summary()!!
        assertEquals(10, s.count)
        assertEquals(5.5f, s.avgMs, 0.01f)
        assertEquals(10f, s.maxMs, 0f)
        assertEquals(9f, s.p90Ms, 0f)
        // ring: new values replace the oldest ones
        for (i in 1..10) t.add(100f)
        assertEquals(100f, t.summary()!!.avgMs, 0.01f)
        assertEquals(20L, t.totalCount())
        t.add(-1f) // ignored
        assertEquals(20L, t.totalCount())
    }

    // ---- state machine --------------------------------------------------------------------------------

    private class Driver(val learner: SliceLearner) {
        var t = 1_000_000L
        val changes = ArrayList<SliceLearner.Change>()

        /** Feeds eligible ticks (500 ms apart) with the sample made by [make] for the current slice until a change comes. */
        fun runUntilChange(maxMs: Long = 400_000L, eligible: Boolean = true, make: (Int) -> Sample): SliceLearner.Change? {
            val end = t + maxMs
            while (t < end) {
                val id = learner.currentId ?: return null
                val slice = learner.sliceFor(id)!!
                val ch = learner.onTick(t, eligible, make(slice))
                t += 500L
                if (ch != null) { changes.add(ch); return ch }
            }
            return null
        }
    }

    private fun sample(over: Float, cv: Float = 0.08f, p90: Float = 6f, drift: Int = 0, backed: Boolean = false, watts: Float = 2.0f) =
        Sample(watts = watts + over, limitWatts = watts, fps = 60f * (1f + cv), driftTotal = drift, writeP90Ms = p90, backedOff = backed)

    private fun learner(vararg ids: String, start: Int = 300): SliceLearner =
        SliceLearner().also { it.configure(ids.toList(), start, emptyMap()) }

    @Test fun nothingHappensBeforeWarmupOrWhenNotEligible() {
        val l = learner("cpu_0")
        val d = Driver(l)
        assertNull(d.runUntilChange(maxMs = SmoothSlice.WARMUP_MS - 1_000L) { sample(0.3f) })
        // not eligible: nothing ever changes
        val l2 = learner("cpu_0")
        assertNull(Driver(l2).runUntilChange(maxMs = 600_000L, eligible = false) { sample(0.3f) })
        assertEquals(300, l2.sliceFor("cpu_0"))
    }

    @Test fun walksDownWhileBetterAndKeepsTheLastGood() {
        val l = learner("cpu_0", start = 300)
        val d = Driver(l)
        // overshoot shrinks with every shorter slice until 100 ms, then gets worse at 70 ms
        val make = { slice: Int ->
            val over = when {
                slice >= 300 -> 0.40f
                slice >= 200 -> 0.30f
                slice >= 150 -> 0.22f
                slice >= 100 -> 0.15f
                else -> 0.60f
            }
            sample(over)
        }
        val seen = ArrayList<SliceLearner.Change>()
        while (l.currentId != null) {
            val ch = d.runUntilChange(make = make) ?: break
            seen.add(ch)
        }
        assertTrue(l.allSettled)
        assertEquals(100, l.learnedMap()["cpu_0"])
        assertEquals(100, l.sliceFor("cpu_0"))
        val last = seen.last()
        assertEquals(SliceLearner.ChangeKind.SETTLED, last.kind)
        assertEquals(70, last.fromMs)   // the slice that was rejected
        assertEquals(100, last.toMs)    // the one that was kept
        // the trial path was 300 -> 200 -> 150 -> 100 -> 70
        val trials = seen.filter { it.kind == SliceLearner.ChangeKind.TRIAL || it.kind == SliceLearner.ChangeKind.KEPT_TRYING }.map { it.toMs }
        assertEquals(listOf(200, 150, 100, 70), trials)
    }

    @Test fun neutralResultKeepsThePreviousSlice() {
        val l = learner("cpu_0", start = 100)
        val d = Driver(l)
        val first = d.runUntilChange { sample(0.3f) }!!
        assertEquals(SliceLearner.ChangeKind.TRIAL, first.kind)
        assertEquals(70, first.toMs)
        val second = d.runUntilChange { sample(0.3f) }!!
        assertEquals(SliceLearner.ChangeKind.SETTLED, second.kind)
        assertEquals(100, l.sliceFor("cpu_0"))
        assertEquals(100, l.learnedMap()["cpu_0"])
    }

    @Test fun worseDriftRevertsToTheLastGoodSlice() {
        val l = learner("cpu_0", start = 100)
        val d = Driver(l)
        d.runUntilChange { sample(0.3f, drift = 0) }          // baseline -> trial 70
        var drift = 0
        val ch = d.runUntilChange { drift += 3; sample(0.1f, drift = drift) }!!  // fewer overshoot but many re-asserts
        assertEquals(SliceLearner.ChangeKind.SETTLED, ch.kind)
        assertEquals(100, l.sliceFor("cpu_0"))
        assertTrue(ch.text.contains("CpuDrift"))
    }

    @Test fun slowWritesStopTheLearnerAtTheLatencyFloor() {
        // p90 write 25 ms -> floor 75 -> 100 ms (ladder). Starting at 100, nothing shorter is allowed.
        val l = learner("cpu_0", start = 100)
        val d = Driver(l)
        val ch = d.runUntilChange { sample(0.3f, p90 = 25f) }!!
        assertEquals(SliceLearner.ChangeKind.SETTLED, ch.kind)
        assertEquals(100, l.sliceFor("cpu_0"))
    }

    @Test fun neverGoesBelowTheHardMinimum() {
        val l = learner("cpu_0", start = 50)
        val d = Driver(l)
        // everything keeps getting better as the slice shrinks
        val make = { slice: Int -> sample(over = slice / 200f) }
        var guard = 0
        while (l.currentId != null && guard++ < 20) d.runUntilChange(make = make) ?: break
        assertTrue(l.allSettled)
        assertTrue(l.learnedMap()["cpu_0"]!! >= SmoothSlice.HARD_MIN_MS)
        assertEquals(30, l.learnedMap()["cpu_0"])
        assertTrue(d.changes.all { it.toMs >= SmoothSlice.HARD_MIN_MS && it.fromMs >= SmoothSlice.HARD_MIN_MS })
    }

    @Test fun interruptedTrialGoesBackToTheLastGoodSlice() {
        val l = learner("cpu_0", start = 100)
        val d = Driver(l)
        d.runUntilChange { sample(0.3f) }   // trial of 70 starts
        assertEquals(70, l.sliceFor("cpu_0"))
        val ch = l.onTick(d.t, false, sample(0.3f))
        assertNotNull(ch)
        assertEquals(SliceLearner.ChangeKind.INTERRUPTED, ch!!.kind)
        assertEquals(100, l.sliceFor("cpu_0"))
        assertFalse(l.allSettled)
    }

    @Test fun clustersAreLearnedOneAtATime() {
        val l = learner("cpu_0", "cpu_3", "cpu_6", start = 100)
        assertEquals("cpu_0", l.currentId)
        val d = Driver(l)
        // neutral everywhere: each cluster does baseline + one trial, then the next one starts
        d.runUntilChange { sample(0.3f) }
        // only the cluster in trial has a different slice
        assertEquals(70, l.sliceFor("cpu_0"))
        assertEquals(100, l.sliceFor("cpu_3"))
        assertEquals(100, l.sliceFor("cpu_6"))
        d.runUntilChange { sample(0.3f) }
        assertEquals("cpu_3", l.currentId)
        d.runUntilChange { sample(0.3f) }
        d.runUntilChange { sample(0.3f) }
        assertEquals("cpu_6", l.currentId)
        d.runUntilChange { sample(0.3f) }
        d.runUntilChange { sample(0.3f) }
        assertTrue(l.allSettled)
        assertEquals(setOf("cpu_0", "cpu_3", "cpu_6"), l.learnedMap().keys)
    }

    @Test fun changedLoadIsMeasuredAgainAndGivesUpAfterTheRetries() {
        val l = learner("cpu_0", start = 100)
        val d = Driver(l)
        var calls = 0
        // baseline window at 2 W, every candidate window at 3.5 W (load changed)
        val make = { slice: Int -> if (slice == 100) sample(0.3f, watts = 2.0f) else sample(0.3f, watts = 3.5f) }
        var last: SliceLearner.Change? = null
        while (l.currentId != null && calls++ < 30) last = d.runUntilChange(make = make) ?: break
        assertTrue(l.allSettled)
        assertEquals(100, l.sliceFor("cpu_0"))
        assertEquals(SliceLearner.ChangeKind.SETTLED, last!!.kind)
        assertTrue(d.changes.count { it.kind == SliceLearner.ChangeKind.REVERTED } == SmoothSlice.MAX_RETRIES)
    }

    @Test fun learnedClustersStartSettledAndStayPut() {
        val l = SliceLearner()
        l.configure(listOf("cpu_0", "cpu_3"), 50, mapOf("cpu_0" to 40))
        assertEquals(40, l.sliceFor("cpu_0"))
        assertEquals("cpu_3", l.currentId)
        assertEquals(mapOf("cpu_0" to 40), l.learnedMap())
        // changing the start value moves unsettled clusters only
        l.configure(listOf("cpu_0", "cpu_3"), 100, mapOf("cpu_0" to 40))
        assertEquals(40, l.sliceFor("cpu_0"))
        assertEquals(100, l.sliceFor("cpu_3"))
    }

    @Test fun resetForgetsEverything() {
        val l = SliceLearner()
        l.configure(listOf("cpu_0"), 50, mapOf("cpu_0" to 40))
        l.reset()
        assertNull(l.sliceFor("cpu_0"))
        assertNull(l.currentId)
        assertTrue(l.learnedMap().isEmpty())
    }

    // ---- v4.1.0 drift fix rule ------------------------------------------------------------------------

    @Test fun driftFixOnlyWhenEnabledMismatchNoThermalCapAndRateLimitPassed() {
        val base = 10_000L
        assertTrue(SmoothSlice.shouldFixDrift(true, false, true, base, 0L))
        assertFalse(SmoothSlice.shouldFixDrift(false, false, true, base, 0L))      // option off
        assertFalse(SmoothSlice.shouldFixDrift(true, false, false, base, 0L))      // nothing overridden
        assertFalse(SmoothSlice.shouldFixDrift(true, true, true, base, 0L))        // thermal cap wins
        assertFalse(SmoothSlice.shouldFixDrift(true, false, true, base, base - 399L)) // too soon after the last fix
        assertTrue(SmoothSlice.shouldFixDrift(true, false, true, base, base - 400L))
    }
}
