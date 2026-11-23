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
import com.example.model.overlayPosPm
import com.example.model.withOverlayPosPm
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test fun portraitAndLandscapeAreIndependent() {
        val base = GovernorConfig()
        val c = base.withOverlayPosPm(false, 100, 200).withOverlayPosPm(true, 900, 0)
        assertEquals(100 to 200, c.overlayPosPm(false))
        assertEquals(900 to 0, c.overlayPosPm(true))
    }

    @Test fun eachStyleKeepsItsOwnSlot() {
        val compact = GovernorConfig().withOverlayPosPm(true, 10, 20)
        val detailed = compact.copy(overlayDetailed = true).withOverlayPosPm(true, 700, 800)
        assertEquals(10 to 20, detailed.copy(overlayDetailed = false).overlayPosPm(true))
        assertEquals(700 to 800, detailed.overlayPosPm(true))
    }

    @Test fun nullKeepsOldAxisAndValuesAreClamped() {
        val c = GovernorConfig().withOverlayPosPm(false, 300, 400).withOverlayPosPm(false, null, 5000)
        assertEquals(300 to 1000, c.overlayPosPm(false))
    }
}
