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

import com.example.model.AfterburnState
import com.example.model.BoostStrength
import com.example.service.AfterburnToastPolicy
import com.example.service.AfterburnTransitionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterburnToastPolicyTest {

    private fun evt(
        pkg: String = "com.game.test",
        prev: AfterburnState,
        next: AfterburnState,
        enabled: Boolean = true,
        tMs: Long = 100_000L,
        inGame: Boolean = true
    ) = AfterburnTransitionEvent(
        packageName = pkg,
        prevState = prev,
        newState = next,
        strength = BoostStrength.MEDIUM,
        toastEnabled = enabled,
        timestampMs = tMs,
        inSelectedGame = inGame
    )

    @Test
    fun triggersOnceOnTransitionIntoBoosting() {
        var s = AfterburnToastPolicy.State()
        val (show1, s1) = AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, tMs = 100_000L))
        assertTrue(show1)
        s = s1

        // Staying in BOOSTING does not re-trigger
        val (show2, s2) = AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.BOOSTING, next = AfterburnState.BOOSTING, tMs = 130_000L))
        assertFalse(show2)
        s = s2

        // Dipping to REDUCED then returning to BOOSTING in same session does not re-trigger
        val (show3, s3) = AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.BOOSTING, next = AfterburnState.REDUCED, tMs = 135_000L))
        assertTrue(show3) // ending toast, shown once for the session
        val (show4, _) = AfterburnToastPolicy.evaluate(s3, evt(prev = AfterburnState.REDUCED, next = AfterburnState.BOOSTING, tMs = 170_000L))
        assertFalse(show4)
    }

    @Test
    fun neverStartsOnReducedOrBlockedStates() {
        val s = AfterburnToastPolicy.State()
        assertFalse(AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.IDLE, next = AfterburnState.REDUCED)).first)
        assertFalse(AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.IDLE, next = AfterburnState.BLOCKED_THERMAL)).first)
        assertFalse(AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.IDLE, next = AfterburnState.BLOCKED_BATTERY)).first)
    }

    @Test
    fun enforces20SecondDebounceAcrossSessionReset() {
        val s0 = AfterburnToastPolicy.State()
        val (show1, s1) = AfterburnToastPolicy.evaluate(s0, evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, tMs = 100_000L))
        assertTrue(show1)

        // App leaves to IDLE, resets session
        val (_, s2) = AfterburnToastPolicy.evaluate(s1, evt(prev = AfterburnState.BOOSTING, next = AfterburnState.IDLE, tMs = 105_000L))

        // Re-enters 10s later -> blocked by 20s debounce
        val (showEarly, s3) = AfterburnToastPolicy.evaluate(s2, evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, tMs = 110_000L))
        assertFalse(showEarly)

        // Leaves and re-enters 21s after first toast -> allowed
        val (_, s4) = AfterburnToastPolicy.evaluate(s3, evt(prev = AfterburnState.BOOSTING, next = AfterburnState.IDLE, tMs = 120_500L))
        val (showAfterDebounce, _) = AfterburnToastPolicy.evaluate(s4, evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, tMs = 121_000L))
        assertTrue(showAfterDebounce)
    }

    @Test
    fun respectsDisabledSetting() {
        val s = AfterburnToastPolicy.State()
        assertFalse(AfterburnToastPolicy.evaluate(s, evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, enabled = false)).first)
    }

    private fun started(): AfterburnToastPolicy.State =
        AfterburnToastPolicy.evaluate(
            AfterburnToastPolicy.State(),
            evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, tMs = 100_000L)
        ).second

    @Test
    fun startToastFromIdleReducedAndBlocked() {
        for (prev in listOf(AfterburnState.IDLE, AfterburnState.REDUCED, AfterburnState.BLOCKED_THERMAL, AfterburnState.BLOCKED_BATTERY)) {
            assertTrue(AfterburnToastPolicy.evaluate(AfterburnToastPolicy.State(), evt(prev = prev, next = AfterburnState.BOOSTING)).first)
        }
    }

    @Test
    fun endingToastWhenBoostingBecomesReducedOrBlocked() {
        for (next in listOf(AfterburnState.REDUCED, AfterburnState.BLOCKED_THERMAL, AfterburnState.BLOCKED_BATTERY)) {
            val (show, _) = AfterburnToastPolicy.evaluate(started(), evt(prev = AfterburnState.BOOSTING, next = next, tMs = 105_000L))
            assertTrue("ending toast for $next", show)
        }
    }

    @Test
    fun endingToastShownOnlyOncePerSession() {
        val (first, s1) = AfterburnToastPolicy.evaluate(started(), evt(prev = AfterburnState.BOOSTING, next = AfterburnState.REDUCED, tMs = 105_000L))
        assertTrue(first)
        val (_, s2) = AfterburnToastPolicy.evaluate(s1, evt(prev = AfterburnState.REDUCED, next = AfterburnState.BOOSTING, tMs = 110_000L))
        val (second, _) = AfterburnToastPolicy.evaluate(s2, evt(prev = AfterburnState.BOOSTING, next = AfterburnState.BLOCKED_THERMAL, tMs = 115_000L))
        assertFalse(second)
    }

    @Test
    fun noEndingToastWhenGameWasLeft() {
        // Leaving the game moves to IDLE/OFF (and the event package is the new foreground app).
        for (next in listOf(AfterburnState.IDLE, AfterburnState.OFF)) {
            assertFalse(AfterburnToastPolicy.evaluate(started(), evt(prev = AfterburnState.BOOSTING, next = next, tMs = 105_000L)).first)
            assertFalse(AfterburnToastPolicy.evaluate(started(), evt(pkg = "com.other.app", prev = AfterburnState.BOOSTING, next = next, tMs = 105_000L, inGame = false)).first)
        }
        // Even a REDUCED/BLOCKED reading while another app is in front is not a toast.
        assertFalse(AfterburnToastPolicy.evaluate(started(), evt(pkg = "com.other.app", prev = AfterburnState.BOOSTING, next = AfterburnState.REDUCED, tMs = 105_000L, inGame = false)).first)
    }

    @Test
    fun noToastOutsideSelectedGame() {
        assertFalse(AfterburnToastPolicy.evaluate(AfterburnToastPolicy.State(), evt(prev = AfterburnState.IDLE, next = AfterburnState.BOOSTING, inGame = false)).first)
        assertFalse(AfterburnToastPolicy.evaluate(started(), evt(prev = AfterburnState.BOOSTING, next = AfterburnState.BLOCKED_THERMAL, inGame = false)).first)
    }

    @Test
    fun strictGateNeedsGamePackageAndForeground() {
        val games = setOf("com.game.test")
        assertTrue(AfterburnToastPolicy.isSelectedGameForeground("com.game.test", games, "com.game.test"))
        // Not in gamePackages (e.g. only in boostPackages).
        assertFalse(AfterburnToastPolicy.isSelectedGameForeground("com.boost.only", games, "com.boost.only"))
        // In gamePackages but another app is in front.
        assertFalse(AfterburnToastPolicy.isSelectedGameForeground("com.game.test", games, "com.other.app"))
        assertFalse(AfterburnToastPolicy.isSelectedGameForeground("", setOf(""), ""))
    }

    @Test
    fun boostedAppGetsPillWhetherItIsInGamesListOrBoostList() {
        val games = setOf("com.game.test")
        val boost = setOf("com.boost.only")
        assertTrue(AfterburnToastPolicy.isBoostedAppForeground("com.game.test", games, boost, "com.game.test"))
        assertTrue(AfterburnToastPolicy.isBoostedAppForeground("com.boost.only", games, boost, "com.boost.only"))
        assertFalse(AfterburnToastPolicy.isBoostedAppForeground("com.boost.only", games, boost, "com.other.app"))
        assertFalse(AfterburnToastPolicy.isBoostedAppForeground("com.random", games, boost, "com.random"))
        assertFalse(AfterburnToastPolicy.isBoostedAppForeground("", setOf(""), setOf(""), ""))
    }

    @Test
    fun noEndingToastWhenDisabledOrWithoutShownStartToast() {
        assertFalse(AfterburnToastPolicy.evaluate(started(), evt(prev = AfterburnState.BOOSTING, next = AfterburnState.REDUCED, enabled = false, tMs = 105_000L)).first)
        // Start toast was never shown for this session (fresh state): no ending toast either.
        assertFalse(AfterburnToastPolicy.evaluate(AfterburnToastPolicy.State(), evt(prev = AfterburnState.BOOSTING, next = AfterburnState.REDUCED)).first)
    }
}
