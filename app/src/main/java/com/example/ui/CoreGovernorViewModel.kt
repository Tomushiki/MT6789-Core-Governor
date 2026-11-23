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
import com.example.governor.BatteryEstimatorUiSnapshot
import com.example.governor.MetricSample
import com.example.governor.PowerModelUiSnapshot
import com.example.governor.SensorPinner
import com.example.governor.SessionInfo
import com.example.governor.UpdateChecker
import com.example.governor.UpdateState
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
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
import com.example.model.withOverlayPosPm
import com.example.model.withOverlayScalePct
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
    val sensorPin = engine.sensorPinner.state
    val logs: StateFlow<List<LogEntry>> = engine.logs
    val powerModelSnapshot: StateFlow<PowerModelUiSnapshot> = engine.powerModel.uiSnapshot
    val batterySnapshot: StateFlow<BatteryEstimatorUiSnapshot> = engine.batteryEstimator.snapshot

    // Performance Metrics tab
    val metricsLive: StateFlow<List<MetricSample>> = engine.metrics.live
    val metricsSessions: StateFlow<List<SessionInfo>> = engine.metrics.sessions
    val metricsRecording: StateFlow<Boolean> = engine.metrics.recording

    private val _selectedSession = MutableStateFlow<SessionInfo?>(null)
    val selectedSession: StateFlow<SessionInfo?> = _selectedSession.asStateFlow()

    private val _selectedSamples = MutableStateFlow<List<MetricSample>>(emptyList())
    val selectedSamples: StateFlow<List<MetricSample>> = _selectedSamples.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _usageStatsGranted = MutableStateFlow(engine.hasUsageStatsPermission())
    val usageStatsGranted: StateFlow<Boolean> = _usageStatsGranted.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _autoUpdateCheck = MutableStateFlow(
        UpdateChecker.autoCheckEnabled(application)
    )
    val autoUpdateCheck: StateFlow<Boolean> = _autoUpdateCheck.asStateFlow()

    val currentVersion: String = com.example.BuildConfig.VERSION_NAME

    init {
        viewModelScope.launch {
            engine.discoverHardware(forceRefresh = false)
        }
        loadInstalledApps()
        if (UpdateChecker.autoCheckDue(application)) checkForUpdate()
    }

    /** Asks GitHub for the latest release. Safe to call repeatedly; ignores taps while busy. */
    fun checkForUpdate() {
        if (_updateState.value == UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.fetch(currentVersion) }
            if (result !is UpdateState.Failed) {
                UpdateChecker.markChecked(getApplication())
            }
            _updateState.value = result
            when (result) {
                is UpdateState.Available ->
                    engine.log(LogLevel.INFO, "Update", "New version ${result.latest} available (installed ${result.current})")
                is UpdateState.Failed ->
                    engine.log(LogLevel.WARN, "Update", "Update check failed: ${result.reason}")
                else -> {}
            }
        }
    }

    fun setAutoUpdateCheck(on: Boolean) {
        UpdateChecker.setAutoCheck(getApplication(), on)
        _autoUpdateCheck.value = on
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        val ctx = getApplication<Application>()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            engine.log(LogLevel.WARN, "Update", "No browser available to open $url")
        }
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
        bgOpacityPct: Int? = null,
        isLandscape: Boolean = false
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
                                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100)
                            ).withOverlayScalePct(
                                landscape = isLandscape,
                                compactPct = compactScalePct,
                                expandedPct = expandedScalePct
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
                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100)
            ).withOverlayScalePct(
                landscape = isLandscape,
                compactPct = compactScalePct,
                expandedPct = expandedScalePct
            )
        }
    }

    fun updateOverlayPosition(xPm: Int?, yPm: Int?, reset: Boolean = false) {
        val landscape = getApplication<Application>().resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        engine.updateConfig { cfg ->
            if (reset) {
                cfg.copy(
                    overlayCompactXPm = 40, overlayCompactYPm = 60,
                    overlayExpandedXPm = 40, overlayExpandedYPm = 60,
                    overlayDetailedXPm = 40, overlayDetailedYPm = 60,
                    overlayCompactXPmL = 40, overlayCompactYPmL = 60,
                    overlayExpandedXPmL = 40, overlayExpandedYPmL = 60,
                    overlayDetailedXPmL = 40, overlayDetailedYPmL = 60
                )
            } else {
                cfg.withOverlayPosPm(landscape, xPm, yPm)
            }
        }
    }

    fun updateSensorPin(
        enabled: Boolean? = null,
        rate: SensorPinner.Rate? = null,
        toggleType: Int? = null,
        pauseWhenScreenOff: Boolean? = null
    ) {
        engine.sensorPinner.setConfig { cfg ->
            val types = if (toggleType == null) {
                cfg.sensorTypes
            } else if (toggleType in cfg.sensorTypes) {
                // Keep at least one sensor selected.
                if (cfg.sensorTypes.size > 1) cfg.sensorTypes - toggleType else cfg.sensorTypes
            } else {
                cfg.sensorTypes + toggleType
            }
            cfg.copy(
                enabled = enabled ?: cfg.enabled,
                rate = rate ?: cfg.rate,
                sensorTypes = types,
                pauseWhenScreenOff = pauseWhenScreenOff ?: cfg.pauseWhenScreenOff
            )
        }
    }

    fun updateRamPin(enabled: Boolean? = null, gameOnly: Boolean? = null) {
        engine.updateConfig { cfg ->
            cfg.copy(
                ramPinMax = enabled ?: cfg.ramPinMax,
                ramPinGameOnly = gameOnly ?: cfg.ramPinGameOnly
            )
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
        hysteresisC: Float? = null,
        chargingAware: Boolean? = null,
        chargingStartOffsetC: Float? = null
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
                    hysteresisC = hysteresisC ?: t.hysteresisC,
                    chargingAware = chargingAware ?: t.chargingAware,
                    chargingStartOffsetC = (chargingStartOffsetC ?: t.chargingStartOffsetC).coerceIn(0f, 5f)
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
        polarity: PowerPolarity? = null,
        componentAware: Boolean? = null
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
                    polarity = polarity ?: p.polarity,
                    componentAware = componentAware ?: p.componentAware
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

    fun setEnergyTabVisible(visible: Boolean) {
        engine.powerModel.setUiVisible(visible)
        engine.batteryEstimator.setUiVisible(visible)
        engine.setSmartUiVisible(visible)
    }

    // v2.7.0 Smart app profiles / Adaptive refresh rate
    val appDemandRows = engine.appDemand.rows
    val appControls = engine.appDemand.controls
    val refreshState = engine.refreshCtl.state

    fun setSmartEnabled(on: Boolean) = engine.updateConfig { it.copy(smartAppProfilesEnabled = on) }
    fun setSmartMode(m: com.example.model.SmartAppMode) = engine.updateConfig { it.copy(smartAppMode = m) }
    fun smartSetControl(pkg: String, c: com.example.governor.AppControl, o: com.example.governor.AppOverride) =
        engine.appDemand.setControl(pkg, c, o)
    fun smartResetApp(pkg: String) = engine.appDemand.resetApp(pkg)
    fun setAdaptiveRefresh(on: Boolean) = engine.updateConfig { it.copy(adaptiveRefreshEnabled = on) }
    fun setAdaptiveHz(idle: Int? = null, active: Int? = null, delayMs: Int? = null) = engine.updateConfig {
        it.copy(
            adaptiveIdleHz = idle ?: it.adaptiveIdleHz,
            adaptiveActiveHz = active ?: it.adaptiveActiveHz,
            adaptiveIdleDelayMs = delayMs ?: it.adaptiveIdleDelayMs
        )
    }
    fun testRefreshSwitch() = engine.refreshCtl.testSwitch()

    fun updateBatterySettings(
        estimationEnabled: Boolean? = null,
        designCapacityOverrideMah: Int? = null
    ) {
        engine.updateConfig { cfg ->
            cfg.copy(
                batteryEstimationEnabled = estimationEnabled ?: cfg.batteryEstimationEnabled,
                batteryDesignCapacityOverrideMah = (designCapacityOverrideMah ?: cfg.batteryDesignCapacityOverrideMah).coerceAtLeast(0)
            )
        }
    }

    fun resetBatteryEstimate() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.batteryEstimator.resetEstimate()
        }
    }

    fun updateEfficiencySettings(
        learningEnabled: Boolean? = null,
        efficiencyEnabled: Boolean? = null,
        efficiencyMode: EfficiencyMode? = null,
        minConfidencePct: Int? = null,
        explorationRate: ExplorationRate? = null
    ) {
        engine.updateConfig { cfg ->
            cfg.copy(
                powerModelLearningEnabled = learningEnabled ?: cfg.powerModelLearningEnabled,
                efficiencyLearningEnabled = efficiencyEnabled ?: cfg.efficiencyLearningEnabled,
                efficiencyMode = efficiencyMode ?: cfg.efficiencyMode,
                minConfidencePct = (minConfidencePct ?: cfg.minConfidencePct).coerceIn(20, 95),
                explorationRate = explorationRate ?: cfg.explorationRate
            )
        }
    }

    fun resetPowerModel() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.powerModel.reset()
        }
    }

    fun exportPowerModelJson(): String = engine.powerModel.exportJson()

    fun selectSession(info: SessionInfo?) {
        _selectedSession.value = info
        _selectedSamples.value = emptyList()
        if (info == null) return
        viewModelScope.launch {
            val samples = engine.metrics.loadSession(info)
            // Ignore the result if another session was picked while loading.
            if (_selectedSession.value?.fileName == info.fileName) _selectedSamples.value = samples
        }
    }

    fun deleteSession(info: SessionInfo) {
        if (_selectedSession.value?.fileName == info.fileName) selectSession(null)
        viewModelScope.launch { engine.metrics.deleteSession(info) }
    }

    fun deleteAllSessions() {
        selectSession(null)
        viewModelScope.launch { engine.metrics.deleteAllSessions() }
    }

    fun clearLiveMetrics() = engine.metrics.clearLive()

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
