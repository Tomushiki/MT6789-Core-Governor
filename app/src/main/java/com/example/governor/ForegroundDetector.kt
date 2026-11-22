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

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.root.RootShell

class ForegroundDetector(
    private val context: Context,
    private val rootShell: RootShell
) {
    private var lastKnownPackage: String = ""
    private var lastRootFallbackCheckMs: Long = 0L

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun detectForegroundPackage(nowMs: Long): String {
        if (hasUsageStatsPermission()) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val events = usm.queryEvents(nowMs - 8_000L, nowMs)
                val event = UsageEvents.Event()
                var latestPkg = ""
                var latestTime = 0L
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    @Suppress("DEPRECATION")
                    val isForegroundEvent = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    if (isForegroundEvent && event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName.orEmpty()
                    }
                }
                if (latestPkg.isNotBlank()) {
                    lastKnownPackage = latestPkg
                    return lastKnownPackage
                }
            }
        }

        // Root shell fallback (throttled to max once per 500ms so dumpsys doesn't add overhead)
        if (nowMs - lastRootFallbackCheckMs >= 500L && rootShell.isRootAvailable) {
            lastRootFallbackCheckMs = nowMs
            val res = rootShell.exec(
                "dumpsys window displays 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | tail -n 2"
            )
            if (res.isSuccess && res.stdout.isNotBlank()) {
                val match = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""").find(res.stdout)
                val pkg = match?.groupValues?.getOrNull(1)
                if (!pkg.isNullOrBlank()) {
                    lastKnownPackage = pkg
                }
            }
        }
        return lastKnownPackage
    }
}
