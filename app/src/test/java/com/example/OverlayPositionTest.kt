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
import com.example.governor.AfterburnLogic
import com.example.model.AfterburnConfig
import com.example.model.BoostStrength
import com.example.model.GovernorConfig
import com.example.model.boostStrengthFor
import com.example.model.withBoostStrengthFor
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
import com.example.model.overlayDetailedScaleFor
import com.example.model.overlayPosPmFor
import com.example.model.overlayScaleFor
import com.example.model.withOverlayPosPmFor
import com.example.model.withOverlayPositionResetFor
import com.example.model.withOverlayScalePctFor
import com.example.model.overlayActiveBgOpacityPct
import com.example.model.overlayBgOpacityFor
import com.example.model.withOverlayBgOpacityFor
import com.example.model.overlayTextScaleFor
import com.example.model.withOverlayTextScaleFor
import com.example.model.PowerThrottleConfig
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

    @Test fun maxScalePctUsesSharedCapAndClamps25To200() {
        // All styles share one cap: 85% of screen width, 50% of screen height.
        // 1000x2000 -> 850x1000 allowed; natural 300x100 -> 283% / 1000% -> clamps to 200.
        assertEquals(200, OverlaySizing.maxScalePct(1000, 2000, OverlayStyle.COMPACT, 300f, 100f))

        // Huge screen still caps at 200%.
        assertEquals(200, OverlaySizing.maxScalePct(4000, 3000, OverlayStyle.COMPACT, 200f, 50f))

        // Portrait phone: 1080 * 0.85 = 918; 918 / 750 = 122.4 -> 122%.
        assertEquals(122, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.COMPACT, 750f, 90f))

        // Same cap for Expanded: 918 / 600 = 153%.
        assertEquals(153, OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.EXPANDED, 600f, 200f))

        // Tight landscape height: 1080 * 0.50 = 540; 540 / 932 = 57.9 -> 57.
        assertEquals(57, OverlaySizing.maxScalePct(2400, 1080, OverlayStyle.DETAILED, 500f, 932f))
        // Very tall content hits the 25% floor.
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

    @Test fun detailedScaleFollowsExpandedUntilSetThenIsIndependent() {
        val c = GovernorConfig(overlayExpandedScalePct = 90)
        assertEquals(90, c.overlayDetailedScaleFor(false))
        assertEquals(90, c.overlayDetailedScaleFor(true))
        val d = c.withOverlayScalePctFor(OverlayStyle.DETAILED, false, 140)
        assertEquals(140, d.overlayDetailedScaleFor(false))
        assertEquals(90, d.overlayExpandedScaleFor(false))
        // Landscape falls back to the detailed portrait size once that is set.
        assertEquals(140, d.overlayDetailedScaleFor(true))
        val e = d.withOverlayScalePctFor(OverlayStyle.DETAILED, true, 60)
        assertEquals(60, e.overlayScaleFor(OverlayStyle.DETAILED, true))
        assertEquals(140, e.overlayScaleFor(OverlayStyle.DETAILED, false))
        assertEquals(90, e.overlayScaleFor(OverlayStyle.EXPANDED, true))
        assertEquals(60, e.copy(overlayDetailed = true).overlayScalePct(true))
    }

    @Test fun anyStyleCanBeEditedWithoutBeingActive() {
        val c = GovernorConfig() // compact is active
            .withOverlayPosPmFor(OverlayStyle.DETAILED, false, 900, 100)
            .withOverlayPosPmFor(OverlayStyle.EXPANDED, true, 5, 6)
        assertEquals(900 to 100, c.overlayPosPmFor(OverlayStyle.DETAILED, false))
        assertEquals(5 to 6, c.overlayPosPmFor(OverlayStyle.EXPANDED, true))
        assertEquals(40 to 60, c.overlayPosPm(false)) // active compact untouched
        assertEquals(false, c.overlayDetailed)
        assertEquals(false, c.overlayExpanded)
        val r = c.withOverlayPositionResetFor(OverlayStyle.DETAILED)
        assertEquals(40 to 60, r.overlayPosPmFor(OverlayStyle.DETAILED, false))
        assertEquals(5 to 6, r.overlayPosPmFor(OverlayStyle.EXPANDED, true))
    }

    @Test fun backgroundOpacityFollowsMainUntilAStyleIsTuned() {
        val c = GovernorConfig(overlayBgOpacityPct = 80)
        assertEquals(80, c.overlayBgOpacityFor(OverlayStyle.DETAILED))
        val d = c.withOverlayBgOpacityFor(OverlayStyle.DETAILED, 30)
        assertEquals(30, d.overlayBgOpacityFor(OverlayStyle.DETAILED))
        assertEquals(80, d.overlayBgOpacityFor(OverlayStyle.COMPACT))
        assertEquals(80, d.overlayBgOpacityFor(OverlayStyle.EXPANDED))
        assertEquals(80, d.overlayActiveBgOpacityPct()) // compact is showing
        assertEquals(30, d.copy(overlayDetailed = true).overlayActiveBgOpacityPct())
        assertEquals(100, d.withOverlayBgOpacityFor(OverlayStyle.COMPACT, 250).overlayBgOpacityFor(OverlayStyle.COMPACT))
    }

    @Test fun perAppBoostStrengthFallsBackToMainAndDrivesTheLevel() {
        val c = GovernorConfig(afterburn = AfterburnConfig(enabled = true, strength = BoostStrength.MEDIUM))
            .withBoostStrengthFor("com.heavy", BoostStrength.MAX)
            .withBoostStrengthFor("com.light", BoostStrength.LIGHT)
        assertEquals(BoostStrength.MAX, c.boostStrengthFor("com.heavy"))
        assertEquals(BoostStrength.LIGHT, c.boostStrengthFor("com.light"))
        assertEquals(BoostStrength.MEDIUM, c.boostStrengthFor("com.other"))
        assertEquals(BoostStrength.MEDIUM, c.boostStrengthFor(""))
        assertEquals(BoostStrength.MEDIUM, c.withBoostStrengthFor("com.heavy", null).boostStrengthFor("com.heavy"))
        fun level(pkg: String) = AfterburnLogic.decide(
            cfg = c.afterburn.copy(strength = c.boostStrengthFor(pkg)),
            appSelected = true, tempC = 30f, throttleStartC = 50f,
            batteryPct = 100, pluggedOrCharging = false, wasActive = false
        ).level
        assertEquals(true, level("com.heavy") > level("com.other"))
        assertEquals(true, level("com.other") > level("com.light"))
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

    @Test fun detailedScaleRoundTripsAndOldConfigsStayUnset() {
        withFile("""{"overlayExpandedScalePct": 90}""") { c ->
            assertEquals(-1, c.overlayDetailedScalePct)
            assertEquals(90, c.overlayDetailedScaleFor(false))
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            val storage = ConfigStorage(ctx)
            storage.saveConfig(
                GovernorConfig().withOverlayScalePct(false, detailedPct = 130).withOverlayScalePct(true, detailedPct = 70)
            )
            val c = storage.loadConfig()
            assertEquals(130, c.overlayDetailedScaleFor(false))
            assertEquals(70, c.overlayDetailedScaleFor(true))
        } finally {
            f.delete()
        }
    }

    @Test fun perStyleBackgroundOpacityRoundTripsAndOldConfigsFollowMain() {
        withFile("""{"overlayBgOpacityPct": 60}""") { c ->
            assertEquals(-1, c.overlayDetailedBgOpacityPct)
            assertEquals(60, c.overlayBgOpacityFor(OverlayStyle.DETAILED))
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            val storage = ConfigStorage(ctx)
            storage.saveConfig(GovernorConfig().withOverlayBgOpacityFor(OverlayStyle.EXPANDED, 25))
            val c = storage.loadConfig()
            assertEquals(25, c.overlayBgOpacityFor(OverlayStyle.EXPANDED))
            assertEquals(-1, c.overlayCompactBgOpacityPct)
        } finally {
            f.delete()
        }
    }

    @Test fun perAppBoostStrengthRoundTripsAndBadValuesAreIgnored() {
        withFile("""{"boostPackages": ["a"], "boostStrengthByPkg": {"a": "MAX", "b": "NOPE"}}""") { c ->
            assertEquals(BoostStrength.MAX, c.boostStrengthByPkg["a"])
            assertEquals(false, c.boostStrengthByPkg.containsKey("b"))
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            val storage = ConfigStorage(ctx)
            storage.saveConfig(GovernorConfig().withBoostStrengthFor("com.x", BoostStrength.LIGHT))
            assertEquals(BoostStrength.LIGHT, storage.loadConfig().boostStrengthFor("com.x"))
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

    @Test
    fun textScale_isPerStyleAndClamped() {
        val c = GovernorConfig()
        assertEquals(100, c.overlayTextScaleFor(OverlayStyle.COMPACT))
        val d = c.withOverlayTextScaleFor(OverlayStyle.DETAILED, 130)
        assertEquals(130, d.overlayTextScaleFor(OverlayStyle.DETAILED))
        assertEquals(100, d.overlayTextScaleFor(OverlayStyle.COMPACT))
        assertEquals(100, d.overlayTextScaleFor(OverlayStyle.EXPANDED))
        assertEquals(160, c.withOverlayTextScaleFor(OverlayStyle.COMPACT, 999).overlayTextScaleFor(OverlayStyle.COMPACT))
        assertEquals(70, c.withOverlayTextScaleFor(OverlayStyle.EXPANDED, 1).overlayTextScaleFor(OverlayStyle.EXPANDED))
    }

    @Test
    fun textScale_changesNaturalSizeForEveryStyleAndOneIsTheDefault() {
        for (style in OverlayStyle.entries) {
            val (w1, h1) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, style)
            val (wDef, hDef) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, style, textScalePct = 100)
            val (wBig, hBig) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, style, textScalePct = 150)
            val (wSmall, hSmall) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, style, textScalePct = 70)
            assertEquals(w1, wDef, 0.001f)
            assertEquals(h1, hDef, 0.001f)
            assert(wBig > w1 || hBig > h1) { "$style should grow with bigger text" }
            assert(wSmall <= w1 && hSmall <= h1) { "$style should not grow with smaller text" }
        }
    }

    @Test
    fun textScale_biggerTextLowersTheMaxSizeSlider() {
        val (w, h) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, OverlayStyle.COMPACT)
        val (wB, hB) = OverlaySizing.measureNaturalSizeAtScaleOne(2.75f, OverlayStyle.COMPACT, textScalePct = 160)
        assert(OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.COMPACT, wB, hB) <=
            OverlaySizing.maxScalePct(1080, 2400, OverlayStyle.COMPACT, w, h))
    }

    @Test
    fun textScaleAndSmoothSwitch_roundTripThroughStorage() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "core_governor_config.json")
        try {
            val storage = ConfigStorage(ctx)
            val cfg = GovernorConfig()
                .withOverlayTextScaleFor(OverlayStyle.EXPANDED, 120)
                .withOverlayTextScaleFor(OverlayStyle.DETAILED, 80)
                .let { it.copy(powerThrottle = it.powerThrottle.copy(smoothPowerLimit = true)) }
            storage.saveConfig(cfg)
            val back = storage.loadConfig()
            assertEquals(100, back.overlayTextScaleFor(OverlayStyle.COMPACT))
            assertEquals(120, back.overlayTextScaleFor(OverlayStyle.EXPANDED))
            assertEquals(80, back.overlayTextScaleFor(OverlayStyle.DETAILED))
            assertEquals(true, back.powerThrottle.smoothPowerLimit)
        } finally {
            f.delete()
        }
    }

    @Test
    fun smoothPowerLimit_isOnForFreshInstallsAndOldConfigsKeepItOff() {
        assertEquals(true, PowerThrottleConfig().smoothPowerLimit)
        withFile("""{"powerThrottle": {"enabled": true, "limitWatts": 5.0}}""") { c ->
            assertEquals(false, c.powerThrottle.smoothPowerLimit)
        }
    }
}
