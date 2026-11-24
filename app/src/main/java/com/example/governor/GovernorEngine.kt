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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.example.data.ConfigStorage
import com.example.data.OriginalCpuBackup
import com.example.data.OriginalHardwareBackup
import com.example.model.BatteryState
import com.example.model.ClusterLiveState
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.GpuLiveState
import com.example.model.HardwareSpec
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.ModeOverride
import com.example.model.PresetItem
import com.example.model.TelemetrySample
import com.example.model.TelemetryState
import com.example.root.RootShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Single authoritative governor controller for CoreGovernor v2.
 * GPU control operates solely via real OPP indexes written to /proc/gpufreqv2/fix_target_opp_index.
 */
class GovernorEngine(context: Context) {

    private val appContext = context.applicationContext
    val storage = ConfigStorage(appContext)
    val rootShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell", msg) }
    }
    private val discovery = HardwareDiscovery(rootShell)
    private val foregroundDetector = ForegroundDetector(appContext, rootShell)

    /** Sensor pinning (Nifty features). Own settings; runs only while the governor is running. */
    val sensorPinner = SensorPinner(appContext) { level, tag, msg -> log(level, tag, msg) }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controlJob: Job? = null

    /** Live and saved history for the Performance Metrics tab. */
    val metrics = MetricsRecorder(java.io.File(appContext.filesDir, "metrics_sessions"), engineScope) { level, tag, msg ->
        log(level, tag, msg)
    }

    /** Overall device rating totals (rating.json). */
    val rating = RatingStore(java.io.File(appContext.filesDir, "rating.json"), engineScope) { level, tag, msg ->
        log(level, tag, msg)
    }

    init {
        metrics.onSample = { s, inGame ->
            rating.add(s.fps, s.watts, s.tempC, s.cpuLoadPct, inGame, s.batteryState.isPluggedOrCharging)
        }
    }

    /** On-device learned power model & opt-in efficiency governor helper. */
    val powerModel = PowerModel(java.io.File(appContext.filesDir, "power_model.json")) { level, tag, msg ->
        log(level, tag, msg)
    }

    /** Long-term battery capacity estimator & charge/discharge energy integrator. */
    val batteryEstimator = BatteryEstimator(java.io.File(appContext.filesDir, "battery_model.json"), engineScope) { level, tag, msg ->
        log(level, tag, msg)
    }

    /** v2.7.0 Smart app profiles: per-app learned demand + ceilings/floors. */
    val appDemand = AppDemandModel(java.io.File(appContext.filesDir, "app_profiles.json"), engineScope, { l, t, m -> log(l, t, m) }) { pkg ->
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) appContext.packageManager.getApplicationInfo(pkg, 0).category else -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** v2.7.0 Shared touch interaction signal (smart profiles + adaptive refresh). */
    val interaction = InteractionSignal(rootShell, engineScope) { l, t, m -> log(l, t, m) }

    /** v2.7.1 Screen-off power analysis. */
    val screenOff = ScreenOffAnalyzer(appContext, rootShell, engineScope, { _config.value.screenOffAnalysisEnabled }) { l, t, m -> log(l, t, m) }

    /** v2.7.0 Adaptive refresh rate. */
    val refreshCtl = RefreshRateController(
        context = appContext,
        rootShell = rootShell,
        scope = engineScope,
        log = { l, t, m -> log(l, t, m) },
        interaction = interaction,
        cfgProvider = { _config.value },
        fpsProvider = { _telemetry.value.fps },
        gameProvider = { _telemetry.value.activeMode == GovernorMode.GAME },
        screenOnProvider = { isScreenInteractive && !isSleepSaving },
        onAutoDisable = { updateConfig { it.copy(adaptiveRefreshEnabled = false) } }
    )
    private val smartLoad = FloatArray(4)
    private val smartRatio = FloatArray(4)
    private var smartWasOn = false

    fun setSmartUiVisible(visible: Boolean) {
        appDemand.uiActive = visible
        refreshCtl.uiActive = visible
    }

    private val _hardwareSpec = MutableStateFlow(HardwareSpec())
    val hardwareSpec: StateFlow<HardwareSpec> = _hardwareSpec.asStateFlow()

    private val _config = MutableStateFlow(storage.loadConfig())
    val config: StateFlow<GovernorConfig> = _config.asStateFlow()

    private val _presets = MutableStateFlow<List<PresetItem>>(emptyList())
    val presets: StateFlow<List<PresetItem>> = _presets.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState(modeOverride = _config.value.modeOverride))
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val logIdSeq = AtomicLong(1L)

    // Loop internal state
    private var prevCpuTicks: Map<Int, CpuTickSnapshot> = emptyMap()
    private val prevRawClusterLoad = mutableMapOf<Int, Float>()
    private val smoothedClusterLoad = mutableMapOf<Int, Float>()
    private val smoothedCoreLoad = mutableMapOf<Int, Float>()
    private var smoothedTotalCpuLoad = 0f
    private var smoothedGpuLoad = 0f

    private data class PolicyPinState(
        var maxOnly: Boolean = false,
        var minRevertCount: Int = 0,
        var perfUnavailable: Boolean = false,
        var minWorksInPerformance: Boolean? = null,
        var pendingPerfMinCheckTargetKhz: Long? = null,
        var pendingPerfMinCheckAtMs: Long = 0L,
        var perfStickFailCount: Int = 0
    )

    private enum class SleepWakeReason {
        SCREEN_ON,
        EXEMPT,
        DISABLED
    }

    private val lastWrittenCpuFreq = mutableMapOf<Int, Long>()
    private val lastReassertMs = mutableMapOf<Int, Long>()
    private val lastDriftLogMs = mutableMapOf<Int, Long>()
    private val driftCount = mutableMapOf<Int, Int>()
    private val policyPinState = mutableMapOf<Int, PolicyPinState>()
    private var lastWrittenGpuOppIndex: Int? = null
    private val failedWriteCooldownUntil = mutableMapOf<String, Long>()

    // Screen-off power saving state
    @Volatile
    private var isScreenInteractive: Boolean =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    @Volatile
    private var isSleepSaving: Boolean = false
    private val sleepMutex = Mutex()
    private var sleepWatcherJob: Job? = null
    private var wakeSignal = CompletableDeferred<Unit>()

    // GED GPU load candidate tracking: prefer first candidate that demonstrates changing values
    private val gedLastValByPath = mutableMapOf<String, Float>()
    private val gedChangedByPath = mutableMapOf<String, Boolean>()
    private var lockedGedLoadPath: String? = null

    // Launch burst state
    private var lastForegroundPkg = ""
    private var burstEndTimeMs = 0L

    // Thermal throttle state
    private var latchedPeakTempC = 0f
    private var currentTempCpuCapPct = 100f
    private var currentTempGpuCapPct = 100f

    // Power throttle state
    private val wattWindow = ArrayDeque<Float>()
    private var overPowerSinceMs: Long? = null
    private var currentPowerCapPct = 100f
    private var gpuShareSmoothed = 0.5f
    private val smoothedClusterShare = mutableMapOf<Int, Float>()
    private var lastPowerSplitLogMs = 0L
    private var lastDominantComponent: String? = null

    // RAM pin state
    private var ramPinned = false
    private var ramOrigMinHz = 0L
    private var lastRamReassertMs = 0L

    // FPS sampler state
    private var fpsJob: Job? = null
    private var timestatsEnabled = false
    private var fpsDaemon: FpsDaemonClient? = null
    private var fpsDaemonFailed = false
    private var fpsDaemonStartRt = 0L

    // Power model staging & cached battery/refresh state (zero-alloc on tick)
    private val pmClusterPolicyIds = IntArray(PowerModel.MAX_CLUSTERS)
    private val pmClusterFreqMhz = IntArray(PowerModel.MAX_CLUSTERS)
    private val pmClusterUtilPct = FloatArray(PowerModel.MAX_CLUSTERS)
    private val pmClusterCoreCounts = IntArray(PowerModel.MAX_CLUSTERS)
    private var cachedBatteryPct = -1
    private var lastBatteryCheckMs = 0L
    private var lastSlowBattSysfsReadMs = 0L
    private var cachedBmStatus = -1
    private var cachedBmPlugged = -1
    private var cachedBattTempC = 0f
    private var cachedBattVoltMv = 0f
    private var cachedBattHealthLabel = "n/a"
    private var cachedChargeCounterMah = -1f
    private var cachedCycleCount = -1
    private var stableBatteryState = BatteryState.UNKNOWN
    private var candidateBatteryState = BatteryState.UNKNOWN
    private var candidateBatterySinceMs = 0L
    private var learnedDischargeSign = 0
    private var dischargeSignScore = 0
    private var smoothedChargeWatts = 0f
    private val displayRefreshHz: Float =
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.takeIf { it >= 30f } ?: 60f

    // Crash handler
    private var defaultCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHookInstalled = false

    init {
        _presets.value = storage.loadPresets(_hardwareSpec.value)
        val initialCfg = _config.value
        batteryEstimator.updateConfig(initialCfg.batteryEstimationEnabled, initialCfg.batteryDesignCapacityOverrideMah)
        engineScope.launch(Dispatchers.IO) {
            powerModel.load()
            batteryEstimator.load()
        }
        powerModel.start(engineScope)
        log(LogLevel.INFO, "Engine", "CoreGovernor v2 initialized (OPP-index GPU control).")
    }

    fun hasUsageStatsPermission(): Boolean = foregroundDetector.hasUsageStatsPermission(forceRefresh = true)

    suspend fun discoverHardware(forceRefresh: Boolean = false): HardwareSpec {
        if (!forceRefresh && _hardwareSpec.value.discoveredAtMs > 0L) {
            return _hardwareSpec.value
        }
        _telemetry.update { it.copy(isDiscovering = true) }
        log(LogLevel.INFO, "Discovery", "Probing CPU policies, gpufreqv2 OPP table, GED loading nodes, thermals, and battery...")

        val hw = discovery.discover()
        _hardwareSpec.value = hw
        powerModel.updateHardwareSpec(hw)
        hw.discoveryNotes.forEach { note ->
            log(if (hw.rootGranted) LogLevel.INFO else LogLevel.ERROR, "Discovery", note)
        }

        if (hw.rootGranted && !_telemetry.value.isRunning) {
            val staleBackup = storage.loadOriginalHardwareBackup()
            if (staleBackup != null) {
                log(LogLevel.WARN, "Recovery", "Found un-restored hardware backup from previous session; restoring.")
                restoreFromBackup(staleBackup)
            }
        }

        val populatedCfg = storage.populateDefaultsForHardware(_config.value, hw)
        _config.value = populatedCfg
        storage.saveConfig(populatedCfg)
        _presets.value = storage.loadPresets(hw)

        // Also capture an initial gpufreq_status snapshot when idle so Monitor/OPP screen has raw status right away
        var initialGpuState = _telemetry.value.gpu
        if (hw.rootGranted && hw.gpu.gpufreqStatusPath != null) {
            val statusRaw = rootShell.readFile(hw.gpu.gpufreqStatusPath).orEmpty()
            val parsed = SysfsParsers.parseGpufreqStatus(statusRaw, hw.gpu.oppTable)
            initialGpuState = initialGpuState.copy(
                available = hw.gpu.discovered,
                currentOppIndex = parsed.oppIndex ?: -1,
                currentFreqKhz = parsed.freqKhz ?: 0L,
                rawGpufreqStatus = statusRaw,
                minAvailableKhz = hw.gpu.availableFreqsKhz.firstOrNull() ?: 0L,
                maxAvailableKhz = hw.gpu.availableFreqsKhz.lastOrNull() ?: 0L
            )
        }

        _telemetry.update {
            it.copy(
                isDiscovering = false,
                rootGranted = hw.rootGranted,
                gpu = initialGpuState,
                ddrSupported = hw.ddr != null,
                tempSensorLabel = hw.thermalSensors.firstOrNull { s -> s.path == populatedCfg.selectedTempSensorPath }?.label
                    ?: hw.thermalSensors.firstOrNull()?.label ?: "Unassigned"
            )
        }
        return hw
    }

    suspend fun refreshGpuOppTable(): HardwareSpec {
        if (!rootShell.ensureSession()) return _hardwareSpec.value
        val notes = mutableListOf<String>()
        val freshGpu = discovery.discoverGpu(notes)
        val statusRaw = freshGpu.gpufreqStatusPath?.let { rootShell.readFile(it) }.orEmpty()
        val parsed = SysfsParsers.parseGpufreqStatus(statusRaw, freshGpu.oppTable)

        val updatedHw = _hardwareSpec.value.copy(gpu = freshGpu)
        _hardwareSpec.value = updatedHw
        powerModel.updateHardwareSpec(updatedHw)
        notes.forEach { log(LogLevel.INFO, "GpuOpp", it) }

        val populatedCfg = storage.populateDefaultsForHardware(_config.value, updatedHw)
        _config.value = populatedCfg
        storage.saveConfig(populatedCfg)

        _telemetry.update {
            it.copy(
                gpu = it.gpu.copy(
                    available = freshGpu.discovered,
                    currentOppIndex = parsed.oppIndex ?: it.gpu.currentOppIndex,
                    currentFreqKhz = parsed.freqKhz ?: it.gpu.currentFreqKhz,
                    rawGpufreqStatus = statusRaw.ifBlank { it.gpu.rawGpufreqStatus },
                    minAvailableKhz = freshGpu.availableFreqsKhz.firstOrNull() ?: 0L,
                    maxAvailableKhz = freshGpu.availableFreqsKhz.lastOrNull() ?: 0L
                )
            )
        }
        return updatedHw
    }

    fun startGovernor() {
        if (controlJob?.isActive == true) return
        controlJob = engineScope.launch {
            // Always re-read hardware & real OPP table on startup so custom/boot-image OPPs are fresh
            val hw = discoverHardware(forceRefresh = true)

            if (!hw.rootGranted) {
                log(LogLevel.ERROR, "Start", "Cannot start governor: root (su) access denied.")
                _telemetry.update { it.copy(isRunning = false, rootGranted = false) }
                return@launch
            }

            if (hw.cpuPolicies.isEmpty()) {
                log(LogLevel.ERROR, "Start", "Cannot start governor: no CPU cpufreq policies discovered.")
                return@launch
            }
            refreshCtl.start()
            screenOff.begin(isScreenInteractive)

            val backup = OriginalHardwareBackup(
                cpus = hw.cpuPolicies.map {
                    OriginalCpuBackup(
                        policyPath = it.path,
                        governor = it.origGovernor,
                        minFreqKhz = it.origMinFreqKhz,
                        maxFreqKhz = it.origMaxFreqKhz
                    )
                },
                fixOppPath = hw.gpu.gpufreqFixOppPath
            )
            storage.saveOriginalHardwareBackup(backup)
            installCrashRestoreHook()

            batteryEstimator.resetSession()
            resetLoopState()
            for (policy in hw.cpuPolicies) {
                val availGovsRaw = rootShell.readFile("${policy.path}/scaling_available_governors").orEmpty()
                val availGovs = availGovsRaw.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val switched = ("performance" in availGovs) &&
                    safeWriteSysfs("${policy.path}/scaling_governor", "performance")
                if (switched) {
                    policyPinState[policy.policyId] = PolicyPinState(maxOnly = true, minWorksInPerformance = false)
                    log(
                        LogLevel.INFO,
                        "CpuPin",
                        "policy${policy.policyId} governor -> performance (default; orig=${policy.origGovernor})"
                    )
                    continue
                } else {
                    storage.setPerformancePinPolicy(policy.policyId, false)
                    log(LogLevel.WARN, "CpuPin", "performance governor unavailable; back to min/max pinning.")
                }
                val ok = safeWriteSysfs("${policy.path}/scaling_governor", "powersave")
                if (ok) {
                    log(LogLevel.INFO, "Governor", "policy${policy.policyId} governor -> powersave (orig=${policy.origGovernor})")
                }
            }

            _telemetry.update { it.copy(isRunning = true, rootGranted = true) }
            log(LogLevel.INFO, "Engine", "Control loop started (poll=${_config.value.pollIntervalMs}ms).")

            if (!isScreenInteractive && _config.value.screenOffSavingEnabled) {
                scheduleScreenOffWatcher()
            }
            startFpsSampler()
            sensorPinner.setScreenInteractive(isScreenInteractive)
            sensorPinner.setServiceRunning(true)

            try {
                while (isActive) {
                    val cycleStart = System.currentTimeMillis()
                    val waitSignal: CompletableDeferred<Unit>? = sleepMutex.withLock {
                        if (isSleepSaving) {
                            CompletableDeferred<Unit>().also { wakeSignal = it }
                        } else {
                            runControlTick(_hardwareSpec.value, cycleStart)
                            null
                        }
                    }
                    if (waitSignal != null) {
                        waitSignal.await()
                        continue
                    }
                    val elapsed = System.currentTimeMillis() - cycleStart
                    val targetInterval = _config.value.pollIntervalMs.coerceIn(50L, 1000L)
                    val sleepMs = (targetInterval - elapsed).coerceAtLeast(15L)
                    delay(sleepMs)
                }
            } finally {
                metrics.onStopped()
                stopFpsSampler()
                sensorPinner.setServiceRunning(false)
                sleepWatcherJob?.cancel()
                sleepWatcherJob = null
                isSleepSaving = false
                restoreOriginalHardwareInternal()
            }
        }
    }

    fun setInitialScreenInteractive(interactive: Boolean) {
        isScreenInteractive = interactive
    }

    fun onScreenOff() {
        screenOff.onScreenOff()
        interaction.setActive(false, false)
        appDemand.flush()
        isScreenInteractive = false
        sensorPinner.setScreenInteractive(false)
        if (controlJob?.isActive == true && _config.value.screenOffSavingEnabled) {
            scheduleScreenOffWatcher()
        }
    }

    fun onScreenOn() {
        isScreenInteractive = true
        screenOff.onScreenOn()
        sensorPinner.setScreenInteractive(true)
        sleepWatcherJob?.cancel()
        sleepWatcherJob = null
        if (isSleepSaving) {
            engineScope.launch {
                exitSleepSaving(SleepWakeReason.SCREEN_ON)
            }
        }
    }

    private fun scheduleScreenOffWatcher() {
        sleepWatcherJob?.cancel()
        if (controlJob?.isActive != true) return
        val cfg = _config.value
        if (!cfg.screenOffSavingEnabled || isScreenInteractive) return

        sleepWatcherJob = engineScope.launch {
            val delayMs = cfg.screenOffDelaySec.coerceIn(0, 60) * 1000L
            if (delayMs > 0L) {
                delay(delayMs)
            }
            while (isActive && !isScreenInteractive && _config.value.screenOffSavingEnabled && controlJob?.isActive == true) {
                val currentCfg = _config.value
                val exempt = isSleepExempt(currentCfg)
                if (exempt) {
                    if (isSleepSaving) {
                        exitSleepSaving(SleepWakeReason.EXEMPT)
                    }
                } else {
                    if (!isSleepSaving) {
                        enterSleepSaving()
                    }
                }
                delay(10_000L)
            }
        }
    }

    private fun isSleepExempt(cfg: GovernorConfig): Boolean {
        if (cfg.screenOffExemptMusic) {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am?.isMusicActive == true) return true
        }
        if (cfg.screenOffExemptCharging && (stableBatteryState.isPluggedOrCharging || isDeviceCharging())) {
            return true
        }
        return false
    }

    private fun isDeviceCharging(): Boolean {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null && bm.isCharging) return true
        val battIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    private fun refreshBatteryStatusIfDue(nowMs: Long) {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val quickStatus = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val statusChanged = quickStatus > 0 && cachedBmStatus > 0 && quickStatus != cachedBmStatus
        if (!statusChanged && lastBatteryCheckMs > 0L && nowMs - lastBatteryCheckMs < 3_000L) return
        lastBatteryCheckMs = nowMs

        val battIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val intentStatus = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        cachedBmStatus = if (intentStatus > 0) intentStatus else quickStatus
        cachedBmPlugged = battIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        if (level >= 0) {
            cachedBatteryPct = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        }
        val rawTemp = battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (rawTemp != Int.MIN_VALUE && rawTemp in -300..1200) {
            cachedBattTempC = rawTemp / 10f
        }
        val rawVolt = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (rawVolt > 0) {
            cachedBattVoltMv = if (rawVolt > 20_000) rawVolt / 1000f else rawVolt.toFloat()
        }
        val healthInt = battIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        cachedBattHealthLabel = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "n/a"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && battIntent != null) {
            val cyc = battIntent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cyc >= 0) cachedCycleCount = cyc
        }
        val ccUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        cachedChargeCounterMah = if (ccUah > 0 && ccUah != Int.MIN_VALUE) ccUah / 1000f else -1f

        batteryEstimator.updateBatteryProperties(
            batteryPct = cachedBatteryPct,
            tempC = cachedBattTempC,
            voltageMv = cachedBattVoltMv,
            healthLabel = cachedBattHealthLabel,
            chargeCounterMah = cachedChargeCounterMah,
            cycleCount = cachedCycleCount
        )
    }

    private suspend fun enterSleepSaving() {
        sleepMutex.withLock {
            if (controlJob?.isActive != true) return@withLock
            if (isSleepSaving || isScreenInteractive) return@withLock
            val cfg = _config.value
            if (!cfg.screenOffSavingEnabled || isSleepExempt(cfg)) return@withLock

            isSleepSaving = true
            val hw = _hardwareSpec.value
            for (p in hw.cpuPolicies) {
                val lowestKhz = p.availableFreqsKhz.firstOrNull() ?: p.origMinFreqKhz
                if (lowestKhz > 0L) {
                    val ok = writeCpuPinnedFreq(p.path, lowestKhz, raising = false)
                    if (ok) {
                        lastWrittenCpuFreq[p.policyId] = lowestKhz
                    }
                }
            }
            val gpuOpps = hw.gpu.oppTable
            val fixPath = hw.gpu.gpufreqFixOppPath
            if (gpuOpps.isNotEmpty() && fixPath != null) {
                val lowestOppIdx = gpuOpps.first().oppIndex
                if (safeWriteSysfs(fixPath, lowestOppIdx.toString())) {
                    lastWrittenGpuOppIndex = lowestOppIdx
                }
            }
            val ddr = hw.ddr
            if (ramPinned && ddr != null) {
                val restoreHz = if (ramOrigMinHz > 0L) ramOrigMinHz else (ddr.availableHz.firstOrNull() ?: 0L)
                if (restoreHz > 0L) {
                    safeWriteSysfs(ddr.minFreqPath, restoreHz.toString())
                }
                ramPinned = false
                log(LogLevel.INFO, "RamPin", "RAM released (${restoreHz / 1_000_000L} MHz min)")
            }
            log(LogLevel.INFO, "ScreenOff", "Sleep saving active: clocks pinned to minimum.")
        }
    }

    private suspend fun exitSleepSaving(reason: SleepWakeReason) {
        sleepMutex.withLock {
            if (!isSleepSaving) return@withLock
            isSleepSaving = false
            prevCpuTicks = emptyMap()
            when (reason) {
                SleepWakeReason.SCREEN_ON -> {
                    val cfg = _config.value
                    if (cfg.normalMode.launchBurstEnabled && cfg.normalMode.launchBurstDurationMs > 0L) {
                        burstEndTimeMs = System.currentTimeMillis() + cfg.normalMode.launchBurstDurationMs
                    }
                    log(LogLevel.INFO, "ScreenOff", "Screen on: control resumed.")
                }
                SleepWakeReason.EXEMPT -> {
                    log(LogLevel.INFO, "ScreenOff", "Exemption active: control resumed.")
                }
                SleepWakeReason.DISABLED -> {
                    log(LogLevel.INFO, "ScreenOff", "Screen-off saving disabled: control resumed.")
                }
            }
            wakeSignal.complete(Unit)
        }
    }

    fun stopAndRestore() {
        storage.flush()
        powerModel.flush()
        batteryEstimator.flush()
        appDemand.flush()
        screenOff.stop()
        appDemand.release()
        smartWasOn = false
        interaction.setActive(false, false)
        refreshCtl.shutdown()
        metrics.onStopped()
        stopFpsSampler()
        sensorPinner.setServiceRunning(false)
        sleepWatcherJob?.cancel()
        sleepWatcherJob = null
        isSleepSaving = false
        wakeSignal.complete(Unit)
        val job = controlJob
        controlJob = null
        if (job != null && job.isActive) {
            log(LogLevel.INFO, "Engine", "Stopping governor and restoring original kernel state...")
            job.cancel()
        } else {
            engineScope.launch {
                restoreOriginalHardwareInternal()
            }
        }
    }

    private suspend fun restoreOriginalHardwareInternal() = withContext(NonCancellable) {
        sleepMutex.withLock {
            failedWriteCooldownUntil.clear()
            val backup = storage.loadOriginalHardwareBackup()
            if (backup != null) {
                restoreFromBackup(backup)
            } else {
                val hw = _hardwareSpec.value
                for (p in hw.cpuPolicies) {
                    safeWriteSysfs("${p.path}/scaling_governor", p.origGovernor)
                    safeWriteSysfs("${p.path}/scaling_min_freq", p.origMinFreqKhz.toString())
                    safeWriteSysfs("${p.path}/scaling_max_freq", p.origMaxFreqKhz.toString())
                }
                hw.gpu.gpufreqFixOppPath?.let { path ->
                    val ok = safeWriteSysfs(path, "-1")
                    log(
                        if (ok) LogLevel.INFO else LogLevel.ERROR,
                        "GpuOpp",
                        "Restore GPU control ($path <- -1): ${if (ok) "OK" else "FAILED"}"
                    )
                }
            }
            if (ramPinned) {
                val ddr = _hardwareSpec.value.ddr
                val restoreHz = if (ramOrigMinHz > 0L) ramOrigMinHz else (ddr?.availableHz?.firstOrNull() ?: 0L)
                if (ddr != null && restoreHz > 0L) {
                    safeWriteSysfs(ddr.minFreqPath, restoreHz.toString())
                    log(LogLevel.INFO, "RamPin", "RAM released (${restoreHz / 1_000_000L} MHz min)")
                }
                ramPinned = false
                ramOrigMinHz = 0L
            }
            lastWrittenCpuFreq.clear()
            lastReassertMs.clear()
            lastDriftLogMs.clear()
            driftCount.clear()
            policyPinState.clear()
            lastWrittenGpuOppIndex = null
            _telemetry.update {
                it.copy(
                    isRunning = false,
                    burstActive = false,
                    burstRemainingMs = 0L,
                    tempThrottleActive = false,
                    powerThrottleActive = false,
                    ddrPinned = false,
                    fps = -1f
                )
            }
            log(LogLevel.INFO, "Restore", "Original CPU governors and GPU unpinned (-1) restored.")
        }
    }

    private suspend fun restoreFromBackup(backup: OriginalHardwareBackup) {
        for (cpu in backup.cpus) {
            safeWriteSysfs("${cpu.policyPath}/scaling_max_freq", cpu.maxFreqKhz.toString())
            safeWriteSysfs("${cpu.policyPath}/scaling_min_freq", cpu.minFreqKhz.toString())
            safeWriteSysfs("${cpu.policyPath}/scaling_governor", cpu.governor)
        }
        backup.fixOppPath?.let { path ->
            val ok = safeWriteSysfs(path, "-1")
            log(
                if (ok) LogLevel.INFO else LogLevel.ERROR,
                "GpuOpp",
                "Unpin GPU OPP ($path <- -1): ${if (ok) "OK" else "FAILED"}"
            )
        }
        storage.clearOriginalHardwareBackup()
    }

    private suspend fun runControlTick(hw: HardwareSpec, nowMs: Long) {
        val cfg = _config.value

        val readPaths = mutableListOf("/proc/stat")
        for (p in hw.cpuPolicies) {
            readPaths.add("${p.path}/scaling_cur_freq")
            readPaths.add("${p.path}/scaling_governor")
            readPaths.add("${p.path}/scaling_min_freq")
            readPaths.add("${p.path}/scaling_max_freq")
        }
        hw.gpu.gpufreqStatusPath?.let { readPaths.add(it) }

        // Read locked GED load path, or probe all GED candidates until one returns a changing value
        val gedCandidatesToRead = lockedGedLoadPath?.let { listOf(it) } ?: hw.gpu.gedLoadCandidatePaths
        readPaths.addAll(gedCandidatesToRead)

        val tempSensor = hw.thermalSensors.firstOrNull { it.path == cfg.selectedTempSensorPath }
            ?: hw.thermalSensors.firstOrNull()
        tempSensor?.let { readPaths.add(it.path) }

        hw.batteryPower.currentPath?.let { readPaths.add(it) }
        hw.batteryPower.voltagePath?.let { readPaths.add(it) }
        val readSlowBattSysfs = lastSlowBattSysfsReadMs == 0L || nowMs - lastSlowBattSysfsReadMs >= 600_000L
        if (readSlowBattSysfs) {
            lastSlowBattSysfsReadMs = nowMs
            hw.batteryPower.chargeFullPath?.let { readPaths.add(it) }
            hw.batteryPower.chargeFullDesignPath?.let { readPaths.add(it) }
            hw.batteryPower.cycleCountPath?.let { readPaths.add(it) }
        }
        if (cfg.overlayEnabled && cfg.overlayDetailed) {
            hw.ddr?.curFreqPath?.let { readPaths.add(it) }
        }

        val batch = rootShell.readFilesBatch(readPaths.distinct())
        if (batch.isEmpty()) return

        refreshBatteryStatusIfDue(nowMs)
        if (readSlowBattSysfs) {
            val fullMah = hw.batteryPower.chargeFullPath?.let { batch[it] }?.let { SysfsParsers.parseCapacityMah(it) }
            val designMah = hw.batteryPower.chargeFullDesignPath?.let { batch[it] }?.let { SysfsParsers.parseCapacityMah(it) }
            val sysfsCycles = hw.batteryPower.cycleCountPath?.let { batch[it] }?.let { SysfsParsers.parseCycleCount(it) }
            batteryEstimator.updateSlowSysfs(fullMah = fullMah, designMah = designMah, cycleCount = sysfsCycles)
        }

        // 1. Foreground app & Mode decision
        val fgPkg = foregroundDetector.detectForegroundPackage(nowMs)
        val prevFg = lastForegroundPkg
        val appChanged = fgPkg.isNotBlank() && prevFg.isNotBlank() && fgPkg != prevFg && fgPkg != appContext.packageName
        if (fgPkg.isNotBlank()) lastForegroundPkg = fgPkg

        val activeMode = when (cfg.modeOverride) {
            ModeOverride.FORCE_NORMAL -> GovernorMode.NORMAL
            ModeOverride.FORCE_GAME -> GovernorMode.GAME
            ModeOverride.AUTO -> if (fgPkg in cfg.gamePackages) GovernorMode.GAME else GovernorMode.NORMAL
        }
        val modeProfile = if (activeMode == GovernorMode.GAME) cfg.gameMode else cfg.normalMode

        // 2. CPU loads from /proc/stat
        val currTicks = SysfsParsers.parseProcStat(batch["/proc/stat"].orEmpty())
        val clusterRawLoads = mutableMapOf<Int, Float>()
        var maxLoadSpike = 0f
        for (p in hw.cpuPolicies) {
            val load = SysfsParsers.computeClusterLoadPct(p.relatedCpus, prevCpuTicks, currTicks, cfg.cpuLoadUsesBusiestCore)
            clusterRawLoads[p.policyId] = load
            val prevLoad = prevRawClusterLoad[p.policyId] ?: load
            maxLoadSpike = maxOf(maxLoadSpike, load - prevLoad)
            prevRawClusterLoad[p.policyId] = load
        }
        val allCpuIds = hw.cpuPolicies.flatMap { it.relatedCpus }.distinct()
        val rawTotalCpuLoad = SysfsParsers.computeTotalCpuLoadPct(allCpuIds, prevCpuTicks, currTicks)
        smoothedTotalCpuLoad = (smoothedTotalCpuLoad + (rawTotalCpuLoad - smoothedTotalCpuLoad) * 0.4f)
            .coerceIn(0f, 100f)
        val rawCoreLoads = SysfsParsers.computePerCoreLoadPct(allCpuIds, prevCpuTicks, currTicks)
        for ((cpuId, rawPct) in rawCoreLoads) {
            val oldPct = smoothedCoreLoad[cpuId] ?: 0f
            smoothedCoreLoad[cpuId] = (oldPct + (rawPct - oldPct) * 0.4f).coerceIn(0f, 100f)
        }
        prevCpuTicks = currTicks

        // 3. Launch burst (Normal mode only)
        if (activeMode == GovernorMode.NORMAL && modeProfile.launchBurstEnabled && modeProfile.launchBurstDurationMs > 0L) {
            val spikeDetected = maxLoadSpike >= modeProfile.launchSpikeThresholdPct
            if (appChanged || spikeDetected) {
                val reason = if (appChanged) "app switch ($fgPkg)" else "load spike (+${maxLoadSpike.toInt()}%)"
                if (nowMs >= burstEndTimeMs) {
                    log(LogLevel.INFO, "Burst", "Launch burst ${modeProfile.launchBurstDurationMs}ms triggered by $reason")
                }
                burstEndTimeMs = nowMs + modeProfile.launchBurstDurationMs
            }
        } else {
            burstEndTimeMs = 0L
        }
        val burstActive = nowMs < burstEndTimeMs
        val burstRemaining = (burstEndTimeMs - nowMs).coerceAtLeast(0L)

        // 5. Power & Battery State (with 3s hysteresis)
        val rawCurrent = hw.batteryPower.currentPath?.let { batch[it]?.toLongOrNull() } ?: 0L
        val rawVoltage = hw.batteryPower.voltagePath?.let { batch[it]?.toLongOrNull() } ?: 0L
        val rawReading = if (hw.batteryPower.currentPath != null && hw.batteryPower.voltagePath != null) {
            SysfsParsers.computePowerReading(
                rawCurrent = rawCurrent,
                rawVoltage = rawVoltage,
                unitScale = cfg.powerThrottle.unitScale,
                polarity = cfg.powerThrottle.polarity,
                bmStatus = cachedBmStatus,
                bmPlugged = cachedBmPlugged,
                learnedDischargeSign = learnedDischargeSign
            )
        } else {
            val fallbackState = when {
                cachedBmPlugged == 0 -> BatteryState.DISCHARGING
                cachedBmStatus == BatteryManager.BATTERY_STATUS_CHARGING -> BatteryState.CHARGING
                cachedBmStatus == BatteryManager.BATTERY_STATUS_FULL -> BatteryState.FULL_PLUGGED
                cachedBmPlugged > 0 -> BatteryState.CHARGING
                else -> BatteryState.UNKNOWN
            }
            PowerReading(
                signedWatts = 0f,
                dischargeWatts = 0f,
                chargeWatts = 0f,
                state = fallbackState,
                currentMa = 0f,
                voltageMv = cachedBattVoltMv
            )
        }

        // Learn AUTO_ABS sign from clear BatteryManager status vs raw current
        if (abs(rawReading.currentMa) > 150f) {
            when (cachedBmStatus) {
                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    val vote = if (rawReading.currentMa > 0f) 1 else -1
                    dischargeSignScore = (dischargeSignScore + vote).coerceIn(-5, 5)
                }
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    val vote = if (rawReading.currentMa < 0f) 1 else -1
                    dischargeSignScore = (dischargeSignScore + vote).coerceIn(-5, 5)
                }
            }
            if (abs(dischargeSignScore) >= 3) {
                learnedDischargeSign = if (dischargeSignScore > 0) 1 else -1
            }
        }

        val rawState = rawReading.state
        if (rawState != BatteryState.UNKNOWN) {
            if (stableBatteryState == BatteryState.UNKNOWN) {
                stableBatteryState = rawState
                candidateBatteryState = rawState
                candidateBatterySinceMs = nowMs
                log(LogLevel.INFO, "Battery", "Battery state -> ${rawState.name}")
            } else if (rawState == stableBatteryState) {
                candidateBatteryState = stableBatteryState
                candidateBatterySinceMs = nowMs
            } else {
                if (candidateBatteryState != rawState) {
                    candidateBatteryState = rawState
                    candidateBatterySinceMs = nowMs
                } else if (nowMs - candidateBatterySinceMs >= 3_000L) {
                    val prevState = stableBatteryState
                    stableBatteryState = rawState
                    wattWindow.clear()
                    overPowerSinceMs = null
                    lastDominantComponent = null
                    if (rawState != BatteryState.DISCHARGING) {
                        currentPowerCapPct = 100f
                    }
                    if (rawState != BatteryState.CHARGING) {
                        smoothedChargeWatts = 0f
                    }
                    log(
                        LogLevel.INFO,
                        "Battery",
                        "Battery: ${prevState.name} -> ${rawState.name} (I=${"%.0f".format(Locale.US, rawReading.currentMa)}mA, V=${"%.0f".format(Locale.US, rawReading.voltageMv)}mV)"
                    )
                }
            }
        }

        // 4. Temperature & Thermal Throttle (now charging-aware via shared stableBatteryState)
        val tempC = tempSensor?.let { sensor ->
            SysfsParsers.parseTemperatureC(batch[sensor.path].orEmpty(), sensor.divisorToCelsius)
        } ?: _telemetry.value.temperatureC

        val (nextTempCpuCap, nextTempGpuCap, nextLatchedPeak) = GovernorMath.stepTempCap(
            currentTempC = tempC,
            prevLatchedPeakTempC = latchedPeakTempC,
            prevCapCpuPct = currentTempCpuCapPct,
            prevCapGpuPct = currentTempGpuCapPct,
            config = cfg.tempThrottle,
            safetyFloorPct = cfg.safetyFloorPct,
            isChargingOrPlugged = stableBatteryState.isPluggedOrCharging
        )
        latchedPeakTempC = nextLatchedPeak
        currentTempCpuCapPct = nextTempCpuCap
        currentTempGpuCapPct = nextTempGpuCap
        val tempThrottleActive = cfg.tempThrottle.enabled &&
            (currentTempCpuCapPct < 99.5f || currentTempGpuCapPct < 99.5f)

        // 5b. Gentle Power Throttle (discharging ONLY; release cleanly while charging/plugged)
        val maxWin = cfg.powerThrottle.averagingSamples.coerceIn(2, 40)
        val avgWatts: Float
        if (stableBatteryState == BatteryState.DISCHARGING) {
            if (rawState == BatteryState.DISCHARGING) {
                wattWindow.addLast(rawReading.dischargeWatts)
                while (wattWindow.size > maxWin) wattWindow.removeFirst()
            }
            avgWatts = if (wattWindow.isNotEmpty()) wattWindow.average().toFloat() else rawReading.dischargeWatts
            smoothedChargeWatts = 0f
            if (cfg.powerThrottle.enabled) {
                if (avgWatts > cfg.powerThrottle.limitWatts) {
                    val startOver = overPowerSinceMs ?: nowMs.also { overPowerSinceMs = it }
                    if (nowMs - startOver >= cfg.powerThrottle.sustainMs) {
                        val prevCap = currentPowerCapPct
                        currentPowerCapPct = (currentPowerCapPct - cfg.powerThrottle.stepPct)
                            .coerceIn(cfg.safetyFloorPct, 100f)
                        if (prevCap >= 99.5f && currentPowerCapPct < 99.5f) {
                            log(
                                LogLevel.WARN,
                                "PowerCap",
                                "Power draw ${"%.2f".format(avgWatts)}W > ${cfg.powerThrottle.limitWatts}W limit; stepping cap down."
                            )
                        }
                        overPowerSinceMs = nowMs - (cfg.powerThrottle.sustainMs / 2L)
                    }
                } else {
                    overPowerSinceMs = null
                    if (avgWatts < cfg.powerThrottle.limitWatts * 0.92f && currentPowerCapPct < 100f) {
                        currentPowerCapPct = (currentPowerCapPct + cfg.powerThrottle.releaseStepPct)
                            .coerceIn(cfg.safetyFloorPct, 100f)
                    }
                }
            } else {
                overPowerSinceMs = null
                currentPowerCapPct = 100f
            }
        } else {
            wattWindow.clear()
            overPowerSinceMs = null
            currentPowerCapPct = 100f
            avgWatts = 0f
            smoothedChargeWatts = if (stableBatteryState == BatteryState.CHARGING) {
                val cw = if (rawState == BatteryState.CHARGING) rawReading.chargeWatts else smoothedChargeWatts
                if (smoothedChargeWatts <= 0f) cw else smoothedChargeWatts + (cw - smoothedChargeWatts) * 0.35f
            } else {
                0f
            }
        }
        val powerThrottleActive = stableBatteryState == BatteryState.DISCHARGING &&
            cfg.powerThrottle.enabled && currentPowerCapPct < 99.5f

        val prevTelemetry = _telemetry.value
        val prevGpu = prevTelemetry.gpu
        val clusterActByPolicy = mutableMapOf<Int, Float>()
        var totalAct = 0f
        var totalCores = 0
        for (c in prevTelemetry.clusters) {
            val cores = c.relatedCpus.size.coerceAtLeast(1)
            val freqRatio = (c.actualFreqKhz.toFloat() / c.maxAvailableKhz.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            val act = (c.smoothedLoadPct / 100f).coerceIn(0f, 1f) * freqRatio * freqRatio * cores
            clusterActByPolicy[c.policyId] = act
            totalAct += act
            totalCores += cores
        }
        val cpuAct = totalAct / totalCores.coerceAtLeast(1).toFloat()
        val gpuAct = if (prevGpu.available && prevGpu.maxAvailableKhz > 0L) {
            val gpuFreqRatio = (prevGpu.currentFreqKhz.toFloat() / prevGpu.maxAvailableKhz.toFloat()).coerceIn(0f, 1f)
            (prevGpu.smoothedLoadPct / 100f).coerceIn(0f, 1f) * gpuFreqRatio * gpuFreqRatio
        } else 0f

        if (prevGpu.available) {
            val weightedGpu = gpuAct * 1.3f
            val denom = weightedGpu + cpuAct
            val rawGpuShare = if (denom >= 0.01f) weightedGpu / denom else 0.5f
            gpuShareSmoothed = (gpuShareSmoothed + (rawGpuShare - gpuShareSmoothed) * 0.2f).coerceIn(0f, 1f)
        } else {
            gpuShareSmoothed = 0.5f
        }

        val (cpuPowerCap, gpuPowerCap) = if (cfg.powerThrottle.componentAware && powerThrottleActive) {
            GovernorMath.splitPowerCap(
                basePowerCapPct = currentPowerCapPct,
                gpuShare = gpuShareSmoothed,
                floorPct = cfg.safetyFloorPct
            )
        } else {
            currentPowerCapPct to currentPowerCapPct
        }

        val clusterCount = hw.cpuPolicies.size.coerceAtLeast(1)
        val clusterFactors = mutableMapOf<Int, Float>()
        val clusterCapPct = mutableMapOf<Int, Float>()
        if (totalAct >= 0.01f) {
            for (p in hw.cpuPolicies) {
                val act = clusterActByPolicy[p.policyId] ?: 0f
                val rawShareTimesCount = (act / totalAct) * clusterCount
                val prevShare = smoothedClusterShare[p.policyId] ?: 1.0f
                val smoothedShare = prevShare + (rawShareTimesCount - prevShare) * 0.2f
                smoothedClusterShare[p.policyId] = smoothedShare
                clusterFactors[p.policyId] = GovernorMath.clusterCutFactor(smoothedShare)
            }
        } else {
            for (p in hw.cpuPolicies) {
                clusterFactors[p.policyId] = 1.0f
            }
        }

        val cpuBaseCut = (100f - cpuPowerCap).coerceAtLeast(0f)
        for (p in hw.cpuPolicies) {
            val factor = if (cfg.powerThrottle.componentAware && powerThrottleActive) {
                clusterFactors[p.policyId] ?: 1.0f
            } else {
                1.0f
            }
            val clusterPowerCap = (100f - cpuBaseCut * factor).coerceIn(cfg.safetyFloorPct, 100f)
            clusterCapPct[p.policyId] = GovernorMath.combineCaps(currentTempCpuCapPct, clusterPowerCap, cfg.safetyFloorPct)
        }

        val effectiveCpuCapPct = GovernorMath.combineCaps(currentTempCpuCapPct, cpuPowerCap, cfg.safetyFloorPct)
        val effectiveGpuCapPct = GovernorMath.combineCaps(currentTempGpuCapPct, gpuPowerCap, cfg.safetyFloorPct)

        if (powerThrottleActive && cfg.powerThrottle.componentAware) {
            val dominant = if (gpuShareSmoothed >= 0.5f) "GPU" else "CPU"
            val sharePct = if (dominant == "GPU") {
                (gpuShareSmoothed * 100f).roundToInt()
            } else {
                ((1f - gpuShareSmoothed) * 100f).roundToInt()
            }
            if ((lastDominantComponent == null || dominant != lastDominantComponent) && nowMs - lastPowerSplitLogMs >= 5_000L) {
                lastPowerSplitLogMs = nowMs
                lastDominantComponent = dominant
                val biggestCluster = hw.cpuPolicies.maxByOrNull { clusterFactors[it.policyId] ?: 1.0f }
                val biggestFactor = biggestCluster?.let { clusterFactors[it.policyId] ?: 1.0f } ?: 1.0f
                val biggestName = biggestCluster?.name ?: "CPU"
                log(
                    LogLevel.INFO,
                    "PowerSplit",
                    "$dominant is main power draw (share $sharePct%): CPU cap ${cpuPowerCap.roundToInt()}%, GPU cap ${gpuPowerCap.roundToInt()}%. Biggest CPU cut: $biggestName ${"%.1f".format(Locale.US, biggestFactor)}x"
                )
            } else if (lastDominantComponent == null) {
                lastDominantComponent = dominant
            }
        } else {
            lastDominantComponent = null
        }

        val isGameMode = activeMode == GovernorMode.GAME
        val scenarioKey = PowerModel.scenarioKeyFor(fgPkg, isGameMode, isScreenInteractive)
        val latestFps = prevTelemetry.fps
        val effBatteryPct = cachedBatteryPct.coerceAtLeast(0)

        // 5b. v2.7.0 smart app profiles (learning + ceilings/floors). Skipped entirely when disabled.
        val needTouch = cfg.smartAppProfilesEnabled || cfg.adaptiveRefreshEnabled
        interaction.setActive(needTouch, isScreenInteractive && !isSleepSaving)
        val smartOn = cfg.smartAppProfilesEnabled && isScreenInteractive && !isSleepSaving && fgPkg.isNotBlank()
        if (smartOn) {
            smartLoad.fill(-1f)
            smartRatio.fill(-1f)
            var si = 0
            for (p in hw.cpuPolicies) {
                if (si > 2) break
                val maxA = p.availableFreqsKhz.lastOrNull() ?: p.origMaxFreqKhz
                val cur = batch["${p.path}/scaling_cur_freq"]?.trim()?.toLongOrNull() ?: 0L
                if (maxA > 0L && cur > 0L) {
                    smartLoad[si] = clusterRawLoads[p.policyId] ?: 0f
                    smartRatio[si] = (cur.toFloat() / maxA.toFloat()).coerceIn(0f, 1f)
                }
                si++
            }
            val pg = prevTelemetry.gpu
            if (pg.available && pg.maxAvailableKhz > 0L) {
                val gf = if (pg.currentFreqKhz > 0L) pg.currentFreqKhz else pg.targetFreqKhz
                if (gf > 0L) {
                    smartLoad[3] = pg.loadPct
                    smartRatio[3] = (gf.toFloat() / pg.maxAvailableKhz.toFloat()).coerceIn(0f, 1f)
                }
            }
            appDemand.tick(
                nowMs = nowMs,
                pkg = fgPkg,
                isGame = isGameMode,
                mode = cfg.smartAppMode,
                cpuClusters = si.coerceAtMost(3),
                load = smartLoad,
                freqRatio = smartRatio,
                fps = latestFps,
                discharging = stableBatteryState == BatteryState.DISCHARGING,
                watts = rawReading.dischargeWatts,
                touchAtMs = interaction.lastTouchMs
            )
            appDemand.flushIfDue(nowMs)
            smartWasOn = true
        } else if (smartWasOn) {
            appDemand.release()
            smartWasOn = false
        }
        val smartApply = smartOn && !isGameMode && appDemand.outputsActive

        // 6. CPU cluster target selection & write-on-change (min = max)
        val clusterLiveList = mutableListOf<ClusterLiveState>()
        var pmClusterIdx = 0
        for (p in hw.cpuPolicies) {
            val rawLoad = clusterRawLoads[p.policyId] ?: 0f
            val prevSmoothed = smoothedClusterLoad[p.policyId] ?: rawLoad
            val smoothed = GovernorMath.stepSmoothedLoad(
                previousSmoothed = prevSmoothed,
                rawLoadPct = rawLoad,
                headroomPct = modeProfile.headroomPct,
                rampUpSpeed = modeProfile.rampUpSpeed,
                rampDownSpeed = modeProfile.rampDownSpeed
            )
            smoothedClusterLoad[p.policyId] = smoothed

            val range = modeProfile.cpuRangesKhz[p.policyId]
            val minAvailable = p.availableFreqsKhz.firstOrNull() ?: p.origMinFreqKhz
            val maxAvailable = p.availableFreqsKhz.lastOrNull() ?: p.origMaxFreqKhz
            val allowedMin = (range?.minKhz ?: minAvailable).coerceIn(minAvailable, maxAvailable)
            val allowedMax = (range?.maxKhz ?: maxAvailable).coerceIn(allowedMin, maxAvailable)
            val baseCapPct = clusterCapPct[p.policyId] ?: effectiveCpuCapPct
            var smartMin = allowedMin
            var smartCapPct = baseCapPct
            if (smartApply && allowedMax > allowedMin) {
                val ci = hw.cpuPolicies.indexOf(p)
                if (ci in 0..2) {
                    val cw = appDemand.ceilWork[ci]
                    if (cw < 99.5f) {
                        val cf = cw / 100f * maxAvailable
                        if (cf < allowedMax) {
                            val span = (allowedMax - allowedMin).toFloat()
                            smartCapPct = minOf(baseCapPct, ((cf - allowedMin) / span * 100f).coerceIn(0f, 100f))
                        }
                    }
                    val fw = appDemand.floorWork[ci]
                    if (fw > 0f && baseCapPct >= 99.5f && rawLoad >= 10f) {
                        val ff = (fw / 100f * maxAvailable).toLong()
                        if (ff > allowedMin) smartMin = ff.coerceIn(allowedMin, allowedMax)
                    }
                }
            }

            val (stdTargetFreq, cappedMax) = GovernorMath.selectFrequency(
                availableFreqsKhz = p.availableFreqsKhz,
                allowedMinKhz = smartMin,
                allowedMaxKhz = allowedMax,
                capPct = smartCapPct,
                loadPct = smoothed,
                forceMaxDuringBurst = burstActive
            )
            val preActualFreq = batch["${p.path}/scaling_cur_freq"]?.toLongOrNull() ?: stdTargetFreq
            val targetFreq = if (cfg.efficiencyLearningEnabled) {
                powerModel.selectEfficientCpuFreqKhz(
                    policyId = p.policyId,
                    availableFreqsKhz = p.availableFreqsKhz,
                    allowedMinKhz = smartMin,
                    cappedMaxKhz = cappedMax,
                    standardTargetKhz = stdTargetFreq,
                    currentFreqKhz = preActualFreq,
                    rawLoadPct = rawLoad,
                    coreCount = p.relatedCpus.size.coerceAtLeast(1),
                    scenarioKey = scenarioKey,
                    isGame = isGameMode,
                    fps = latestFps,
                    targetRefreshHz = displayRefreshHz,
                    efficiencyEnabled = true,
                    efficiencyMode = cfg.efficiencyMode,
                    minConfidencePct = cfg.minConfidencePct,
                    explorationRate = cfg.explorationRate,
                    burstActive = burstActive,
                    thermalThrottled = tempThrottleActive,
                    batteryPct = effBatteryPct,
                    nowMs = nowMs,
                    batteryState = stableBatteryState
                )
            } else {
                stdTargetFreq
            }

            val liveGov = batch["${p.path}/scaling_governor"]?.trim().orEmpty().ifBlank { "powersave" }
            var effectiveGov = liveGov
            val prevWritten = lastWrittenCpuFreq[p.policyId]
            val liveMin = batch["${p.path}/scaling_min_freq"]?.trim()?.toLongOrNull()
            val liveMax = batch["${p.path}/scaling_max_freq"]?.trim()?.toLongOrNull()
            val pinState = policyPinState.getOrPut(p.policyId) { PolicyPinState() }
            var justSwitchedToPerf = false

            if (pinState.maxOnly && pinState.minWorksInPerformance == null) {
                val checkTarget = pinState.pendingPerfMinCheckTargetKhz
                if (checkTarget != null && nowMs - pinState.pendingPerfMinCheckAtMs >= 140L) {
                    pinState.pendingPerfMinCheckTargetKhz = null
                    if (liveMin == checkTarget) {
                        pinState.minWorksInPerformance = true
                        log(
                            LogLevel.INFO,
                            "CpuPin",
                            "policy${p.policyId}: min sticks under performance governor; pinning min=max."
                        )
                    } else {
                        pinState.minWorksInPerformance = false
                        log(
                            LogLevel.WARN,
                            "CpuPin",
                            "policy${p.policyId}: min still reverted under performance; pinning max only."
                        )
                    }
                }
            }

            if (!pinState.maxOnly && !pinState.perfUnavailable && prevWritten != null) {
                if (liveMax == prevWritten && liveMin != null && liveMin != prevWritten) {
                    pinState.minRevertCount += 1
                    if (pinState.minRevertCount >= 3) {
                        val availGovsRaw = rootShell.readFile("${p.path}/scaling_available_governors").orEmpty()
                        val availGovs = availGovsRaw.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                        val perfAllowed = "performance" in availGovs
                        val switched = perfAllowed && safeWriteSysfs("${p.path}/scaling_governor", "performance")
                        if (switched) {
                            pinState.maxOnly = true
                            pinState.minRevertCount = 0
                            pinState.perfStickFailCount = 0
                            effectiveGov = "performance"
                            justSwitchedToPerf = true
                            storage.setPerformancePinPolicy(p.policyId, true)
                            log(
                                LogLevel.INFO,
                                "CpuPin",
                                "policy${p.policyId}: kernel ignores scaling_min_freq; switching to max-only pinning with performance governor."
                            )
                        } else {
                            pinState.maxOnly = false
                            pinState.minRevertCount = 0
                            pinState.perfUnavailable = true
                            storage.setPerformancePinPolicy(p.policyId, false)
                            if (liveGov != "powersave") {
                                failedWriteCooldownUntil.remove("${p.path}/scaling_governor")
                                if (safeWriteSysfs("${p.path}/scaling_governor", "powersave")) {
                                    effectiveGov = "powersave"
                                }
                            }
                            log(
                                LogLevel.WARN,
                                "CpuPin",
                                "performance governor unavailable; back to min/max pinning."
                            )
                        }
                    }
                } else {
                    pinState.minRevertCount = 0
                }
            }

            val desiredGov = if (pinState.maxOnly) "performance" else "powersave"
            if (effectiveGov != desiredGov) {
                val govPath = "${p.path}/scaling_governor"
                val canTryGov = shouldAttemptWrite(govPath, nowMs)
                val govOk = safeWriteSysfs(govPath, desiredGov)
                if (govOk) {
                    effectiveGov = desiredGov
                } else if (canTryGov && pinState.maxOnly) {
                    pinState.maxOnly = false
                    pinState.minRevertCount = 0
                    pinState.perfUnavailable = true
                    storage.setPerformancePinPolicy(p.policyId, false)
                    failedWriteCooldownUntil.remove(govPath)
                    if (safeWriteSysfs(govPath, "powersave")) {
                        effectiveGov = "powersave"
                    }
                    log(
                        LogLevel.WARN,
                        "CpuPin",
                        "performance governor unavailable; back to min/max pinning."
                    )
                }
            }

            val writeMaxOnly = pinState.maxOnly && pinState.minWorksInPerformance == false
            val drifted = prevWritten != null && prevWritten == targetFreq &&
                if (writeMaxOnly) {
                    liveMax != null && liveMax != targetFreq
                } else {
                    (liveMin != null && liveMin != targetFreq) || (liveMax != null && liveMax != targetFreq)
                }
            val reassertDue = drifted && nowMs - (lastReassertMs[p.policyId] ?: 0L) >= 400L
            if (pinState.maxOnly && pinState.minWorksInPerformance != null && prevWritten != null && prevWritten == targetFreq) {
                if (reassertDue) {
                    pinState.perfStickFailCount += 1
                    if (pinState.perfStickFailCount >= 3) {
                        storage.setPerformancePinPolicy(p.policyId, false)
                        pinState.maxOnly = false
                        pinState.minWorksInPerformance = null
                        pinState.minRevertCount = 0
                        pinState.perfStickFailCount = 0
                        pinState.pendingPerfMinCheckTargetKhz = null
                        failedWriteCooldownUntil.remove("${p.path}/scaling_governor")
                        if (safeWriteSysfs("${p.path}/scaling_governor", "powersave")) {
                            effectiveGov = "powersave"
                        }
                        log(
                            LogLevel.WARN,
                            "CpuPin",
                            "policy${p.policyId}: pinned min=max stopped sticking; cleared saved performance flag and re-detecting."
                        )
                    }
                } else if (!drifted) {
                    pinState.perfStickFailCount = 0
                }
            }
            if (targetFreq > 0L && (targetFreq != prevWritten || reassertDue || justSwitchedToPerf)) {
                if (reassertDue) {
                    lastReassertMs[p.policyId] = nowMs
                    // The kernel/system overriding the limits now and then is normal and is fixed on the spot, so it is
                    // only counted here and summarised once a minute (keeps the log, and the 400-line buffer, free for real events).
                    val n = (driftCount[p.policyId] ?: 0) + 1
                    driftCount[p.policyId] = n
                    if (nowMs - (lastDriftLogMs[p.policyId] ?: 0L) >= 60_000L) {
                        lastDriftLogMs[p.policyId] = nowMs
                        driftCount[p.policyId] = 0
                        log(LogLevel.INFO, "CpuDrift", "policy${p.policyId}: kernel changed the limits $n time(s) since the last note; each one was re-asserted (normal). Last: wanted ${targetFreq / 1000} MHz, kernel had min=${liveMin?.div(1000)} max=${liveMax?.div(1000)} MHz.")
                    }
                }
                val ok = if (pinState.maxOnly && pinState.minWorksInPerformance == false) {
                    safeWriteSysfs("${p.path}/scaling_max_freq", targetFreq.toString())
                } else {
                    val raising = if (reassertDue) (liveMax == null || targetFreq >= liveMax) else (prevWritten == null || targetFreq >= prevWritten)
                    writeCpuPinnedFreq(p.path, targetFreq, raising)
                }
                if (ok) {
                    lastWrittenCpuFreq[p.policyId] = targetFreq
                    if (pinState.maxOnly && pinState.minWorksInPerformance == null) {
                        pinState.pendingPerfMinCheckTargetKhz = targetFreq
                        pinState.pendingPerfMinCheckAtMs = nowMs
                    }
                }
            }

            val actualFreq = preActualFreq
            if (pmClusterIdx < PowerModel.MAX_CLUSTERS) {
                pmClusterPolicyIds[pmClusterIdx] = p.policyId
                pmClusterFreqMhz[pmClusterIdx] = (actualFreq / 1000L).toInt()
                pmClusterUtilPct[pmClusterIdx] = rawLoad
                pmClusterCoreCounts[pmClusterIdx] = p.relatedCpus.size.coerceAtLeast(1)
                pmClusterIdx++
            }
            clusterLiveList.add(
                ClusterLiveState(
                    policyId = p.policyId,
                    name = p.name,
                    relatedCpus = p.relatedCpus,
                    loadPct = rawLoad,
                    smoothedLoadPct = smoothed,
                    targetFreqKhz = targetFreq,
                    actualFreqKhz = actualFreq,
                    governor = effectiveGov,
                    allowedMinKhz = allowedMin,
                    allowedMaxKhz = allowedMax,
                    cappedMaxKhz = cappedMax,
                    minAvailableKhz = minAvailable,
                    maxAvailableKhz = maxAvailable
                )
            )
        }

        // 7. GPU scaling solely via sorted OPP positions -> real oppIndex pinned to /proc/gpufreqv2/fix_target_opp_index
        val gpuSpec = hw.gpu
        var gpuLiveState = GpuLiveState(available = gpuSpec.discovered)
        if (gpuSpec.discovered && gpuSpec.oppTable.isNotEmpty()) {
            val rawStatusText = gpuSpec.gpufreqStatusPath?.let { batch[it].orEmpty() }.orEmpty()
            val statusParsed = SysfsParsers.parseGpufreqStatus(rawStatusText, gpuSpec.oppTable)

            // Resolve GPU load from GED candidate nodes, picking the first candidate that returns a changing value
            val (rawGpuLoad, activeLoadPath) = resolveGedGpuLoad(
                candidates = gedCandidatesToRead,
                batch = batch,
                statusUtilFallback = statusParsed.utilPct
            )

            smoothedGpuLoad = GovernorMath.stepSmoothedLoad(
                previousSmoothed = smoothedGpuLoad,
                rawLoadPct = rawGpuLoad,
                headroomPct = modeProfile.gpuHeadroomPct,
                rampUpSpeed = modeProfile.gpuRampUpSpeed,
                rampDownSpeed = modeProfile.gpuRampDownSpeed
            )

            val gMinAvail = gpuSpec.oppTable.first().freqKhz
            val gMaxAvail = gpuSpec.oppTable.last().freqKhz
            val gRange = modeProfile.gpuRangeKhz
            val gAllowedMin = (gRange?.minKhz ?: gMinAvail).coerceIn(gMinAvail, gMaxAvail)
            val gAllowedMax = (gRange?.maxKhz ?: gMaxAvail).coerceIn(gAllowedMin, gMaxAvail)

            var gSmartMin = gAllowedMin
            var gSmartCap = effectiveGpuCapPct
            if (smartApply && gAllowedMax > gAllowedMin) {
                val cw = appDemand.ceilWork[3]
                if (cw < 99.5f) {
                    val cf = cw / 100f * gMaxAvail
                    if (cf < gAllowedMax) {
                        val span = (gAllowedMax - gAllowedMin).toFloat()
                        gSmartCap = minOf(effectiveGpuCapPct, ((cf - gAllowedMin) / span * 100f).coerceIn(0f, 100f))
                    }
                }
                val fw = appDemand.floorWork[3]
                if (fw > 0f && effectiveGpuCapPct >= 99.5f && smoothedGpuLoad >= 10f) {
                    val ff = (fw / 100f * gMaxAvail).toLong()
                    if (ff > gAllowedMin) gSmartMin = ff.coerceIn(gAllowedMin, gAllowedMax)
                }
            }
            val selection = GovernorMath.selectGpuOppPosition(
                sortedOpps = gpuSpec.oppTable,
                allowedMinKhz = gSmartMin,
                allowedMaxKhz = gAllowedMax,
                capPct = gSmartCap,
                loadPct = smoothedGpuLoad,
                forceMaxDuringBurst = burstActive
            )

            if (selection != null) {
                val chosenPos = if (cfg.efficiencyLearningEnabled) {
                    powerModel.selectEfficientGpuOppPos(
                        sortedOpps = gpuSpec.oppTable,
                        allowedMinPos = selection.allowedMinPos,
                        cappedMaxPos = selection.cappedMaxPos,
                        standardTargetPos = selection.targetPos,
                        currentFreqKhz = statusParsed.freqKhz ?: selection.targetOpp.freqKhz,
                        rawLoadPct = rawGpuLoad,
                        scenarioKey = scenarioKey,
                        isGame = isGameMode,
                        fps = latestFps,
                        targetRefreshHz = displayRefreshHz,
                        efficiencyEnabled = true,
                        efficiencyMode = cfg.efficiencyMode,
                        minConfidencePct = cfg.minConfidencePct,
                        explorationRate = cfg.explorationRate,
                        burstActive = burstActive,
                        thermalThrottled = tempThrottleActive,
                        batteryPct = effBatteryPct,
                        nowMs = nowMs,
                        batteryState = stableBatteryState
                    )
                } else {
                    selection.targetPos
                }
                val chosenOpp = gpuSpec.oppTable[chosenPos]
                val targetOppIdx = chosenOpp.oppIndex
                val targetFreqKhz = chosenOpp.freqKhz

                if (targetOppIdx != lastWrittenGpuOppIndex) {
                    val fixPath = gpuSpec.gpufreqFixOppPath
                    if (fixPath != null) {
                        val writeAttempted = shouldAttemptWrite(fixPath, nowMs)
                        if (writeAttempted) {
                            val ok = safeWriteSysfs(fixPath, targetOppIdx.toString())
                            if (ok) {
                                lastWrittenGpuOppIndex = targetOppIdx
                            } else {
                                log(
                                    LogLevel.ERROR,
                                    "GpuOpp",
                                    "Pin OPP idx=$targetOppIdx (pos=$chosenPos/${gpuSpec.oppTable.lastIndex}, " +
                                        "${targetFreqKhz} kHz / ${targetFreqKhz / 1000} MHz) -> FAILED"
                                )
                            }
                        }
                    }
                }

                gpuLiveState = GpuLiveState(
                    available = true,
                    loadPct = rawGpuLoad,
                    smoothedLoadPct = smoothedGpuLoad,
                    loadSourcePath = activeLoadPath,
                    targetSortedPos = chosenPos,
                    targetOppIndex = targetOppIdx,
                    targetFreqKhz = targetFreqKhz,
                    currentOppIndex = statusParsed.oppIndex ?: -1,
                    currentFreqKhz = statusParsed.freqKhz ?: 0L,
                    rawGpufreqStatus = rawStatusText,
                    allowedMinPos = selection.allowedMinPos,
                    allowedMaxPos = selection.allowedMaxPos,
                    cappedMaxPos = selection.cappedMaxPos,
                    allowedMinKhz = gpuSpec.oppTable[selection.allowedMinPos].freqKhz,
                    allowedMaxKhz = gpuSpec.oppTable[selection.allowedMaxPos].freqKhz,
                    cappedMaxKhz = selection.cappedMaxKhz,
                    minAvailableKhz = gMinAvail,
                    maxAvailableKhz = gMaxAvail
                )
            }
        }

        powerModel.submitTick(
            timestampMs = nowMs,
            watts = if (stableBatteryState == BatteryState.DISCHARGING) rawReading.dischargeWatts else 0f,
            isCharging = stableBatteryState.isPluggedOrCharging,
            isInteractive = isScreenInteractive,
            isSleepSaving = isSleepSaving,
            isGame = isGameMode,
            fgPkg = fgPkg,
            fps = latestFps,
            learningEnabled = cfg.powerModelLearningEnabled,
            efficiencyEnabled = cfg.efficiencyLearningEnabled,
            clusterCount = pmClusterIdx,
            clusterPolicyIds = pmClusterPolicyIds,
            clusterFreqMhz = pmClusterFreqMhz,
            clusterUtilPct = pmClusterUtilPct,
            clusterCoreCounts = pmClusterCoreCounts,
            gpuAvailable = gpuLiveState.available,
            gpuFreqMhz = ((if (gpuLiveState.currentFreqKhz > 0L) gpuLiveState.currentFreqKhz else gpuLiveState.targetFreqKhz) / 1000L).toInt(),
            gpuUtilPct = gpuLiveState.loadPct,
            batteryState = stableBatteryState
        )

        val estVoltMv = if (rawReading.voltageMv > 0f) rawReading.voltageMv else cachedBattVoltMv
        batteryEstimator.recordTick(
            nowMs = nowMs,
            batteryState = stableBatteryState,
            absCurrentMa = abs(rawReading.currentMa),
            voltageMv = estVoltMv,
            dischargeWatts = if (stableBatteryState == BatteryState.DISCHARGING) rawReading.dischargeWatts else 0f,
            chargeWatts = if (stableBatteryState == BatteryState.CHARGING) rawReading.chargeWatts else 0f,
            batteryPct = cachedBatteryPct,
            pollIntervalMs = cfg.pollIntervalMs,
            estimationEnabled = cfg.batteryEstimationEnabled
        )

        // 7b. Keep RAM at max & read DDR frequency if detailed overlay active
        val ddrSpec = hw.ddr
        val desiredPin = cfg.ramPinMax && ddrSpec != null && isScreenInteractive && (!cfg.ramPinGameOnly || activeMode == GovernorMode.GAME)
        if (ddrSpec != null && ddrSpec.availableHz.isNotEmpty()) {
            val maxHz = ddrSpec.availableHz.last()
            val minHz = ddrSpec.availableHz.first()
            if (desiredPin && !ramPinned) {
                val curMin = rootShell.readFile(ddrSpec.minFreqPath)?.trim()?.toLongOrNull()
                ramOrigMinHz = if (curMin != null && curMin < maxHz) curMin else minHz
                if (safeWriteSysfs(ddrSpec.minFreqPath, maxHz.toString())) {
                    ramPinned = true
                    lastRamReassertMs = nowMs
                    log(LogLevel.INFO, "RamPin", "RAM pinned at ${maxHz / 1_000_000L} MHz")
                }
            } else if (ramPinned && desiredPin) {
                if (nowMs - lastRamReassertMs >= 5_000L) {
                    lastRamReassertMs = nowMs
                    val liveMin = rootShell.readFile(ddrSpec.minFreqPath)?.trim()?.toLongOrNull()
                    if (liveMin != maxHz) {
                        safeWriteSysfs(ddrSpec.minFreqPath, maxHz.toString())
                    }
                }
            } else if (!desiredPin && ramPinned) {
                val restoreHz = if (ramOrigMinHz > 0L) ramOrigMinHz else minHz
                safeWriteSysfs(ddrSpec.minFreqPath, restoreHz.toString())
                ramPinned = false
                log(LogLevel.INFO, "RamPin", "RAM released (${restoreHz / 1_000_000L} MHz min)")
            }
        } else if (ramPinned) {
            ramPinned = false
        }

        val ddrFreqMhz = if (cfg.overlayEnabled && cfg.overlayDetailed && ddrSpec != null) {
            (batch[ddrSpec.curFreqPath]?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L
        } else {
            0L
        }

        val signedWattsOut = when (stableBatteryState) {
            BatteryState.DISCHARGING -> -avgWatts
            BatteryState.CHARGING -> smoothedChargeWatts
            BatteryState.FULL_PLUGGED, BatteryState.UNKNOWN -> 0f
        }
        val estSnap = batteryEstimator.snapshot.value
        val resolvedCycles = if (cachedCycleCount >= 0) cachedCycleCount else estSnap.cycleCount

        // 8. Push telemetry & history sample
        val pollDuration = System.currentTimeMillis() - nowMs
        _telemetry.update { prev ->
            val nextHistory = (prev.history + TelemetrySample(
                timestampMs = nowMs,
                tempC = tempC,
                watts = avgWatts,
                avgCpuLoadPct = smoothedTotalCpuLoad,
                gpuLoadPct = gpuLiveState.loadPct,
                effectiveCpuCapPct = effectiveCpuCapPct,
                effectiveGpuCapPct = effectiveGpuCapPct
            )).takeLast(90)

            prev.copy(
                isRunning = true,
                rootGranted = true,
                activeMode = activeMode,
                modeOverride = cfg.modeOverride,
                foregroundPackage = fgPkg,
                burstActive = burstActive,
                burstRemainingMs = burstRemaining,
                clusters = clusterLiveList,
                gpu = gpuLiveState,
                totalCpuLoadPct = smoothedTotalCpuLoad,
                coreLoadsPct = smoothedCoreLoad.toMap(),
                ddrFreqMhz = ddrFreqMhz,
                ddrSupported = ddrSpec != null,
                ddrPinned = ramPinned,
                temperatureC = tempC,
                tempSensorLabel = tempSensor?.label ?: "Unassigned",
                watts = avgWatts,
                chargeWatts = smoothedChargeWatts,
                signedWatts = signedWattsOut,
                batteryState = stableBatteryState,
                batteryPct = cachedBatteryPct,
                batteryTempC = cachedBattTempC,
                batteryVoltageMv = cachedBattVoltMv,
                batteryHealthLabel = cachedBattHealthLabel,
                batteryCycleCount = resolvedCycles,
                chargeCounterMah = cachedChargeCounterMah,
                rawCurrentMa = rawReading.currentMa,
                rawVoltageMv = rawReading.voltageMv,
                tempThrottleActive = tempThrottleActive,
                tempCpuCapPct = currentTempCpuCapPct,
                tempGpuCapPct = currentTempGpuCapPct,
                powerThrottleActive = powerThrottleActive,
                powerCapPct = currentPowerCapPct,
                effectiveCpuCapPct = effectiveCpuCapPct,
                effectiveGpuCapPct = effectiveGpuCapPct,
                lastPollDurationMs = pollDuration,
                history = nextHistory
            )
        }
        metrics.onTelemetry(_telemetry.value, nowMs)
    }

    private fun startFpsSampler() {
        if (fpsJob?.isActive == true) return
        fpsJob = engineScope.launch {
            runFpsSamplerLoop()
        }
    }

    private fun stopFpsSampler() {
        fpsJob?.cancel()
        fpsJob = null
        fpsDaemon?.stop()
        fpsDaemon = null
        _telemetry.update { it.copy(fps = -1f) }
    }

    private suspend fun runFpsSamplerLoop() {
        var lastPkg = ""
        var inactivePublished = false
        val lastDiagByKey = mutableMapOf<String, String>()
        var prevTimestats = emptyMap<String, Long>()
        var consecutiveErrors = 0
        var lastSource = ""
        var kernelPath: String? = null
        var kernelChecked = false
        var kernelAwk = false
        var sfLastFrames = -1L
        var sfLastRt = 0L
        var sfUnsupported = false
        var lastLatDetailRt = 0L
        var smoothedFps = 0f
        var lastTid = -2
        var lastHelperNote = ""
        var lastHelperNoteRt = 0L

        val ignored = listOf(
            "StatusBar", "NavigationBar", "ScreenDecor", "Wallpaper", "InputMethod",
            "com.android.systemui", "Letterbox", "Dim Layer", "Splash", "Background for",
            "Display Overlay", "ActivityRecord", "Task=", "WindowToken", "DefaultTaskDisplayArea",
            "RootWrapper", "mWindowContainer", "ImeContainer", "OneHanded", "Bounds for"
        )

        fun diag(msg: String) {
            val key = msg.substringBefore(":")
            if (lastDiagByKey[key] != msg) {
                lastDiagByKey[key] = msg
                log(LogLevel.INFO, "FPS", msg)
            }
        }

        fun q(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

        // Method A (VTools-style): kernel display fps node, if the device has one
        suspend fun sampleKernel(): Float? {
            if (!kernelChecked) {
                kernelChecked = true
                val r = rootShell.exec(
                    "for f in /sys/class/drm/sde-crtc-0/measured_fps /sys/class/graphics/fb0/measured_fps; do " +
                        "[ -e \$f ] && echo \$f && break; done"
                )
                val path = r.stdout.lines().firstOrNull { it.startsWith("/sys/") }?.trim()
                if (path != null) {
                    kernelPath = path
                    kernelAwk = path.contains("sde-crtc")
                }
            }
            val path = kernelPath ?: return null
            val r = rootShell.exec("cat $path 2>/dev/null")
            if (r.stdout.isBlank()) return null
            val trimmedOut = r.stdout.trim()
            val parts = trimmedOut.split(WHITESPACE_REGEX)
            val token = if (kernelAwk) parts.getOrNull(1) else parts.firstOrNull()
            return token?.toFloatOrNull()
        }

        // Method B (VTools-style): SurfaceFlinger global frame counter (binder transaction 1013)
        suspend fun sampleServiceCall(): Float? {
            if (sfUnsupported) return null
            val r = rootShell.exec("service call SurfaceFlinger 1013 2>/dev/null")
            val text = r.stdout
            val idx = text.indexOf("(")
            if (!text.contains("Parcel") || idx < 0 || text.length < idx + 9) {
                sfUnsupported = true
                return null
            }
            val frames = text.substring(idx + 1, idx + 9).toLongOrNull(16)
            if (frames == null) {
                sfUnsupported = true
                return null
            }
            val now = SystemClock.elapsedRealtime()
            val prevFrames = sfLastFrames
            val prevRt = sfLastRt
            sfLastFrames = frames
            sfLastRt = now
            if (prevFrames < 0L || prevRt <= 0L || now <= prevRt) return null
            if (frames < prevFrames) return null
            return (frames - prevFrames) * 1000f / (now - prevRt)
        }

        // Main method: SurfaceFlinger timestats frame counters.
        // Android 16 lists the same layer several times (one entry per render-rate bucket),
        // so the counters of all entries with the same layer name are added together.
        suspend fun sampleTimestats(pkg: String): Float? {
            if (!timestatsEnabled) {
                rootShell.exec("dumpsys SurfaceFlinger --timestats -enable")
                timestatsEnabled = true
            }
            val res = rootShell.exec("dumpsys SurfaceFlinger --timestats -dump | grep -E '^layerName|^totalFrames'")
            if (res.stdout.isBlank()) return null
            val currentLayers = mutableMapOf<String, Long>()
            var currentLayer: String? = null
            res.stdout.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("layerName")) {
                    val name = trimmed.substringAfter("=", "").trim()
                    if (name.isNotEmpty()) currentLayer = name
                } else if (trimmed.startsWith("totalFrames")) {
                    val cl = currentLayer
                    if (cl != null) {
                        val frames = trimmed.substringAfter("=", "").trim().toLongOrNull()
                        if (frames != null) currentLayers[cl] = (currentLayers[cl] ?: 0L) + frames
                        currentLayer = null
                    }
                }
            }
            val prev = prevTimestats
            prevTimestats = currentLayers
            if (prev.isEmpty() || currentLayers.isEmpty()) return null
            var matching = currentLayers.filterKeys { it.contains(pkg) }
            var usedFallback = false
            if (matching.isEmpty()) {
                usedFallback = true
                matching = currentLayers.filterKeys { n -> ignored.none { n.contains(it, ignoreCase = true) } }
            }
            var maxDelta = 0L
            var bestName = ""
            for ((name, count) in matching) {
                val p = prev[name] ?: continue
                val d = (count - p).coerceAtLeast(0L)
                if (d > maxDelta) {
                    maxDelta = d
                    bestName = name
                }
            }
            diag(
                "timestats method: ${matching.size} layer(s) for $pkg" +
                    (if (usedFallback) " (no package match, fallback)" else "") +
                    ": ${matching.keys.take(3).joinToString(" | ")}"
            )
            return maxDelta.toFloat()
        }

        var lastSampleRt = 0L
        while (engineScope.isActive && controlJob?.isActive == true) {
            val cfg = _config.value
            // All three overlay styles show FPS now (the compact one too), so measure whenever the overlay is on.
            val active = cfg.overlayEnabled && isScreenInteractive
            if (!active) {
                if (!inactivePublished) {
                    _telemetry.update { it.copy(fps = -1f) }
                    inactivePublished = true
                }
                lastPkg = ""
                prevTimestats = emptyMap()
                lastSampleRt = 0L
                consecutiveErrors = 0
                if (fpsDaemon != null) {
                    fpsDaemon?.stop()
                    fpsDaemon = null
                }
                delay(1000L)
                continue
            }
            inactivePublished = false

            val enginePkg = _telemetry.value.foregroundPackage
            // The helper knows the focused task itself, so prefer its package: it never lags behind the app switch.
            val helperPkg = fpsDaemon?.takeIf { it.isAlive }?.taskPkg.orEmpty()
            val pkg = if (helperPkg.isNotBlank() && helperPkg != "?" && helperPkg != "focused-root") helperPkg else enginePkg
            if (pkg.isBlank()) {
                diag("No foreground package detected yet")
                _telemetry.update { it.copy(fps = -1f) }
                delay(1000L)
                continue
            }
            // Every app is measured except the system UI itself.
            if (pkg == "com.android.systemui") {
                _telemetry.update { it.copy(fps = -1f) }
                lastPkg = pkg
                delay(1000L)
                continue
            }
            if (pkg != lastPkg) {
                lastPkg = pkg
                prevTimestats = emptyMap()
                lastSampleRt = 0L
                _telemetry.update { it.copy(fps = 0f) }
            }

            // Preferred: root task-FPS callback (same method as Metric)
            if (!fpsDaemonFailed) {
                var d = fpsDaemon
                if (d == null) {
                    d = FpsDaemonClient(appContext.applicationInfo.sourceDir)
                    fpsDaemon = d
                    fpsDaemonStartRt = SystemClock.elapsedRealtime()
                    if (!d.start()) {
                        log(LogLevel.WARN, "FPS", "Task-FPS helper could not start: ${d.lastError}. Using backup methods.")
                        fpsDaemonFailed = true
                    }
                }
                if (!fpsDaemonFailed) {
                    val nowRt = SystemClock.elapsedRealtime()
                    if (!d.isAlive) {
                        log(LogLevel.WARN, "FPS", "Task-FPS helper exited (${d.lastError.ifBlank { d.rawTail() }}). Using backup methods.")
                        fpsDaemonFailed = true
                        d.stop()
                        fpsDaemon = null
                    } else if (d.fpsAtRt > 0L || d.latencyAtRt > 0L) {
                        diag("Task-FPS helper: task=${d.taskInfo}")
                        if (d.lastError.isNotBlank() && d.lastError != lastHelperNote && nowRt - lastHelperNoteRt > 10000L) {
                            lastHelperNote = d.lastError
                            lastHelperNoteRt = nowRt
                            log(LogLevel.INFO, "FPS", "Helper note: ${d.lastError}")
                        }
                        val tid = d.taskId
                        if (tid != lastTid) { lastTid = tid; smoothedFps = 0f }
                        // Only values that belong to the app on screen right now are used (never the previous app's).
                        val cbFresh = d.fpsAtRt > 0L && d.fpsTask == tid && nowRt - d.fpsAtRt < 3500L
                        val latFresh = d.latencyAtRt > 0L && d.latencyTask == tid && nowRt - d.latencyAtRt < 1500L
                        val cbV = if (cbFresh) d.fps else -1f
                        val latV = if (latFresh) d.latencyFps else -1f
                        // The task callback can under-report in games, the layer value can be missing in some apps: take the higher.
                        val glFresh = d.globalAtRt > 0L && d.globalTask == tid && nowRt - d.globalAtRt < 2000L
                        val glV = if (glFresh) d.globalFps else -1f
                        // Apps that draw into a SurfaceView (many games) show up in neither of the two above;
                        // for them use the whole-screen composition rate (ignore the tiny overlay-only updates).
                        val useComp = cbV <= 0f && latV <= 0f && glV > 8f
                        val raw = if (useComp) glV else maxOf(cbV, latV, 0f)
                        val src = when {
                            useComp -> "sf-composition"
                            cbV > latV && cbV >= 0f -> "task-callback"
                            latV >= 0f -> "sf-latency"
                            else -> "no-frames"
                        }
                        // Light smoothing so the number is steady but still reacts within about a second.
                        smoothedFps = if (raw <= 0f) 0f else if (smoothedFps <= 0f) raw else smoothedFps + (raw - smoothedFps) * 0.55f
                        if (src != lastSource) {
                            lastSource = src
                            log(LogLevel.INFO, "FPS", "Source: $src (${d.taskInfo}) ${if (src == "sf-latency") d.latencyLayer else ""}")
                        }
                        if (nowRt - lastLatDetailRt > 8000L) {
                            lastLatDetailRt = nowRt
                            log(LogLevel.INFO, "FPS", "Compare: task=${d.taskInfo} callback=${if (cbFresh) d.fps.toInt().toString() else "none"} layer=${if (latFresh) d.latencyFps.toInt().toString() else "none"} screen=${if (glFresh) d.globalFps.toInt().toString() else "none"} shown=${smoothedFps.toInt()} via=${d.latencyLayer.substringBefore(" |")}")
                        }
                        _telemetry.update { it.copy(fps = smoothedFps) }
                        delay(500L)
                        continue
                    } else {
                        diag("Task-FPS helper: task=${d.taskInfo} err=${d.lastError}")
                        if (nowRt - fpsDaemonStartRt > 8000L) diag("Task-FPS helper running (${d.taskInfo.ifBlank { "no task" }}) but no callback data yet; err=${d.lastError.ifBlank { d.rawTail() }}")
                    }
                }
            }

            val g = sampleKernel() ?: sampleServiceCall()
            var source = "kernel/service"
            val sampleRt = SystemClock.elapsedRealtime()
            val fpsVal: Float = if (g != null && g > 0f) {
                g
            } else {
                val b = sampleTimestats(pkg)
                val elapsed = if (lastSampleRt > 0L) (sampleRt - lastSampleRt) / 1000f else 0f
                if (b != null && elapsed > 0f) {
                    source = "timestats"
                    b / elapsed
                } else {
                    source = "waiting"
                    g ?: 0f
                }
            }
            if (source != lastSource) {
                lastSource = source
                log(LogLevel.INFO, "FPS", "Source: $source (kernelNode=${kernelPath ?: "none"}, sfCounter=${if (sfUnsupported) "unsupported" else "ok"})")
            }
            lastSampleRt = sampleRt

            if (g == null && source == "waiting") {
                consecutiveErrors++
                if (consecutiveErrors >= 5) diag("Both FPS methods returned no data for $pkg (root dumpsys may be blocked)")
            } else {
                consecutiveErrors = 0
            }
            _telemetry.update { it.copy(fps = fpsVal) }
            delay(1000L)
        }
    }

    private fun resolveGedGpuLoad(
        candidates: List<String>,
        batch: Map<String, String>,
        statusUtilFallback: Float?
    ): Pair<Float, String> {
        lockedGedLoadPath?.let { locked ->
            val v = SysfsParsers.parseGpuUtilPct(batch[locked].orEmpty())
            if (v != null) return v to locked
        }

        var firstValid: Pair<Float, String>? = null
        for (path in candidates) {
            val parsed = SysfsParsers.parseGpuUtilPct(batch[path].orEmpty()) ?: continue
            if (firstValid == null) firstValid = parsed to path
            val prev = gedLastValByPath[path]
            if (prev != null && prev != parsed) {
                gedChangedByPath[path] = true
                if (lockedGedLoadPath == null) {
                    lockedGedLoadPath = path
                    log(LogLevel.INFO, "GpuLoad", "Selected changing GED load node: $path")
                }
                gedLastValByPath[path] = parsed
                return parsed to path
            }
            gedLastValByPath[path] = parsed
        }

        if (firstValid != null) return firstValid
        return (statusUtilFallback ?: 0f) to (if (statusUtilFallback != null) "gpufreq_status" else "none")
    }

    private suspend fun writeCpuPinnedFreq(policyPath: String, targetKhz: Long, raising: Boolean): Boolean {
        val freqStr = targetKhz.toString()
        return if (raising) {
            val okMax = safeWriteSysfs("$policyPath/scaling_max_freq", freqStr)
            val okMin = safeWriteSysfs("$policyPath/scaling_min_freq", freqStr)
            okMax && okMin
        } else {
            val okMin = safeWriteSysfs("$policyPath/scaling_min_freq", freqStr)
            val okMax = safeWriteSysfs("$policyPath/scaling_max_freq", freqStr)
            okMin && okMax
        }
    }

    private fun shouldAttemptWrite(path: String, nowMs: Long): Boolean {
        val cooldownUntil = failedWriteCooldownUntil[path] ?: 0L
        return nowMs >= cooldownUntil
    }

    private suspend fun safeWriteSysfs(path: String, value: String): Boolean {
        val now = System.currentTimeMillis()
        if (!shouldAttemptWrite(path, now)) return false

        val res = rootShell.writeSysfs(path, value)
        if (!res.isSuccess) {
            failedWriteCooldownUntil[path] = now + if (path.contains("/cpufreq/")) 750L else 6_000L
            log(LogLevel.ERROR, "SysfsWrite", "Write failed [$path <- $value]: ${res.error ?: res.stdout}")
            return false
        }
        return true
    }

    fun updateConfig(transform: (GovernorConfig) -> GovernorConfig) {
        val prev = _config.value
        val next = transform(prev)
        _config.value = next
        storage.saveConfigDebounced(next)
        _telemetry.update { it.copy(modeOverride = next.modeOverride) }

        if (prev.batteryEstimationEnabled != next.batteryEstimationEnabled ||
            prev.batteryDesignCapacityOverrideMah != next.batteryDesignCapacityOverrideMah
        ) {
            batteryEstimator.updateConfig(next.batteryEstimationEnabled, next.batteryDesignCapacityOverrideMah)
        }

        if (prev.efficiencyLearningEnabled != next.efficiencyLearningEnabled || prev.efficiencyMode != next.efficiencyMode) {
            powerModel.onEfficiencySettingChanged(next.efficiencyLearningEnabled, next.efficiencyMode)
            if (prev.efficiencyLearningEnabled != next.efficiencyLearningEnabled) {
                log(
                    LogLevel.INFO,
                    "Efficiency",
                    if (next.efficiencyLearningEnabled) "Efficiency governor ENABLED (mode=${next.efficiencyMode.name})" else "Efficiency governor DISABLED (standard scaling)"
                )
            }
        }

        if (controlJob?.isActive == true) {
            if (!next.screenOffSavingEnabled) {
                sleepWatcherJob?.cancel()
                sleepWatcherJob = null
                if (isSleepSaving) {
                    engineScope.launch {
                        exitSleepSaving(SleepWakeReason.DISABLED)
                    }
                }
            } else if (!isScreenInteractive) {
                val screenCfgChanged =
                    prev.screenOffSavingEnabled != next.screenOffSavingEnabled ||
                        prev.screenOffDelaySec != next.screenOffDelaySec ||
                        prev.screenOffExemptMusic != next.screenOffExemptMusic ||
                        prev.screenOffExemptCharging != next.screenOffExemptCharging
                if (screenCfgChanged) {
                    if (isSleepSaving && isSleepExempt(next)) {
                        engineScope.launch {
                            exitSleepSaving(SleepWakeReason.EXEMPT)
                        }
                    }
                    if (!isSleepSaving || !prev.screenOffSavingEnabled) {
                        scheduleScreenOffWatcher()
                    }
                }
            }
        }
    }

    fun cycleModeOverride() {
        updateConfig { cfg ->
            val next = when (cfg.modeOverride) {
                ModeOverride.AUTO -> ModeOverride.FORCE_GAME
                ModeOverride.FORCE_GAME -> ModeOverride.FORCE_NORMAL
                ModeOverride.FORCE_NORMAL -> ModeOverride.AUTO
            }
            log(LogLevel.INFO, "Mode", "Mode override -> ${next.name}")
            cfg.copy(modeOverride = next)
        }
    }

    fun saveNamedPreset(name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val newPreset = PresetItem(
            id = "user_${System.currentTimeMillis()}",
            name = cleanName,
            builtIn = false,
            config = _config.value
        )
        val updated = _presets.value + newPreset
        _presets.value = updated
        storage.saveUserPresets(updated)
        log(LogLevel.INFO, "Presets", "Saved preset '$cleanName'.")
    }

    fun loadPreset(preset: PresetItem) {
        val populated = storage.populateDefaultsForHardware(preset.config, _hardwareSpec.value)
        _config.value = populated
        storage.saveConfig(populated)
        _telemetry.update { it.copy(modeOverride = populated.modeOverride) }
        log(LogLevel.INFO, "Presets", "Loaded preset '${preset.name}'.")
    }

    fun deletePreset(preset: PresetItem) {
        if (preset.builtIn) return
        val updated = _presets.value.filterNot { it.id == preset.id }
        _presets.value = updated
        storage.saveUserPresets(updated)
        log(LogLevel.INFO, "Presets", "Deleted preset '${preset.name}'.")
    }

    fun applyImportedData(importedConfig: GovernorConfig, importedPresets: List<PresetItem>) {
        val populated = storage.populateDefaultsForHardware(importedConfig, _hardwareSpec.value)
        _config.value = populated
        storage.saveConfig(populated)
        if (importedPresets.isNotEmpty()) {
            val existingUser = _presets.value.filter { !it.builtIn }
            val merged = _presets.value.filter { it.builtIn } + existingUser + importedPresets.filter { !it.builtIn }
            _presets.value = merged.distinctBy { it.id }
            storage.saveUserPresets(_presets.value)
        }
        _telemetry.update { it.copy(modeOverride = populated.modeOverride) }
        log(LogLevel.INFO, "Import", "Imported JSON configuration (${importedPresets.size} custom preset(s)).")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = logIdSeq.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        _logs.update { curr -> (listOf(entry) + curr).take(400) }
    }

    private fun resetLoopState() {
        prevCpuTicks = emptyMap()
        prevRawClusterLoad.clear()
        smoothedClusterLoad.clear()
        smoothedCoreLoad.clear()
        smoothedTotalCpuLoad = 0f
        smoothedGpuLoad = 0f
        lastWrittenCpuFreq.clear()
        lastReassertMs.clear()
        lastDriftLogMs.clear()
        policyPinState.clear()
        lastWrittenGpuOppIndex = null
        failedWriteCooldownUntil.clear()
        gedLastValByPath.clear()
        gedChangedByPath.clear()
        lockedGedLoadPath = null
        wattWindow.clear()
        overPowerSinceMs = null
        currentPowerCapPct = 100f
        gpuShareSmoothed = 0.5f
        smoothedClusterShare.clear()
        lastPowerSplitLogMs = 0L
        lastDominantComponent = null
        ramPinned = false
        ramOrigMinHz = 0L
        lastRamReassertMs = 0L
        latchedPeakTempC = 0f
        currentTempCpuCapPct = 100f
        currentTempGpuCapPct = 100f
        burstEndTimeMs = 0L
        stableBatteryState = BatteryState.UNKNOWN
        candidateBatteryState = BatteryState.UNKNOWN
        candidateBatterySinceMs = 0L
        learnedDischargeSign = 0
        dischargeSignScore = 0
        smoothedChargeWatts = 0f
        lastSlowBattSysfsReadMs = 0L
        lastBatteryCheckMs = 0L
    }

    private fun installCrashRestoreHook() {
        if (crashHookInstalled) return
        crashHookInstalled = true
        defaultCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val backup = storage.loadOriginalHardwareBackup()
                if (backup != null) {
                    val cmds = mutableListOf<String>()
                    backup.cpus.forEach { c ->
                        cmds.add("printf '%s' '${c.maxFreqKhz}' > '${c.policyPath}/scaling_max_freq'")
                        cmds.add("printf '%s' '${c.minFreqKhz}' > '${c.policyPath}/scaling_min_freq'")
                        cmds.add("printf '%s' '${c.governor}' > '${c.policyPath}/scaling_governor'")
                    }
                    backup.fixOppPath?.let { cmds.add("printf '%s' '-1' > '$it'") }
                    rootShell.writeSysfsBlocking(cmds)
                    storage.clearOriginalHardwareBackup()
                }
            } catch (_: Throwable) {
            } finally {
                defaultCrashHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
