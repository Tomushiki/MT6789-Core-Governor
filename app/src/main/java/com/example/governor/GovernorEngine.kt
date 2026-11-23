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
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.example.data.ConfigStorage
import com.example.data.OriginalCpuBackup
import com.example.data.OriginalHardwareBackup
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
import kotlin.math.roundToInt

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

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controlJob: Job? = null

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

    // Crash handler
    private var defaultCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHookInstalled = false

    init {
        _presets.value = storage.loadPresets(_hardwareSpec.value)
        log(LogLevel.INFO, "Engine", "CoreGovernor v2 initialized (OPP-index GPU control).")
    }

    fun hasUsageStatsPermission(): Boolean = foregroundDetector.hasUsageStatsPermission()

    suspend fun discoverHardware(forceRefresh: Boolean = false): HardwareSpec {
        if (!forceRefresh && _hardwareSpec.value.discoveredAtMs > 0L) {
            return _hardwareSpec.value
        }
        _telemetry.update { it.copy(isDiscovering = true) }
        log(LogLevel.INFO, "Discovery", "Probing CPU policies, gpufreqv2 OPP table, GED loading nodes, thermals, and battery...")

        val hw = discovery.discover()
        _hardwareSpec.value = hw
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

            resetLoopState()
            val rememberedPerfPolicies = storage.loadPerformancePinPolicies()
            for (policy in hw.cpuPolicies) {
                val availGovsRaw = rootShell.readFile("${policy.path}/scaling_available_governors").orEmpty()
                val availGovs = availGovsRaw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
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
                stopFpsSampler()
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
        isScreenInteractive = false
        if (controlJob?.isActive == true && _config.value.screenOffSavingEnabled) {
            scheduleScreenOffWatcher()
        }
    }

    fun onScreenOn() {
        isScreenInteractive = true
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
        if (cfg.screenOffExemptCharging && isDeviceCharging()) {
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
        stopFpsSampler()
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
        if (cfg.overlayEnabled && cfg.overlayDetailed) {
            hw.ddr?.curFreqPath?.let { readPaths.add(it) }
        }

        val batch = rootShell.readFilesBatch(readPaths.distinct())
        if (batch.isEmpty()) return

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

        // 4. Temperature & Thermal Throttle
        val tempC = tempSensor?.let { sensor ->
            SysfsParsers.parseTemperatureC(batch[sensor.path].orEmpty(), sensor.divisorToCelsius)
        } ?: _telemetry.value.temperatureC

        val (nextTempCpuCap, nextTempGpuCap, nextLatchedPeak) = GovernorMath.stepTempCap(
            currentTempC = tempC,
            prevLatchedPeakTempC = latchedPeakTempC,
            prevCapCpuPct = currentTempCpuCapPct,
            prevCapGpuPct = currentTempGpuCapPct,
            config = cfg.tempThrottle,
            safetyFloorPct = cfg.safetyFloorPct
        )
        latchedPeakTempC = nextLatchedPeak
        currentTempCpuCapPct = nextTempCpuCap
        currentTempGpuCapPct = nextTempGpuCap
        val tempThrottleActive = cfg.tempThrottle.enabled &&
            (currentTempCpuCapPct < 99.5f || currentTempGpuCapPct < 99.5f)

        // 5. Power & Gentle Power Throttle
        val rawCurrent = hw.batteryPower.currentPath?.let { batch[it]?.toLongOrNull() } ?: 0L
        val rawVoltage = hw.batteryPower.voltagePath?.let { batch[it]?.toLongOrNull() } ?: 0L
        val powerReading = if (hw.batteryPower.currentPath != null && hw.batteryPower.voltagePath != null) {
            SysfsParsers.computePowerReading(
                rawCurrent = rawCurrent,
                rawVoltage = rawVoltage,
                unitScale = cfg.powerThrottle.unitScale,
                polarity = cfg.powerThrottle.polarity
            )
        } else {
            PowerReading(0f, 0f, 0f)
        }

        val maxWin = cfg.powerThrottle.averagingSamples.coerceIn(2, 40)
        wattWindow.addLast(powerReading.watts)
        while (wattWindow.size > maxWin) wattWindow.removeFirst()
        val avgWatts = if (wattWindow.isNotEmpty()) wattWindow.average().toFloat() else powerReading.watts

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
        val powerThrottleActive = cfg.powerThrottle.enabled && currentPowerCapPct < 99.5f

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

        // 6. CPU cluster target selection & write-on-change (min = max)
        val clusterLiveList = mutableListOf<ClusterLiveState>()
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

            val (targetFreq, cappedMax) = GovernorMath.selectFrequency(
                availableFreqsKhz = p.availableFreqsKhz,
                allowedMinKhz = allowedMin,
                allowedMaxKhz = allowedMax,
                capPct = clusterCapPct[p.policyId] ?: effectiveCpuCapPct,
                loadPct = smoothed,
                forceMaxDuringBurst = burstActive
            )

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
                        val availGovs = availGovsRaw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
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
                    if (nowMs - (lastDriftLogMs[p.policyId] ?: 0L) >= 5_000L) {
                        lastDriftLogMs[p.policyId] = nowMs
                        log(LogLevel.WARN, "CpuDrift", "policy${p.policyId}: wanted ${targetFreq / 1000} MHz but kernel has min=${liveMin?.div(1000)} max=${liveMax?.div(1000)} MHz; re-asserting.")
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

            val actualFreq = batch["${p.path}/scaling_cur_freq"]?.toLongOrNull() ?: targetFreq
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

            val selection = GovernorMath.selectGpuOppPosition(
                sortedOpps = gpuSpec.oppTable,
                allowedMinKhz = gAllowedMin,
                allowedMaxKhz = gAllowedMax,
                capPct = effectiveGpuCapPct,
                loadPct = smoothedGpuLoad,
                forceMaxDuringBurst = burstActive
            )

            if (selection != null) {
                val targetOppIdx = selection.targetOpp.oppIndex
                val targetFreqKhz = selection.targetOpp.freqKhz

                if (targetOppIdx != lastWrittenGpuOppIndex) {
                    val fixPath = gpuSpec.gpufreqFixOppPath
                    if (fixPath != null) {
                        val writeAttempted = shouldAttemptWrite(fixPath, nowMs)
                        if (writeAttempted) {
                            val ok = safeWriteSysfs(fixPath, targetOppIdx.toString())
                            log(
                                if (ok) LogLevel.INFO else LogLevel.ERROR,
                                "GpuOpp",
                                "Pin OPP idx=$targetOppIdx (pos=${selection.targetPos}/${gpuSpec.oppTable.lastIndex}, " +
                                    "${targetFreqKhz} kHz / ${targetFreqKhz / 1000} MHz) -> ${if (ok) "OK" else "FAILED"}"
                            )
                            if (ok) {
                                lastWrittenGpuOppIndex = targetOppIdx
                            }
                        }
                    }
                }

                gpuLiveState = GpuLiveState(
                    available = true,
                    loadPct = rawGpuLoad,
                    smoothedLoadPct = smoothedGpuLoad,
                    loadSourcePath = activeLoadPath,
                    targetSortedPos = selection.targetPos,
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
                rawCurrentMa = powerReading.currentMa,
                rawVoltageMv = powerReading.voltageMv,
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
        _telemetry.update { it.copy(fps = -1f) }
    }

    private suspend fun runFpsSamplerLoop() {
        var lastPkg = ""
        var inactivePublished = false
        val lastDiagByKey = mutableMapOf<String, String>()
        val loggedRaw = mutableSetOf<String>()
        var lastLayerListRt = 0L
        var layerNames = emptyList<String>()
        val lastPresentTs = mutableMapOf<String, Long>()
        var prevTimestats = emptyMap<String, Long>()
        var consecutiveErrors = 0
        var lastSource = ""
        var kernelPath: String? = null
        var kernelChecked = false
        var kernelAwk = false
        var sfLastFrames = -1L
        var sfLastRt = 0L
        var sfUnsupported = false

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
            val token = if (kernelAwk) r.stdout.trim().split(Regex("\\s+")).getOrNull(1) else r.stdout.trim().split(Regex("\\s+")).firstOrNull()
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

        // Method 1: dumpsys SurfaceFlinger --latency <layer> (exact present timestamps)
        suspend fun sampleLatency(pkg: String): Float? {
            val now = SystemClock.elapsedRealtime()
            if (layerNames.isEmpty() || now - lastLayerListRt > 5_000L) {
                val r = rootShell.exec("dumpsys SurfaceFlinger --list 2>/dev/null")
                var names = r.stdout.lines().map { it.trim() }.filter {
                    it.isNotEmpty() && it.length < 200 &&
                        !it.contains("RequestedLayerState") && !it.contains("parentId=")
                }
                if (names.isEmpty()) {
                    // Newer Android prints a verbose dump for --list; take real layer names from timestats instead.
                    if (!timestatsEnabled) {
                        rootShell.exec("dumpsys SurfaceFlinger --timestats -enable")
                        timestatsEnabled = true
                    }
                    val t = rootShell.exec("dumpsys SurfaceFlinger --timestats -dump | grep -E '^layerName'")
                    names = t.stdout.lines().mapNotNull { l ->
                        l.trim().takeIf { it.startsWith("layerName") }?.substringAfter("=", "")?.trim()
                    }.filter { it.isNotEmpty() }
                }
                if (names.isNotEmpty()) {
                    layerNames = names
                    lastLayerListRt = now
                }
            }
            if (layerNames.isEmpty()) return null
            var cands = layerNames.filter { it.contains(pkg) }
            var usedFallback = false
            if (cands.isEmpty()) {
                usedFallback = true
                cands = layerNames.filter { n ->
                    ignored.none { n.contains(it, ignoreCase = true) } &&
                        (n.contains("SurfaceView") || n.contains("/"))
                }
            }
            if (cands.isEmpty()) return null
            cands = cands.sortedByDescending { it.contains("SurfaceView") }.take(6)

            var best: Float? = null
            var parsedAny = false
            for (name in cands) {
                val r = rootShell.exec("dumpsys SurfaceFlinger --latency ${q(name)} 2>/dev/null")
                val rows = r.stdout.lines().drop(1).map { it.trim().split(Regex("\\s+")) }
                var ts = emptyList<Long>()
                for (col in intArrayOf(1, 2, 0)) {
                    ts = rows.mapNotNull { p -> p.getOrNull(col)?.toLongOrNull() }
                        .filter { it in 1L until (Long.MAX_VALUE / 2) }
                    if (ts.isNotEmpty()) break
                }
                if (ts.isEmpty()) {
                    val rawKey = "$name|${r.stdout.take(120)}"
                    if (loggedRaw.size < 20 && loggedRaw.add(rawKey)) {
                        log(LogLevel.INFO, "FPS", "latency raw for ${name.take(80)}: ${r.stdout.take(120).replace("\n", " / ")}")
                    }
                    continue
                }
                parsedAny = true
                val newest = ts.maxOrNull() ?: continue
                val prevNewest = lastPresentTs[name]
                lastPresentTs[name] = newest
                if (prevNewest == null) continue
                val newFrames = ts.count { it > prevNewest }
                val spanSec = (newest - prevNewest) / 1_000_000_000f
                val f = if (newFrames > 0 && spanSec > 0f) newFrames / spanSec else 0f
                if (best == null || f > best!!) best = f
            }
            diag(
                "latency method: ${cands.size} layer(s)" +
                    (if (usedFallback) " (no package match, fallback)" else "") +
                    ", parsed=$parsedAny: ${cands.take(3).joinToString(" | ")}"
            )
            return if (parsedAny) (best ?: 0f) else null
        }

        // Method 2: SurfaceFlinger timestats frame counters
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
                        if (frames != null) currentLayers[cl] = frames
                        currentLayer = null
                    }
                }
            }
            val prev = prevTimestats
            prevTimestats = currentLayers
            if (prev.isEmpty() || currentLayers.isEmpty()) return null
            var matching = currentLayers.filterKeys { it.contains(pkg) }
            if (matching.isEmpty()) {
                matching = currentLayers.filterKeys { n -> ignored.none { n.contains(it, ignoreCase = true) } }
            }
            var maxDelta = 0L
            for ((name, count) in matching) {
                val p = prev[name] ?: continue
                val d = (count - p).coerceAtLeast(0L)
                if (d > maxDelta) maxDelta = d
            }
            diag("timestats method: ${currentLayers.size} layer(s) seen: ${currentLayers.keys.take(3).joinToString(" | ")}")
            return maxDelta.toFloat()
        }

        var lastSampleRt = 0L
        while (engineScope.isActive && controlJob?.isActive == true) {
            val cfg = _config.value
            val active = cfg.overlayEnabled && (cfg.overlayExpanded || cfg.overlayDetailed) && isScreenInteractive
            if (!active) {
                if (!inactivePublished) {
                    _telemetry.update { it.copy(fps = -1f) }
                    inactivePublished = true
                }
                lastPkg = ""
                lastPresentTs.clear()
                prevTimestats = emptyMap()
                lastSampleRt = 0L
                consecutiveErrors = 0
                delay(1000L)
                continue
            }
            inactivePublished = false

            val pkg = _telemetry.value.foregroundPackage
            if (pkg.isBlank()) {
                diag("No foreground package detected yet")
                _telemetry.update { it.copy(fps = -1f) }
                delay(1000L)
                continue
            }
            if (pkg != lastPkg) {
                lastPkg = pkg
                lastPresentTs.clear()
                prevTimestats = emptyMap()
                lastSampleRt = 0L
                lastLayerListRt = 0L
                _telemetry.update { it.copy(fps = 0f) }
            }

            val g = sampleKernel() ?: sampleServiceCall()
            var source = "kernel/service"
            var a: Float? = null
            val sampleRt = SystemClock.elapsedRealtime()
            val fpsVal: Float = if (g != null && g > 0f) {
                g
            } else {
                a = sampleLatency(pkg)
                if (a != null && a > 0f) {
                    source = "latency"
                    a
                } else {
                    val b = sampleTimestats(pkg)
                    val elapsed = if (lastSampleRt > 0L) (sampleRt - lastSampleRt) / 1000f else 0f
                    if (b != null && b > 0f && elapsed > 0f) {
                        source = "timestats"
                        b / elapsed
                    } else {
                        source = "idle"
                        g ?: a ?: 0f
                    }
                }
            }
            if (source != lastSource) {
                lastSource = source
                log(LogLevel.INFO, "FPS", "Source: $source (kernelNode=${kernelPath ?: "none"}, sfCounter=${if (sfUnsupported) "unsupported" else "ok"})")
            }
            lastSampleRt = sampleRt

            if (g == null && a == null && fpsVal == 0f) {
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
        storage.saveConfig(next)
        _telemetry.update { it.copy(modeOverride = next.modeOverride) }

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
