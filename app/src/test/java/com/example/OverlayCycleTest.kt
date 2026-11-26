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

import com.example.model.GovernorConfig
import com.example.service.OverlayCycle
import com.example.service.OverlayCycle.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCycleTest {

    @Test
    fun offConfigReadsAsOff() {
        assertEquals(Step.OFF, OverlayCycle.current(GovernorConfig(overlayEnabled = false)))
    }

    @Test
    fun stylesAreReadFromTheFlags() {
        val on = GovernorConfig(overlayEnabled = true)
        assertEquals(Step.COMPACT, OverlayCycle.current(on))
        assertEquals(Step.EXPANDED, OverlayCycle.current(on.copy(overlayExpanded = true)))
        assertEquals(Step.DETAILED, OverlayCycle.current(on.copy(overlayDetailed = true)))
        // Detailed wins if both flags were ever set.
        assertEquals(Step.DETAILED, OverlayCycle.current(on.copy(overlayExpanded = true, overlayDetailed = true)))
    }

    @Test
    fun fourTapsGoAroundAndBackToOff() {
        var cfg = GovernorConfig(overlayEnabled = false)
        val seen = mutableListOf<Step>()
        repeat(4) {
            cfg = OverlayCycle.advance(cfg)
            seen += OverlayCycle.current(cfg)
        }
        assertEquals(listOf(Step.COMPACT, Step.EXPANDED, Step.DETAILED, Step.OFF), seen)
    }

    @Test
    fun applyingAStepSetsExactlyOneStyle() {
        val base = GovernorConfig(overlayEnabled = false, overlayExpanded = true, overlayDetailed = true)
        val compact = OverlayCycle.apply(base, Step.COMPACT)
        assertTrue(compact.overlayEnabled)
        assertFalse(compact.overlayExpanded)
        assertFalse(compact.overlayDetailed)
        val expanded = OverlayCycle.apply(base, Step.EXPANDED)
        assertTrue(expanded.overlayExpanded)
        assertFalse(expanded.overlayDetailed)
        val detailed = OverlayCycle.apply(base, Step.DETAILED)
        assertTrue(detailed.overlayDetailed)
        assertFalse(detailed.overlayExpanded)
    }

    @Test
    fun offKeepsEveryOtherSetting() {
        val cfg = GovernorConfig(overlayEnabled = true, overlayDetailed = true, overlayOpacityPct = 70)
        val off = OverlayCycle.apply(cfg, Step.OFF)
        assertFalse(off.overlayEnabled)
        assertEquals(70, off.overlayOpacityPct)
        assertTrue(off.overlayDetailed)
    }
}
