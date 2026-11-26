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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import com.example.model.AfterburnState
import com.example.model.LogLevel
import com.example.model.StutterHunterConfig
import com.example.model.StutterHunterMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Shared helper returning protected system/essential packages (own app, launcher, keyboard, dialer). */
object ProtectedPackages {
    fun resolve(ctx: Context): Set<String> {
        val s = HashSet<String>()
        s.add(ctx.packageName)
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { s.add(it) }
        } catch (_: Exception) {
        }
        try {
            Settings.Secure.getString(ctx.contentResolver, "default_input_method")
                ?.substringBefore('/')?.let { if (it.isNotBlank()) s.add(it) }
        } catch (_: Exception) {
        }
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let { s.add(it) }
        } catch (_: Exception) {
        }
        return s
    }
}

enum class HunterPhase {
    OFF,
    IDLE,
    LEARNING,
    WATCHING,
    CHECKING,
    STOPPED
}

/** Why the governor itself (not a background process) is holding performance down. */
enum class GovernorThrottleCause { NONE, THERMAL, BATTERY }

enum class HunterActionResult {
    GOVERNOR_THERMAL,
    GOVERNOR_BATTERY,
    REPORTED,
    STOPPED_CHECKING,
    RECOVERED,
    DID_NOT_HELP,
    STOP_FAILED
}

data class HunterActionEntry(
    val id: Long,
    val timestampMs: Long,
    val gamePackage: String,
    val culpritPackage: String,
    val cpuPct: Float,
    val result: HunterActionResult,
    val fpsBefore: Int,
    val fpsAfter: Int? = null
)

data class StutterHunterStatus(
    val phase: HunterPhase = HunterPhase.OFF,
    val gamePackage: String = "",
    val learningPct: Int = 0,
    val baselineFps: Int = 0,
    val lastTargetPackage: String = "",
    val actions: List<HunterActionEntry> = emptyList()
)

/** Pure Stutter Hunter logic (no Android dependencies). */
object StutterLogic {
    const val WARMUP_MS = 20_000L
    const val MIN_BASELINE_SAMPLES = 120
    const val RECENT_HEALTHY_CAP = 240
    const val DIP_WINDOW_SECONDS = 10
    const val CONSECUTIVE_DIP_TRIGGER = 3
    const val WINDOW_DIP_TRIGGER = 5
    const val THROTTLE_COOLDOWN_MS = 10_000L
    const val GPU_HIGH_LOAD_PCT = 90f
    const val STOP_COOLDOWN_MS = 30_000L
    const val MAX_STOPS_PER_PACKAGE = 2
    const val MAX_STOPS_PER_SESSION = 6
    const val MAX_ACTION_HISTORY = 10
    const val NOTIFY_COOLDOWN_MS = 20_000L

    data class BaselineRecord(
        val fps: Float = 0f,
        val sampleCount: Int = 0
    )

    data class CulpritCandidate(
        val packageName: String,
        val avgCpuPct: Float,
        /** Set (> 0) when the app was picked for the RAM it holds under memory pressure, not for CPU. */
        val ramMb: Float = 0f
    )

    /** Memory state read with the process list. psiSomeAvg10 < 0 means the kernel has no pressure info. */
    data class MemInfo(val totalMb: Float, val availableMb: Float, val psiSomeAvg10: Float = -1f) {
        val availablePct: Float get() = if (totalMb > 0f) availableMb / totalMb * 100f else 100f
        /** Real pressure: little RAM left, or the kernel reports tasks stalling on memory. */
        val underPressure: Boolean get() = availablePct < LOW_AVAILABLE_PCT || psiSomeAvg10 >= PSI_STALL_PCT
    }

    const val LOW_AVAILABLE_PCT = 12f
    const val PSI_STALL_PCT = 8f
    const val MEM_HOG_MIN_MB = 350f
    const val MEM_HOG_MIN_SHARE = 0.06f

