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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.CoreGovernorApp
import com.example.model.LogLevel

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUpdate) return

        val app = context.applicationContext as? CoreGovernorApp ?: return
        val lastRunning = BootState.wasRunning(context)
        val restore = BootState.restoreEnabled(context)
        val startOnBoot = app.engine.config.value.startOnBoot

        val start = if (isBoot) {
            BootPolicy.shouldStartAfterBoot(startOnBoot, restore, lastRunning)
        } else {
            BootPolicy.shouldStartAfterUpdate(restore, lastRunning)
        }
        if (start && isBoot) {
            val d = BootGuard.evaluate(BootState.autoStartPending(context), BootState.bootFails(context))
            BootState.recordGuardDecision(context, d)
            if (!d.allowStart) {
                app.engine.log(
                    LogLevel.WARN, "Boot",
                    "Boot guard: the governor did not stay up after the last ${BootGuard.MAX_FAILS} boot starts, so this auto-start is skipped. Start it by hand from the app."
                )
                return
            }
        }
        if (start) {
            val why = if (isBoot) {
                if (startOnBoot) "start-on-boot is on" else "it was running before the reboot"
            } else {
                "it was running before the app update"
            }
            app.engine.log(LogLevel.INFO, "Boot", "$action received; starting GovernorService ($why).")
            GovernorService.start(context, fromBoot = isBoot)
        } else {
            app.engine.log(
                LogLevel.INFO, "Boot",
                "$action received; not starting (startOnBoot=$startOnBoot, restoreLastState=$restore, wasRunning=$lastRunning)."
            )
        }
    }
}
