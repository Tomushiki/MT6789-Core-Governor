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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.CoreGovernorApp
import com.example.MainActivity
import com.example.R
import com.example.governor.GovernorEngine
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GovernorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryJob: Job? = null
    private lateinit var engine: GovernorEngine

    override fun onCreate() {
        super.onCreate()
        engine = (application as CoreGovernorApp).engine
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stopAndRestore()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CYCLE_MODE -> {
                engine.cycleModeOverride()
                return START_STICKY
            }
            else -> {
                startForegroundCompat(buildNotification(engine.telemetry.value))
                engine.startGovernor()
                observeTelemetry()
                return START_STICKY
            }
        }
    }

    private fun observeTelemetry() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = serviceScope.launch {
            var lastNotifyMs = 0L
            engine.telemetry.collectLatest { state ->
                val now = System.currentTimeMillis()
                if (now - lastNotifyMs >= 900L) {
                    lastNotifyMs = now
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(state: TelemetryState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GovernorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val modeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GovernorService::class.java).apply { action = ACTION_CYCLE_MODE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusTitle = if (state.isRunning) {
            val capText = if (state.effectiveCpuCapPct < 99.5f || state.effectiveGpuCapPct < 99.5f) {
                " • Cap ${state.effectiveCpuCapPct.toInt()}%/${state.effectiveGpuCapPct.toInt()}%"
            } else ""
            "CoreGovernor [${state.activeMode.name}]$capText"
        } else {
            "CoreGovernor Initializing..."
        }

        val clustersSummary = state.clusters.joinToString(" | ") { c ->
            "P${c.policyId}:${c.targetFreqKhz / 1000}M"
        }
        val gpuText = if (state.gpu.available) "GPU:${state.gpu.targetFreqKhz / 1000}M" else ""
        val telemetryLine = "${"%.1f".format(state.temperatureC)}°C • ${"%.2f".format(state.watts)}W • $clustersSummary $gpuText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(statusTitle)
            .setContentText(telemetryLine.trim())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_toggle_mode), modeIntent)
            .addAction(0, getString(R.string.action_stop_governor), stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        engine.stopAndRestore()
        telemetryJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "core_governor_service"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.action.START_GOVERNOR"
        const val ACTION_STOP = "com.example.action.STOP_GOVERNOR"
        const val ACTION_CYCLE_MODE = "com.example.action.CYCLE_MODE"

        fun start(context: Context) {
            val intent = Intent(context, GovernorService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GovernorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