    /**
     * Second-chance pick for when nothing is burning CPU: under real memory pressure, the biggest background
     * app (same safety filters as the CPU pick) that holds a large share of RAM in every sample.
     * Without pressure, or without a clearly big holder, nothing is picked.
     */
    fun pickMemoryHog(
        deltaSnapshots: List<List<ProcRow>>,
        mem: MemInfo?,
        foregroundGame: String,
        ownPackage: String,
        protectedPackages: Set<String>,
        systemPackages: Set<String>,
        whitelist: Set<String>,
        excludedInSession: Set<String> = emptySet()
    ): CulpritCandidate? {
        if (mem == null || !mem.underPressure || deltaSnapshots.isEmpty()) return null
        val minMb = maxOf(MEM_HOG_MIN_MB, mem.totalMb * MEM_HOG_MIN_SHARE)
        val lowestMb = HashMap<String, Float>()
        val cpu = HashMap<String, Float>()
        val seenCount = HashMap<String, Int>()
        for (snapshot in deltaSnapshots) {
            for (row in snapshot) {
                val pkg = row.key
                if (pkg.isBlank() || !row.isApp || !ProcessMonitor.PACKAGE_RE.matches(pkg)) continue
                if (pkg == foregroundGame || row.foreground) continue
                if (pkg == ownPackage || pkg in protectedPackages || row.protectedReason != null) continue
                if (pkg in systemPackages || pkg in whitelist || pkg in excludedInSession) continue
                seenCount[pkg] = (seenCount[pkg] ?: 0) + 1
                lowestMb[pkg] = minOf(lowestMb[pkg] ?: Float.MAX_VALUE, row.ramMb)
                cpu[pkg] = (cpu[pkg] ?: 0f) + row.cpuPct.coerceAtLeast(0f)
            }
        }
        // An app missing from any sample is not a steady holder: only count apps present in every sample.
        val best = lowestMb.filter { it.value >= minMb && seenCount[it.key] == deltaSnapshots.size }.maxByOrNull { it.value } ?: return null
        return CulpritCandidate(best.key, (cpu[best.key] ?: 0f) / deltaSnapshots.size, ramMb = best.value)
    }

