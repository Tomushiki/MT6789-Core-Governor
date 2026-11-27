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
import android.content.pm.PackageManager
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
import com.example.model.EffectiveGameConfig
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.boostStrengthFor
import com.example.model.GpuLiveState
import com.example.model.HardwareSpec
import com.example.model.AfterburnState
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/** How long the Afterburn app stays counted as in front after the foreground package briefly changes. */
private const val BOOST_FG_GRACE_MS = 3_000L

/** How long a power reading may be reused when a fresh one cannot be read. */
private const val STALE_POWER_REUSE_MS = 2_500L

/** v3.8.1: weight of the newest power sample in the overlay's live watts (higher = faster, noisier). */
private const val LIVE_WATTS_ALPHA = 0.6f

/** v3.8.2: how often the overlay's own power reader samples the battery nodes. */
private const val LIVE_SAMPLER_PERIOD_MS = 120L

/** v3.8.2: while the overlay's own reader has published this recently, the control tick leaves liveWatts alone. */
private const val LIVE_SAMPLER_FRESH_MS = 600L

/**
 * Single authoritative governor controller for CoreGovernor v2.
 * GPU control operates solely via real OPP indexes written to /proc/gpufreqv2/fix_target_opp_index.
 */
class GovernorEngine(context: Context) {

    // IMPORTANT: log state must be declared FIRST. Stores below (RatingStore, etc.) call log()
    // from their constructors; if these were declared later they'd still be null -> crash on startup.
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    private val logIdSeq = AtomicLong(1L)

