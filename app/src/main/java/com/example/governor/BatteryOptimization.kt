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

package com.example.governor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.example.root.RootShell

/**
 * Helps keep CoreGovernor alive in the background by taking it off Android's battery optimization
 * (Doze / app standby) list.
 *
 * Two ways, tried in this order by the UI:
 *  1. Root: whitelist the package in the device-idle controller, which needs no prompt. The system
 *     keeps this list across reboots.
 *  2. Android's own prompt ("Allow app to always run in the background?").
 */
object BatteryOptimization {

    /** True when Android already exempts this app from battery optimization. */
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** The shell command used by the root method. Exposed so it can be tested. */
    fun rootCommand(packageName: String): String =
        "(dumpsys deviceidle whitelist +$packageName || cmd deviceidle whitelist +$packageName) >/dev/null 2>&1; " +
            "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
            "cmd appops set $packageName RUN_IN_BACKGROUND allow >/dev/null 2>&1; true"

    /**
     * Whitelists the app using root, then asks Android whether it worked.
     * Returns the real result, not the command's exit code.
     */
    suspend fun exemptWithRoot(context: Context, root: RootShell): Boolean {
        if (isIgnoring(context)) return true
        root.exec(rootCommand(context.packageName))
        return isIgnoring(context)
    }

    /** Opens Android's own prompt. Falls back to the battery optimization list, then the app's info page. */
    fun openSystemPrompt(context: Context) {
        val pkg = context.packageName
        val attempts = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(pkg)
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // try the next one
            }
        }
    }

    /** The app's info page, where many ROMs keep their own background / battery switches. */
    fun openAppDetails(context: Context) {
        try {
            context.startActivity(appDetailsIntent(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // nothing else to try
        }
    }

    private fun appDetailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
}
