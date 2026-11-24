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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigStorage
import com.example.model.GovernorConfig
import com.example.model.withOverlayPositionsReset
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.example.model.overlayCompactScaleFor
import com.example.model.overlayExpandedScaleFor
import com.example.model.overlayPosPm
import com.example.model.overlayScalePct
import com.example.model.withOverlayPosPm
import com.example.model.withOverlayScalePct
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

    @Test fun maxScalePctUsesStyleFractionAndClamps25To200() {
        // Compact allows 60% width x 35% height; 1000x2000 -> 600x700 allowed.
        // Natural 300x100 -> width ratio 2.0 (200%), height ratio 7.0 -> 200.
        assertEquals(200, OverlaySizing.maxScalePct(1000, 2000, OverlayStyle.COMPACT, 300f, 100f))

        // Huge screen still caps at 200%.
        assertEquals(200, OverlaySizing.maxScalePct(4000, 3000, OverlayStyle.COMPACT, 200f, 50f))

        // Portrait phone compact pill constrained by 60% width: 1080 * 0.60 = 648; 648 / 750 = 86.4 -> 86%.
        assertEquals(86, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.COMPACT, 750f, 90f))

        // Expanded/Detailed allow 45% width x 35% height: 1080 * 0.45 = 486; 486 / 600 = 81%.
        assertEquals(81, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.EXPANDED, 600f, 200f))

        // Tight landscape height: 1080 * 0.35 = 378; 378 / 932 = 40.5 -> 40 (the floor is now 25%).
        assertEquals(40, OverlaySizing.maxScalePct(2400, 1080, OverlayStyle.DETAILED, 500f, 932f))
        assertEquals(25, OverlaySizing.maxScalePct(2400, 1080, OverlayStyle.DETAILED, 500f, 3000f))
    }

    @Test fun windowOriginStaysFlushOnScreenBounds() {
        assertEquals(0 to 0, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 0, 0))
        assertEquals(780 to 2200, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 1000, 1000))
        assertEquals(0 to 0, OverlaySizing.computeWindowOriginPx(500, 500, 700, 700, 1000, 1000))
        assertEquals(780 to 2200, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 1500, 1200))
        assertEquals(390 to 1100, OverlaySizing.computeWindowOriginPx(1080, 2400, 300, 200, 500, 500))
    }

    @Test fun landscapeScaleFallsBackToPortraitSentinelAndSavesIndependently() {
        val def = GovernorConfig(overlayCompactScalePct = 115, overlayExpandedScalePct = 90)
        assertEquals(-1, def.overlayCompactScalePctL)
        assertEquals(-1, def.overlayExpandedScalePctL)
        // Sentinel -1 falls back to portrait scale in landscape
        assertEquals(115, def.overlayCompactScaleFor(landscape = true))
        assertEquals(90, def.overlayExpandedScaleFor(landscape = true))

        val updated = def.withOverlayScalePct(landscape = true, compactPct = 140, expandedPct = 300)
        assertEquals(115, updated.overlayCompactScaleFor(landscape = false))
        assertEquals(90, updated.overlayExpandedScaleFor(landscape = false))
        assertEquals(140, updated.overlayCompactScaleFor(landscape = true))
        assertEquals(200, updated.overlayExpandedScaleFor(landscape = true))

        // Active style selection respects compact vs expanded vs detailed
        assertEquals(140, updated.copy(overlayExpanded = false, overlayDetailed = false).overlayScalePct(true))
        assertEquals(200, updated.copy(overlayExpanded = true, overlayDetailed = false).overlayScalePct(true))
        assertEquals(200, updated.copy(overlayExpanded = false, overlayDetailed = true).overlayScalePct(true))

        // Changing positions leaves scales intact
        val moved = updated.withOverlayPosPm(landscape = true, xPm = 40, yPm = 60)
        assertEquals(140, moved.overlayCompactScaleFor(landscape = true))
        assertEquals(115, moved.overlayCompactScaleFor(landscape = false))
    }

    private fun styled(compact: Boolean = false, expanded: Boolean = false, detailed: Boolean = false) =
        GovernorConfig(overlayExpanded = expanded, overlayDetailed = detailed)

    @Test fun compactStyleKeepsPortraitAndLandscapePositionsApart() {
        val c = styled().withOverlayPosPm(false, 111, 222).withOverlayPosPm(true, 333, 444)
        assertEquals(111 to 222, c.overlayPosPm(false))
        assertEquals(333 to 444, c.overlayPosPm(true))
        assertEquals(111, c.overlayCompactXPm)
        assertEquals(333, c.overlayCompactXPmL)
    }

    @Test fun expandedStyleKeepsPortraitAndLandscapePositionsApart() {
        val c = styled(expanded = true).withOverlayPosPm(false, 111, 222).withOverlayPosPm(true, 333, 444)
        assertEquals(111 to 222, c.overlayPosPm(false))
        assertEquals(333 to 444, c.overlayPosPm(true))
        assertEquals(40 to 60, c.copy(overlayExpanded = false).overlayPosPm(true)) // other styles untouched
    }

    @Test fun detailedStyleKeepsPortraitAndLandscapePositionsApart() {
        val c = styled(detailed = true).withOverlayPosPm(false, 111, 222).withOverlayPosPm(true, 333, 444)
        assertEquals(111 to 222, c.overlayPosPm(false))
        assertEquals(333 to 444, c.overlayPosPm(true))
        assertEquals(40 to 60, c.copy(overlayDetailed = false).overlayPosPm(true))
    }

    @Test fun landscapeDragDoesNotChangePortraitAndRotatingBackRestoresIt() {
        val portrait = styled(expanded = true).withOverlayPosPm(false, 250, 750)
        val dragged = portrait.withOverlayPosPm(true, 900, 100)
        assertEquals(250 to 750, dragged.overlayPosPm(false))
        assertEquals(900 to 100, dragged.overlayPosPm(true))
    }

    @Test fun landscapeSizeChangesDoNotAlterPortraitSize() {
        val c = GovernorConfig(overlayCompactScalePct = 120, overlayExpandedScalePct = 80)
            .withOverlayScalePct(landscape = true, compactPct = 60, expandedPct = 150)
        assertEquals(120, c.overlayCompactScalePct)
        assertEquals(80, c.overlayExpandedScalePct)
        assertEquals(60, c.overlayCompactScaleFor(true))
        assertEquals(150, c.overlayExpandedScaleFor(true))
    }

    @Test fun resetRestoresDefaultsForEveryStyleInBothOrientations() {
        var c = GovernorConfig()
        for (style in listOf(styled(), styled(expanded = true), styled(detailed = true))) {
            c = c.copy(overlayExpanded = style.overlayExpanded, overlayDetailed = style.overlayDetailed)
                .withOverlayPosPm(false, 1, 2).withOverlayPosPm(true, 3, 4)
        }
        val reset = c.withOverlayPositionsReset()
        for (style in listOf(styled(), styled(expanded = true), styled(detailed = true))) {
            val r = reset.copy(overlayExpanded = style.overlayExpanded, overlayDetailed = style.overlayDetailed)
            assertEquals(40 to 60, r.overlayPosPm(false))
            assertEquals(40 to 60, r.overlayPosPm(true))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayConfigStorageTest {
    private fun withFile(json: String, block: (GovernorConfig) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            f.writeText(json)
            block(ConfigStorage(ctx).loadConfig())
        } finally {
            f.delete()
        }
    }

    @Test fun oldConfigWithoutLandscapeFieldsFallsBackToPortraitValues() {
        withFile(
            """{"overlayCompactXPm": 123, "overlayCompactYPm": 234, "overlayExpandedXPm": 345, "overlayExpandedYPm": 456,
               "overlayDetailedXPm": 567, "overlayDetailedYPm": 678, "overlayCompactScalePct": 130, "overlayExpandedScalePct": 90}"""
        ) { c ->
            assertEquals(123 to 234, c.overlayPosPm(true))
            assertEquals(345 to 456, c.copy(overlayExpanded = true).overlayPosPm(true))
            assertEquals(567 to 678, c.copy(overlayDetailed = true).overlayPosPm(true))
            assertEquals(130, c.overlayCompactScaleFor(true))
            assertEquals(90, c.overlayExpandedScaleFor(true))
        }
    }

    @Test fun savedLandscapeFieldsRoundTripSeparatelyFromPortrait() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            val storage = ConfigStorage(ctx)
            storage.saveConfig(
                GovernorConfig().withOverlayPosPm(false, 100, 200).withOverlayPosPm(true, 800, 900)
                    .withOverlayScalePct(landscape = true, compactPct = 70)
            )
            val c = storage.loadConfig()
            assertEquals(100 to 200, c.overlayPosPm(false))
            assertEquals(800 to 900, c.overlayPosPm(true))
            assertEquals(70, c.overlayCompactScaleFor(true))
            assertEquals(100, c.overlayCompactScaleFor(false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun orderScreenSides_followsOrientationNotInputOrder() {
        assertEquals(2400 to 1080, OverlaySizing.orderScreenSides(1080, 2400, true))
        assertEquals(2400 to 1080, OverlaySizing.orderScreenSides(2400, 1080, true))
        assertEquals(1080 to 2400, OverlaySizing.orderScreenSides(2400, 1080, false))
    }

    @Test
    fun maxScale_landscapeExpandedAllowsMoreThanPortraitWidthCap() {
        val (w, h) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, OverlayStyle.EXPANDED)
        val land = OverlaySizing.maxScalePct(2400, 1080, OverlayStyle.EXPANDED, w, h)
        val wrong = OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.EXPANDED, w, h)
        assert(land > wrong)
    }
}