    fun percentile75(values: Collection<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val pos = (sorted.size - 1) * 0.75f
        val lo = pos.toInt().coerceIn(0, sorted.lastIndex)
        val hi = (lo + 1).coerceIn(0, sorted.lastIndex)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    class BaselineTracker(initial: BaselineRecord = BaselineRecord()) {
        var baselineFps: Float = initial.fps.coerceAtLeast(0f)
            private set
        var sampleCount: Int = initial.sampleCount.coerceAtLeast(0)
            private set
        var dirty: Boolean = false

        private val recentHealthy = ArrayDeque<Float>()
        private var healthyInSession: Int = 0

        val isReady: Boolean
            get() = sampleCount >= MIN_BASELINE_SAMPLES && baselineFps > 0f

        val learningPct: Int
            get() = ((sampleCount * 100) / MIN_BASELINE_SAMPLES).coerceIn(0, 99)

        fun onSample(
            fps: Float,
            dipPct: Int,
            elapsedSinceForegroundMs: Long = Long.MAX_VALUE
        ) {
            if (fps <= 0f || elapsedSinceForegroundMs < WARMUP_MS) return
            val dipRatio = dipPct.coerceIn(60, 90) / 100f

            if (isReady) {
                if (fps < baselineFps * dipRatio) {
                    // Ignore dips so bad frames or short stutters cannot drag the baseline down.
                    return
                }
                addHealthy(fps)
                healthyInSession++
                if (sampleCount < 10_000) sampleCount++
                if (healthyInSession >= 20 && healthyInSession % 10 == 0) {
                    val sessionP75 = percentile75(recentHealthy)
                    if (sessionP75 > 0f) {
                        val alpha = if (sessionP75 < baselineFps) 0.01f else 0.04f
                        baselineFps = baselineFps * (1f - alpha) + sessionP75 * alpha
                        dirty = true
                    }
                }
            } else {
                if (recentHealthy.size >= 20) {
                    val est = percentile75(recentHealthy)
                    if (fps < est * dipRatio) {
                        // Ignore dips even while building the first 120-second baseline.
                        return
                    }
                }
                addHealthy(fps)
                sampleCount++
                if (sampleCount >= MIN_BASELINE_SAMPLES) {
                    baselineFps = percentile75(recentHealthy)
                }
                dirty = true
            }
        }

        private fun addHealthy(fps: Float) {
            recentHealthy.addLast(fps)
            while (recentHealthy.size > RECENT_HEALTHY_CAP) recentHealthy.removeFirst()
        }

        fun toRecord(): BaselineRecord = BaselineRecord(baselineFps, sampleCount)

        fun resetSession() {
            recentHealthy.clear()
            healthyInSession = 0
        }
    }

    /**
     * Governor-caused throttling only. Requires an actual active cap or Afterburn block;
     * a low battery percentage alone never counts.
     */
    fun classifyThrottle(
        tempThrottleActive: Boolean,
        powerThrottleActive: Boolean,
        afterburnState: AfterburnState = AfterburnState.OFF
    ): GovernorThrottleCause = when {
        tempThrottleActive || afterburnState == AfterburnState.BLOCKED_THERMAL -> GovernorThrottleCause.THERMAL
        powerThrottleActive || afterburnState == AfterburnState.BLOCKED_BATTERY -> GovernorThrottleCause.BATTERY
        else -> GovernorThrottleCause.NONE
    }

    class StutterDetector {
        private var consecutiveDips = 0
        private val window = ArrayDeque<Boolean>()
        private var lastThrottleMs: Long? = null
        private var lastThermalMs: Long? = null
        private var lastBatteryMs: Long? = null

        /** Set when the last triggered stutter was attributed to governor throttling. */
        var lastSuppressedCause: GovernorThrottleCause = GovernorThrottleCause.NONE
            private set

        fun recordTick(
            nowMs: Long,
            fps: Float,
            baselineFps: Float,
            baselineReady: Boolean,
            dipPct: Int,
            tempThrottleActive: Boolean,
            powerThrottleActive: Boolean,
            gpuLoadPct: Float,
            afterburnState: AfterburnState = AfterburnState.OFF
        ): Boolean {
            when (classifyThrottle(tempThrottleActive, powerThrottleActive, afterburnState)) {
                GovernorThrottleCause.THERMAL -> { lastThrottleMs = nowMs; lastThermalMs = nowMs }
                GovernorThrottleCause.BATTERY -> { lastThrottleMs = nowMs; lastBatteryMs = nowMs }
                GovernorThrottleCause.NONE -> Unit
            }
            // Keep the old behavior for the plain flags even if both were set.
            if (powerThrottleActive) { lastThrottleMs = nowMs; lastBatteryMs = nowMs }
            if (tempThrottleActive) { lastThrottleMs = nowMs; lastThermalMs = nowMs }
            lastSuppressedCause = GovernorThrottleCause.NONE
            if (!baselineReady || baselineFps <= 0f || fps <= 0f) return false

            val threshold = baselineFps * (dipPct.coerceIn(60, 90) / 100f)
            val isDip = fps < threshold
            consecutiveDips = if (isDip) consecutiveDips + 1 else 0
            window.addLast(isDip)
            while (window.size > DIP_WINDOW_SECONDS) window.removeFirst()

            val triggered = consecutiveDips >= CONSECUTIVE_DIP_TRIGGER ||
                window.count { it } >= WINDOW_DIP_TRIGGER
            if (!triggered) return false

            consecutiveDips = 0
            window.clear()
            lastSuppressedCause = governorCause(nowMs)
            return !isNonProcessCause(nowMs, gpuLoadPct)
        }

        /** Most recent governor throttle cause inside the cooldown window (thermal wins ties). */
        fun governorCause(nowMs: Long): GovernorThrottleCause {
            val t = lastThermalMs
            val b = lastBatteryMs
            val thermalRecent = t != null && nowMs - t <= THROTTLE_COOLDOWN_MS
            val batteryRecent = b != null && nowMs - b <= THROTTLE_COOLDOWN_MS
            return when {
                thermalRecent && batteryRecent -> if (t!! >= b!!) GovernorThrottleCause.THERMAL else GovernorThrottleCause.BATTERY
                thermalRecent -> GovernorThrottleCause.THERMAL
                batteryRecent -> GovernorThrottleCause.BATTERY
                else -> GovernorThrottleCause.NONE
            }
        }

        fun isNonProcessCause(nowMs: Long, gpuLoadPct: Float): Boolean {
            val throttledRecently = lastThrottleMs != null &&
                (nowMs - lastThrottleMs!!) <= THROTTLE_COOLDOWN_MS
            return throttledRecently || gpuLoadPct >= GPU_HIGH_LOAD_PCT
        }

        fun reset() {
            consecutiveDips = 0
            window.clear()
            lastThrottleMs = null
            lastThermalMs = null
            lastBatteryMs = null
            lastSuppressedCause = GovernorThrottleCause.NONE
        }
    }

    fun pickCulprit(
        deltaSnapshots: List<List<ProcRow>>,
        foregroundGame: String,
        ownPackage: String,
        protectedPackages: Set<String>,
        systemPackages: Set<String>,
        whitelist: Set<String>,
        cpuThresholdPct: Int,
        excludedInSession: Set<String> = emptySet()
    ): CulpritCandidate? {
        if (deltaSnapshots.isEmpty()) return null
        val sumCpu = HashMap<String, Float>()

        for (snapshot in deltaSnapshots) {
            for (row in snapshot) {
                val pkg = row.key
                if (pkg.isBlank()) continue
                if (!row.isApp || !ProcessMonitor.PACKAGE_RE.matches(pkg)) continue
                if (pkg == foregroundGame || row.foreground) continue
                if (pkg == ownPackage || pkg in protectedPackages) continue
                if (row.protectedReason != null) continue
                if (pkg in systemPackages || pkg in whitelist || pkg in excludedInSession) continue
                sumCpu[pkg] = (sumCpu[pkg] ?: 0f) + row.cpuPct.coerceAtLeast(0f)
            }
        }

        val sampleCount = deltaSnapshots.size.toFloat()
        var bestPkg: String? = null
        var bestAvg = 0f
        for ((pkg, total) in sumCpu) {
            val avg = total / sampleCount
            if (avg >= cpuThresholdPct.toFloat() && avg > bestAvg) {
                bestAvg = avg
                bestPkg = pkg
            }
        }
        return bestPkg?.let { CulpritCandidate(it, bestAvg) }
    }

    class SessionBrakes {
        private var lastStopMs: Long? = null
        private val stopCounts = HashMap<String, Int>()
        private var totalStops = 0
        private val ignoredPackages = HashSet<String>()

        @Synchronized
        fun canStop(pkg: String, nowMs: Long): Boolean {
            if (totalStops >= MAX_STOPS_PER_SESSION) return false
            if ((stopCounts[pkg] ?: 0) >= MAX_STOPS_PER_PACKAGE) return false
            if (pkg in ignoredPackages) return false
            val last = lastStopMs
            if (last != null && (nowMs - last) < STOP_COOLDOWN_MS) return false
            return true
        }

        @Synchronized
        fun recordStop(pkg: String, nowMs: Long) {
            lastStopMs = nowMs
            totalStops++
            stopCounts[pkg] = (stopCounts[pkg] ?: 0) + 1
        }

        @Synchronized
        fun markDidNotHelp(pkg: String) {
            ignoredPackages.add(pkg)
        }

        @Synchronized
        fun excludedForStop(): Set<String> {
            val out = HashSet<String>(ignoredPackages)
            for ((pkg, count) in stopCounts) {
                if (count >= MAX_STOPS_PER_PACKAGE) out.add(pkg)
            }
            return out
        }

        @Synchronized
        fun reset() {
            lastStopMs = null
            stopCounts.clear()
            totalStops = 0
            ignoredPackages.clear()
        }
    }

    fun didRecover(baselineFps: Float, dipPct: Int, fpsAfter: Float): Boolean {
        if (baselineFps <= 0f || fpsAfter <= 0f) return false
        return fpsAfter >= baselineFps * (dipPct.coerceIn(60, 90) / 100f)
    }
}

class StutterHunterRunner(
    private val context: Context,
    private val engine: GovernorEngine,
    private val isScreenInteractive: () -> Boolean
) {
    private val baselineFile = File(context.filesDir, "stutter_baseline.json")
    private val ioLock = Any()
    // v3.7.0: onStop() is called from the service (main thread) while run() works on a background thread.
    private val stateLock = Any()
    private val baselines = ConcurrentHashMap<String, StutterLogic.BaselineTracker>()
    private val systemAppCache = ConcurrentHashMap<String, Boolean>()
    private val detector = StutterLogic.StutterDetector()
    private val brakes = StutterLogic.SessionBrakes()
    private val lastNotifyByPkgMs = ConcurrentHashMap<String, Long>()
    @Volatile private var lastMem: StutterLogic.MemInfo? = null

    private var activeGamePkg: String = ""
    private var gameEnteredAtMs: Long = 0L
    private var lastBaselineSaveMs: Long = System.currentTimeMillis()
    private var anyDirty: Boolean = false

    init {
        loadBaselines()
    }

    suspend fun run(scope: CoroutineScope) {
        try {
            while (scope.isActive) {
                val cfg = engine.config.value
                val hunterCfg = cfg.stutterHunter
                val tel = engine.telemetry.value

                if (!hunterCfg.enabled) {
                    endGameSessionIfNeeded()
                    updateUiPhase(HunterPhase.OFF)
                    delay(TICK_MS)
                    continue
                }

                val fg = tel.foregroundPackage
                val active = tel.isRunning &&
                    tel.rootGranted &&
                    isScreenInteractive() &&
                    fg.isNotBlank() &&
                    fg in cfg.gamePackages

                if (!active) {
                    endGameSessionIfNeeded()
                    updateUiPhase(HunterPhase.IDLE)
                    delay(TICK_MS)
                    continue
                }

                val nowMs = System.currentTimeMillis()
                val shouldHunt: Boolean
                val suppressedCause: GovernorThrottleCause
                val tracker: StutterLogic.BaselineTracker
                synchronized(stateLock) {
                    if (fg != activeGamePkg) {
                        endGameSessionIfNeeded()
                        activeGamePkg = fg
                        gameEnteredAtMs = nowMs
                        lastBaselineSaveMs = nowMs
                    }

                    tracker = baselines.getOrPut(fg) { StutterLogic.BaselineTracker() }
                    val elapsedInGameMs = nowMs - gameEnteredAtMs
                    tracker.onSample(tel.fps, hunterCfg.dipPct, elapsedInGameMs)
                    if (tracker.dirty) anyDirty = true

                    if (anyDirty && (nowMs - lastBaselineSaveMs) >= SAVE_INTERVAL_MS) {
                        saveBaselines()
                        lastBaselineSaveMs = nowMs
                    }

                    val readyToAct = tracker.isReady && elapsedInGameMs >= StutterLogic.WARMUP_MS
                    updateWatchingOrLearningStatus(fg, tracker)

                    shouldHunt = detector.recordTick(
                        nowMs = nowMs,
                        fps = tel.fps,
                        baselineFps = tracker.baselineFps,
                        baselineReady = readyToAct,
                        dipPct = hunterCfg.dipPct,
                        tempThrottleActive = tel.tempThrottleActive,
                        powerThrottleActive = tel.powerThrottleActive,
                        gpuLoadPct = tel.gpu.loadPct,
                        afterburnState = tel.afterburnState
                    )
                    // Read under the same lock: onStop() resets the detector from the main thread.
                    suppressedCause = detector.lastSuppressedCause
                }

                if (!shouldHunt && suppressedCause != GovernorThrottleCause.NONE) {
                    reportGovernorThrottle(fg, suppressedCause, tel.fps.roundToInt().coerceAtLeast(0), nowMs)
                }

                if (shouldHunt) {
                    investigateAndAct(
                        scope = scope,
                        gamePkg = fg,
                        tracker = tracker,
                        hunterCfg = hunterCfg,
                        fpsBefore = tel.fps.roundToInt().coerceAtLeast(0)
                    )
                }

                delay(TICK_MS)
            }
        } finally {
            onStop()
        }
    }

    fun onStop() {
        endGameSessionIfNeeded()
        updateUiPhase(HunterPhase.OFF)
    }

    private fun endGameSessionIfNeeded(): Unit = synchronized(stateLock) {
        if (activeGamePkg.isNotEmpty()) {
            baselines[activeGamePkg]?.resetSession()
            activeGamePkg = ""
            gameEnteredAtMs = 0L
        }
        detector.reset()
        brakes.reset()
        lastNotifyByPkgMs.clear()
        if (anyDirty) {
            saveBaselines()
            lastBaselineSaveMs = System.currentTimeMillis()
        }
    }

    private fun isStillActiveFor(gamePkg: String): Boolean {
        val cfg = engine.config.value
        val tel = engine.telemetry.value
        return cfg.stutterHunter.enabled &&
            tel.isRunning &&
            tel.rootGranted &&
            isScreenInteractive() &&
            tel.foregroundPackage == gamePkg &&
            gamePkg in cfg.gamePackages
    }

    private suspend fun waitWhileActive(scope: CoroutineScope, gamePkg: String, durationMs: Long): Boolean {
        var remaining = durationMs
        while (remaining > 0L && scope.isActive) {
            if (!isStillActiveFor(gamePkg)) return false
            val step = minOf(500L, remaining)
            delay(step)
            remaining -= step
        }
        return scope.isActive && isStillActiveFor(gamePkg)
    }

    /** Stutter caused by the governor itself: report the reason, never investigate or stop a process. */
    private fun reportGovernorThrottle(gamePkg: String, cause: GovernorThrottleCause, fps: Int, nowMs: Long) {
        val thermal = cause == GovernorThrottleCause.THERMAL
        engine.log(
            LogLevel.INFO,
            TAG,
            "FPS dip during $gamePkg (~$fps FPS) caused by governor ${if (thermal) "thermal" else "battery/power"} throttling; no process blamed"
        )
        recordAction(
            HunterActionEntry(
                id = actionSeq.getAndIncrement(),
                timestampMs = nowMs,
                gamePackage = gamePkg,
                culpritPackage = "",
                cpuPct = 0f,
                result = if (thermal) HunterActionResult.GOVERNOR_THERMAL else HunterActionResult.GOVERNOR_BATTERY,
                fpsBefore = fps
            )
        )
    }

    private suspend fun investigateAndAct(
        scope: CoroutineScope,
        gamePkg: String,
        tracker: StutterLogic.BaselineTracker,
        hunterCfg: StutterHunterConfig,
        fpsBefore: Int
    ) {
        updateUiStatus(
            phase = HunterPhase.CHECKING,
            gamePackage = gamePkg,
            learningPct = tracker.learningPct,
            baselineFps = tracker.baselineFps.roundToInt(),
            lastTarget = ""
        )

        val extraProtected = ProtectedPackages.resolve(context)
        val snap1 = sampleProcessesOnce() ?: return
        var prevTicks = snap1.first
        var prevMs = snap1.second

        val groupedSnapshots = ArrayList<List<ProcRow>>(2)
        for (idx in 0 until 2) {
            if (!waitWhileActive(scope, gamePkg, SAMPLE_GAP_MS)) return
            val next = sampleProcessesOnce() ?: break
            val dtMs = (next.second - prevMs).coerceAtLeast(1L)
            val rows = ProcessMonitor.group(
                procs = next.third,
                prevTicks = prevTicks,
                dtMs = dtMs,
                foreground = gamePkg,
                extraProtected = extraProtected
            )
            groupedSnapshots.add(rows)
            prevTicks = next.first
            prevMs = next.second
        }

        if (groupedSnapshots.isEmpty() || !isStillActiveFor(gamePkg)) return

        val systemPkgs = HashSet<String>()
        for (rows in groupedSnapshots) {
            for (r in rows) {
                if (r.isApp && ProcessMonitor.PACKAGE_RE.matches(r.key) && isSystemPackage(r.key)) {
                    systemPkgs.add(r.key)
                }
            }
        }

        val excluded = if (hunterCfg.mode == StutterHunterMode.STOP) {
            brakes.excludedForStop()
        } else {
            emptySet()
        }

        val culprit = StutterLogic.pickCulprit(
            deltaSnapshots = groupedSnapshots,
            foregroundGame = gamePkg,
            ownPackage = context.packageName,
            protectedPackages = extraProtected,
            systemPackages = systemPkgs,
            whitelist = hunterCfg.whitelist,
            cpuThresholdPct = hunterCfg.cpuThresholdPct,
            excludedInSession = excluded
        ) ?: StutterLogic.pickMemoryHog(
            deltaSnapshots = groupedSnapshots,
            mem = lastMem,
            foregroundGame = gamePkg,
            ownPackage = context.packageName,
            protectedPackages = extraProtected,
            systemPackages = systemPkgs,
            whitelist = hunterCfg.whitelist,
            excludedInSession = excluded
        ) ?: return

        val nowMs = System.currentTimeMillis()
        val avgCpuInt = culprit.avgCpuPct.roundToInt()
        val usage = if (culprit.ramMb > 0f) "${culprit.ramMb.roundToInt()} MB RAM while memory is low" else "$avgCpuInt% CPU"

        if (hunterCfg.mode == StutterHunterMode.NOTIFY) {
            val lastNotified = lastNotifyByPkgMs[culprit.packageName]
            if (lastNotified != null && (nowMs - lastNotified) < StutterLogic.NOTIFY_COOLDOWN_MS) {
                return
            }
            lastNotifyByPkgMs[culprit.packageName] = nowMs
            engine.log(
                LogLevel.INFO,
                TAG,
                "FPS dip during $gamePkg (~$fpsBefore FPS, normal ${tracker.baselineFps.roundToInt()}): ${culprit.packageName} using $usage"
            )
            recordAction(
                HunterActionEntry(
                    id = actionSeq.getAndIncrement(),
                    timestampMs = nowMs,
                    gamePackage = gamePkg,
                    culpritPackage = culprit.packageName,
                    cpuPct = culprit.avgCpuPct,
                    result = HunterActionResult.REPORTED,
                    fpsBefore = fpsBefore
                )
            )
            return
        }

        // Mode == STOP
        if (!brakes.canStop(culprit.packageName, nowMs)) {
            return
        }

        val stopRes = try {
            engine.rootShell.exec("am force-stop ${culprit.packageName}")
        } catch (_: Throwable) {
            return
        }

        if (!stopRes.isSuccess) {
            engine.log(
                LogLevel.WARN,
                TAG,
                "Failed to stop ${culprit.packageName}: ${stopRes.error ?: "exit ${stopRes.exitCode}"}"
            )
            recordAction(
                HunterActionEntry(
                    id = actionSeq.getAndIncrement(),
                    timestampMs = nowMs,
                    gamePackage = gamePkg,
                    culpritPackage = culprit.packageName,
                    cpuPct = culprit.avgCpuPct,
                    result = HunterActionResult.STOP_FAILED,
                    fpsBefore = fpsBefore
                )
            )
            return
        }

        brakes.recordStop(culprit.packageName, nowMs)
        val entryId = actionSeq.getAndIncrement()
        engine.log(
            LogLevel.INFO,
            TAG,
            "Stopped ${culprit.packageName} ($usage) during $gamePkg dip ($fpsBefore FPS)"
        )
        recordAction(
            HunterActionEntry(
                id = entryId,
                timestampMs = nowMs,
                gamePackage = gamePkg,
                culpritPackage = culprit.packageName,
                cpuPct = culprit.avgCpuPct,
                result = HunterActionResult.STOPPED_CHECKING,
                fpsBefore = fpsBefore
            )
        )
        updateUiStatus(
            phase = HunterPhase.STOPPED,
            gamePackage = gamePkg,
            learningPct = tracker.learningPct,
            baselineFps = tracker.baselineFps.roundToInt(),
            lastTarget = culprit.packageName
        )

        if (!waitWhileActive(scope, gamePkg, RECOVERY_WAIT_MS)) return

        val fpsAfterRaw = engine.telemetry.value.fps
        if (fpsAfterRaw > 0f) {
            val fpsAfter = fpsAfterRaw.roundToInt()
            val recovered = StutterLogic.didRecover(tracker.baselineFps, hunterCfg.dipPct, fpsAfterRaw)
            if (!recovered) {
                brakes.markDidNotHelp(culprit.packageName)
                engine.log(
                    LogLevel.INFO,
                    TAG,
                    "Stopping ${culprit.packageName} did not help ($fpsBefore -> $fpsAfter FPS); ignoring for this session"
                )
                updateActionResult(entryId, HunterActionResult.DID_NOT_HELP, fpsAfter)
            } else {
                updateActionResult(entryId, HunterActionResult.RECOVERED, fpsAfter)
            }
        }
    }

    private suspend fun sampleProcessesOnce(): Triple<Map<Int, Long>, Long, List<ProcSnap>>? {
        return try {
            val res = engine.scanShell.exec(ProcessMonitor.COMMAND)
            if (!res.isSuccess || res.stdout.isBlank()) return null
            val now = System.currentTimeMillis()
            val procs = ProcessMonitor.parse(res.stdout)
            if (procs.isEmpty()) return null
            lastMem = ProcessMonitor.parseMem(res.stdout)
            Triple(procs.associate { it.pid to it.ticks }, now, procs)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSystemPackage(pkg: String): Boolean {
        return systemAppCache.getOrPut(pkg) {
            try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            } catch (_: Throwable) {
                true
            }
        }
    }

    private fun updateWatchingOrLearningStatus(gamePkg: String, tracker: StutterLogic.BaselineTracker) {
        if (tracker.isReady) {
            updateUiStatus(
                phase = HunterPhase.WATCHING,
                gamePackage = gamePkg,
                learningPct = 100,
                baselineFps = tracker.baselineFps.roundToInt(),
                lastTarget = ""
            )
        } else {
            updateUiStatus(
                phase = HunterPhase.LEARNING,
                gamePackage = gamePkg,
                learningPct = tracker.learningPct,
                baselineFps = 0,
                lastTarget = ""
            )
        }
    }

    private fun loadBaselines() = synchronized(ioLock) {
        try {
            if (!baselineFile.exists()) return@synchronized
            val root = JSONObject(baselineFile.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val obj = root.optJSONObject(pkg) ?: continue
                val fps = obj.optDouble("fps", 0.0).toFloat()
                val samples = obj.optInt("samples", 0)
                if (fps > 0f && samples > 0) {
                    baselines[pkg] = StutterLogic.BaselineTracker(StutterLogic.BaselineRecord(fps, samples))
                }
            }
        } catch (e: Exception) {
            engine.log(LogLevel.WARN, TAG, "Could not load stutter baselines: ${e.message}")
        }
    }

    private fun saveBaselines() = synchronized(ioLock) {
        try {
            val root = JSONObject()
            for ((pkg, tracker) in baselines) {
                val rec = tracker.toRecord()
                if (rec.sampleCount > 0) {
                    root.put(pkg, JSONObject().apply {
                        put("fps", rec.fps.toDouble())
                        put("samples", rec.sampleCount)
                    })
                }
                tracker.dirty = false
            }
            val tmp = File(baselineFile.parentFile, "${baselineFile.name}.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(baselineFile)) {
                baselineFile.writeText(root.toString())
                tmp.delete()
            }
            anyDirty = false
        } catch (e: Exception) {
            engine.log(LogLevel.WARN, TAG, "Could not save stutter baselines: ${e.message}")
        }
    }

    private fun updateUiPhase(phase: HunterPhase) {
        val curr = _status.value
        if (curr.phase == phase && curr.gamePackage.isEmpty() && curr.lastTargetPackage.isEmpty()) return
        _status.value = curr.copy(
            phase = phase,
            gamePackage = "",
            learningPct = 0,
            baselineFps = 0,
            lastTargetPackage = ""
        )
    }

    private fun updateUiStatus(
        phase: HunterPhase,
        gamePackage: String,
        learningPct: Int,
        baselineFps: Int,
        lastTarget: String
    ) {
        val curr = _status.value
        if (curr.phase == phase &&
            curr.gamePackage == gamePackage &&
            curr.learningPct == learningPct &&
            curr.baselineFps == baselineFps &&
            curr.lastTargetPackage == lastTarget
        ) return
        _status.value = curr.copy(
            phase = phase,
            gamePackage = gamePackage,
            learningPct = learningPct,
            baselineFps = baselineFps,
            lastTargetPackage = lastTarget
        )
    }

    private fun recordAction(entry: HunterActionEntry) {
        val curr = _status.value
        val updated = (listOf(entry) + curr.actions).take(StutterLogic.MAX_ACTION_HISTORY)
        _status.value = curr.copy(actions = updated)
    }

    private fun updateActionResult(id: Long, result: HunterActionResult, fpsAfter: Int) {
        val curr = _status.value
        val updated = curr.actions.map {
            if (it.id == id) it.copy(result = result, fpsAfter = fpsAfter) else it
        }
        _status.value = curr.copy(actions = updated)
    }

    companion object {
        private const val TAG = "Hunter"
        private const val TICK_MS = 1_000L
        private const val SAMPLE_GAP_MS = 2_000L
        private const val RECOVERY_WAIT_MS = 6_000L
        private const val SAVE_INTERVAL_MS = 300_000L

        private val actionSeq = AtomicLong(1L)
        private val _status = MutableStateFlow(StutterHunterStatus())
        val status: StateFlow<StutterHunterStatus> = _status.asStateFlow()
    }
}
