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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as? CoreGovernorApp ?: return
            val cfg = app.engine.config.value
            if (cfg.startOnBoot) {
                app.engine.log(LogLevel.INFO, "Boot", "BOOT_COMPLETED received; auto-starting GovernorService.")
                GovernorService.start(context)
            }
        }
    }
}
