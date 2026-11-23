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

package com.example.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CoreGovernorApp
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.InstalledAppInfo
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.ModeOverride
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.PresetItem
import com.example.model.ThrottleCurveShape
import com.example.service.GovernorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoreGovernorViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as CoreGovernorApp).engine

    val hardwareSpec = engine.hardwareSpec
    val config = engine.config
    val presets = engine.presets
    val telemetry = engine.telemetry
    val logs: StateFlow<List<LogEntry>> = engine.logs

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _usageStatsGranted = MutableStateFlow(engine.hasUsageStatsPermission())
    val usageStatsGranted: StateFlow<Boolean> = _usageStatsGranted.asStateFlow()

    init {
        viewModelScope.launch {
            engine.discoverHardware(forceRefresh = false)
        }
        loadInstalledApps()
    }

    fun refreshUsageStatsPermission() {
        _usageStatsGranted.value = engine.hasUsageStatsPermission()
    }

    fun rediscoverHardware() {
        viewModelScope.launch {
            engine.discoverHardware(forceRefresh = true)
        }
    }

    fun refreshGpuOppTable() {
        viewModelScope.launch {
            engine.refreshGpuOppTable()
        }
    }

    fun toggleGovernorRunning() {
        val ctx = getApplication<Application>()
        if (telemetry.value.isRunning) {
            GovernorService.stop(ctx)
        } else {
            GovernorService.start(ctx)
        }
    }

    fun setModeOverride(override: ModeOverride) {
        engine.updateConfig { it.copy(modeOverride = override) }
        engine.log(LogLevel.INFO, "Mode", "Mode override set to ${override.name}")
    }

    fun updatePollInterval(ms: Long) {
        engine.updateConfig { it.copy(pollIntervalMs = ms.coerceIn(50L, 1000L)) }
    }

    fun updateSafetyFloor(pct: Float) {
        engine.updateConfig { it.copy(safetyFloorPct = pct.coerceIn(5f, 95f)) }
    }

    fun updateStartOnBoot(enabled: Boolean) {
        engine.updateConfig { it.copy(startOnBoot = enabled) }
    }

    fun updateCpuLoadUsesBusiestCore(enabled: Boolean) {
        engine.updateConfig { it.copy(cpuLoadUsesBusiestCore = enabled) }
    }

    fun updateScreenOffSaving(
        enabled: Boolean? = null,
        delaySec: Int? = null,
        exemptMusic: Boolean? = null,
        exemptCharging: Boolean? = null
    ) {
        engine.updateConfig { cfg ->
            cfg.copy(
                screenOffSavingEnabled = enabled ?: cfg.screenOffSavingEnabled,
                screenOffDelaySec = (delaySec ?: cfg.screenOffDelaySec).coerceIn(0, 60),
                screenOffExemptMusic = exemptMusic ?: cfg.screenOffExemptMusic,
                screenOffExemptCharging = exemptCharging ?: cfg.screenOffExemptCharging
            )
        }
    }

    fun updateOverlay(
        enabled: Boolean? = null,
        clickThrough: Boolean? = null,
        gameOnly: Boolean? = null,
        opacityPct: Int? = null,
        compactScalePct: Int? = null,
        expandedScalePct: Int? = null,
        bgOpacityPct: Int? = null
    ) {
        if (enabled == true) {
            val ctx = getApplication<Application>()
            if (!Settings.canDrawOverlays(ctx)) {
                viewModelScope.launch {
                    engine.rootShell.exec("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
                    if (Settings.canDrawOverlays(ctx)) {
                        engine.updateConfig { cfg ->
                            cfg.copy(
                                overlayEnabled = true,
                                overlayClickThrough = clickThrough ?: cfg.overlayClickThrough,
                                overlayGameOnly = gameOnly ?: cfg.overlayGameOnly,
                                overlayOpacityPct = (opacityPct ?: cfg.overlayOpacityPct).coerceIn(30, 100),
                                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100),
                                overlayCompactScalePct = (compactScalePct ?: cfg.overlayCompactScalePct).coerceIn(40, 400),
                                overlayExpandedScalePct = (expandedScalePct ?: cfg.overlayExpandedScalePct).coerceIn(40, 400)
                            )
                        }
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    }
                }
                return
            }
        }
        engine.updateConfig { cfg ->
            cfg.copy(
                overlayEnabled = enabled ?: cfg.overlayEnabled,
                overlayClickThrough = clickThrough ?: cfg.overlayClickThrough,
                overlayGameOnly = gameOnly ?: cfg.overlayGameOnly,
                overlayOpacityPct = (opacityPct ?: cfg.overlayOpacityPct).coerceIn(30, 100),
                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100),
                overlayCompactScalePct = (compactScalePct ?: cfg.overlayCompactScalePct).coerceIn(40, 400),
                overlayExpandedScalePct = (expandedScalePct ?: cfg.overlayExpandedScalePct).coerceIn(40, 400)
            )
        }
    }

    fun updateOverlayPosition(xPm: Int?, yPm: Int?, reset: Boolean = false) {
        engine.updateConfig { cfg ->
            if (reset) {
                cfg.copy(
                    overlayCompactXPm = 40,
                    overlayCompactYPm = 60,
                    overlayExpandedXPm = 40,
                    overlayExpandedYPm = 60
                )
            } else if (cfg.overlayExpanded) {
                cfg.copy(
                    overlayExpandedXPm = (xPm ?: cfg.overlayExpandedXPm).coerceIn(0, 1000),
                    overlayExpandedYPm = (yPm ?: cfg.overlayExpandedYPm).coerceIn(0, 1000)
                )
            } else {
                cfg.copy(
                    overlayCompactXPm = (xPm ?: cfg.overlayCompactXPm).coerceIn(0, 1000),
                    overlayCompactYPm = (yPm ?: cfg.overlayCompactYPm).coerceIn(0, 1000)
                )
            }
        }
    }

    fun selectTempSensor(path: String) {
        engine.updateConfig { it.copy(selectedTempSensorPath = path) }
    }

    fun updateClusterRange(mode: GovernorMode, policyId: Int, range: FreqRange) {
        engine.updateConfig { cfg ->
            if (mode == GovernorMode.NORMAL) {
                val updatedMap = cfg.normalMode.cpuRangesKhz.toMutableMap().apply { put(policyId, range) }
                cfg.copy(normalMode = cfg.normalMode.copy(cpuRangesKhz = updatedMap))
            } else {
                val updatedMap = cfg.gameMode.cpuRangesKhz.toMutableMap().apply { put(policyId, range) }
                cfg.copy(gameMode = cfg.gameMode.copy(cpuRangesKhz = updatedMap))
            }
        }
    }

    fun updateGpuRange(mode: GovernorMode, range: FreqRange) {
        engine.updateConfig { cfg ->
            if (mode == GovernorMode.NORMAL) {
                cfg.copy(normalMode = cfg.normalMode.copy(gpuRangeKhz = range))
            } else {
                cfg.copy(gameMode = cfg.gameMode.copy(gpuRangeKhz = range))
            }
        }
    }

    fun updateModeScalingParams(
        mode: GovernorMode,
        headroomPct: Float? = null,
        rampUpSpeed: Float? = null,
        rampDownSpeed: Float? = null,
        gpuHeadroomPct: Float? = null,
        gpuRampUpSpeed: Float? = null,
        gpuRampDownSpeed: Float? = null,
        launchBurstEnabled: Boolean? = null,
        launchBurstDurationMs: Long? = null,
        launchSpikeThresholdPct: Float? = null
    ) {
        engine.updateConfig { cfg ->
            val current = if (mode == GovernorMode.NORMAL) cfg.normalMode else cfg.gameMode
            val next = current.copy(
                headroomPct = headroomPct ?: current.headroomPct,
                rampUpSpeed = rampUpSpeed ?: current.rampUpSpeed,
                rampDownSpeed = rampDownSpeed ?: current.rampDownSpeed,
                gpuHeadroomPct = gpuHeadroomPct ?: current.gpuHeadroomPct,
                gpuRampUpSpeed = gpuRampUpSpeed ?: current.gpuRampUpSpeed,
                gpuRampDownSpeed = gpuRampDownSpeed ?: current.gpuRampDownSpeed,
                launchBurstEnabled = launchBurstEnabled ?: current.launchBurstEnabled,
                launchBurstDurationMs = launchBurstDurationMs ?: current.launchBurstDurationMs,
                launchSpikeThresholdPct = launchSpikeThresholdPct ?: current.launchSpikeThresholdPct
            )
            if (mode == GovernorMode.NORMAL) cfg.copy(normalMode = next) else cfg.copy(gameMode = next)
        }
    }

    fun updateTempThrottle(
        enabled: Boolean? = null,
        startTempC: Float? = null,
        fullTempC: Float? = null,
        cpuMaxReductionPct: Float? = null,
        gpuMaxReductionPct: Float? = null,
        curveShape: ThrottleCurveShape? = null,
        reactionSpeed: Float? = null,
        hysteresisC: Float? = null
    ) {
        engine.updateConfig { cfg ->
            val t = cfg.tempThrottle
            val newStart = startTempC ?: t.startTempC
            val newFull = (fullTempC ?: t.fullTempC).coerceAtLeast(newStart + 2f)
            cfg.copy(
                tempThrottle = t.copy(
                    enabled = enabled ?: t.enabled,
                    startTempC = newStart,
                    fullTempC = newFull,
                    cpuMaxReductionPct = cpuMaxReductionPct ?: t.cpuMaxReductionPct,
                    gpuMaxReductionPct = gpuMaxReductionPct ?: t.gpuMaxReductionPct,
                    curveShape = curveShape ?: t.curveShape,
                    reactionSpeed = reactionSpeed ?: t.reactionSpeed,
                    hysteresisC = hysteresisC ?: t.hysteresisC
                )
            )
        }
    }

    fun updatePowerThrottle(
        enabled: Boolean? = null,
        limitWatts: Float? = null,
        sustainMs: Long? = null,
        stepPct: Float? = null,
        releaseStepPct: Float? = null,
        averagingSamples: Int? = null,
        unitScale: PowerUnitScale? = null,
        polarity: PowerPolarity? = null
    ) {
        engine.updateConfig { cfg ->
            val p = cfg.powerThrottle
            cfg.copy(
                powerThrottle = p.copy(
                    enabled = enabled ?: p.enabled,
                    limitWatts = limitWatts ?: p.limitWatts,
                    sustainMs = sustainMs ?: p.sustainMs,
                    stepPct = stepPct ?: p.stepPct,
                    releaseStepPct = releaseStepPct ?: p.releaseStepPct,
                    averagingSamples = averagingSamples ?: p.averagingSamples,
                    unitScale = unitScale ?: p.unitScale,
                    polarity = polarity ?: p.polarity
                )
            )
        }
    }

    fun toggleGamePackage(packageName: String) {
        engine.updateConfig { cfg ->
            val next = cfg.gamePackages.toMutableSet()
            if (packageName in next) next.remove(packageName) else next.add(packageName)
            cfg.copy(gamePackages = next)
        }
    }

    fun saveNamedPreset(name: String) = engine.saveNamedPreset(name)
    fun loadPreset(preset: PresetItem) = engine.loadPreset(preset)
    fun deletePreset(preset: PresetItem) = engine.deletePreset(preset)

    fun exportJson(uri: Uri): Boolean {
        val ok = engine.storage.exportToUri(uri, config.value, presets.value)
        engine.log(
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            "Export",
            if (ok) "Exported configuration JSON." else "Failed to export configuration JSON."
        )
        return ok
    }

    fun importJson(uri: Uri): Boolean {
        val result = engine.storage.importFromUri(uri)
        return if (result != null) {
            engine.applyImportedData(result.first, result.second)
            true
        } else {
            engine.log(LogLevel.ERROR, "Import", "Invalid or unreadable JSON file.")
            false
        }
    }

    fun clearLogs() = engine.clearLogs()

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val list = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
                val selfPkg = getApplication<Application>().packageName
                resolved
                    .mapNotNull { ri ->
                        val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                        if (ai.packageName == selfPkg) return@mapNotNull null
                        val label = ri.loadLabel(pm).toString().ifBlank { ai.packageName }
                        val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        InstalledAppInfo(
                            packageName = ai.packageName,
                            label = label,
                            isSystemApp = isSys
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            }
            _installedApps.value = list
            _isLoadingApps.value = false
        }
    }
}
