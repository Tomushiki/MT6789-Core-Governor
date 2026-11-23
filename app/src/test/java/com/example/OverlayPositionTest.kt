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
import com.example.service.OverlaySizing
import com.example.service.OverlayStyle
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

    @Test fun maxScalePctUsesStyleFractionAndClamps50To200() {
        // Compact allows 60% width x 35% height; 1000x2000 -> 600x700 allowed.
        // Natural 300x100 -> width ratio 2.0 (200%), height ratio 7.0 -> 200.
        assertEquals(200, OverlaySizing.maxScalePct(1000, 2000, OverlayStyle.COMPACT, 300f, 100f))

        // Huge screen still caps at 200%.
        assertEquals(200, OverlaySizing.maxScalePct(4000, 3000, OverlayStyle.COMPACT, 200f, 50f))

        // Portrait phone compact pill constrained by 60% width: 1080 * 0.60 = 648; 648 / 750 = 86.4 -> 86%.
        assertEquals(86, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.COMPACT, 750f, 90f))

        // Expanded/Detailed allow 45% width x 35% height: 1080 * 0.45 = 486; 486 / 600 = 81%.
        assertEquals(81, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.EXPANDED, 600f, 200f))

        // Tight landscape height floors at 50%: 1080 * 0.35 = 378; 378 / 932 = 40.5 -> clamped to 50.
        assertEquals(50, OverlaySizing.maxScalePct(2400, 1080, OverlayStyle.DETAILED, 500f, 932f))
    }

    @Test fun windowOriginStaysFlushOnScreenBounds() {
        assertEquals(0 to 0, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 0, 0))
        assertEquals(780 to 2200, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 1000, 1000))
        assertEquals(0 to 0, OverlaySizing.computeWindowOriginPx(500, 500, 700, 700, 1000, 1000))
        assertEquals(780 to 2200, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 1500, 1200))
        assertEquals(390 to 1100, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 500, 500))
    }
}

