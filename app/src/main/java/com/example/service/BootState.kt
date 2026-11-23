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

import android.content.Context

/**
 * Remembers whether the governor was running, so it can be brought back after a reboot.
 * "Running" is cleared only by an explicit stop (Stop button or notification action). A system
 * shutdown, a crash or a kill from the system leave it set, which is what we want after a reboot.
 * Kept in its own small preferences file, so the main config is untouched.
 */
object BootState {
    private const val PREFS = "coregovernor_boot"
    private const val KEY_LAST_RUNNING = "last_running"
    private const val KEY_RESTORE = "restore_last_state"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wasRunning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LAST_RUNNING, false)

    fun setRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LAST_RUNNING, running).apply()
    }

    /** "Restore last state after reboot". On by default. */
    fun restoreEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RESTORE, true)

    fun setRestoreEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RESTORE, enabled).apply()
    }
}