    private val appContext = context.applicationContext
    val storage = ConfigStorage(appContext)
    val rootShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell", msg) }
    }

    /**
     * v3.7.0 second root shell for slow, occasional jobs (thread scan, process scans, FPS dumpsys), so they
     * can never queue in front of the control loop's power/frequency reads. Writes stay on [rootShell].
     */
    val bgShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell(bg)", msg) }
    }

    /**
     * v3.7.0 third root shell: process scans (Stutter Hunter, System tab) get their own lane so they can
     * never queue behind the thread scan or the FPS dumpsys on [bgShell]. Read-only; writes stay on [rootShell].
     */
    val scanShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell(scan)", msg) }
    }

    /**
     * v4.0.8 fourth root shell: only the Game Thread Controller's scan and taskset writes. A scan can hold a shell for
     * several seconds in a busy game; on [bgShell] it queued the FPS reads behind it (FPS updated about every 8 s).
     */
    val threadShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell(thread)", msg) }
    }
    /**
     * v4.1.1 fifth root shell: only the smooth limiter's fast switcher writes. On [rootShell] those writes waited behind
     * the control loop's reads (about 160 ms per write in the 4.1.0 log), so slices could not be shorter than that.
     * The control loop does not write a cluster while the fast switcher owns it, and hands over only after the
     * fast switcher's in-flight write has finished (see [fastInFlight]).
     */
    val fastShell = RootShell().apply {
        onLog = { msg -> log(LogLevel.INFO, "RootShell(fast)", msg) }
    }
    private val discovery = HardwareDiscovery(rootShell)
    private val foregroundDetector = ForegroundDetector(appContext, rootShell)

    /** Sensor pinning (Nifty features). Own settings; runs only while the governor is running. */
    val sensorPinner = SensorPinner(appContext) { level, tag, msg -> log(level, tag, msg) }

    /** Root keep-alive protections + watchdog manager (Fix 4). */
    val processProtection = ProcessProtectionManager(appContext, rootShell) { level, tag, msg ->
        log(level, tag, msg)
    }

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
        metrics.snapshotProvider = { pkg ->
            val cleanPkg = pkg.ifBlank { _telemetry.value.foregroundPackage }
            val preparedCfg = prepareAutoTuneForSessionStart(cleanPkg)
            val eff = AutoTuneLogic.resolveEffectiveForGame(preparedCfg, cleanPkg)
            SessionTweakSnapshot.capture(preparedCfg, eff, cleanPkg)
        }
        metrics.onSessionSaved = { savedInfo ->
            engineScope.launch(Dispatchers.Default) {
                handleSessionSavedForAutoTune(savedInfo)
            }
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

    /** v4.2.0: a week of battery level/power/temperature history for the Energy screen graphs. */
    val batteryHistory = BatteryHistoryStore(java.io.File(appContext.filesDir, "battery_history.csv"), engineScope)

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

    /** v3.6.6 Charge sleep guard (stops background apps while charging with the screen off) and fast wake. */
    val chargeGuard = ChargeGuard(
        ctx = appContext,
        rootShell = rootShell,
        scope = engineScope,
        isCharging = { stableBatteryState.isPluggedOrCharging || isDeviceCharging() },
        onWakeHint = { onWakeHint() }
    ) { l, t, m -> log(l, t, m) }

    @Volatile private var wakeBoostUntilMs = 0L
    private val WAKE_BOOST_MS = 2500L

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

    private val configUpdateLock = Any()

    // v4.0.0 Zero-allocation per-game override cache (recomputed ONLY when config instance or fg package changes).
    private var cachedResolveCfgRef: GovernorConfig? = null
    private var cachedResolvePkg: String = ""
    private var cachedEffectiveGameProfile: EffectiveGameConfig? = null
    private var lastAutoTunePrepPkg: String = ""

    private fun resolveEffectiveGameCached(cfg: GovernorConfig, activeMode: GovernorMode, fgPkg: String): EffectiveGameConfig? {
        if (activeMode != GovernorMode.GAME || fgPkg.isBlank()) {
            cachedResolveCfgRef = null
            cachedResolvePkg = ""
            cachedEffectiveGameProfile = null
            return null
        }
        if (cfg !== cachedResolveCfgRef || fgPkg != cachedResolvePkg || cachedEffectiveGameProfile == null) {
            cachedResolveCfgRef = cfg
            cachedResolvePkg = fgPkg
            cachedEffectiveGameProfile = AutoTuneLogic.resolveEffectiveForGame(cfg, fgPkg)
        }
        return cachedEffectiveGameProfile
    }

    private fun prepareAutoTuneForSessionStart(pkg: String): GovernorConfig {
        if (pkg.isBlank()) return _config.value
        val cur = _config.value
        val quickCheck = AutoTuneLogic.prepareSessionStart(cur, pkg)
        if (quickCheck === cur || quickCheck == cur) return cur
        synchronized(configUpdateLock) {
            val latest = _config.value
            val prepared = AutoTuneLogic.prepareSessionStart(latest, pkg)
            if (prepared != latest) {
                _config.value = prepared
                storage.saveConfigDebounced(prepared)
                val st = prepared.autoTuneByPkg[pkg]
                val cand = st?.pendingCandidate
                if (cand != null) {
                    log(
                        LogLevel.INFO,
                        "AutoTune",
                        "Trial #${st.pendingTrialId} ready for $pkg: ${cand.param.name} -> ${AutoTuneLogic.formatValue(cand.param, cand.value)}"
                    )
                }
            }
            return _config.value
        }
    }

    private fun handleSessionSavedForAutoTune(session: SessionInfo) {
        val pkg = session.packageName
        if (pkg.isBlank()) return
        var evaluatedRecord: com.example.model.AutoTuneTrialRecord? = null
        var convergedNow = false
        updateConfig(markMidSession = false) { cfg ->
            val st = cfg.autoTuneByPkg[pkg] ?: return@updateConfig cfg
            if (!st.enabled || st.paused) return@updateConfig cfg
            val prevCount = st.trials.size
            val nextCfg = AutoTuneLogic.evaluateCompletedSession(cfg, session)
            val nextSt = nextCfg.autoTuneByPkg[pkg]
            if (nextSt != null && nextSt.trials.size > prevCount) {
                evaluatedRecord = nextSt.trials.lastOrNull()
            }
            if (nextSt != null && !st.converged && nextSt.converged) {
                convergedNow = true
            }
            nextCfg
        }
        evaluatedRecord?.let { rec ->
            log(
                LogLevel.INFO,
                "AutoTune",
                "$pkg Trial #${rec.trialId} -> ${rec.status.name} (${rec.reasonCode}, avg ${"%.1f".format(Locale.US, rec.avgFps)} FPS, p5 ${"%.1f".format(Locale.US, rec.p5Fps)})"
            )
        }
        if (convergedNow) {
            log(LogLevel.INFO, "AutoTune", "$pkg auto-tune converged.")
        }
    }

    private fun isTuningConfigChange(prev: GovernorConfig, next: GovernorConfig): Boolean {
        return prev.gameMode != next.gameMode ||
            prev.tempThrottle != next.tempThrottle ||
            prev.powerThrottle != next.powerThrottle ||
            prev.afterburn != next.afterburn ||
            prev.boostPackages != next.boostPackages ||
            prev.boostStrengthByPkg != next.boostStrengthByPkg ||
            prev.ramPinMax != next.ramPinMax ||
            prev.ramPinGameOnly != next.ramPinGameOnly ||
            prev.stutterHunter != next.stutterHunter ||
            prev.gameThreadController.enabled != next.gameThreadController.enabled ||
            prev.gameThreadController.mappings != next.gameThreadController.mappings ||
            prev.adaptiveRefreshEnabled != next.adaptiveRefreshEnabled ||
            prev.adaptiveIdleHz != next.adaptiveIdleHz ||
            prev.adaptiveActiveHz != next.adaptiveActiveHz ||
            prev.perGameProfiles != next.perGameProfiles ||
            prev.safetyFloorPct != next.safetyFloorPct ||
            prev.efficiencyLearningEnabled != next.efficiencyLearningEnabled ||
            prev.efficiencyMode != next.efficiencyMode
    }

    /** v3.6.7/3.6.8 Game Thread Controller: thread-level taskset CPU affinity in Game mode & thread history. */
    val gameThreadRuntime = GameThreadRuntime(
        shellExec = { cmd -> threadShell.exec(cmd) },
        log = { l, t, m -> log(l, t, m) },
        onThreadsObserved = { pkg, procName, observedAtMs, threads ->
            recordObservedGameThreads(pkg, procName, observedAtMs, threads)
        }
    )

    fun setSmartUiVisible(visible: Boolean) {
        appDemand.uiActive = visible
        refreshCtl.uiActive = visible
    }

    private fun resolveAppDisplayName(packageName: String): String {
        val clean = packageName.trim()
        if (clean.isEmpty()) return ""
        return try {
            val pm = appContext.packageManager ?: return ""
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(clean, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(clean, 0)
            }
            pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun recordObservedGameThreads(
        packageName: String,
        processName: String,
        observedAtMs: Long,
        threads: List<LiveThreadInfo>
    ) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || threads.isEmpty()) return
        val displayName = resolveAppDisplayName(cleanPkg)
        val cpuCount = GameThreadLogic.availableCpuCount(_hardwareSpec.value)
        updateConfig { cfg ->
            val updatedGtc = GameThreadHistoryLogic.mergeObservation(
                config = cfg.gameThreadController,
                packageName = cleanPkg,
                displayName = displayName,
                processName = processName,
                observedThreads = threads,
                observedAtMs = observedAtMs,
                availableCpuCount = cpuCount
            )
            if (updatedGtc == cfg.gameThreadController) cfg else cfg.copy(gameThreadController = updatedGtc)
        }
    }

    suspend fun inspectForegroundGameThreads(nowMs: Long = System.currentTimeMillis()): GameThreadInspectionResult {
        val rootOk = rootShell.ensureSession()
        val fgPkg = foregroundDetector.detectForegroundPackage(nowMs)
        val cfg = _config.value
        val cpuCount = GameThreadLogic.availableCpuCount(_hardwareSpec.value)
        return gameThreadRuntime.inspectForegroundGame(
            nowMs = nowMs,
            fgPkg = fgPkg,
            gamePackages = cfg.gamePackages,
            rootAvailable = rootOk,
            availableCpuCount = cpuCount,
            config = cfg.gameThreadController
        )
    }

    private val _hardwareSpec = MutableStateFlow(HardwareSpec())
    val hardwareSpec: StateFlow<HardwareSpec> = _hardwareSpec.asStateFlow()

    private val _config = MutableStateFlow(storage.loadConfig())
    val config: StateFlow<GovernorConfig> = _config.asStateFlow()

    private val _presets = MutableStateFlow<List<PresetItem>>(emptyList())
    val presets: StateFlow<List<PresetItem>> = _presets.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState(modeOverride = _config.value.modeOverride))
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private val _afterburnEvents = MutableSharedFlow<com.example.service.AfterburnTransitionEvent>(extraBufferCapacity = 8)
    val afterburnEvents: SharedFlow<com.example.service.AfterburnTransitionEvent> = _afterburnEvents.asSharedFlow()

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
    private val failedWriteCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // v2.7.4 adaptive polling state (written by the control tick, read by the control loop)
    private var pollState = PollPolicy.State()
    @Volatile
    private var pollNextIntervalMs = 0L
    private var pollStretchLogged = false
    private var pollLastLogMs = 0L

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

    // v3.0.0 Afterburn state
    private var afterburnDecision = AfterburnDecision(0f, AfterburnState.OFF)
    private var boostSelectedUntilMs = 0L
    private var lastBoostPkg = ""
    private var smoothedSagMv = 0f
    private var lastSagSampleMs = 0L
    private var lastAfterburnWhyMs = 0L
    private val batteryTruthModel = BatteryTruthModel().also {
        it.load(appContext.getSharedPreferences("battery_truth", 0).getString("profile", null))
    }
    private var batteryTruth = BatteryTruthState()

    // Power throttle state
    private val wattWindow = ArrayDeque<Float>()
    // v3.8.1: fast watts for the overlay (EMA over the raw samples), 0 = no sample yet.
    private var liveWattsEma = 0f

    // v3.8.2: the overlay's own power reader. It runs on its own timer, so a slow control tick in a game
    // (many frequency writes through the root shell) no longer slows the live watts.
    private var liveSamplerJob: Job? = null
    @Volatile private var liveSamplerPublishedAtMs = 0L
    private var overPowerSinceMs: Long? = null
    private var lastPowerStepMs = 0L
    private var lastPowerOverMs = 0L
    private var currentPowerCapPct = 100f
    private var gpuShareSmoothed = 0.5f
    private val smoothedClusterShare = mutableMapOf<Int, Float>()
    private var lastPowerSplitLogMs = 0L
    private var lastDominantComponent: String? = null

    // v3.8.0 smooth power limit state
    private val smoothTrim = SmoothPowerLimiter.Trim()
    private val smoothSched = java.util.concurrent.ConcurrentHashMap<String, SmoothPowerLimiter.DutyScheduler>()
    private val fastPlans = java.util.concurrent.ConcurrentHashMap<String, FastPlan>()
    private val fastOwnedPrev = HashSet<String>()
    private var fastJob: Job? = null
    private val FAST_TICK_MS = 20L
    private val FAST_PLAN_STALE_MS = 1500L

    // v4.0.9 slice learning, write latency and drift counting for the fast switcher
    private val sliceLearner = SliceLearner()
    private val writeLatCpu = WriteLatencyTracker()
    private val writeLatOther = WriteLatencyTracker()
    private val writeLatFast = WriteLatencyTracker()
    private val fastInFlight = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val driftTotal = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val fastWrittenMirror = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fastWrittenAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fastDriftStamp = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fastForceRewrite = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val fastLastFixMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fastFixCount = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val fastFixLogMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private var lastLatLogMs = 0L
    private var lastSlowWriteLogMs = 0L
    private var lastManualSliceMs = -1
    private var lastSliceAuto = false

    /** What the fast switcher needs to alternate one component between two steps on its own timer. */
    private class FastPlan(
        val isGpu: Boolean,
        val path: String,
        val lowKhz: Long,
        val highKhz: Long,
        val duty: Float,
        val desiredKhz: Long,
        val maxOnly: Boolean,
        val gpuOppByFreq: Map<Long, Int>,
        val stampMs: Long
    )

    private class SmoothStep(val lowKhz: Long, val highKhz: Long, val duty: Float)
    private val smoothPlanText = HashMap<String, String>()
    private val smoothClusterPowerOnlyCap = HashMap<Int, Float>()
    private var smoothActive = false
    private var smoothGateReason: String? = null
    private var smoothDisabledUntilMs = 0L
    private var smoothLastTickMs = 0L
    private var smoothTickGapMs = 0f
    private var smoothLastLogMs = 0L
    private var smoothLastGateLogMs = 0L
    private var smoothTrimValue = 0f

    private fun resetSmoothPowerLimit() {
        smoothTrim.reset()
        smoothSched.values.forEach { it.reset() }
        smoothPlanText.clear()
        smoothClusterPowerOnlyCap.clear()
        smoothActive = false
        smoothTrimValue = 0f
        fastPlans.clear()
        fastWrittenMirror.clear()
        fastWrittenAtMs.clear()
        fastForceRewrite.clear()
        fastJob?.cancel()
        fastJob = null
    }

    private fun fastPick(plan: FastPlan, high: Boolean): Long =
        if (plan.desiredKhz <= plan.lowKhz) plan.desiredKhz
        else if (high && plan.desiredKhz >= plan.highKhz) plan.highKhz
        else plan.lowKhz

    /**
     * v3.8.0 fast switcher: its own 20 ms timer that only flips a component between two adjacent steps, so the
     * switch speed does not depend on how long a full control tick takes. The control loop publishes a plan
     * about every tick; a plan older than FAST_PLAN_STALE_MS is ignored and the loop writes again as usual.
     */
    private suspend fun fastSwitchLoop() {
        val lastWritten = HashMap<String, Long>()
        var idleSinceMs = 0L
        while (engineScope.isActive && controlJob?.isActive == true) {
            val now = System.currentTimeMillis()
            var anyFresh = false
            for ((id, plan) in fastPlans) {
                if (now - plan.stampMs > FAST_PLAN_STALE_MS) {
                    lastWritten.remove(id)
                    fastWrittenMirror.remove(id)
                    continue
                }
                anyFresh = true
                val sched = smoothSched.getOrPut(id) { SmoothPowerLimiter.DutyScheduler() }
                val high = if (plan.desiredKhz <= plan.lowKhz) {
                    sched.reset()
                    false
                } else {
                    sched.update(now, plan.duty)
                }
                val f = fastPick(plan, high)
                if (fastForceRewrite.remove(id) != null) lastWritten.remove(id)
                if (lastWritten[id] == f) continue
                fastInFlight[id] = now
                val ok = try {
                    if (plan.isGpu) {
                        val idx = plan.gpuOppByFreq[f]
                        idx != null && safeWriteSysfs(plan.path, idx.toString(), fastShell)
                    } else if (plan.maxOnly) {
                        safeWriteSysfs("${plan.path}/scaling_max_freq", f.toString(), fastShell)
                    } else {
                        writeCpuPinnedFreq(plan.path, f, f >= (lastWritten[id] ?: 0L), fastShell)
                    }
                } finally {
                    fastInFlight.remove(id)
                }
                if (ok) {
                    lastWritten[id] = f
                    fastWrittenMirror[id] = f
                    fastWrittenAtMs[id] = System.currentTimeMillis()
                } else {
                    lastWritten.remove(id)
                    fastWrittenMirror.remove(id)
                }
            }
            if (anyFresh) {
                idleSinceMs = 0L
                delay(FAST_TICK_MS)
            } else {
                if (idleSinceMs == 0L) idleSinceMs = now
                if (now - idleSinceMs > 5_000L) break
                delay(100L)
            }
        }
        fastJob = null
    }

    /**
     * Plans the two adjacent steps for one component from model-predicted power, or null for the plain
     * behaviour. The control loop treats [SmoothStep.lowKhz] as the cap; the fast switcher does the alternating.
     */
    private fun smoothCapStep(
        compId: String,
        stepsKhz: List<Long>,
        capPct: Float,
        utilPct: Float,
        curKhz: Long,
        scenarioKey: String,
        minConfidencePct: Float,
        trim: Float
    ): SmoothStep? {
        if (stepsKhz.size < 2) {
            smoothSched[compId]?.reset()
            return null
        }
        val steps = ArrayList<SmoothPowerLimiter.Step>(stepsKhz.size)
        for (f in stepsKhz) {
            // Same work at another frequency means another utilisation.
            val util = if (curKhz > 0L) (utilPct * curKhz.toFloat() / f.toFloat()).coerceIn(1f, 100f) else utilPct.coerceIn(1f, 100f)
            val (mw, conf) = powerModel.predictPowerMw(compId, (f / 1000L).toInt(), util, scenarioKey)
            steps.add(SmoothPowerLimiter.Step(f, mw, conf))
        }
        val plan = SmoothPowerLimiter.plan(steps, capPct / 100f, minConfidencePct, trim)
        if (!plan.smooth) {
            smoothSched[compId]?.reset()
            smoothPlanText[compId] = "plain (${plan.reason})"
            return null
        }
        val twoSteps = plan.highIdx > plan.lowIdx
        smoothPlanText[compId] = if (twoSteps) {
            "${steps[plan.lowIdx].freqKhz / 1000}/${steps[plan.highIdx].freqKhz / 1000}MHz ${(plan.duty * 100f).roundToInt()}%"
        } else {
            "${steps[plan.lowIdx].freqKhz / 1000}MHz"
        }
        return SmoothStep(steps[plan.lowIdx].freqKhz, steps[plan.highIdx].freqKhz, if (twoSteps) plan.duty else 0f)
    }

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
    @Volatile private var cachedBmStatus = -1
    @Volatile private var cachedBmPlugged = -1
    private var cachedBattTempC = 0f
    private var cachedBattVoltMv = 0f
    private var cachedBattHealthLabel = "n/a"
    private var cachedChargeCounterMah = -1f
    private var cachedCycleCount = -1
    @Volatile private var stableBatteryState = BatteryState.UNKNOWN
    private var candidateBatteryState = BatteryState.UNKNOWN
    private var candidateBatterySinceMs = 0L
    @Volatile private var learnedDischargeSign = 0
    private var dischargeSignScore = 0
    private var smoothedChargeWatts = 0f
    private var lastGoodPowerReading: PowerReading? = null
    private var lastGoodPowerAtMs = 0L

    private fun readSysfsDirect(path: String): Long? = try {
        java.io.File(path).readText().trim().toLongOrNull()
    } catch (_: Throwable) {
        null
    }
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
            batteryHistory.load()
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
            // v4.3: root helper that keeps the frequency/GPU files open for the fast switcher (falls back to the shell).
            fastShell.enableFastWriter(appContext.applicationInfo.sourceDir)

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
            startLiveWattsSampler()
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
                    val baseInterval = _config.value.pollIntervalMs.coerceIn(50L, 1000L)
                    val nextInterval = pollNextIntervalMs
                    val targetInterval = if (nextInterval > baseInterval) nextInterval else baseInterval
                    val sleepMs = (targetInterval - elapsed).coerceAtLeast(15L)
                    if (targetInterval > baseInterval) {
                        sleepStretched(sleepMs, baseInterval)
                    } else {
                        delay(sleepMs)
                    }
                }
            } finally {
                metrics.onStopped()
                fastPlans.clear()
                fastJob?.cancel()
                fastJob = null
                stopFpsSampler()
                stopLiveWattsSampler()
                sensorPinner.setGameActive(false)
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

    /**
     * Stretched (adaptive) sleep. Sleeps in slices of the configured interval so a touch, a screen
     * change or a config change ends the stretch within one normal interval, as before.
     */
    private suspend fun sleepStretched(totalMs: Long, sliceMs: Long) {
        val touchAtStart = interaction.lastTouchMs
        val cfgAtStart = _config.value
        var remaining = totalMs
        while (remaining > 0L) {
            val slice = minOf(remaining, sliceMs.coerceAtLeast(15L))
            delay(slice)
            remaining -= slice
            if (remaining <= 0L) return
            val cfgNow = _config.value
            if (interaction.lastTouchMs != touchAtStart ||
                !isScreenInteractive ||
                cfgNow.pollIntervalMs != cfgAtStart.pollIntervalMs ||
                !cfgNow.adaptivePollingEnabled ||
                cfgNow.modeOverride != cfgAtStart.modeOverride
            ) {
                return
            }
        }
    }

    /** Power button seen while the screen is off: leave sleep saving and boost clocks before the screen lights. */
    private fun onWakeHint() {
        if (isScreenInteractive) return
        wakeBoostUntilMs = System.currentTimeMillis() + WAKE_BOOST_MS
        if (isSleepSaving) engineScope.launch { exitSleepSaving(SleepWakeReason.SCREEN_ON) }
    }

    fun onScreenOff() {
        screenOff.onScreenOff()
        chargeGuard.onScreenOff()
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
        chargeGuard.onScreenOn()
        if (ChargeGuardStore.load(appContext).fastWake) {
            wakeBoostUntilMs = System.currentTimeMillis() + WAKE_BOOST_MS
        }
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
        // The charge sleep guard replaces the "keep normal while charging" exemption while it is on.
        if (cfg.screenOffExemptCharging && !ChargeGuardStore.load(appContext).enabled &&
            (stableBatteryState.isPluggedOrCharging || isDeviceCharging())
        ) {
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
            if (System.currentTimeMillis() < wakeBoostUntilMs) return@withLock
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
        batteryHistory.flush()
        appDemand.flush()
        screenOff.stop()
        chargeGuard.stop()
        appDemand.release()
        smartWasOn = false
        interaction.setActive(false, false)
        refreshCtl.shutdown()
        gameThreadRuntime.reset()
        metrics.onStopped()
        stopFpsSampler()
        stopLiveWattsSampler()
        fastShell.releaseFastWriter()
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
        val rawCfg = _config.value
        pollNextIntervalMs = 0L

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

        val tempSensor = hw.thermalSensors.firstOrNull { it.path == rawCfg.selectedTempSensorPath }
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
        if (rawCfg.overlayEnabled && rawCfg.overlayDetailed) {
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

        val activeMode = when (rawCfg.modeOverride) {
            ModeOverride.FORCE_NORMAL -> GovernorMode.NORMAL
            ModeOverride.FORCE_GAME -> GovernorMode.GAME
            ModeOverride.AUTO -> if (fgPkg in rawCfg.gamePackages) GovernorMode.GAME else GovernorMode.NORMAL
        }
        // Activate next Auto-Tune trial ONLY at game session start (never mid-session).
        val cfg = if (activeMode == GovernorMode.GAME && fgPkg.isNotBlank()) {
            if (!metrics.recording.value && fgPkg != lastAutoTunePrepPkg) {
                lastAutoTunePrepPkg = fgPkg
                prepareAutoTuneForSessionStart(fgPkg)
            } else {
                rawCfg
            }
        } else {
            lastAutoTunePrepPkg = ""
            rawCfg
        }
        val effGame = resolveEffectiveGameCached(cfg, activeMode, fgPkg)
        val modeProfile = if (activeMode == GovernorMode.GAME) (effGame?.gameMode ?: cfg.gameMode) else cfg.normalMode
        val effTempThrottle = if (activeMode == GovernorMode.GAME && effGame != null) effGame.tempThrottle else cfg.tempThrottle
        val effPowerThrottle = if (activeMode == GovernorMode.GAME && effGame != null) effGame.powerThrottle else cfg.powerThrottle
        sensorPinner.setGameActive(activeMode == GovernorMode.GAME)

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
        // v3.6.6 fast wake: a power-button wake forces max clocks for a moment, whatever the burst setting is.
        if (nowMs < wakeBoostUntilMs && burstEndTimeMs < wakeBoostUntilMs) burstEndTimeMs = wakeBoostUntilMs
        val burstActive = nowMs < burstEndTimeMs
        val burstRemaining = (burstEndTimeMs - nowMs).coerceAtLeast(0L)

        // 5. Power & Battery State (with 3s hysteresis)
        // v3.7.0: when the root read lacks the battery nodes, read them directly (no shell, so it cannot
        // queue behind a busy root shell). Falls back to nothing if the app is not allowed to read them.
        val rawCurrentOrNull = hw.batteryPower.currentPath?.let { path ->
            batch[path]?.trim()?.toLongOrNull() ?: readSysfsDirect(path)
        }
        val rawVoltageOrNull = hw.batteryPower.voltagePath?.let { path ->
            batch[path]?.trim()?.toLongOrNull() ?: readSysfsDirect(path)
        }
        // A failed/empty read (busy root shell in games) must not count as a 0 W sample: reuse the last good
        // reading, but only briefly. A frozen value used to stay on screen (and drive the cap) indefinitely.
        val readMissing = hw.batteryPower.currentPath != null && hw.batteryPower.voltagePath != null &&
            (rawCurrentOrNull == null || rawVoltageOrNull == null) && lastGoodPowerReading != null &&
            nowMs - lastGoodPowerAtMs < STALE_POWER_REUSE_MS
        val rawCurrent = rawCurrentOrNull ?: 0L
        val rawVoltage = rawVoltageOrNull ?: 0L
        val rawReading = if (readMissing) {
            lastGoodPowerReading!!
        } else if (hw.batteryPower.currentPath != null && hw.batteryPower.voltagePath != null) {
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

        if (!readMissing && rawCurrentOrNull != null && rawVoltageOrNull != null) {
            lastGoodPowerReading = rawReading
            lastGoodPowerAtMs = nowMs
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
                    liveWattsEma = 0f
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
            config = effTempThrottle,
            safetyFloorPct = cfg.safetyFloorPct,
            isChargingOrPlugged = stableBatteryState.isPluggedOrCharging
        )
        latchedPeakTempC = nextLatchedPeak
        currentTempCpuCapPct = nextTempCpuCap
        currentTempGpuCapPct = nextTempGpuCap
        val tempThrottleActive = effTempThrottle.enabled &&
            (currentTempCpuCapPct < 99.5f || currentTempGpuCapPct < 99.5f)

        // 4b. v3.0.0 Afterburn: decided from temperature + battery together, per selected app.
        batteryTruth = batteryTruthModel.update(
            nowMs = nowMs,
            voltageMv = if (rawReading.voltageMv > 0f) rawReading.voltageMv else cachedBattVoltMv,
            dischargeMa = if (stableBatteryState == BatteryState.DISCHARGING) abs(rawReading.currentMa) else -abs(rawReading.currentMa),
            gaugePct = cachedBatteryPct,
            discharging = stableBatteryState == BatteryState.DISCHARGING,
            plugged = stableBatteryState.isPluggedOrCharging
        )
        batteryTruthModel.takeDirtySerialized()?.let { txt ->
            appContext.getSharedPreferences("battery_truth", 0).edit().putString("profile", txt).apply()
            log(LogLevel.INFO, "Battery", "Battery calibration saved (full-charge ${batteryTruth.fullChargeMv.roundToInt()} mV).")
        }
        // Decisions use the voltage-graded level when it is trustworthy, otherwise the gauge.
        // The charge-side grade (v3.6.6) is shown only: the charger inflates voltage, so Afterburn never uses it.
        // v3.7.0: the voltage-graded level may sit at most 10 points under the gauge, so one sag-distorted
        // estimate can never push a healthy phone into the low-battery rules or the 5% cutoff.
        val effBattPct = if (batteryTruth.valid && !batteryTruth.charging && batteryTruth.confidencePct >= 50) {
            batteryTruth.truePct.roundToInt().coerceIn(0, 100).coerceAtLeast((cachedBatteryPct - 10).coerceAtLeast(0))
        } else cachedBatteryPct
        val prevBoostState = afterburnDecision.state
        // v3.7.0: a game that briefly shows another package (login page, WebView, overlay, shade) must not drop
        // the boost: the selected app counts as still in front for a short grace period.
        if (fgPkg in cfg.boostPackages) {
            boostSelectedUntilMs = nowMs + BOOST_FG_GRACE_MS
            lastBoostPkg = fgPkg
        }
        // v3.7.2: the app being boosted (also during the grace period) decides the strength.
        val boostPkg = if (fgPkg in cfg.boostPackages) fgPkg else lastBoostPkg
        val boostStrength = if (effGame != null && boostPkg == fgPkg) effGame.afterburnStrength else cfg.boostStrengthFor(boostPkg)
        val boostAppSelected = fgPkg in cfg.boostPackages || nowMs < boostSelectedUntilMs
        // v3.7.0: one load spike must not trip the voltage-sag cut: judge the sag on a ~2 s average.
        val sagDt = (nowMs - lastSagSampleMs).coerceIn(0L, 2_000L)
        lastSagSampleMs = nowMs
        val sagAlpha = (sagDt / 2_000f).coerceIn(0.05f, 1f)
        smoothedSagMv += (batteryTruth.sagMv - smoothedSagMv) * sagAlpha
        afterburnDecision = AfterburnLogic.decide(
            cfg = cfg.afterburn.copy(strength = boostStrength),
            appSelected = boostAppSelected,
            tempC = tempC,
            throttleStartC = effTempThrottle.startTempC,
            batteryPct = effBattPct,
            pluggedOrCharging = stableBatteryState.isPluggedOrCharging,
            wasActive = afterburnDecision.active,
            sagMv = smoothedSagMv
        )
        // v3.7.0: say why it is not boosting (the state log skips IDLE, so a missed foreground match was invisible).
        if (cfg.afterburn.enabled && fgPkg in cfg.boostPackages && !afterburnDecision.active &&
            nowMs - lastAfterburnWhyMs >= 10_000L
        ) {
            lastAfterburnWhyMs = nowMs
            log(
                LogLevel.INFO,
                "Afterburn",
                "Afterburn waiting (${afterburnDecision.state.label}): ${"%.1f".format(Locale.US, tempC)}C vs start ${"%.1f".format(Locale.US, effTempThrottle.startTempC)}C (needs ${"%.1f".format(Locale.US, cfg.afterburn.thermalMarginC)}C margin), batt ${effBattPct}% (gauge ${cachedBatteryPct}%), sag ${smoothedSagMv.roundToInt()}mV"
            )
        }
        val boostLevel = afterburnDecision.level
        if (afterburnDecision.state != prevBoostState) {
            _afterburnEvents.tryEmit(
                com.example.service.AfterburnTransitionEvent(
                    packageName = fgPkg,
                    prevState = prevBoostState,
                    newState = afterburnDecision.state,
                    strength = boostStrength,
                    toastEnabled = cfg.afterburn.toastEnabled,
                    timestampMs = nowMs,
                    // v3.7.1: any boosted app (Games or Boost list) gets the pill, not only Games-list apps.
                    inSelectedGame = com.example.service.AfterburnToastPolicy.isBoostedAppForeground(
                        eventPkg = fgPkg,
                        gamePackages = cfg.gamePackages,
                        boostPackages = cfg.boostPackages,
                        foregroundPkg = fgPkg
                    )
                )
            )
        }
        if (afterburnDecision.state != prevBoostState &&
            afterburnDecision.state != AfterburnState.IDLE && prevBoostState != AfterburnState.IDLE
        ) {
            log(
                LogLevel.INFO,
                "Afterburn",
                "Afterburn: ${prevBoostState.label} -> ${afterburnDecision.state.label} (${(boostLevel * 100f).roundToInt()}%, ${"%.1f".format(Locale.US, tempC)}C, batt ${effBattPct}%, sag ${batteryTruth.sagMv.roundToInt()}mV)"
            )
        }

        // 5b. Gentle Power Throttle (discharging ONLY; release cleanly while charging/plugged)
        val maxWin = effPowerThrottle.averagingSamples.coerceIn(2, 40)
        val avgWatts: Float
        if (stableBatteryState == BatteryState.DISCHARGING) {
            if (rawState == BatteryState.DISCHARGING) {
                wattWindow.addLast(rawReading.dischargeWatts)
                while (wattWindow.size > maxWin) wattWindow.removeFirst()
            }
            avgWatts = if (wattWindow.isNotEmpty()) wattWindow.average().toFloat() else rawReading.dischargeWatts
            smoothedChargeWatts = 0f
            if (effPowerThrottle.enabled) {
                val fastWatts = if (wattWindow.isNotEmpty()) wattWindow.toList().takeLast(3).average().toFloat() else avgWatts
                val prevCap = currentPowerCapPct
                val st = PowerCapController.step(
                    prev = PowerCapController.State(currentPowerCapPct, overPowerSinceMs ?: -1L, lastPowerStepMs, lastPowerOverMs),
                    nowMs = nowMs,
                    avgWatts = avgWatts,
                    fastWatts = fastWatts,
                    cfg = effPowerThrottle,
                    floorPct = cfg.safetyFloorPct,
                    bypassed = boostLevel > 0f
                )
                currentPowerCapPct = st.capPct
                overPowerSinceMs = st.overSinceMs.takeIf { it >= 0L }
                lastPowerStepMs = st.lastStepMs
                lastPowerOverMs = st.lastOverMs
                if (prevCap >= 99.5f && currentPowerCapPct < 99.5f) {
                    log(
                        LogLevel.WARN,
                        "PowerCap",
                        "Power draw ${"%.2f".format(avgWatts)}W > ${effPowerThrottle.limitWatts}W limit; stepping cap down."
                    )
                }
            } else {
                overPowerSinceMs = null
                currentPowerCapPct = 100f
            }
        } else {
            wattWindow.clear()
            liveWattsEma = 0f
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
            effPowerThrottle.enabled && currentPowerCapPct < 99.5f

        // 5c. v3.8.0 smooth power limit gate (OFF by default; falls back to the plain cap on any doubt)
        run {
            // Gaps over 1 s are a pause (screen off, idle), not loop speed: ignore them. Faster average so one slow tick
            // does not keep the limiter paused for seconds.
            val gapRaw = if (smoothLastTickMs > 0L) nowMs - smoothLastTickMs else 0L
            smoothLastTickMs = nowMs
            if (gapRaw in 1L..1000L) {
                val gap = gapRaw.toFloat()
                smoothTickGapMs = if (smoothTickGapMs <= 0f) gap else smoothTickGapMs * 0.7f + gap * 0.3f
            }
            val manualSliceMs = SmoothSlice.clampMs(cfg.smoothSliceMs)
            val autoSlice = cfg.smoothSliceAuto
            if (manualSliceMs != lastManualSliceMs) {
                if (lastManualSliceMs > 0) {
                    log(LogLevel.INFO, "SmoothSlice", "Slice length set to $manualSliceMs ms (setting changed).")
                }
                lastManualSliceMs = manualSliceMs
            }
            if (autoSlice != lastSliceAuto) {
                lastSliceAuto = autoSlice
                if (autoSlice) {
                    sliceLearner.reset()
                    log(LogLevel.INFO, "SmoothSlice", "Automatic slice learning on: starts at $manualSliceMs ms, only in long game sessions under the power limit.")
                } else {
                    sliceLearner.abortTrial()
                    smoothSched.values.forEach { it.minPhaseMs = manualSliceMs.toLong() }
                    log(LogLevel.INFO, "SmoothSlice", "Automatic slice learning off: using $manualSliceMs ms.")
                }
            }
            val thermalCapActive = currentTempCpuCapPct < 99.5f || currentTempGpuCapPct < 99.5f
            val wanted = effPowerThrottle.smoothPowerLimit && (powerThrottleActive || thermalCapActive)
            var reason: String? = null
            if (wanted) {
                val rate = smoothSched.values.maxOfOrNull { it.switchesPerSec(nowMs) } ?: 0f
                if (rate > SmoothPowerLimiter.MAX_SWITCHES_PER_SEC * 2f) {
                    smoothDisabledUntilMs = nowMs + SmoothPowerLimiter.BACKOFF_MS
                }
                // Too many writes: make phases longer; relax again when calm.
                for ((schedId, sc) in smoothSched) {
                    val floorPhase = effectiveSliceMs(schedId, manualSliceMs, autoSlice).toLong()
                    sc.minPhaseMs = when {
                        rate > SmoothPowerLimiter.MAX_SWITCHES_PER_SEC -> (sc.minPhaseMs * 2).coerceAtMost(SmoothPowerLimiter.MAX_PHASE_MS)
                        rate < SmoothPowerLimiter.MAX_SWITCHES_PER_SEC / 2f -> (sc.minPhaseMs - 10L).coerceAtLeast(floorPhase)
                        else -> sc.minPhaseMs.coerceAtLeast(floorPhase)
                    }
                }
                reason = SmoothPowerLimiter.gate(
                    pollMs = cfg.pollIntervalMs,
                    avgTickGapMs = smoothTickGapMs,
                    switchesPerSec = rate,
                    nowMs = nowMs,
                    disabledUntilMs = smoothDisabledUntilMs
                )
            }
            val nowActive = wanted && reason == null
            if (nowActive) {
                val settled = nowMs - lastPowerStepMs >= 1000L
                // Watt-based trim only follows the power limit; thermal-only runs get no trim.
                smoothTrimValue = smoothTrim.update(nowMs, avgWatts, effPowerThrottle.limitWatts, powerThrottleActive, settled)
            } else {
                resetSmoothPowerLimit()
                smoothActive = false
            }
            if (wanted) {
                // Same cause = one line (the numbers in the text change every tick); repeat at most every 10 s.
                val key = reason?.filterNot { it.isDigit() }
                if (key != smoothGateReason) {
                    val wasPaused = smoothGateReason != null
                    smoothGateReason = key
                    if (reason != null) {
                        smoothLastGateLogMs = nowMs
                        log(LogLevel.WARN, "SmoothLimit", "Smooth power limit paused: $reason (poll ${cfg.pollIntervalMs}ms). Using the normal cap.")
                    } else if (wasPaused) {
                        log(LogLevel.INFO, "SmoothLimit", "Smooth power limit resumed (${smoothTickGapMs.roundToInt()}ms per tick).")
                    }
                } else if (reason != null && nowMs - smoothLastGateLogMs >= 10_000L) {
                    smoothLastGateLogMs = nowMs
                    log(LogLevel.WARN, "SmoothLimit", "Still paused: $reason (poll ${cfg.pollIntervalMs}ms).")
                }
            } else {
                smoothGateReason = null
            }
            if (nowActive && !smoothActive) {
                log(LogLevel.INFO, "SmoothLimit", "Smooth power limit active (poll ${cfg.pollIntervalMs}ms).")
            }
            if (nowActive) smoothActive = true
            if (nowActive && fastJob?.isActive != true) {
                fastJob = engineScope.launch { fastSwitchLoop() }
            }
            if (autoSlice) {
                runSliceLearning(
                    nowMs = nowMs,
                    cfg = cfg,
                    manualSliceMs = manualSliceMs,
                    watts = avgWatts,
                    limitWatts = effPowerThrottle.limitWatts,
                    eligible = nowActive && activeMode == GovernorMode.GAME && !burstActive &&
                        powerThrottleActive && avgWatts > 0f && effPowerThrottle.limitWatts > 0f &&
                        nowMs - lastPowerStepMs >= 1000L
                )
            }
            logWriteLatency(nowMs)
            if (nowActive && nowMs - smoothLastLogMs >= 5_000L && smoothPlanText.isNotEmpty()) {
                smoothLastLogMs = nowMs
                val rate = smoothSched.values.maxOfOrNull { it.switchesPerSec(nowMs) } ?: 0f
                val plans = smoothPlanText.entries.sortedBy { it.key }.joinToString("; ") { "${it.key} ${it.value}" }
                log(
                    LogLevel.INFO,
                    "SmoothLimit",
                    "$plans | ${"%.2f".format(Locale.US, avgWatts)}W of ${"%.1f".format(Locale.US, effPowerThrottle.limitWatts)}W | " +
                        "tick ${smoothTickGapMs.roundToInt()}ms | ${"%.1f".format(Locale.US, rate)} switches/s | trim ${"%+.2f".format(Locale.US, smoothTrimValue)}"
                )
            }
        }

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

        val (cpuPowerCapRaw, gpuPowerCapRaw) = if (effPowerThrottle.componentAware && powerThrottleActive) {
            GovernorMath.splitPowerCap(
                basePowerCapPct = currentPowerCapPct,
                gpuShare = gpuShareSmoothed,
                floorPct = cfg.safetyFloorPct
            )
        } else {
            currentPowerCapPct to currentPowerCapPct
        }

        val cpuPowerCap = AfterburnLogic.relieveCap(cpuPowerCapRaw, boostLevel)
        val gpuPowerCap = AfterburnLogic.relieveCap(gpuPowerCapRaw, boostLevel)

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
            val factor = if (effPowerThrottle.componentAware && powerThrottleActive) {
                clusterFactors[p.policyId] ?: 1.0f
            } else {
                1.0f
            }
            val clusterPowerCap = (100f - cpuBaseCut * factor).coerceIn(cfg.safetyFloorPct, 100f)
            smoothClusterPowerOnlyCap[p.policyId] = clusterPowerCap
            clusterCapPct[p.policyId] = GovernorMath.combineCaps(currentTempCpuCapPct, clusterPowerCap, cfg.safetyFloorPct)
        }

        val effectiveCpuCapPct = GovernorMath.combineCaps(currentTempCpuCapPct, cpuPowerCap, cfg.safetyFloorPct)
        val effectiveGpuCapPct = GovernorMath.combineCaps(currentTempGpuCapPct, gpuPowerCap, cfg.safetyFloorPct)

        if (powerThrottleActive && effPowerThrottle.componentAware) {
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

            var (stdTargetFreq, cappedMax) = GovernorMath.selectFrequency(
                availableFreqsKhz = p.availableFreqsKhz,
                allowedMinKhz = smartMin,
                allowedMaxKhz = allowedMax,
                capPct = smartCapPct,
                loadPct = smoothed,
                forceMaxDuringBurst = burstActive
            )
            var smoothStep: SmoothStep? = null
            if (smoothActive && !burstActive && smartCapPct < 99.5f && smartCapPct == baseCapPct) {
                val cPowerTighter = (smoothClusterPowerOnlyCap[p.policyId] ?: 100f) <= currentTempCpuCapPct + 0.01f
                val ladder = p.availableFreqsKhz.sorted().filter { it in smartMin..allowedMax }
                smoothStep = smoothCapStep(
                    compId = "cpu_${p.policyId}",
                    stepsKhz = ladder,
                    capPct = smartCapPct,
                    utilPct = rawLoad,
                    curKhz = batch["${p.path}/scaling_cur_freq"]?.trim()?.toLongOrNull() ?: 0L,
                    scenarioKey = scenarioKey,
                    minConfidencePct = cfg.smoothMinConfidencePct.toFloat(),
                    trim = if (cPowerTighter) smoothTrimValue else 0f
                )
                val ss = smoothStep
                if (ss != null && ss.lowKhz != cappedMax) {
                    val re = GovernorMath.selectFrequency(
                        availableFreqsKhz = p.availableFreqsKhz,
                        allowedMinKhz = smartMin,
                        allowedMaxKhz = ss.lowKhz,
                        capPct = 100f,
                        loadPct = smoothed,
                        forceMaxDuringBurst = false
                    )
                    stdTargetFreq = re.first
                    cappedMax = re.second
                }
            }
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
            // v3.8.0: hand the component to the fast switcher while it alternates two steps.
            val fastId = "cpu_${p.policyId}"
            var fastOwned = false
            val ssNow = smoothStep
            if (ssNow != null && ssNow.highKhz > ssNow.lowKhz && fastJob?.isActive == true) {
                val desiredHigh = GovernorMath.selectFrequency(
                    availableFreqsKhz = p.availableFreqsKhz,
                    allowedMinKhz = smartMin,
                    allowedMaxKhz = ssNow.highKhz,
                    capPct = 100f,
                    loadPct = smoothed,
                    forceMaxDuringBurst = false
                ).first
                if (desiredHigh > ssNow.lowKhz) {
                    fastPlans[fastId] = FastPlan(
                        isGpu = false, path = p.path, lowKhz = ssNow.lowKhz, highKhz = ssNow.highKhz,
                        duty = ssNow.duty, desiredKhz = desiredHigh, maxOnly = writeMaxOnly,
                        gpuOppByFreq = emptyMap(), stampMs = nowMs
                    )
                    fastOwned = true
                }
            }
            if (!fastOwned) fastPlans.remove(fastId)
            if (fastOwned) {
                // The control loop does not re-assert while the fast switcher owns the cluster, so count the times the
                // kernel overrode the fast switcher's last write (once per write, and only when our read is newer than it).
                val fw = fastWrittenMirror[fastId]
                val at = fastWrittenAtMs[fastId] ?: 0L
                val mismatch = fw != null && liveMax != null && liveMax != fw && nowMs - at >= 250L
                if (mismatch && fastDriftStamp[fastId] != at) {
                    fastDriftStamp[fastId] = at
                    driftTotal[p.policyId] = (driftTotal[p.policyId] ?: 0) + 1
                }
                // v4.1.0 (option, off by default): tell the fast switcher to write its value again.
                if (SmoothSlice.shouldFixDrift(
                        enabled = cfg.smoothFixDrift,
                        thermalCapActive = currentTempCpuCapPct < 99.5f || currentTempGpuCapPct < 99.5f,
                        mismatch = mismatch,
                        nowMs = nowMs,
                        lastFixMs = fastLastFixMs[fastId] ?: 0L
                    )
                ) {
                    fastLastFixMs[fastId] = nowMs
                    fastForceRewrite[fastId] = true
                    val fixes = (fastFixCount[p.policyId] ?: 0) + 1
                    fastFixCount[p.policyId] = fixes
                    if (nowMs - (fastFixLogMs[p.policyId] ?: 0L) >= 60_000L) {
                        fastFixLogMs[p.policyId] = nowMs
                        fastFixCount[p.policyId] = 0
                        log(LogLevel.INFO, "CpuDrift", "policy${p.policyId}: kernel overrode the smooth limit $fixes time(s) since the last note; written again (fix option on). Kernel had max=${liveMax?.div(1000)} MHz, wanted ${fw?.div(1000)} MHz.")
                    }
                }
            }
            if (fastOwned) {
                fastOwnedPrev.add(fastId)
            } else if (fastOwnedPrev.remove(fastId)) {
                lastWrittenCpuFreq.remove(p.policyId) // the fast switcher moved it; make the loop write again
            }
            if (!fastOwned && !fastInFlight.containsKey(fastId) && targetFreq > 0L && (targetFreq != prevWritten || reassertDue || justSwitchedToPerf)) {
                if (reassertDue) {
                    lastReassertMs[p.policyId] = nowMs
                    // The kernel/system overriding the limits now and then is normal and is fixed on the spot, so it is
                    // only counted here and summarised once a minute (keeps the log, and the 400-line buffer, free for real events).
                    val n = (driftCount[p.policyId] ?: 0) + 1
                    driftCount[p.policyId] = n
                    driftTotal[p.policyId] = (driftTotal[p.policyId] ?: 0) + 1
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
            var selection = GovernorMath.selectGpuOppPosition(
                sortedOpps = gpuSpec.oppTable,
                allowedMinKhz = gSmartMin,
                allowedMaxKhz = gAllowedMax,
                capPct = gSmartCap,
                loadPct = smoothedGpuLoad,
                forceMaxDuringBurst = burstActive
            )
            var gSmoothStep: SmoothStep? = null
            if (selection != null && smoothActive && !burstActive && gSmartCap < 99.5f && gSmartCap == effectiveGpuCapPct) {
                val gPowerTighter = gpuPowerCap <= currentTempGpuCapPct + 0.01f
                val gLadder = gpuSpec.oppTable.map { it.freqKhz }.distinct().sorted().filter { it in gSmartMin..gAllowedMax }
                gSmoothStep = smoothCapStep(
                    compId = "gpu",
                    stepsKhz = gLadder,
                    capPct = gSmartCap,
                    utilPct = rawGpuLoad,
                    curKhz = statusParsed.freqKhz ?: selection.targetOpp.freqKhz,
                    scenarioKey = scenarioKey,
                    minConfidencePct = cfg.smoothMinConfidencePct.toFloat(),
                    trim = if (gPowerTighter) smoothTrimValue else 0f
                )
                val gss = gSmoothStep
                if (gss != null && gss.lowKhz != selection.cappedMaxKhz) {
                    selection = GovernorMath.selectGpuOppPosition(
                        sortedOpps = gpuSpec.oppTable,
                        allowedMinKhz = gSmartMin,
                        allowedMaxKhz = gss.lowKhz,
                        capPct = 100f,
                        loadPct = smoothedGpuLoad,
                        forceMaxDuringBurst = false
                    ) ?: selection
                }
            }
            // v3.8.0: hand the GPU to the fast switcher while it alternates two steps.
            var gpuFastOwned = false
            val gssNow = gSmoothStep
            val gFixPath = gpuSpec.gpufreqFixOppPath
            if (gssNow != null && gssNow.highKhz > gssNow.lowKhz && gFixPath != null && fastJob?.isActive == true) {
                val desiredHigh = GovernorMath.selectGpuOppPosition(
                    sortedOpps = gpuSpec.oppTable,
                    allowedMinKhz = gSmartMin,
                    allowedMaxKhz = gssNow.highKhz,
                    capPct = 100f,
                    loadPct = smoothedGpuLoad,
                    forceMaxDuringBurst = false
                )?.targetOpp?.freqKhz ?: 0L
                if (desiredHigh > gssNow.lowKhz) {
                    val oppMap = HashMap<Long, Int>()
                    for (o in gpuSpec.oppTable) oppMap.putIfAbsent(o.freqKhz, o.oppIndex)
                    fastPlans["gpu"] = FastPlan(
                        isGpu = true, path = gFixPath, lowKhz = gssNow.lowKhz, highKhz = gssNow.highKhz,
                        duty = gssNow.duty, desiredKhz = desiredHigh, maxOnly = false,
                        gpuOppByFreq = oppMap, stampMs = nowMs
                    )
                    gpuFastOwned = true
                }
            }
            if (!gpuFastOwned) fastPlans.remove("gpu")
            if (gpuFastOwned) {
                fastOwnedPrev.add("gpu")
            } else if (fastOwnedPrev.remove("gpu")) {
                lastWrittenGpuOppIndex = null
            }

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

                if (!gpuFastOwned && !fastInFlight.containsKey("gpu") && targetOppIdx != lastWrittenGpuOppIndex) {
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

        batteryEstimator.setCalibrationAnchor(batteryTruth.calCapacityMah, batteryTruth.calCapacitySlow)
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
            estimationEnabled = cfg.batteryEstimationEnabled,
            internalResistanceMohm = if (batteryTruth.resistanceSamples >= 3) batteryTruth.internalResistanceMohm else 0f
        )
        batteryHistory.record(
            BatteryHistoryPoint(
                timeMs = System.currentTimeMillis(),
                pct = cachedBatteryPct,
                state = stableBatteryState,
                watts = when (stableBatteryState) {
                    BatteryState.CHARGING -> rawReading.chargeWatts
                    BatteryState.DISCHARGING -> -rawReading.dischargeWatts
                    else -> 0f
                },
                tempC = cachedBattTempC,
                voltageMv = estVoltMv,
                screenOn = isScreenInteractive
            )
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

        // v3.8.1: live watts for the overlay. The averaged value above (10 samples by default) lags by about a
        // second and a half, which hides spikes; this one follows the raw samples closely.
        val liveWatts: Float = if (stableBatteryState == BatteryState.DISCHARGING) {
            if (rawState == BatteryState.DISCHARGING && !readMissing) {
                val raw = rawReading.dischargeWatts
                liveWattsEma = if (liveWattsEma <= 0f) raw else liveWattsEma + (raw - liveWattsEma) * LIVE_WATTS_ALPHA
            }
            if (liveWattsEma > 0f) liveWattsEma else avgWatts
        } else {
            liveWattsEma = 0f
            0f
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
                // v3.8.2: keep the overlay reader's fresher value when it is running; otherwise use the tick's own.
                liveWatts = if (liveSamplerPublishedAtMs > 0L &&
                    System.currentTimeMillis() - liveSamplerPublishedAtMs < LIVE_SAMPLER_FRESH_MS &&
                    prev.liveWatts > 0f
                ) prev.liveWatts else liveWatts,
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
                afterburnState = afterburnDecision.state,
                afterburnLevelPct = (boostLevel * 100f).roundToInt(),
                truePct = if (batteryTruth.valid) batteryTruth.truePct else -1f,
                trueHeadroomPct = batteryTruth.headroomPct,
                batteryChemistry = batteryTruth.chemistry.label,
                batterySagMv = batteryTruth.sagMv,
                batteryResistanceMohm = batteryTruth.internalResistanceMohm,
                truthConfidencePct = batteryTruth.confidencePct,
                calChargeDone = batteryTruth.calChargeDone,
                calDischargeDone = batteryTruth.calDischargeDone,
                calRunning = batteryTruth.calRunning,
                fullChargeMv = batteryTruth.fullChargeMv,
                fullChargeMah = batteryTruth.fullChargeMah,
                effectiveCpuCapPct = effectiveCpuCapPct,
                effectiveGpuCapPct = effectiveGpuCapPct,
                lastPollDurationMs = pollDuration,
                history = nextHistory
            )
        }
        metrics.onTelemetry(_telemetry.value, nowMs)

        // 8b. v3.6.7 Game Thread Controller (runs >= 2s apart, only when enabled, Game mode, configured game foreground)
        gameThreadRuntime.onControlTick(
            nowMs = nowMs,
            config = cfg.gameThreadController,
            activeMode = activeMode,
            fgPkg = fgPkg,
            gamePackages = cfg.gamePackages,
            rootAvailable = rootShell.isRootAvailable,
            availableCpuCount = GameThreadLogic.availableCpuCount(hw),
            isSleepSaving = isSleepSaving
        )

        // 9. v2.7.4 adaptive polling: decide how long the loop may sleep before the next tick.
        val pollBase = cfg.pollIntervalMs.coerceIn(50L, 1000L)
        val pollDecision = PollPolicy.decide(
            pollState,
            PollPolicy.Inputs(
                nowMs = nowMs,
                enabled = cfg.adaptivePollingEnabled,
                baseIntervalMs = pollBase,
                gameMode = activeMode == GovernorMode.GAME,
                burstActive = burstActive,
                tempThrottleActive = tempThrottleActive,
                powerThrottleActive = powerThrottleActive,
                cpuLoadPct = smoothedTotalCpuLoad,
                gpuLoadPct = gpuLiveState.smoothedLoadPct,
                maxLoadSpikePct = maxLoadSpike,
                lastTouchMs = interaction.lastTouchMs,
                screenInteractive = isScreenInteractive,
                foregroundChanged = fgPkg.isNotBlank() && prevFg.isNotBlank() && fgPkg != prevFg,
                // v3.8.1: a visible overlay wants live watts, so the loop is never stretched while it is on.
                overlayLive = cfg.overlayEnabled
            )
        )
        pollState = pollDecision.state
        pollNextIntervalMs = pollDecision.intervalMs
        if (pollDecision.stretched != pollStretchLogged && nowMs - pollLastLogMs >= 10_000L) {
            pollStretchLogged = pollDecision.stretched
            pollLastLogMs = nowMs
            log(
                LogLevel.INFO,
                "Poll",
                if (pollDecision.stretched) {
                    "Adaptive polling: calm, polling every ${pollDecision.intervalMs}ms (min ${pollBase}ms)."
                } else {
                    "Adaptive polling: activity, polling every ${pollBase}ms."
                }
            )
        }
    }

    /**
     * v3.8.2: reads the battery current and voltage on its own timer while the overlay is showing, so the
     * overlay's watts follow the phone even when a game makes the control tick slow. It reads the nodes
     * directly (no shell); if the app may not read them, it uses its own small root shell, never the main one.
     * Only the overlay's [TelemetryState.liveWatts] is written here; the limiter, history, logs and
     * notification keep using the control loop's averaged value.
     */
    private fun startLiveWattsSampler() {
        if (liveSamplerJob?.isActive == true) return
        liveSamplerJob = engineScope.launch {
            var directFails = 0
            var liveShell: RootShell? = null
            var ema = 0f
            try {
                while (isActive) {
                    val cfg = _config.value
                    val bp = _hardwareSpec.value.batteryPower
                    val curPath = bp.currentPath
                    val voltPath = bp.voltagePath
                    val wanted = cfg.overlayEnabled && isScreenInteractive && !isSleepSaving &&
                        stableBatteryState == BatteryState.DISCHARGING && curPath != null && voltPath != null
                    if (!wanted) {
                        ema = 0f
                        liveSamplerPublishedAtMs = 0L
                        delay(400L)
                        continue
                    }
                    var cur: Long? = null
                    var volt: Long? = null
                    if (directFails < 3) {
                        withContext(Dispatchers.IO) {
                            cur = readSysfsDirect(curPath!!)
                            volt = readSysfsDirect(voltPath!!)
                        }
                        if (cur == null || volt == null) directFails++ else directFails = 0
                    }
                    if ((cur == null || volt == null) && directFails >= 3 && rootShell.isRootAvailable) {
                        val sh = liveShell ?: RootShell().also { liveShell = it }
                        val m = sh.readFilesBatch(listOf(curPath!!, voltPath!!))
                        cur = m[curPath]?.trim()?.toLongOrNull()
                        volt = m[voltPath]?.trim()?.toLongOrNull()
                    }
                    val c = cur
                    val v = volt
                    if (c != null && v != null) {
                        val r = SysfsParsers.computePowerReading(
                            rawCurrent = c,
                            rawVoltage = v,
                            unitScale = cfg.powerThrottle.unitScale,
                            polarity = cfg.powerThrottle.polarity,
                            bmStatus = cachedBmStatus,
                            bmPlugged = cachedBmPlugged,
                            learnedDischargeSign = learnedDischargeSign
                        )
                        if (r.state == BatteryState.DISCHARGING && r.dischargeWatts > 0f) {
                            ema = if (ema <= 0f) r.dischargeWatts else ema + (r.dischargeWatts - ema) * LIVE_WATTS_ALPHA
                            liveSamplerPublishedAtMs = System.currentTimeMillis()
                            val out = ema
                            _telemetry.update { it.copy(liveWatts = out) }
                        }
                    }
                    delay(LIVE_SAMPLER_PERIOD_MS)
                }
            } finally {
                liveSamplerPublishedAtMs = 0L
                try {
                    withContext(NonCancellable) { liveShell?.close() }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun stopLiveWattsSampler() {
        liveSamplerJob?.cancel()
        liveSamplerJob = null
        liveSamplerPublishedAtMs = 0L
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
            val r = bgShell.exec("cat $path 2>/dev/null")
            if (r.stdout.isBlank()) return null
            val trimmedOut = r.stdout.trim()
            val parts = trimmedOut.split(WHITESPACE_REGEX)
            val token = if (kernelAwk) parts.getOrNull(1) else parts.firstOrNull()
            return token?.toFloatOrNull()
        }

        // Method B (VTools-style): SurfaceFlinger global frame counter (binder transaction 1013)
        suspend fun sampleServiceCall(): Float? {
            if (sfUnsupported) return null
            val r = bgShell.exec("service call SurfaceFlinger 1013 2>/dev/null")
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
                bgShell.exec("dumpsys SurfaceFlinger --timestats -enable")
                timestatsEnabled = true
            }
            val res = bgShell.exec("dumpsys SurfaceFlinger --timestats -dump | grep -E '^layerName|^totalFrames'")
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

    /** Slice the scheduler of [id] must not go below: learned/trial value in auto mode, the setting otherwise. */
    private fun effectiveSliceMs(id: String, manualMs: Int, auto: Boolean): Int =
        if (auto) (sliceLearner.sliceFor(id) ?: manualMs) else manualMs

    /**
     * Time of one write round trip through the root shell. It includes any wait behind other commands on that
     * shell, which is what limits how fast the smooth limiter can really switch.
     */
    private fun recordWriteLatency(path: String, ms: Float, fast: Boolean = false) {
        if (fast) writeLatFast.add(ms)
        else if (path.contains("/cpufreq/")) writeLatCpu.add(ms) else writeLatOther.add(ms)
        if (ms >= 150f) {
            val now = System.currentTimeMillis()
            if (now - lastSlowWriteLogMs >= 10_000L) {
                lastSlowWriteLogMs = now
                log(LogLevel.WARN, "WriteLat", "Slow frequency write: ${ms.roundToInt()} ms [$path]${if (fast) " (fast shell)" else ""}")
            }
        }
    }

    private fun logWriteLatency(nowMs: Long) {
        if (nowMs - lastLatLogMs < 30_000L) return
        lastLatLogMs = nowMs
        val c = writeLatCpu.summary()
        val o = writeLatOther.summary()
        val fs = writeLatFast.summary()
        if ((c?.count ?: 0) < 5 && (o?.count ?: 0) < 5 && (fs?.count ?: 0) < 5) return
        fun txt(name: String, s: WriteLatencyTracker.Summary?): String? =
            if (s == null || s.count < 5) null else
                "$name n=${s.count} avg ${"%.1f".format(Locale.US, s.avgMs)} p50 ${"%.1f".format(Locale.US, s.p50Ms)} p90 ${"%.1f".format(Locale.US, s.p90Ms)} max ${"%.1f".format(Locale.US, s.maxMs)} ms"
        val line = listOfNotNull(txt("fast switcher", fs), txt("control CPU", c), txt("control GPU/other", o)).joinToString(" | ")
        log(LogLevel.INFO, "WriteLat", "Write time (recent writes, includes waiting for the shell): $line")
    }

    private fun runSliceLearning(nowMs: Long, cfg: GovernorConfig, manualSliceMs: Int, eligible: Boolean, watts: Float, limitWatts: Float) {
        val ids = smoothSched.keys.filter { it.startsWith("cpu_") }.sorted()
        sliceLearner.configure(ids, manualSliceMs, SmoothSlice.decode(cfg.smoothLearnedSlices))
        val cur = sliceLearner.currentId ?: return
        val policy = cur.removePrefix("cpu_").toIntOrNull()
        val slice = sliceLearner.sliceFor(cur) ?: manualSliceMs
        val sample = SmoothSlice.Sample(
            watts = watts,
            limitWatts = limitWatts,
            fps = _telemetry.value.fps,
            driftTotal = if (policy != null) driftTotal[policy] ?: 0 else 0,
            writeP90Ms = writeLatFast.p90(),
            backedOff = (smoothSched[cur]?.minPhaseMs ?: 0L) > slice
        )
        val ch = sliceLearner.onTick(nowMs, eligible, sample) ?: return
        if (ch.fromMs != ch.toMs || ch.kind == SliceLearner.ChangeKind.SETTLED) {
            smoothSched[ch.id]?.minPhaseMs = ch.toMs.toLong()
        }
        log(LogLevel.INFO, "SmoothSlice", ch.text)
        if (ch.kind == SliceLearner.ChangeKind.SETTLED) {
            val encoded = SmoothSlice.encode(sliceLearner.learnedMap())
            updateConfig(markMidSession = false) { it.copy(smoothLearnedSlices = encoded) }
        }
    }

    private suspend fun writeCpuPinnedFreq(policyPath: String, targetKhz: Long, raising: Boolean, shell: RootShell = rootShell): Boolean {
        val freqStr = targetKhz.toString()
        val maxPath = "$policyPath/scaling_max_freq"
        val minPath = "$policyPath/scaling_min_freq"
        // One root round trip, same order as before: raise = max then min, lower = min then max.
        val writes = if (raising) listOf(maxPath to freqStr, minPath to freqStr)
        else listOf(minPath to freqStr, maxPath to freqStr)
        return safeWriteSysfsOrdered(writes, shell).all { it }
    }

    private fun shouldAttemptWrite(path: String, nowMs: Long): Boolean {
        val cooldownUntil = failedWriteCooldownUntil[path] ?: 0L
        return nowMs >= cooldownUntil
    }

    /** Batched [safeWriteSysfs]: per-path cooldown, logging and failure handling are unchanged. */
    private suspend fun safeWriteSysfsOrdered(writes: List<Pair<String, String>>, shell: RootShell = rootShell): List<Boolean> {
        val now = System.currentTimeMillis()
        // Decide once: another coroutine (fast switcher) can set a cooldown while we run, and re-checking
        // afterwards would shift which result belongs to which write.
        val allowed = writes.map { shouldAttemptWrite(it.first, now) }
        val attempt = writes.filterIndexed { i, _ -> allowed[i] }
        if (attempt.isEmpty()) return writes.map { false }
        val t0 = System.nanoTime()
        val results = shell.writeSysfsOrdered(attempt)
        recordWriteLatency(attempt.first().first, (System.nanoTime() - t0) / 1_000_000f, shell === fastShell)
        var n = 0
        return writes.mapIndexed { i, w ->
            if (!allowed[i]) return@mapIndexed false
            val res = results[n++]
            if (!res.isSuccess) {
                failedWriteCooldownUntil[w.first] = now + if (w.first.contains("/cpufreq/")) 750L else 6_000L
                log(LogLevel.ERROR, "SysfsWrite", "Write failed [${w.first} <- ${w.second}]: ${res.error ?: res.stdout}")
                false
            } else true
        }
    }

    private suspend fun safeWriteSysfs(path: String, value: String, shell: RootShell = rootShell): Boolean {
        val now = System.currentTimeMillis()
        if (!shouldAttemptWrite(path, now)) return false

        val t0 = System.nanoTime()
        val res = shell.writeSysfs(path, value)
        recordWriteLatency(path, (System.nanoTime() - t0) / 1_000_000f, shell === fastShell)
        if (!res.isSuccess) {
            failedWriteCooldownUntil[path] = now + if (path.contains("/cpufreq/")) 750L else 6_000L
            log(LogLevel.ERROR, "SysfsWrite", "Write failed [$path <- $value]: ${res.error ?: res.stdout}")
            return false
        }
        return true
    }

    fun updateConfig(markMidSession: Boolean = true, transform: (GovernorConfig) -> GovernorConfig) {
        val prev: GovernorConfig
        val next: GovernorConfig
        synchronized(configUpdateLock) {
            prev = _config.value
            next = transform(prev)
            if (prev == next) return
            _config.value = next
            storage.saveConfigDebounced(next)
        }
        if (markMidSession && metrics.recording.value && isTuningConfigChange(prev, next)) {
            metrics.recordMidSessionConfigChange()
        }
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

        if (prev.gameThreadController.enabled && !next.gameThreadController.enabled) {
            gameThreadRuntime.reset()
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

    fun setProtectFromKill(enabled: Boolean) {
        updateConfig { it.copy(protectFromKillEnabled = enabled) }
        if (!enabled) {
            processProtection.clearStopFlagSynchronously()
        }
        val running = _telemetry.value.isRunning
        engineScope.launch(Dispatchers.IO) {
            processProtection.applyAndVerify(enabled = enabled, watchServiceAlive = enabled && running)
        }
    }

    fun reverifyProtection() {
        val cfg = _config.value
        val running = _telemetry.value.isRunning
        engineScope.launch(Dispatchers.IO) {
            processProtection.applyAndVerify(
                enabled = cfg.protectFromKillEnabled,
                watchServiceAlive = cfg.protectFromKillEnabled && running
            )
        }
    }

    fun stopProtectionWatchdogAsync() {
        processProtection.clearStopFlagSynchronously()
        engineScope.launch(Dispatchers.IO) {
            processProtection.stopWatchdog()
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
        if (preset.id == D8300UserPreset.ID) {
            // Device preset: patch the CURRENT config (caps, temperature throttle, sensor) and keep all other settings.
            val patched = D8300UserPreset.apply(_config.value, _hardwareSpec.value)
            if (patched == null) {
                log(LogLevel.WARN, "Presets", "Preset '${preset.name}' is not for this device.")
                return
            }
            val populatedPatched = storage.populateDefaultsForHardware(patched, _hardwareSpec.value)
            _config.value = populatedPatched
            storage.saveConfig(populatedPatched)
            if (metrics.recording.value) metrics.recordMidSessionConfigChange()
            log(LogLevel.INFO, "Presets", "Applied preset '${preset.name}' (caps and temperature throttle only).")
            return
        }
        val populated = storage.populateDefaultsForHardware(preset.config, _hardwareSpec.value)
        _config.value = populated
        storage.saveConfig(populated)
        if (metrics.recording.value) metrics.recordMidSessionConfigChange()
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
        if (metrics.recording.value) metrics.recordMidSessionConfigChange()
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
        pollState = PollPolicy.State()
        pollNextIntervalMs = 0L
        pollStretchLogged = false
        pollLastLogMs = 0L
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
        liveWattsEma = 0f
        overPowerSinceMs = null
        currentPowerCapPct = 100f
        resetSmoothPowerLimit()
        gpuShareSmoothed = 0.5f
        smoothedClusterShare.clear()
        lastPowerSplitLogMs = 0L
        lastDominantComponent = null
        ramPinned = false
        ramOrigMinHz = 0L
        lastRamReassertMs = 0L
        latchedPeakTempC = 0f
        afterburnDecision = AfterburnDecision(0f, AfterburnState.OFF)
        boostSelectedUntilMs = 0L
        smoothedSagMv = 0f
        lastSagSampleMs = 0L
        lastPowerStepMs = 0L
        lastPowerOverMs = 0L
        batteryTruthModel.reset()
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
        gameThreadRuntime.reset()
        cachedResolveCfgRef = null
        cachedResolvePkg = ""
        cachedEffectiveGameProfile = null
        lastAutoTunePrepPkg = ""
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
