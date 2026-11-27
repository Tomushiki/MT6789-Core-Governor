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
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.governor.CulpritTracker
import com.example.governor.ProcRow
import com.example.governor.ProcessMonitor
import com.example.governor.ProtectedPackages
import com.example.governor.RatingAgg
import com.example.governor.RatingModel
import com.example.governor.RatingResult
import com.example.governor.Standby
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.example.CoreGovernorApp
import com.example.R
import com.example.data.LocaleHelper
import com.example.governor.BatteryEstimatorUiSnapshot
import com.example.governor.GameThreadHistoryLogic
import com.example.governor.GameThreadInspectionResult
import com.example.governor.GameThreadLogic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.governor.AutoTuneLogic
import com.example.governor.MetricSample
import com.example.governor.PowerModelUiSnapshot
import com.example.governor.SensorPinner
import com.example.governor.SessionInfo
import com.example.governor.UpdateChecker
import com.example.governor.UpdateState
import com.example.model.AutoTuneMode
import com.example.model.AutoTuneParam
import com.example.model.CompositeScoreWeights
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.InstalledAppInfo
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.ModeOverride
import com.example.model.PerGameProfile
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.PresetItem
import com.example.model.ThreadAffinityMapping
import com.example.model.ThrottleCurveShape
import com.example.model.withOverlayPosPm
import com.example.model.withBoostStrengthFor
import com.example.model.withOverlayBgOpacityFor
import com.example.model.withOverlayTextScaleFor
import com.example.model.withOverlayPosPmFor
import com.example.model.withOverlayPositionResetFor
import com.example.model.withOverlayScalePctFor
import com.example.service.OverlayStyle
import com.example.model.withOverlayPositionsReset
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

    private fun lctx(): Context = LocaleHelper.wrap(getApplication())

    val hardwareSpec = engine.hardwareSpec
    val config = engine.config
    val presets = engine.presets
    val telemetry = engine.telemetry

    // Low-rate views of telemetry so the top bar and similar do not recompose on every tick.
    val activeMode: StateFlow<GovernorMode> = telemetry
        .map { it.activeMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), telemetry.value.activeMode)
    val isRunning: StateFlow<Boolean> = telemetry
        .map { it.isRunning }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), telemetry.value.isRunning)
    val sensorPin = engine.sensorPinner.state
    val protectionStatus: StateFlow<com.example.governor.ProtectionStatus> = engine.processProtection.status
    val logs: StateFlow<List<LogEntry>> = engine.logs
    val powerModelSnapshot: StateFlow<PowerModelUiSnapshot> = engine.powerModel.uiSnapshot
    val batterySnapshot: StateFlow<BatteryEstimatorUiSnapshot> = engine.batteryEstimator.snapshot
    val batteryHistory: StateFlow<List<com.example.governor.BatteryHistoryPoint>> = engine.batteryHistory.history
    fun clearBatteryHistory() = engine.batteryHistory.clear()
    val stutterHunterStatus: StateFlow<com.example.governor.StutterHunterStatus> =
        com.example.governor.StutterHunterRunner.status

    // Performance Metrics tab
    val metricsLive: StateFlow<List<MetricSample>> = engine.metrics.live
    val metricsSessions: StateFlow<List<SessionInfo>> = engine.metrics.sessions
    val metricsRecording: StateFlow<Boolean> = engine.metrics.recording

    private val _selectedSession = MutableStateFlow<SessionInfo?>(null)
    val selectedSession: StateFlow<SessionInfo?> = _selectedSession.asStateFlow()

    private val _selectedSamples = MutableStateFlow<List<MetricSample>>(emptyList())
    val selectedSamples: StateFlow<List<MetricSample>> = _selectedSamples.asStateFlow()

    private val _comparisonSamples = MutableStateFlow<Map<String, List<MetricSample>>>(emptyMap())
    val comparisonSamples: StateFlow<Map<String, List<MetricSample>>> = _comparisonSamples.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _usageStatsGranted = MutableStateFlow(engine.hasUsageStatsPermission())
    val usageStatsGranted: StateFlow<Boolean> = _usageStatsGranted.asStateFlow()

    private val _gameThreadInspection = MutableStateFlow(
        GameThreadInspectionResult(
            availableCpuCount = GameThreadLogic.availableCpuCount(hardwareSpec.value)
        )
    )
    val gameThreadInspection: StateFlow<GameThreadInspectionResult> = _gameThreadInspection.asStateFlow()

    private val _isRefreshingGameThreads = MutableStateFlow(false)
    val isRefreshingGameThreads: StateFlow<Boolean> = _isRefreshingGameThreads.asStateFlow()
    private val gameThreadRefreshMutex = Mutex()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _autoUpdateCheck = MutableStateFlow(
        UpdateChecker.autoCheckEnabled(application)
    )
    val autoUpdateCheck: StateFlow<Boolean> = _autoUpdateCheck.asStateFlow()

    private val _includePrereleases = MutableStateFlow(
        UpdateChecker.includePrereleases(application)
    )
    val includePrereleases: StateFlow<Boolean> = _includePrereleases.asStateFlow()

    private val _dismissedUpdateVersion = MutableStateFlow(
        UpdateChecker.dismissedVersion(application)
    )
    val dismissedUpdateVersion: StateFlow<String?> = _dismissedUpdateVersion.asStateFlow()

    val currentVersion: String = com.example.BuildConfig.VERSION_NAME

    init {
        viewModelScope.launch {
            engine.discoverHardware(forceRefresh = false)
        }
        loadInstalledApps()
        if (UpdateChecker.autoCheckDue(application)) checkForUpdate()
    }

    /** Asks GitHub for releases with ETag & rate-limit caching. Safe to call repeatedly; ignores taps while busy. */
    fun checkForUpdate() {
        if (_updateState.value == UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.fetch(getApplication(), currentVersion)
            }
            _updateState.value = result
            when (result) {
                is UpdateState.Available ->
                    engine.log(LogLevel.INFO, "Update", "New version ${result.latest} available (installed ${result.current})")
                is UpdateState.Failed ->
                    engine.log(LogLevel.WARN, "Update", "Update check failed: ${result.reason}")
                else -> Unit
            }
        }
    }

    fun setAutoUpdateCheck(on: Boolean) {
        UpdateChecker.setAutoCheck(getApplication(), on)
        _autoUpdateCheck.value = on
    }

    fun setIncludePrereleases(on: Boolean) {
        val ctx = getApplication<Application>()
        UpdateChecker.setIncludePrereleases(ctx, on)
        _includePrereleases.value = on
        val cached = UpdateChecker.evaluateFromCache(ctx, currentVersion)
        if (cached != null) {
            _updateState.value = cached
        } else {
            checkForUpdate()
        }
    }

    fun dismissUpdate(version: String) {
        UpdateChecker.setDismissedVersion(getApplication(), version)
        _dismissedUpdateVersion.value = version
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        val ctx = getApplication<Application>()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: android.content.ActivityNotFoundException) {
            engine.log(LogLevel.WARN, "Update", "No browser available to open $url")
        } catch (_: Exception) {
            engine.log(LogLevel.WARN, "Update", "Could not open $url")
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

    fun updateAdaptivePolling(enabled: Boolean) {
        engine.updateConfig { it.copy(adaptivePollingEnabled = enabled) }
    }

    fun updateSafetyFloor(pct: Float) {
        engine.updateConfig { it.copy(safetyFloorPct = pct.coerceIn(5f, 95f)) }
    }

    fun updateStartOnBoot(enabled: Boolean) {
        engine.updateConfig { it.copy(startOnBoot = enabled) }
    }

    fun setProtectFromKill(enabled: Boolean) = engine.setProtectFromKill(enabled)
    fun reverifyProtection() = engine.reverifyProtection()

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
                                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100),
                                // The main slider sets every style; the per-style sliders then fine-tune one.
                                overlayCompactBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayCompactBgOpacityPct,
                                overlayExpandedBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayExpandedBgOpacityPct,
                                overlayDetailedBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayDetailedBgOpacityPct
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
                overlayBgOpacityPct = (bgOpacityPct ?: cfg.overlayBgOpacityPct).coerceIn(0, 100),
                                // The main slider sets every style; the per-style sliders then fine-tune one.
                                overlayCompactBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayCompactBgOpacityPct,
                                overlayExpandedBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayExpandedBgOpacityPct,
                                overlayDetailedBgOpacityPct = if (bgOpacityPct != null) -1 else cfg.overlayDetailedBgOpacityPct
            ).withOverlayScalePct(
                landscape = isLandscape,
                compactPct = compactScalePct,
                expandedPct = expandedScalePct
            )
        }
    }

    fun updateOverlayPosition(xPm: Int?, yPm: Int?, reset: Boolean = false, isLandscape: Boolean = false) {
        // Orientation comes from the screen the user is looking at (same as the size sliders).
        val landscape = isLandscape
        engine.updateConfig { cfg ->
            if (reset) {
                cfg.withOverlayPositionsReset()
            } else {
                cfg.withOverlayPosPm(landscape, xPm, yPm)
            }
        }
    }

    /** Tunes one overlay style (size, position and/or background opacity) without it having to be the active one. */
    fun updateOverlayStyle(
        style: OverlayStyle,
        isLandscape: Boolean,
        scalePct: Int?,
        xPm: Int?,
        yPm: Int?,
        bgOpacityPct: Int?,
        reset: Boolean
    ) {
        engine.updateConfig { cfg ->
            var next = cfg
            if (reset) next = next.withOverlayPositionResetFor(style)
            if (scalePct != null) next = next.withOverlayScalePctFor(style, isLandscape, scalePct)
            if (xPm != null || yPm != null) next = next.withOverlayPosPmFor(style, isLandscape, xPm, yPm)
            if (bgOpacityPct != null) next = next.withOverlayBgOpacityFor(style, bgOpacityPct)
            next
        }
    }

    /** v3.8.0: text size of one overlay style (70..160 %). */
    fun setOverlayTextScale(style: OverlayStyle, pct: Int) {
        engine.updateConfig { cfg -> cfg.withOverlayTextScaleFor(style, pct) }
    }

    fun updateSensorPin(
        enabled: Boolean? = null,
        rate: SensorPinner.Rate? = null,
        toggleType: Int? = null,
        pauseWhenScreenOff: Boolean? = null,
        gameOnly: Boolean? = null
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
                pauseWhenScreenOff = pauseWhenScreenOff ?: cfg.pauseWhenScreenOff,
                gameOnly = gameOnly ?: cfg.gameOnly
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

    fun setSmoothMinConfidence(pct: Int) {
        engine.updateConfig { cfg -> cfg.copy(smoothMinConfidencePct = pct.coerceIn(10, 95)) }
    }

    fun setSmoothSliceMs(ms: Int) {
        engine.updateConfig { cfg -> cfg.copy(smoothSliceMs = ms.coerceIn(30, 400)) }
    }

    fun setSmoothFixDrift(enabled: Boolean) {
        engine.updateConfig { cfg -> cfg.copy(smoothFixDrift = enabled) }
    }

    fun setSmoothSliceAuto(enabled: Boolean) {
        // Switching learning on starts from scratch (forgets earlier results); switching it off keeps nothing in use.
        engine.updateConfig { cfg ->
            cfg.copy(smoothSliceAuto = enabled, smoothLearnedSlices = if (enabled) "" else cfg.smoothLearnedSlices)
        }
    }

    fun setSmoothPowerLimit(enabled: Boolean) {
        engine.updateConfig { cfg ->
            cfg.copy(powerThrottle = cfg.powerThrottle.copy(smoothPowerLimit = enabled))
        }
    }

    fun updateAfterburn(
        enabled: Boolean? = null,
        strength: com.example.model.BoostStrength? = null,
        thermalConditionEnabled: Boolean? = null,
        thermalMarginC: Float? = null,
        batteryConditionEnabled: Boolean? = null,
        batteryThresholdPct: Int? = null,
        voltageSagEnabled: Boolean? = null,
        toastEnabled: Boolean? = null
    ) {
        engine.updateConfig { cfg ->
            val b = cfg.afterburn
            cfg.copy(
                afterburn = b.copy(
                    enabled = enabled ?: b.enabled,
                    strength = strength ?: b.strength,
                    thermalConditionEnabled = thermalConditionEnabled ?: b.thermalConditionEnabled,
                    thermalMarginC = (thermalMarginC ?: b.thermalMarginC).coerceIn(3f, 8f),
                    batteryConditionEnabled = batteryConditionEnabled ?: b.batteryConditionEnabled,
                    batteryThresholdPct = (batteryThresholdPct ?: b.batteryThresholdPct).coerceIn(10, 90),
                    voltageSagEnabled = voltageSagEnabled ?: b.voltageSagEnabled,
                    toastEnabled = toastEnabled ?: b.toastEnabled
                )
            )
        }
    }

    fun updateStutterHunter(
        enabled: Boolean? = null,
        mode: com.example.model.StutterHunterMode? = null,
        dipPct: Int? = null,
        cpuThresholdPct: Int? = null
    ) {
        engine.updateConfig { cfg ->
            val h = cfg.stutterHunter
            cfg.copy(
                stutterHunter = h.copy(
                    enabled = enabled ?: h.enabled,
                    mode = mode ?: h.mode,
                    dipPct = (dipPct ?: h.dipPct).coerceIn(60, 90),
                    cpuThresholdPct = (cpuThresholdPct ?: h.cpuThresholdPct).coerceIn(10, 40)
                )
            )
        }
    }

    fun toggleHunterWhitelist(packageName: String) {
        engine.updateConfig { cfg ->
            val h = cfg.stutterHunter
            val next = h.whitelist.toMutableSet()
            if (packageName in next) next.remove(packageName) else next.add(packageName)
            cfg.copy(stutterHunter = h.copy(whitelist = next))
        }
    }

    fun toggleBoostPackage(packageName: String) {
        engine.updateConfig { cfg ->
            val next = cfg.boostPackages.toMutableSet()
            val removing = packageName in next
            if (removing) next.remove(packageName) else next.add(packageName)
            val updated = cfg.copy(boostPackages = next)
            // A removed app also forgets its own strength.
            if (removing) updated.withBoostStrengthFor(packageName, null) else updated
        }
    }

    /** Sets one boosted app's Afterburn strength; null makes it follow the main strength again. */
    fun setBoostStrength(packageName: String, strength: com.example.model.BoostStrength?) {
        engine.updateConfig { cfg -> cfg.withBoostStrengthFor(packageName, strength) }
    }

    fun toggleGamePackage(packageName: String) {
        engine.updateConfig { cfg ->
            val next = cfg.gamePackages.toMutableSet()
            if (packageName in next) next.remove(packageName) else next.add(packageName)
            cfg.copy(gamePackages = next)
        }
    }

    fun refreshGameThreads() {
        viewModelScope.launch {
            if (gameThreadRefreshMutex.isLocked) return@launch
            gameThreadRefreshMutex.withLock {
                _isRefreshingGameThreads.value = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        engine.inspectForegroundGameThreads()
                    }
                    _gameThreadInspection.value = result
                } finally {
                    _isRefreshingGameThreads.value = false
                }
            }
        }
    }

    fun setGameThreadEnabled(enabled: Boolean) {
        engine.updateConfig { cfg ->
            cfg.copy(gameThreadController = cfg.gameThreadController.copy(enabled = enabled))
        }
    }

    fun setGameThreadAutoClassify(enabled: Boolean) {
        engine.updateConfig { cfg ->
            cfg.copy(gameThreadController = cfg.gameThreadController.copy(autoClassify = enabled))
        }
    }

    fun setGameThreadSelectedGame(packageName: String) {
        val clean = packageName.trim()
        engine.updateConfig { cfg ->
            cfg.copy(gameThreadController = cfg.gameThreadController.copy(selectedGamePackage = clean))
        }
        refreshGameThreads()
    }

    fun removeHistoricalThread(packageName: String, threadName: String) {
        engine.updateConfig { cfg ->
            val updated = GameThreadHistoryLogic.removeHistoricalThread(
                config = cfg.gameThreadController,
                packageName = packageName,
                threadName = threadName
            )
            if (updated == cfg.gameThreadController) cfg else cfg.copy(gameThreadController = updated)
        }
    }

    fun clearGameThreadHistory(packageName: String) {
        engine.updateConfig { cfg ->
            val updated = GameThreadHistoryLogic.clearGameHistory(
                config = cfg.gameThreadController,
                packageName = packageName
            )
            if (updated == cfg.gameThreadController) cfg else cfg.copy(gameThreadController = updated)
        }
    }

    fun clearAllThreadHistory() {
        engine.updateConfig { cfg ->
            val updated = GameThreadHistoryLogic.clearAllHistory(cfg.gameThreadController)
            if (updated == cfg.gameThreadController) cfg else cfg.copy(gameThreadController = updated)
        }
    }

    fun upsertThreadMappings(newMappings: List<ThreadAffinityMapping>) {
        if (newMappings.isEmpty()) return
        val cpuCount = GameThreadLogic.availableCpuCount(hardwareSpec.value)
        engine.updateConfig { cfg ->
            val gtc = cfg.gameThreadController
            val updated = gtc.mappings.toMutableList()
            for (raw in newMappings) {
                val pattern = raw.pattern.trim()
                val mask = GameThreadLogic.sanitizeHexMask(raw.cpuMask, cpuCount) ?: continue
                if (pattern.isEmpty()) continue
                val clean = raw.copy(
                    pattern = pattern,
                    cpuMask = mask,
                    packageName = raw.packageName.trim()
                )
                val existingIdx = updated.indexOfFirst {
                    it.pattern == clean.pattern &&
                        it.exactMatch == clean.exactMatch &&
                        it.packageName == clean.packageName
                }
                if (existingIdx >= 0) {
                    updated[existingIdx] = clean
                } else {
                    updated.add(clean)
                }
            }
            cfg.copy(gameThreadController = gtc.copy(mappings = updated))
        }
    }

    fun updateThreadMapping(index: Int, mapping: ThreadAffinityMapping) {
        val cpuCount = GameThreadLogic.availableCpuCount(hardwareSpec.value)
        val pattern = mapping.pattern.trim()
        val mask = GameThreadLogic.sanitizeHexMask(mapping.cpuMask, cpuCount) ?: return
        if (pattern.isEmpty()) return
        val clean = mapping.copy(
            pattern = pattern,
            cpuMask = mask,
            packageName = mapping.packageName.trim()
        )
        engine.updateConfig { cfg ->
            val gtc = cfg.gameThreadController
            if (index !in gtc.mappings.indices) return@updateConfig cfg
            val updated = gtc.mappings.toMutableList()
            updated[index] = clean
            cfg.copy(gameThreadController = gtc.copy(mappings = updated))
        }
    }

    fun removeThreadMapping(index: Int) {
        engine.updateConfig { cfg ->
            val gtc = cfg.gameThreadController
            if (index !in gtc.mappings.indices) return@updateConfig cfg
            val updated = gtc.mappings.toMutableList()
            updated.removeAt(index)
            cfg.copy(gameThreadController = gtc.copy(mappings = updated))
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

    /** The live metrics list is only built while the Metrics tab is on screen. */
    fun setMetricsTabVisible(visible: Boolean) {
        engine.metrics.setLiveVisible(visible)
    }

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

    // v2.7.1 Screen-off analysis
    val screenOffSnapshot = engine.screenOff.snapshot
    fun setScreenOffEnabled(on: Boolean) = engine.updateConfig { it.copy(screenOffAnalysisEnabled = on) }
    fun resetScreenOff() = engine.screenOff.resetAll()

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

    fun loadComparisonSamples(sessions: List<SessionInfo>) {
        if (sessions.isEmpty()) {
            _comparisonSamples.value = emptyMap()
            return
        }
        val names = sessions.map { it.fileName }.toSet()
        viewModelScope.launch {
            val current = _comparisonSamples.value
            val next = HashMap<String, List<MetricSample>>(sessions.size)
            for (info in sessions) {
                val cached = current[info.fileName]
                next[info.fileName] = cached ?: engine.metrics.loadSession(info)
            }
            _comparisonSamples.value = next.filterKeys { it in names }
        }
    }

    fun toggleSessionKept(info: SessionInfo) {
        val nextKept = !info.kept
        if (_selectedSession.value?.fileName == info.fileName) {
            _selectedSession.value = _selectedSession.value?.copy(kept = nextKept)
        }
        viewModelScope.launch { engine.metrics.setSessionKept(info, nextKept) }
    }

    fun deleteSession(info: SessionInfo) {
        if (_selectedSession.value?.fileName == info.fileName) selectSession(null)
        viewModelScope.launch { engine.metrics.deleteSession(info) }
    }

    fun deleteAllSessions(includeKept: Boolean = false) {
        if (includeKept || _selectedSession.value?.kept != true) selectSession(null)
        viewModelScope.launch { engine.metrics.deleteAllSessions(includeKept) }
    }

    fun updateScoreWeights(weights: CompositeScoreWeights) {
        engine.updateConfig(markMidSession = false) { it.copy(scoreWeights = weights) }
    }

    fun setPerGameProfile(pkg: String, profile: PerGameProfile?) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        engine.updateConfig { cfg ->
            val next = cfg.perGameProfiles.toMutableMap()
            if (profile == null || profile.isEmpty) {
                next.remove(clean)
            } else {
                next[clean] = profile
            }
            cfg.copy(perGameProfiles = next)
        }
    }

    fun setAutoTuneEnabled(pkg: String, enabled: Boolean) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.setEnabled(it, pkg, enabled) }
    }

    fun setAutoTuneMode(pkg: String, mode: AutoTuneMode) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.setMode(it, pkg, mode) }
    }

    fun setAutoTuneMinMinutes(pkg: String, minutes: Int) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.setMinSessionMinutes(it, pkg, minutes) }
    }

    fun setAutoTunePaused(pkg: String, paused: Boolean) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.setPaused(it, pkg, paused) }
    }

    fun toggleAutoTuneLock(pkg: String, param: AutoTuneParam) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.toggleLockParam(it, pkg, param) }
    }

    fun applyAutoTuneSuggestion(pkg: String) {
        engine.updateConfig { AutoTuneLogic.applySuggestion(it, pkg) }
    }

    fun resetAutoTuneToBaseline(pkg: String) {
        engine.updateConfig { AutoTuneLogic.resetToBaseline(it, pkg) }
    }

    fun revertAutoTuneToUserSettings(pkg: String) {
        engine.updateConfig { AutoTuneLogic.revertToUserSettings(it, pkg) }
    }

    fun retuneAutoTune(pkg: String) {
        engine.updateConfig(markMidSession = false) { AutoTuneLogic.retune(it, pkg) }
    }

    fun clearLiveMetrics() = engine.metrics.clearLive()

    /** Copies a saved session's CSV file to a location the user picked. */
    fun exportSessionCsv(info: SessionInfo, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    engine.metrics.sessionFile(info).inputStream().use { it.copyTo(out) }
                }
                engine.log(LogLevel.INFO, "Metrics", "Saved session CSV to the chosen file.")
            } catch (e: Exception) {
                engine.log(LogLevel.WARN, "Metrics", "Could not save the CSV: ${e.message}")
            }
        }
    }

    // ---- Device rating ----

    val deviceRating: StateFlow<RatingResult> = combine(
        engine.rating.snapshot,
        engine.screenOff.snapshot
    ) { agg, off ->
        val standby = if (off.hasData && off.offPctPerHWeek >= 0f) Standby(off.offPctPerHWeek, off.deepSleepPctWeek) else null
        RatingModel.compute(agg, standby)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RatingModel.compute(RatingAgg(), null))

    fun resetRating() = engine.rating.reset()

    // ---- System tab: process monitor ----

    private val _systemRows = MutableStateFlow<List<ProcRow>>(emptyList())
    val systemRows: StateFlow<List<ProcRow>> = _systemRows.asStateFlow()
    private val _systemError = MutableStateFlow<String?>(null)
    val systemError: StateFlow<String?> = _systemError.asStateFlow()
    private val _systemNote = MutableStateFlow<String?>(null)
    val systemNote: StateFlow<String?> = _systemNote.asStateFlow()
    private val _culpritThreshold = MutableStateFlow(15f)
    val culpritThreshold: StateFlow<Float> = _culpritThreshold.asStateFlow()

    private var systemJob: Job? = null
    private var prevTicks: Map<Int, Long>? = null
    private var prevMs = 0L
    private val tracker = CulpritTracker()

    fun setCulpritThreshold(pct: Float) { _culpritThreshold.value = pct }
    fun dismissSystemNote() { _systemNote.value = null }

    @Volatile
    private var appInForeground = true

    /** Called from the activity lifecycle (started = true, stopped = false). */
    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    private fun canSampleProcesses(): Boolean {
        if (!appInForeground) return false
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    /** Sampling runs only while the System tab is on screen, the app is in front and the screen is on. */
    fun setSystemTabVisible(visible: Boolean) {
        if (visible) {
            if (systemJob?.isActive == true) return
            systemJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (!canSampleProcesses()) {
                        // Paused: drop the old reading so a long gap is not averaged into the next one.
                        if (prevTicks != null) {
                            prevTicks = null
                            tracker.clear()
                        }
                        delay(PROCESS_PAUSED_CHECK_MS)
                        continue
                    }
                    val first = prevTicks == null
                    sampleProcesses()
                    // The first sample is immediate and the next follows quickly, so CPU % shows up fast.
                    delay(if (first) PROCESS_FIRST_GAP_MS else PROCESS_SAMPLE_MS)
                }
            }
        } else {
            systemJob?.cancel()
            systemJob = null
            prevTicks = null
            tracker.clear()
        }
    }

    private suspend fun sampleProcesses() {
        try {
            engine.scanShell.ensureSession()
            val r = engine.scanShell.exec(ProcessMonitor.COMMAND)
            val now = System.currentTimeMillis()
            val procs = ProcessMonitor.parse(r.stdout)
            if (procs.isEmpty()) {
                _systemError.value = lctx().getString(R.string.system_err_no_root)
                return
            }
            _systemError.value = null
            val prev = prevTicks
            val rows = ProcessMonitor.group(
                procs, prev, if (prev == null) 0L else now - prevMs,
                engine.telemetry.value.foregroundPackage, protectedPackages()
            )
            prevTicks = procs.associate { it.pid to it.ticks }
            prevMs = now
            _systemRows.value = tracker.update(rows, _culpritThreshold.value)
        } catch (e: Exception) {
            _systemError.value = lctx().getString(R.string.system_err_failed, e.message ?: "")
        }
    }

    private fun protectedPackages(): Set<String> = ProtectedPackages.resolve(getApplication())

    /** Stops an app with root. Only real, unprotected, non-foreground packages are allowed. */
    fun killApp(row: ProcRow) {
        if (!row.killable) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = engine.rootShell.exec("am force-stop ${row.key}")
            val ok = r.isSuccess
            _systemNote.value = if (ok) {
                lctx().getString(R.string.system_note_stopped, row.key)
            } else {
                lctx().getString(R.string.system_note_stop_failed, row.key)
            }
            engine.log(if (ok) LogLevel.INFO else LogLevel.WARN, "System", "Stop ${row.key}: ${if (ok) "ok" else r.error ?: "failed"}")
        }
    }

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

/** System tab process sampling: normal gap (v2.7.4: 8 s, was 4 s), quick second sample, and pause re-check. */
private const val PROCESS_SAMPLE_MS = 8_000L
private const val PROCESS_FIRST_GAP_MS = 3_000L
private const val PROCESS_PAUSED_CHECK_MS = 2_000L
