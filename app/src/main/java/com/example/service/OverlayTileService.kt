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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.CoreGovernorApp
import com.example.MainActivity
import com.example.R
import com.example.data.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * v3.8.1 Quick Settings tile for the overlay. Each tap goes to the next step:
 * Off -> Compact -> Expanded -> Detailed -> Off. It also keeps up when the overlay itself is tapped.
 */
class OverlayTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    private val engine
        get() = (application as CoreGovernorApp).engine

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch {
            engine.config
                .map { OverlayCycle.current(it) }
                .distinctUntilChanged()
                .collect { renderTile(it) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val goingTo = OverlayCycle.next(OverlayCycle.current(engine.config.value))
        if (goingTo != OverlayCycle.Step.OFF && !Settings.canDrawOverlays(this)) {
            scope.launch {
                // Same as the Tuning switch: with root the permission can be granted without a prompt.
                try {
                    engine.rootShell.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                } catch (_: Throwable) {
                }
                if (Settings.canDrawOverlays(this@OverlayTileService)) {
                    applyStep()
                } else {
                    openAndCollapse(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
            return
        }
        applyStep()
    }

    private fun applyStep() {
        engine.updateConfig { OverlayCycle.advance(it) }
        val step = OverlayCycle.current(engine.config.value)
        if (step != OverlayCycle.Step.OFF && !GovernorService.isRunning()) {
            // The overlay only shows while the service runs. If Android refuses to start it from here,
            // open the app so the governor can be started by hand.
            try {
                GovernorService.start(this)
            } catch (_: Throwable) {
                openAndCollapse(Intent(this, MainActivity::class.java))
            }
        }
    }

    private fun openAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderTile(step: OverlayCycle.Step) {
        val tile = qsTile ?: return
        val styleName = getString(
            when (step) {
                OverlayCycle.Step.OFF -> R.string.tile_overlay_off
                OverlayCycle.Step.COMPACT -> R.string.tile_overlay_compact
                OverlayCycle.Step.EXPANDED -> R.string.tile_overlay_expanded
                OverlayCycle.Step.DETAILED -> R.string.tile_overlay_detailed
            }
        )
        val label = getString(R.string.tile_overlay_label)
        tile.state = if (step == OverlayCycle.Step.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_overlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = label
            tile.subtitle = styleName
        } else {
            tile.label = "$label: $styleName"
        }
        tile.updateTile()
    }
}
