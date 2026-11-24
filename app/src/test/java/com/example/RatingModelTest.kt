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

import com.example.governor.RatingAgg
import com.example.governor.RatingModel
import com.example.governor.Standby
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingModelTest {
    private fun feed(a: RatingAgg, n: Int, fps: Float, w: Float, t: Float, load: Float, game: Boolean, charging: Boolean = false) {
        repeat(n) { a.add(fps, w, t, load, game, charging) }
    }

    @Test
    fun notEnoughDataGivesNoScore() {
        val a = RatingAgg()
        feed(a, 600, 60f, 3f, 38f, 20f, true)
        val r = RatingModel.compute(a, null)
        assertNull(r.score)
    }

    @Test
    fun chargingSamplesAreIgnored() {
        val a = RatingAgg()
        feed(a, 5000, 60f, 3f, 38f, 20f, true, charging = true)
        assertEquals(0L, a.seconds)
    }

    @Test
    fun goodPhoneBeatsBadPhone() {
        val good = RatingAgg()
        feed(good, 1500, 60f, 2.5f, 34f, 5f, true)
        feed(good, 1500, -1f, 1.2f, 33f, 4f, false)
        val bad = RatingAgg()
        feed(bad, 1500, 25f, 6f, 45f, 28f, true)
        feed(bad, 1500, -1f, 5f, 44f, 30f, false)
        val g = RatingModel.compute(good, Standby(0.4f, 90)).score
        val b = RatingModel.compute(bad, Standby(2.4f, 10)).score
        assertNotNull(g)
        assertNotNull(b)
        assertTrue(g!! > 80)
        assertTrue(b!! < 25)
    }

    @Test
    fun p5FpsReadsHistogram() {
        val a = RatingAgg()
        feed(a, 95, 60f, 3f, 38f, 20f, true)
        feed(a, 5, 20f, 3f, 38f, 20f, true)
        assertEquals(20f, a.p5Fps()!!, 0.001f)
    }
}
