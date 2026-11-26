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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.CoreGovernorApp
import com.example.MainActivity
import com.example.R
import com.example.data.LocaleHelper
import com.example.governor.GovernorEngine
import com.example.governor.StutterHunterRunner
import com.example.model.BatteryState
import com.example.model.GovernorMode
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class GovernorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryJob: Job? = null
    private var overlayNotifyJob: Job? = null
    private var protectionJob: Job? = null
    private var afterburnToastJob: Job? = null
    private var stutterHunterJob: Job? = null
    private var stutterHunterRunner: StutterHunterRunner? = null
    @Volatile
    private var screenInteractiveFlag: Boolean = true
    private lateinit var engine: GovernorEngine
    private lateinit var overlayController: OverlayController
    private lateinit var afterburnToastController: AfterburnToastController
    private var screenReceiverRegistered = false
    private var lastNotificationKey: String? = null
    private var localizedCtx: Context = this

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val openIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, GovernorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val modeIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            2,
            Intent(this, GovernorService::class.java).apply { action = ACTION_CYCLE_MODE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val unlockIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            3,
            Intent(this, GovernorService::class.java).apply { action = ACTION_UNLOCK_OVERLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractiveFlag = false
                    engine.onScreenOff()
                    overlayController.setScreenInteractive(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractiveFlag = true
                    engine.onScreenOn()
                    overlayController.setScreenInteractive(true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunningFlag = true
        localizedCtx = LocaleHelper.wrap(this)
        engine = (application as CoreGovernorApp).engine
        ensureNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = pm.isInteractive
        screenInteractiveFlag = interactive
        engine.setInitialScreenInteractive(interactive)
        overlayController = OverlayController(this, engine, serviceScope)
        overlayController.setScreenInteractive(interactive)
        overlayController.start()
        afterburnToastController = AfterburnToastController(
            this,
            serviceScope,
            contextWrapper = { LocaleHelper.wrap(it) }
        )
        afterburnToastJob = serviceScope.launch {
            engine.afterburnEvents.collect { evt ->
                afterburnToastController.onEvent(evt)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private var stableJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // An explicit stop: do not bring the governor back after the next reboot.
                BootState.setRunning(this, false)
                stableJob?.cancel()
                protectionJob?.cancel()
                protectionJob = null
                stutterHunterJob?.cancel()
                stutterHunterJob = null
                stutterHunterRunner?.onStop()
                engine.stopProtectionWatchdogAsync()
                BootState.clearGuard(this)
                engine.stopAndRestore()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CYCLE_MODE -> {
                engine.cycleModeOverride()
                return START_STICKY
            }
            ACTION_UNLOCK_OVERLAY -> {
                engine.updateConfig { it.copy(overlayClickThrough = false) }
                notifyIfChanged(engine.telemetry.value)
                return START_STICKY
            }
            ACTION_REFRESH_LOCALE -> {
                localizedCtx = LocaleHelper.wrap(this)
                ensureNotificationChannel()
                lastNotificationKey = null
                notifyIfChanged(engine.telemetry.value)
                overlayController.refreshLocale()
                return START_STICKY
            }
            else -> {
                // Remember that it should be running, so it is restored after a reboot.
                BootState.setRunning(this, true)
                // A start by hand (not from the boot receiver) clears the boot guard right away.
                if (intent != null && !intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) BootState.clearGuard(this)
                stableJob?.cancel()
                stableJob = serviceScope.launch {
                    kotlinx.coroutines.delay(BootGuard.STABLE_MS)
                    BootState.clearGuard(this@GovernorService)
                }
                startForegroundCompat(buildNotification(engine.telemetry.value))
                engine.startGovernor()
                observeTelemetry()
                startProtectionLoop()
                startStutterHunterLoop()
                return START_STICKY
            }
        }
    }

    private fun startStutterHunterLoop() {
        if (stutterHunterJob?.isActive == true) return
        val runner = stutterHunterRunner ?: StutterHunterRunner(
            context = applicationContext,
            engine = engine,
            isScreenInteractive = { screenInteractiveFlag }
        ).also { stutterHunterRunner = it }
        stutterHunterJob = serviceScope.launch(Dispatchers.IO) {
            runner.run(this)
        }
    }

    private fun startProtectionLoop() {
        if (protectionJob?.isActive == true) return
        if (engine.config.value.protectFromKillEnabled) {
            engine.processProtection.touchStopFlag()
        }
        protectionJob = serviceScope.launch(Dispatchers.IO) {
            val initialEnabled = engine.config.value.protectFromKillEnabled
            engine.processProtection.applyAndVerify(
                enabled = initialEnabled,
                watchServiceAlive = initialEnabled
            )
            while (isActive) {
                kotlinx.coroutines.delay(30_000L)
                engine.processProtection.periodicMaintain(engine.config.value.protectFromKillEnabled)
            }
        }
    }

    private fun observeTelemetry() {
        if (overlayNotifyJob?.isActive != true) {
            overlayNotifyJob = serviceScope.launch {
                engine.config
                    .map { it.overlayEnabled to it.overlayClickThrough }
                    .distinctUntilChanged()
                    .collectLatest {
                        notifyIfChanged(engine.telemetry.value)
                    }
            }
        }
        if (telemetryJob?.isActive == true) return
        telemetryJob = serviceScope.launch {
            var lastNotifyMs = 0L
            engine.telemetry.collectLatest { state ->
                val now = System.currentTimeMillis()
                if (now - lastNotifyMs >= 900L) {
                    lastNotifyMs = now
                    notifyIfChanged(state)
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

    private data class NotificationContent(
        val key: String,
        val statusTitle: String,
        val telemetryLine: String,
        val showUnlock: Boolean
    )

    private fun buildNotificationContent(state: TelemetryState): NotificationContent {
        val statusTitle = if (state.isRunning) {
            val modeLabel = localizedCtx.getString(
                if (state.activeMode == GovernorMode.GAME) R.string.mode_game else R.string.mode_normal
            )
            val capText = if (state.effectiveCpuCapPct < 99.5f || state.effectiveGpuCapPct < 99.5f) {
                localizedCtx.getString(
                    R.string.notif_cap_suffix,
                    state.effectiveCpuCapPct.toInt(),
                    state.effectiveGpuCapPct.toInt()
                )
            } else ""
            localizedCtx.getString(R.string.notif_title_running, modeLabel, capText)
        } else {
            localizedCtx.getString(R.string.notif_title_initializing)
        }

        val clustersSummary = state.clusters.joinToString(" | ") { c ->
            "P${c.policyId}:${c.targetFreqKhz / 1000}M"
        }
        val gpuText = if (state.gpu.available) "GPU:${state.gpu.targetFreqKhz / 1000}M" else ""
        val powerText = when (state.batteryState) {
            BatteryState.CHARGING -> localizedCtx.getString(
                R.string.notif_power_charging,
                String.format(Locale.US, "%.2f", state.chargeWatts)
            )
            BatteryState.FULL_PLUGGED -> localizedCtx.getString(R.string.notif_power_plugged)
            BatteryState.DISCHARGING -> localizedCtx.getString(
                R.string.notif_power_discharging,
                String.format(Locale.US, "%.2f", state.watts)
            )
            BatteryState.UNKNOWN -> localizedCtx.getString(R.string.notif_power_unknown)
        }
        val telemetryLine = localizedCtx.getString(
            R.string.notif_telemetry_line,
            String.format(Locale.US, "%.1f", state.temperatureC),
            powerText,
            clustersSummary,
            gpuText
        ).trim()
        val cfg = engine.config.value
        val showUnlock = cfg.overlayEnabled && cfg.overlayClickThrough
        val key = "$statusTitle\u0000$telemetryLine\u0000$showUnlock"
        return NotificationContent(key, statusTitle, telemetryLine, showUnlock)
    }

    private fun buildNotification(state: TelemetryState): Notification {
        val content = buildNotificationContent(state)
        lastNotificationKey = content.key
        return buildNotification(content)
    }

    private fun buildNotification(content: NotificationContent): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.statusTitle)
            .setContentText(content.telemetryLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, localizedCtx.getString(R.string.action_toggle_mode), modeIntent)
            .addAction(0, localizedCtx.getString(R.string.action_stop_governor), stopIntent)

        if (content.showUnlock) {
            builder.addAction(0, localizedCtx.getString(R.string.action_unlock_overlay), unlockIntent)
        }
        return builder.build()
    }

    private fun notifyIfChanged(state: TelemetryState) {
        val content = buildNotificationContent(state)
        if (content.key == lastNotificationKey) return
        lastNotificationKey = content.key
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localizedCtx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localizedCtx.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunningFlag = false
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            screenReceiverRegistered = false
        }
        overlayController.stop()
        afterburnToastController.dismissNow()
        engine.stopAndRestore()
        engine.storage.flush()
        engine.powerModel.flush()
        engine.appDemand.flush()
        engine.batteryEstimator.flush()
        engine.rating.flush()
        stableJob?.cancel()
        protectionJob?.cancel()
        stutterHunterJob?.cancel()
        stutterHunterJob = null
        stutterHunterRunner?.onStop()
        afterburnToastJob?.cancel()
        overlayNotifyJob?.cancel()
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
        const val ACTION_UNLOCK_OVERLAY = "com.example.action.UNLOCK_OVERLAY"
        const val ACTION_REFRESH_LOCALE = "com.example.action.REFRESH_LOCALE"
        const val EXTRA_FROM_BOOT = "com.example.extra.FROM_BOOT"

        @Volatile
        private var isRunningFlag: Boolean = false

        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, GovernorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FROM_BOOT, fromBoot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** v3.8.1: true while the service is alive (used by the Quick Settings tile). */
        fun isRunning(): Boolean = isRunningFlag

        fun stop(context: Context) {
            val intent = Intent(context, GovernorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun refreshLocale(context: Context) {
            if (!isRunningFlag) return
            val intent = Intent(context, GovernorService::class.java).apply { action = ACTION_REFRESH_LOCALE }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
