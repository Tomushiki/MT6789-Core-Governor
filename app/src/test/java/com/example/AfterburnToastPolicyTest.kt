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
        tMs: Long = 100_000L
    ) = AfterburnTransitionEvent(
        packageName = pkg,
        prevState = prev,
        newState = next,
        strength = BoostStrength.MEDIUM,
        toastEnabled = enabled,
        timestampMs = tMs
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
        assertFalse(show3)
        val (show4, _) = AfterburnToastPolicy.evaluate(s3, evt(prev = AfterburnState.REDUCED, next = AfterburnState.BOOSTING, tMs = 170_000L))
        assertFalse(show4)
    }

    @Test
    fun neverTriggersOnReducedOrBlockedStates() {
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
}
