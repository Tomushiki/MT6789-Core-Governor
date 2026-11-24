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

package com.example.service

/**
 * Crash-loop guard for the automatic start after a reboot. If the service never reached a stable
 * run ([STABLE_MS]) after [MAX_FAILS] boot starts in a row, the next boot start is skipped once,
 * so a bad setting can never lock the user out. Pure decision logic.
 */
object BootGuard {
    const val MAX_FAILS = 3
    const val STABLE_MS = 60_000L

    data class Decision(val allowStart: Boolean, val fails: Int, val tripped: Boolean)

    /**
     * @param previousAttemptUnstable the last boot start never reached a stable run
     * @param fails consecutive unstable boot starts counted so far
     */
    fun evaluate(previousAttemptUnstable: Boolean, fails: Int): Decision {
        val f = if (previousAttemptUnstable) fails + 1 else 0
        return if (f >= MAX_FAILS) Decision(allowStart = false, fails = 0, tripped = true)
        else Decision(allowStart = true, fails = f, tripped = false)
    }
}
