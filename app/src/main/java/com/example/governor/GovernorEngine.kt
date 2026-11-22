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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single authoritative governor controller for CoreGovernor v2.
 * GPU control operates solely via real OPP indexes written to /proc/gpufreqv2/fix_target_opp_index.
 */
class GovernorEngine(context: Context) {

    private val appContext = context.applicationContext
    val storage = ConfigStorage(appContext)
    val rootShell = RootShell()
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
    private var smoothedGpuLoad = 0f

    private val lastWrittenCpuFreq = mutableMapOf<Int, Long>()
    private var lastWrittenGpuOppIndex: Int? = null
    private val failedWriteCooldownUntil = mutableMapOf<String, Long>()

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

            for (policy in hw.cpuPolicies) {
                val ok = safeWriteSysfs("${policy.path}/scaling_governor", "powersave")
                if (ok) {
                    log(LogLevel.INFO, "Governor", "policy${policy.policyId} governor -> powersave (orig=${policy.origGovernor})")
                }
            }

            resetLoopState()
            _telemetry.update { it.copy(isRunning = true, rootGranted = true) }
            log(LogLevel.INFO, "Engine", "Control loop started (poll=${_config.value.pollIntervalMs}ms).")

            try {
                while (isActive) {
                    val cycleStart = System.currentTimeMillis()
                    runControlTick(_hardwareSpec.value, cycleStart)
                    val elapsed = System.currentTimeMillis() - cycleStart
                    val targetInterval = _config.value.pollIntervalMs.coerceIn(50L, 1000L)
                    val sleepMs = (targetInterval - elapsed).coerceAtLeast(15L)
                    delay(sleepMs)
                }
            } finally {
                restoreOriginalHardwareInternal()
            }
        }
    }

    fun stopAndRestore() {
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

    private suspend fun restoreOriginalHardwareInternal() {
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
        lastWrittenCpuFreq.clear()
        lastWrittenGpuOppIndex = null
        _telemetry.update {
            it.copy(
                isRunning = false,
                burstActive = false,
                burstRemainingMs = 0L,
                tempThrottleActive = false,
                powerThrottleActive = false
            )
        }
        log(LogLevel.INFO, "Restore", "Original CPU governors and GPU unpinned (-1) restored.")
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
            val load = SysfsParsers.computeClusterLoadPct(p.relatedCpus, prevCpuTicks, currTicks)
            clusterRawLoads[p.policyId] = load
            val prevLoad = prevRawClusterLoad[p.policyId] ?: load
            maxLoadSpike = maxOf(maxLoadSpike, load - prevLoad)
            prevRawClusterLoad[p.policyId] = load
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

        val effectiveCpuCapPct = GovernorMath.combineCaps(currentTempCpuCapPct, currentPowerCapPct, cfg.safetyFloorPct)
        val effectiveGpuCapPct = GovernorMath.combineCaps(currentTempGpuCapPct, currentPowerCapPct, cfg.safetyFloorPct)

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
                capPct = effectiveCpuCapPct,
                loadPct = smoothed,
                forceMaxDuringBurst = burstActive
            )

            val liveGov = batch["${p.path}/scaling_governor"]?.trim().orEmpty().ifBlank { "powersave" }
            if (liveGov != "powersave") {
                safeWriteSysfs("${p.path}/scaling_governor", "powersave")
            }

            val prevWritten = lastWrittenCpuFreq[p.policyId]
            if (targetFreq > 0L && targetFreq != prevWritten) {
                val ok = writeCpuPinnedFreq(p.path, targetFreq, prevWritten)
                if (ok) {
                    lastWrittenCpuFreq[p.policyId] = targetFreq
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
                    governor = liveGov,
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

        // 8. Push telemetry & history sample
        val avgCpuLoad = if (clusterLiveList.isNotEmpty()) {
            clusterLiveList.map { it.loadPct }.average().toFloat()
        } else 0f

        val pollDuration = System.currentTimeMillis() - nowMs
        _telemetry.update { prev ->
            val nextHistory = (prev.history + TelemetrySample(
                timestampMs = nowMs,
                tempC = tempC,
                watts = avgWatts,
                avgCpuLoadPct = avgCpuLoad,
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

    private suspend fun writeCpuPinnedFreq(policyPath: String, targetKhz: Long, prevWrittenKhz: Long?): Boolean {
        val freqStr = targetKhz.toString()
        return if (prevWrittenKhz == null || targetKhz >= prevWrittenKhz) {
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
            failedWriteCooldownUntil[path] = now + 6_000L
            log(LogLevel.ERROR, "SysfsWrite", "Write failed [$path <- $value]: ${res.error ?: res.stdout}")
            return false
        }
        return true
    }

    fun updateConfig(transform: (GovernorConfig) -> GovernorConfig) {
        val next = transform(_config.value)
        _config.value = next
        storage.saveConfig(next)
        _telemetry.update { it.copy(modeOverride = next.modeOverride) }
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
        smoothedGpuLoad = 0f
        lastWrittenCpuFreq.clear()
        lastWrittenGpuOppIndex = null
        failedWriteCooldownUntil.clear()
        gedLastValByPath.clear()
        gedChangedByPath.clear()
        lockedGedLoadPath = null
        wattWindow.clear()
        overPowerSinceMs = null
        currentPowerCapPct = 100f
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
