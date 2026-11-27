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

import com.example.model.BatteryState
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
import com.example.model.GpuOpp
import com.example.model.HardwareSpec
import com.example.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

data class ComponentFreqCurvePoint(
    val freqMhz: Int,
    val predictedMw: Float,
    val confidencePct: Float,
    val samples: Int
)

data class ComponentPowerSummary(
    val componentId: String,
    val displayName: String,
    val liveFreqMhz: Int,
    val liveUtilPct: Float,
    val livePredictedMw: Float,
    val liveConfidencePct: Float,
    val curvePoints: List<ComponentFreqCurvePoint>
)

data class PowerModelUiSnapshot(
    val learningEnabled: Boolean = true,
    val efficiencyEnabled: Boolean = false,
    val totalSamples: Long = 0L,
    val overallConfidencePct: Float = 0f,
    val scenariosLearned: Int = 0,
    val lastSavedMs: Long = 0L,
    val baselineMw: Float = 650f,
    val liveMeasuredMw: Float = 0f,
    val liveEstimatedMw: Float = 0f,
    val liveErrorPct: Float = 0f,
    val activeScenario: String = "idle",
    val backoffActive: Boolean = false,
    val backoffRemainingSec: Int = 0,
    val efficiencySavingsPct: Float = 0f,
    val samplesSkippedCharging: Long = 0L,
    val learningPausedCharging: Boolean = false,
    val batteryState: BatteryState = BatteryState.DISCHARGING,
    val components: List<ComponentPowerSummary> = emptyList()
)

/**
 * On-device learned power model and efficiency governor helper.
 *
 * Algorithm:
 *   Total device discharge power is modeled additively as:
 *     P_pred(mW) = baselineMw + sum_i w[component_i, freq_i, utilBand_i]
 *
 *   Training runs on a ~1 Hz background coroutine using preallocated primitive staging buffers:
 *   - Sample filtering drops charging, screen-off sleep, invalid/zero wattage, transient frequency/load
 *     jumps (>40% clock/load delta across 1 s), and rolling-MAD outliers.
 *   - Online Projected Regularized LMS updates `baselineMw` and active bucket weights `w[k]` with
 *     normalized step size `lr / (1 + k)`, non-negative clamping, gentle L2 shrinkage toward a weak
 *     physical prior when a bucket has few samples, and exponential sample-count decay (`0.9995`)
 *     so the model continuously adapts over device lifetime.
 *   - Global weights are paired with a bounded cross-scenario overlay (max 24 scenarios, LRU evicted)
 *     and blended smoothly by bucket confidence (0..100%).
 */
class PowerModel(
    private val storageFile: File,
    private val onLog: (LogLevel, String, String) -> Unit = { _, _, _ -> }
) {

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CLUSTERS = 8
        const val MAX_SCENARIOS = 24
        const val AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000L
        private const val DECAY_FACTOR = 0.9995f
        /** v4.0.6 faster learning: confidence curve time constant in samples (was 16: ~60% at 14 samples, now ~60% at 8). */
        private const val CONF_TAU_SAMPLES = 9f
        /** Share of the neighbour's confidence a never-visited frequency inherits when its power is interpolated (was 0.45). */
        private const val INTERP_CONF_FACTOR = 0.7f
        /** Multiplier on the per-sample correction step (was 1.0). */
        private const val FAST_STEP_BOOST = 1.35f
        private const val BACKOFF_DURATION_MS = 8_000L
        private const val STEP_COOLDOWN_MS = 600L
        private const val EXPLORE_HOLD_MS = 1_500L
        private const val EXPLORE_COOLDOWN_MS = 6_000L
        private const val BATTERY_STATE_SETTLE_MS = 5_000L

        fun utilBand(utilPct: Float): Int = when {
            utilPct < 10f -> 0
            utilPct < 25f -> 1
            utilPct < 50f -> 2
            utilPct < 75f -> 3
            else -> 4
        }

        fun bandMidUtil(band: Int): Float = when (band) {
            0 -> 5f
            1 -> 17.5f
            2 -> 37.5f
            3 -> 62.5f
            else -> 87.5f
        }

        fun scenarioKeyFor(fgPkg: String, isGame: Boolean, isInteractive: Boolean): String {
            if (!isInteractive) return "idle"
            if (fgPkg.isBlank() || fgPkg == "com.android.systemui") return "idle"
            return if (isGame) "game:$fgPkg" else fgPkg
        }
    }

    private data class BucketStats(
        var powerMw: Float,
        var effSamples: Float = 0f,
        var rawCount: Int = 0,
        var penaltyFactor: Float = 1f,
        var lastSeenMs: Long = 0L
    )

    private data class ScenarioData(
        val key: String,
        var lastAccessMs: Long,
        var sampleCount: Long = 0L,
        val buckets: MutableMap<Long, BucketStats> = HashMap()
    )

    private data class ComponentMeta(
        val index: Int,
        val componentId: String,
        val displayName: String,
        val isGpu: Boolean,
        val freqsMhz: IntArray,
        val maxFreqMhz: Int,
        val coreCount: Int
    )

    private class TickStagingSlot {
        var seq: Long = 0L
        var timestampMs: Long = 0L
        var watts: Float = 0f
        var isCharging: Boolean = false
        var batteryState: BatteryState = BatteryState.DISCHARGING
        var isInteractive: Boolean = true
        var isSleepSaving: Boolean = false
        var isGame: Boolean = false
        var fgPkg: String = ""
        var fps: Float = -1f
        var learningEnabled: Boolean = true
        var efficiencyEnabled: Boolean = false
        var clusterCount: Int = 0
        val clusterPolicyIds = IntArray(MAX_CLUSTERS)
        val clusterFreqMhz = IntArray(MAX_CLUSTERS)
        val clusterUtilPct = FloatArray(MAX_CLUSTERS)
        val clusterCoreCounts = IntArray(MAX_CLUSTERS)
        var gpuAvailable: Boolean = false
        var gpuFreqMhz: Int = 0
        var gpuUtilPct: Float = 0f
    }

    private data class SelectorDecisionState(
        var lastDecisionMs: Long = 0L,
        var lastTargetKhz: Long = 0L,
        var lastStandardKhz: Long = 0L,
        var lastWasLowered: Boolean = false,
        var lastBucketKey: Long = -1L,
        var exploreTargetKhz: Long = 0L,
        var exploreUntilMs: Long = 0L,
        var lastExploreAtMs: Long = 0L
    )

    private val stageLock = Any()
    private val staging = TickStagingSlot()
    private var lastProcessedSeq = 0L

    private val modelLock = Any()
    private val componentIdToIndex = HashMap<String, Int>()
    private val componentMetaById = LinkedHashMap<String, ComponentMeta>()
    private val globalBuckets = HashMap<Long, BucketStats>()
    private val scenarios = LinkedHashMap<String, ScenarioData>()

    private var baselineMw: Float = 650f
    private var totalSamples: Long = 0L
    private var rollingMadMw: Float = 600f
    private var dirty: Boolean = false
    private var lastSavedMs: Long = 0L

    // Previous 1 Hz state for transient jump filtering
    private var prevHasSample = false
    private var prevSampleWatts = 0f
    private val prevSampleFreqMhz = HashMap<String, Int>()
    private val prevSampleUtilPct = HashMap<String, Float>()

    // Live diagnostics (updated at ~1 Hz)
    private var liveMeasuredMw = 0f
    private var liveEstimatedMw = 0f
    private var liveActiveScenario = "idle"
    private var liveLearningEnabled = true
    private var liveEfficiencyEnabled = false
    private var lastTrainBatteryState = BatteryState.DISCHARGING
    private var lastBatteryFlipMs = 0L
    private var samplesSkippedCharging = 0L
    private val liveCompFreqMhz = HashMap<String, Int>()
    private val liveCompUtilPct = HashMap<String, Float>()
    private var smoothedSavingsPct = 0f
    private var lastMinuteLogMs = 0L

    // Stage 2 efficiency control state
    private val selectorStates = HashMap<String, SelectorDecisionState>()
    private var backoffUntilMs = 0L
    private var lastModeLogKey: String? = null
    private var lastBackoffLogMs = 0L

    @Volatile
    private var uiVisible: Boolean = false
    private var samplerJob: Job? = null
    private var appScope: CoroutineScope? = null

    private val _uiSnapshot = MutableStateFlow(PowerModelUiSnapshot())
    val uiSnapshot: StateFlow<PowerModelUiSnapshot> = _uiSnapshot.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            refreshUiSnapshotNow()
        }
    }

    fun start(scope: CoroutineScope) {
        appScope = scope
        if (samplerJob?.isActive == true) return
        samplerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1_000L)
                processLatestSampleIfDue()
            }
        }
    }

    fun updateHardwareSpec(hw: HardwareSpec) {
        synchronized(modelLock) {
            val freshOrder = LinkedHashMap<String, ComponentMeta>()
            for (p in hw.cpuPolicies) {
                val id = "cpu_${p.policyId}"
                val freqsMhz = p.availableFreqsKhz
                    .map { (it / 1000L).toInt() }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                    .toIntArray()
                val idx = getOrAssignCompIndexLocked(id)
                freshOrder[id] = ComponentMeta(
                    index = idx,
                    componentId = id,
                    displayName = p.name.ifBlank { "CPU Policy ${p.policyId}" },
                    isGpu = false,
                    freqsMhz = freqsMhz,
                    maxFreqMhz = freqsMhz.lastOrNull() ?: 2200,
                    coreCount = p.relatedCpus.size.coerceAtLeast(1)
                )
            }
            if (hw.gpu.discovered && hw.gpu.oppTable.isNotEmpty()) {
                val id = "gpu"
                val freqsMhz = hw.gpu.oppTable
                    .map { (it.freqKhz / 1000L).toInt() }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                    .toIntArray()
                val idx = getOrAssignCompIndexLocked(id)
                freshOrder[id] = ComponentMeta(
                    index = idx,
                    componentId = id,
                    displayName = "GPU",
                    isGpu = true,
                    freqsMhz = freqsMhz,
                    maxFreqMhz = freqsMhz.lastOrNull() ?: 1000,
                    coreCount = 1
                )
            }
            if (freshOrder.isNotEmpty()) {
                componentMetaById.clear()
                componentMetaById.putAll(freshOrder)
            }
        }
        if (uiVisible) refreshUiSnapshotNow()
    }

    /**
     * Zero-allocation staging call from GovernorEngine control tick.
     */
    fun submitTick(
        timestampMs: Long,
        watts: Float,
        isCharging: Boolean,
        isInteractive: Boolean,
        isSleepSaving: Boolean,
        isGame: Boolean,
        fgPkg: String,
        fps: Float,
        learningEnabled: Boolean,
        efficiencyEnabled: Boolean,
        clusterCount: Int,
        clusterPolicyIds: IntArray,
        clusterFreqMhz: IntArray,
        clusterUtilPct: FloatArray,
        clusterCoreCounts: IntArray,
        gpuAvailable: Boolean,
        gpuFreqMhz: Int,
        gpuUtilPct: Float,
        batteryState: BatteryState = if (isCharging) BatteryState.CHARGING else BatteryState.DISCHARGING
    ) {
        synchronized(stageLock) {
            staging.timestampMs = timestampMs
            staging.watts = watts
            staging.isCharging = isCharging
            staging.batteryState = batteryState
            staging.isInteractive = isInteractive
            staging.isSleepSaving = isSleepSaving
            staging.isGame = isGame
            staging.fgPkg = fgPkg
            staging.fps = fps
            staging.learningEnabled = learningEnabled
            staging.efficiencyEnabled = efficiencyEnabled
            val n = clusterCount.coerceIn(0, MAX_CLUSTERS)
            staging.clusterCount = n
            for (i in 0 until n) {
                staging.clusterPolicyIds[i] = clusterPolicyIds[i]
                staging.clusterFreqMhz[i] = clusterFreqMhz[i]
                staging.clusterUtilPct[i] = clusterUtilPct[i]
                staging.clusterCoreCounts[i] = clusterCoreCounts[i]
            }
            staging.gpuAvailable = gpuAvailable
            staging.gpuFreqMhz = gpuFreqMhz
            staging.gpuUtilPct = gpuUtilPct
            staging.seq += 1L
        }
    }

    private fun processLatestSampleIfDue() {
        var timestampMs = 0L
        var watts = 0f
        var isCharging = false
        var batteryState = BatteryState.DISCHARGING
        var isInteractive = true
        var isSleepSaving = false
        var isGame = false
        var fgPkg = ""
        var learningEnabled = true
        var efficiencyEnabled = false
        var clusterCount = 0
        val policyIds = IntArray(MAX_CLUSTERS)
        val freqMhz = IntArray(MAX_CLUSTERS)
        val utilPct = FloatArray(MAX_CLUSTERS)
        val coreCounts = IntArray(MAX_CLUSTERS)
        var gpuAvailable = false
        var gpuFreqMhz = 0
        var gpuUtilPct = 0f

        synchronized(stageLock) {
            if (staging.seq == lastProcessedSeq || staging.seq == 0L) {
                if (uiVisible) refreshUiSnapshotNow()
                return
            }
            lastProcessedSeq = staging.seq
            timestampMs = staging.timestampMs
            watts = staging.watts
            isCharging = staging.isCharging
            batteryState = staging.batteryState
            isInteractive = staging.isInteractive
            isSleepSaving = staging.isSleepSaving
            isGame = staging.isGame
            fgPkg = staging.fgPkg
            learningEnabled = staging.learningEnabled
            efficiencyEnabled = staging.efficiencyEnabled
            clusterCount = staging.clusterCount
            for (i in 0 until clusterCount) {
                policyIds[i] = staging.clusterPolicyIds[i]
                freqMhz[i] = staging.clusterFreqMhz[i]
                utilPct[i] = staging.clusterUtilPct[i]
                coreCounts[i] = staging.clusterCoreCounts[i]
            }
            gpuAvailable = staging.gpuAvailable
            gpuFreqMhz = staging.gpuFreqMhz
            gpuUtilPct = staging.gpuUtilPct
        }

        val jsonToSave = trainStepInternal(
            timestampMs = timestampMs,
            watts = watts,
            isCharging = isCharging,
            isInteractive = isInteractive,
            isSleepSaving = isSleepSaving,
            isGame = isGame,
            fgPkg = fgPkg,
            learningEnabled = learningEnabled,
            efficiencyEnabled = efficiencyEnabled,
            clusterCount = clusterCount,
            clusterPolicyIds = policyIds,
            clusterFreqMhz = freqMhz,
            clusterUtilPct = utilPct,
            clusterCoreCounts = coreCounts,
            gpuAvailable = gpuAvailable,
            gpuFreqMhz = gpuFreqMhz,
            gpuUtilPct = gpuUtilPct,
            batteryState = batteryState
        )

        if (jsonToSave != null) {
            appScope?.launch(Dispatchers.IO) {
                writeJsonAtomically(jsonToSave)
            } ?: writeJsonAtomically(jsonToSave)
        }

        if (uiVisible) {
            refreshUiSnapshotNow()
        }
    }

    /**
     * Exposed internally for deterministic unit tests and background 1 Hz sampler.
     * Returns serialized JSON if a periodic 5-minute save should be written now.
     */
    internal fun trainStepInternal(
        timestampMs: Long,
        watts: Float,
        isCharging: Boolean,
        isInteractive: Boolean,
        isSleepSaving: Boolean,
        isGame: Boolean,
        fgPkg: String,
        learningEnabled: Boolean,
        efficiencyEnabled: Boolean,
        clusterCount: Int,
        clusterPolicyIds: IntArray,
        clusterFreqMhz: IntArray,
        clusterUtilPct: FloatArray,
        clusterCoreCounts: IntArray,
        gpuAvailable: Boolean,
        gpuFreqMhz: Int,
        gpuUtilPct: Float,
        batteryState: BatteryState = if (isCharging) BatteryState.CHARGING else BatteryState.DISCHARGING
    ): String? {
        val scenarioKey = scenarioKeyFor(fgPkg, isGame, isInteractive)
        val measuredMw = (watts * 1000f).coerceIn(0f, 60_000f)

        var shouldSaveJson: String? = null
        var statusMsg: String? = null

        synchronized(modelLock) {
            liveLearningEnabled = learningEnabled
            liveEfficiencyEnabled = efficiencyEnabled
            liveActiveScenario = scenarioKey
            liveMeasuredMw = measuredMw
            if (batteryState != lastTrainBatteryState) {
                lastTrainBatteryState = batteryState
                lastBatteryFlipMs = timestampMs
            }

            val activeKeys = ArrayList<Long>(clusterCount + 1)
            val activePriors = ArrayList<Float>(clusterCount + 1)
            var totalUtilSum = 0f
            var transientJump = false

            for (i in 0 until clusterCount) {
                val pid = clusterPolicyIds[i]
                val compId = "cpu_$pid"
                val fMhz = snapFreqMhzLocked(compId, clusterFreqMhz[i])
                val u = clusterUtilPct[i].coerceIn(0f, 100f)
                val cores = clusterCoreCounts[i].coerceAtLeast(1)
                ensureMetaExistsLocked(compId, "CPU Policy $pid", isGpu = false, fMhz = fMhz, cores = cores)

                liveCompFreqMhz[compId] = fMhz
                liveCompUtilPct[compId] = u
                totalUtilSum += u

                if (prevHasSample) {
                    val prevF = prevSampleFreqMhz[compId] ?: fMhz
                    val prevU = prevSampleUtilPct[compId] ?: u
                    val maxF = (componentMetaById[compId]?.maxFreqMhz ?: max(fMhz, 2000)).coerceAtLeast(1)
                    if (abs(fMhz - prevF).toFloat() / maxF > 0.40f || abs(u - prevU) > 40f) {
                        transientJump = true
                    }
                }
                prevSampleFreqMhz[compId] = fMhz
                prevSampleUtilPct[compId] = u

                if (fMhz > 0) {
                    val key = packBucketKeyLocked(compId, fMhz, utilBand(u))
                    activeKeys.add(key)
                    activePriors.add(computePriorMwLocked(compId, fMhz, u))
                }
            }

            if (gpuAvailable && gpuFreqMhz > 0) {
                val compId = "gpu"
                val fMhz = snapFreqMhzLocked(compId, gpuFreqMhz)
                val u = gpuUtilPct.coerceIn(0f, 100f)
                ensureMetaExistsLocked(compId, "GPU", isGpu = true, fMhz = fMhz, cores = 1)

                liveCompFreqMhz[compId] = fMhz
                liveCompUtilPct[compId] = u
                totalUtilSum += u

                if (prevHasSample) {
                    val prevF = prevSampleFreqMhz[compId] ?: fMhz
                    val prevU = prevSampleUtilPct[compId] ?: u
                    val maxF = (componentMetaById[compId]?.maxFreqMhz ?: max(fMhz, 900)).coerceAtLeast(1)
                    if (abs(fMhz - prevF).toFloat() / maxF > 0.40f || abs(u - prevU) > 40f) {
                        transientJump = true
                    }
                }
                prevSampleFreqMhz[compId] = fMhz
                prevSampleUtilPct[compId] = u

                val key = packBucketKeyLocked(compId, fMhz, utilBand(u))
                activeKeys.add(key)
                activePriors.add(computePriorMwLocked(compId, fMhz, u))
            }

            val prevWatts = prevSampleWatts
            val wasHasPrev = prevHasSample
            prevHasSample = true
            prevSampleWatts = watts

            // Compute live model prediction even if we skip learning on this sample
            var estSum = baselineMw
            for (idx in activeKeys.indices) {
                estSum += predictBucketMwLocked(activeKeys[idx], activePriors[idx], scenarioKey).first
            }
            liveEstimatedMw = estSum

            val isDischargeState = !isCharging && batteryState == BatteryState.DISCHARGING
            val settledAfterFlip = (timestampMs - lastBatteryFlipMs) >= BATTERY_STATE_SETTLE_MS
            if (learningEnabled && isInteractive && !isSleepSaving && !isDischargeState) {
                samplesSkippedCharging += 1L
            }

            val powerValid = watts.isFinite() && watts > 0.05f && watts <= 60f
            val powerJump = wasHasPrev && abs((watts - prevWatts) * 1000f) > 2500f && transientJump
            val sampleClean = learningEnabled &&
                isInteractive &&
                !isSleepSaving &&
                isDischargeState &&
                settledAfterFlip &&
                powerValid &&
                activeKeys.isNotEmpty() &&
                !powerJump &&
                !transientJump

            if (sampleClean) {
                val error = measuredMw - estSum
                val outlierThreshold = max(1800f, 3.8f * rollingMadMw)
                val isOutlier = totalSamples >= 20L && abs(error) > outlierThreshold

                // Adapt rolling MAD gently
                val absErr = abs(error).coerceAtMost(6000f)
                val madAlpha = if (isOutlier) 0.01f else 0.06f
                rollingMadMw = (rollingMadMw + (absErr - rollingMadMw) * madAlpha).coerceIn(120f, 3500f)

                if (!isOutlier) {
                    val k = activeKeys.size
                    // Projected regularized LMS update
                    val step = FAST_STEP_BOOST * 0.09f / (1f + k * 0.5f)
                    val avgUtil = totalUtilSum / max(1, k)
                    val baseLr = if (avgUtil < 15f) 0.045f else 0.015f
                    baselineMw = (baselineMw + error * baseLr).coerceIn(150f, 3500f)

                    val scenario = getOrCreateScenarioLocked(scenarioKey, timestampMs)
                    scenario.sampleCount += 1L
                    scenario.lastAccessMs = timestampMs

                    // Share residual weighted by each active bucket's prior magnitude so bigger clusters absorb realistic attribution
                    var priorSum = 0f
                    for (p in activePriors) priorSum += p.coerceAtLeast(25f)
                    priorSum = priorSum.coerceAtLeast(1f)

                    for (i in 0 until k) {
                        val key = activeKeys[i]
                        val prior = activePriors[i]
                        val share = (prior.coerceAtLeast(25f) / priorSum) * k
                        val delta = error * step * share.coerceIn(0.35f, 2.2f)

                        updateBucketLocked(globalBuckets, key, prior, delta, timestampMs)
                        updateBucketLocked(scenario.buckets, key, prior, delta * 1.15f, timestampMs)
                    }

                    // Recompute live estimate right after update
                    var postEst = baselineMw
                    for (idx in activeKeys.indices) {
                        postEst += predictBucketMwLocked(activeKeys[idx], activePriors[idx], scenarioKey).first
                    }
                    liveEstimatedMw = postEst

                    totalSamples += 1L
                    dirty = true

                    if (lastSavedMs == 0L) lastSavedMs = timestampMs
                    if (timestampMs - lastSavedMs >= AUTO_SAVE_INTERVAL_MS) {
                        lastSavedMs = timestampMs
                        shouldSaveJson = serializeToJsonStringLocked()
                        dirty = false
                    }
                }
            }

            if (learningEnabled && timestampMs - lastMinuteLogMs >= 60_000L && totalSamples > 0L) {
                lastMinuteLogMs = timestampMs
                val conf = computeOverallConfidenceLocked()
                val errPct = if (liveMeasuredMw > 50f) {
                    abs(liveEstimatedMw - liveMeasuredMw) / liveMeasuredMw * 100f
                } else 0f
                statusMsg = "samples=$totalSamples, conf=${conf.roundToInt()}%, scenarios=${scenarios.size}, " +
                    "est=${(liveEstimatedMw / 1000f).format2()}W vs meas=${(liveMeasuredMw / 1000f).format2()}W (err=${errPct.roundToInt()}%)"
            }
        }

        statusMsg?.let { onLog(LogLevel.INFO, "PowerModel", it) }
        return shouldSaveJson
    }

    private fun updateBucketLocked(
        map: MutableMap<Long, BucketStats>,
        key: Long,
        prior: Float,
        delta: Float,
        nowMs: Long
    ) {
        val b = map.getOrPut(key) { BucketStats(powerMw = prior) }
        val regPull = if (b.effSamples < 15f) (prior - b.powerMw) * 0.02f else 0f
        val effectiveLr = when {
            b.effSamples < 8f -> 1.25f
            b.effSamples < 30f -> 0.95f
            else -> 0.75f
        }
        b.powerMw = (b.powerMw + delta * effectiveLr + regPull).coerceIn(5f, 15_000f)
        b.effSamples = b.effSamples * DECAY_FACTOR + 1f
        b.rawCount += 1
        if (b.penaltyFactor < 1f) {
            b.penaltyFactor = (b.penaltyFactor + 0.02f).coerceAtMost(1f)
        }
        b.lastSeenMs = nowMs
    }

    /**
     * Predicts power draw (mW) and confidence (0..100%) for a component at given frequency and utilization.
     */
    fun predictPowerMw(
        component: String,
        freqMhz: Int,
        utilPct: Float,
        scenarioKey: String
    ): Pair<Float, Float> {
        synchronized(modelLock) {
            val snappedFreq = snapFreqMhzLocked(component, freqMhz)
            val band = utilBand(utilPct.coerceIn(0f, 100f))
            val compIdx = componentIdToIndex[component]
            val prior = computePriorMwLocked(component, snappedFreq, utilPct)
            if (compIdx == null) return prior to 0f
            val key = packKey(compIdx, snappedFreq, band)
            val (pred, conf01) = predictBucketMwLocked(key, prior, scenarioKey)
            if (conf01 > 0.01f) {
                return pred to (conf01 * 100f).coerceIn(0f, 100f)
            }
            // Fallback interpolation across nearest observed frequency bucket in same component
            val interp = interpolateComponentPowerLocked(compIdx, snappedFreq, band, prior, scenarioKey)
            return interp.first to (interp.second * 100f).coerceIn(0f, 100f)
        }
    }

    /**
     * Returns modeled mW per unit of work for comparing efficiency across frequency options.
     */
    fun costPerPerf(
        component: String,
        freqMhz: Int,
        utilPct: Float,
        scenarioKey: String,
        totalWorkUnits: Float
    ): Float {
        val (mw, _) = predictPowerMw(component, freqMhz, utilPct, scenarioKey)
        return mw / totalWorkUnits.coerceAtLeast(0.01f)
    }

    /**
     * Stage 2: Opt-in CPU frequency choice minimizing cost/perf within bounds when model confidence allows.
     * Returns standardTargetKhz unchanged when disabled, in backoff, throttled/bursting, or below confidence threshold.
     */
    fun selectEfficientCpuFreqKhz(
        policyId: Int,
        availableFreqsKhz: List<Long>,
        allowedMinKhz: Long,
        cappedMaxKhz: Long,
        standardTargetKhz: Long,
        currentFreqKhz: Long,
        rawLoadPct: Float,
        coreCount: Int,
        scenarioKey: String,
        isGame: Boolean,
        fps: Float,
        targetRefreshHz: Float,
        efficiencyEnabled: Boolean,
        efficiencyMode: EfficiencyMode,
        minConfidencePct: Int,
        explorationRate: ExplorationRate,
        burstActive: Boolean,
        thermalThrottled: Boolean,
        batteryPct: Int,
        nowMs: Long,
        batteryState: BatteryState = BatteryState.DISCHARGING
    ): Long {
        if (!efficiencyEnabled || availableFreqsKhz.isEmpty()) return standardTargetKhz
        if (burstActive) return standardTargetKhz

        val compId = "cpu_$policyId"
        var logEvent: Pair<LogLevel, String>? = null
        val chosenKhz: Long

        synchronized(modelLock) {
            val modeDesc = "${efficiencyMode.name}/${explorationRate.name}"
            if (lastModeLogKey != modeDesc) {
                lastModeLogKey = modeDesc
                logEvent = LogLevel.INFO to "Efficiency governor active: mode=${efficiencyMode.name}, minConf=$minConfidencePct%, explore=${explorationRate.name}"
            }

            val st = selectorStates.getOrPut(compId) { SelectorDecisionState() }
            // Check regression from previous cycle
            val regressed = checkAndTriggerBackoffLocked(
                compId = compId,
                st = st,
                standardTargetKhz = standardTargetKhz,
                rawLoadPct = rawLoadPct,
                isGame = isGame,
                fps = fps,
                targetRefreshHz = targetRefreshHz,
                nowMs = nowMs
            )
            if (regressed != null) {
                logEvent = LogLevel.WARN to regressed
            }

            if (nowMs < backoffUntilMs) {
                st.lastTargetKhz = standardTargetKhz
                st.lastStandardKhz = standardTargetKhz
                st.lastWasLowered = false
                return standardTargetKhz
            }

            val candidates = availableFreqsKhz.filter { it in allowedMinKhz..cappedMaxKhz }
            if (candidates.size <= 1) return standardTargetKhz

            // Bounded exploration check (discharge only)
            if (batteryState == BatteryState.DISCHARGING && !thermalThrottled && batteryPct > 20 &&
                nowMs < st.exploreUntilMs && st.exploreTargetKhz in allowedMinKhz..cappedMaxKhz
            ) {
                st.lastTargetKhz = st.exploreTargetKhz
                st.lastStandardKhz = standardTargetKhz
                st.lastWasLowered = st.exploreTargetKhz < standardTargetKhz
                return st.exploreTargetKhz
            }

            // Should we hold previous decision for hysteresis unless load spiked sharply?
            if (st.lastDecisionMs > 0L && nowMs - st.lastDecisionMs < STEP_COOLDOWN_MS && rawLoadPct < 82f &&
                st.lastTargetKhz in allowedMinKhz..cappedMaxKhz
            ) {
                return st.lastTargetKhz
            }

            // Performance-first safeguard during active low-FPS game scenes
            if (efficiencyMode == EfficiencyMode.PERFORMANCE_FIRST && isGame && fps > 0f && targetRefreshHz >= 30f && fps < targetRefreshHz * 0.96f) {
                st.lastTargetKhz = standardTargetKhz
                st.lastStandardKhz = standardTargetKhz
                st.lastWasLowered = false
                return standardTargetKhz
            }
            if (isGame && fps > 0f && targetRefreshHz >= 30f && fps < targetRefreshHz * 0.93f) {
                // Frame pacing is already dipping: never pick below standardTargetKhz
                st.lastTargetKhz = standardTargetKhz
                st.lastStandardKhz = standardTargetKhz
                st.lastWasLowered = false
                return standardTargetKhz
            }

            val refFreqKhz = (if (currentFreqKhz > 0L) currentFreqKhz else standardTargetKhz).coerceAtLeast(allowedMinKhz)
            val currentWork = refFreqKhz.toFloat() * (rawLoadPct.coerceIn(1f, 100f) / 100f)
            val maxSafeProjectedUtil = when (efficiencyMode) {
                EfficiencyMode.AGGRESSIVE_SAVER -> 86f
                EfficiencyMode.BALANCED -> 74f
                EfficiencyMode.PERFORMANCE_FIRST -> 60f
            }
            val minDemandKhz = ((currentWork / (maxSafeProjectedUtil / 100f))).toLong()
                .let { req ->
                    if (efficiencyMode == EfficiencyMode.PERFORMANCE_FIRST) maxOf(req, (standardTargetKhz * 0.85f).toLong()) else req
                }
                .coerceIn(allowedMinKhz, cappedMaxKhz)

            val stdMhz = (standardTargetKhz / 1000L).toInt()
            val (stdPredMw, _) = predictPowerMwUnlocked(compId, stdMhz, rawLoadPct, scenarioKey)

            var bestKhz = standardTargetKhz
            var bestScore = Float.MAX_VALUE
            var bestPredMw = stdPredMw
            var anyQualified = false

            for (candKhz in candidates) {
                if (candKhz < minDemandKhz) continue
                val projUtil = ((currentWork / candKhz.toFloat()) * 100f).coerceIn(1f, 100f)
                if (projUtil > maxSafeProjectedUtil && candKhz < cappedMaxKhz) continue

                val fMhz = (candKhz / 1000L).toInt()
                val compIdx = componentIdToIndex[compId] ?: continue
                val snappedF = snapFreqMhzLocked(compId, fMhz)
                val key = packKey(compIdx, snappedF, utilBand(projUtil))
                val prior = computePriorMwLocked(compId, snappedF, projUtil)
                val (mw, conf01) = predictBucketMwLocked(key, prior, scenarioKey)
                val confPct = conf01 * 100f
                if (confPct < minConfidencePct) continue

                val workUnits = (candKhz / 1_000_000f) * coreCount * (projUtil / 100f)
                val cpp = mw / workUnits.coerceAtLeast(0.05f)
                val score = when (efficiencyMode) {
                    EfficiencyMode.AGGRESSIVE_SAVER -> mw * 0.7f + cpp * 0.3f
                    EfficiencyMode.BALANCED -> cpp
                    EfficiencyMode.PERFORMANCE_FIRST -> cpp * 0.75f - (candKhz / 1_000_000f) * 20f
                }
                if (score < bestScore) {
                    bestScore = score
                    bestKhz = candKhz
                    bestPredMw = mw
                    anyQualified = true
                }
            }

            // Rare bounded exploration into adjacent steps to learn unvisited OPPs
            if (batteryState == BatteryState.DISCHARGING && !thermalThrottled && batteryPct > 20 && nowMs - st.lastExploreAtMs >= EXPLORE_COOLDOWN_MS) {
                val baseProb = when (efficiencyMode) {
                    EfficiencyMode.PERFORMANCE_FIRST -> explorationRate.baseProb * 0.5f
                    else -> explorationRate.baseProb
                }
                if (Random.nextFloat() < baseProb) {
                    val baseIdx = candidates.indexOf(if (anyQualified) bestKhz else standardTargetKhz)
                        .takeIf { it >= 0 } ?: 0
                    val stepDir = if (Random.nextBoolean()) 1 else -1
                    val adjIdx = (baseIdx + stepDir).coerceIn(0, candidates.lastIndex)
                    val adjKhz = candidates[adjIdx]
                    if (adjKhz != bestKhz) {
                        st.exploreTargetKhz = adjKhz
                        st.exploreUntilMs = nowMs + EXPLORE_HOLD_MS
                        st.lastExploreAtMs = nowMs
                        bestKhz = adjKhz
                    }
                }
            }

            if (!anyQualified && nowMs >= st.exploreUntilMs) {
                bestKhz = standardTargetKhz
                bestPredMw = stdPredMw
            }

            if (bestKhz < standardTargetKhz && stdPredMw > 50f) {
                val savedPct = ((stdPredMw - bestPredMw) / stdPredMw * 100f).coerceIn(-10f, 60f)
                smoothedSavingsPct = smoothedSavingsPct * 0.9f + savedPct * 0.1f
            } else {
                smoothedSavingsPct *= 0.95f
            }

            st.lastDecisionMs = nowMs
            st.lastTargetKhz = bestKhz
            st.lastStandardKhz = standardTargetKhz
            st.lastWasLowered = bestKhz < standardTargetKhz
            val chosenMhz = snapFreqMhzLocked(compId, (bestKhz / 1000L).toInt())
            st.lastBucketKey = componentIdToIndex[compId]?.let { packKey(it, chosenMhz, utilBand(rawLoadPct)) } ?: -1L
            chosenKhz = bestKhz
        }

        logEvent?.let { (lvl, msg) -> onLog(lvl, "Efficiency", msg) }
        return chosenKhz
    }

    /**
     * Stage 2: Opt-in GPU OPP position choice within [allowedMinPos..cappedMaxPos].
     */
    fun selectEfficientGpuOppPos(
        sortedOpps: List<GpuOpp>,
        allowedMinPos: Int,
        cappedMaxPos: Int,
        standardTargetPos: Int,
        currentFreqKhz: Long,
        rawLoadPct: Float,
        scenarioKey: String,
        isGame: Boolean,
        fps: Float,
        targetRefreshHz: Float,
        efficiencyEnabled: Boolean,
        efficiencyMode: EfficiencyMode,
        minConfidencePct: Int,
        explorationRate: ExplorationRate,
        burstActive: Boolean,
        thermalThrottled: Boolean,
        batteryPct: Int,
        nowMs: Long,
        batteryState: BatteryState = BatteryState.DISCHARGING
    ): Int {
        if (!efficiencyEnabled || sortedOpps.isEmpty() || burstActive) return standardTargetPos
        val minP = allowedMinPos.coerceIn(0, sortedOpps.lastIndex)
        val maxP = cappedMaxPos.coerceIn(minP, sortedOpps.lastIndex)
        if (minP >= maxP) return standardTargetPos

        val standardKhz = sortedOpps[standardTargetPos.coerceIn(minP, maxP)].freqKhz
        val compId = "gpu"
        var logEvent: Pair<LogLevel, String>? = null
        val chosenPos: Int

        synchronized(modelLock) {
            val st = selectorStates.getOrPut(compId) { SelectorDecisionState() }
            val regressed = checkAndTriggerBackoffLocked(
                compId = compId,
                st = st,
                standardTargetKhz = standardKhz,
                rawLoadPct = rawLoadPct,
                isGame = isGame,
                fps = fps,
                targetRefreshHz = targetRefreshHz,
                nowMs = nowMs
            )
            if (regressed != null) {
                logEvent = LogLevel.WARN to regressed
            }

            if (nowMs < backoffUntilMs) {
                st.lastTargetKhz = standardKhz
                st.lastStandardKhz = standardKhz
                st.lastWasLowered = false
                return standardTargetPos
            }

            if (isGame && fps > 0f && targetRefreshHz >= 30f && fps < targetRefreshHz * 0.94f) {
                st.lastTargetKhz = standardKhz
                st.lastStandardKhz = standardKhz
                st.lastWasLowered = false
                return standardTargetPos
            }

            if (batteryState == BatteryState.DISCHARGING && !thermalThrottled && batteryPct > 20 && nowMs < st.exploreUntilMs) {
                val expPos = sortedOpps.indexOfFirst { it.freqKhz == st.exploreTargetKhz }
                if (expPos in minP..maxP) return expPos
            }

            if (st.lastDecisionMs > 0L && nowMs - st.lastDecisionMs < STEP_COOLDOWN_MS && rawLoadPct < 82f) {
                val prevPos = sortedOpps.indexOfFirst { it.freqKhz == st.lastTargetKhz }
                if (prevPos in minP..maxP) return prevPos
            }

            val refFreqKhz = (if (currentFreqKhz > 0L) currentFreqKhz else standardKhz).coerceAtLeast(sortedOpps[minP].freqKhz)
            val currentWork = refFreqKhz.toFloat() * (rawLoadPct.coerceIn(1f, 100f) / 100f)
            val maxSafeUtil = when (efficiencyMode) {
                EfficiencyMode.AGGRESSIVE_SAVER -> 86f
                EfficiencyMode.BALANCED -> 75f
                EfficiencyMode.PERFORMANCE_FIRST -> 62f
            }
            val minDemandKhz = ((currentWork / (maxSafeUtil / 100f))).toLong()

            var bestPos = standardTargetPos.coerceIn(minP, maxP)
            var bestScore = Float.MAX_VALUE
            var anyQualified = false

            for (pos in minP..maxP) {
                val opp = sortedOpps[pos]
                if (opp.freqKhz < minDemandKhz) continue
                if (efficiencyMode == EfficiencyMode.PERFORMANCE_FIRST && opp.freqKhz < (standardKhz * 0.85f).toLong()) continue

                val projUtil = ((currentWork / opp.freqKhz.toFloat()) * 100f).coerceIn(1f, 100f)
                if (projUtil > maxSafeUtil && pos < maxP) continue

                val compIdx = componentIdToIndex[compId] ?: continue
                val fMhz = snapFreqMhzLocked(compId, (opp.freqKhz / 1000L).toInt())
                val key = packKey(compIdx, fMhz, utilBand(projUtil))
                val prior = computePriorMwLocked(compId, fMhz, projUtil)
                val (mw, conf01) = predictBucketMwLocked(key, prior, scenarioKey)
                if (conf01 * 100f < minConfidencePct) continue

                val workUnits = (opp.freqKhz / 1_000_000f) * (projUtil / 100f)
                val cpp = mw / workUnits.coerceAtLeast(0.05f)
                val score = when (efficiencyMode) {
                    EfficiencyMode.AGGRESSIVE_SAVER -> mw * 0.7f + cpp * 0.3f
                    EfficiencyMode.BALANCED -> cpp
                    EfficiencyMode.PERFORMANCE_FIRST -> cpp * 0.75f - (opp.freqKhz / 1_000_000f) * 20f
                }
                if (score < bestScore) {
                    bestScore = score
                    bestPos = pos
                    anyQualified = true
                }
            }

            if (batteryState == BatteryState.DISCHARGING && !thermalThrottled && batteryPct > 20 && nowMs - st.lastExploreAtMs >= EXPLORE_COOLDOWN_MS) {
                val baseProb = if (efficiencyMode == EfficiencyMode.PERFORMANCE_FIRST) {
                    explorationRate.baseProb * 0.5f
                } else explorationRate.baseProb
                if (Random.nextFloat() < baseProb) {
                    val base = if (anyQualified) bestPos else standardTargetPos.coerceIn(minP, maxP)
                    val adj = (base + if (Random.nextBoolean()) 1 else -1).coerceIn(minP, maxP)
                    if (adj != bestPos) {
                        st.exploreTargetKhz = sortedOpps[adj].freqKhz
                        st.exploreUntilMs = nowMs + EXPLORE_HOLD_MS
                        st.lastExploreAtMs = nowMs
                        bestPos = adj
                    }
                }
            }

            if (!anyQualified && nowMs >= st.exploreUntilMs) {
                bestPos = standardTargetPos.coerceIn(minP, maxP)
            }

            val chosenKhz = sortedOpps[bestPos].freqKhz
            st.lastDecisionMs = nowMs
            st.lastTargetKhz = chosenKhz
            st.lastStandardKhz = standardKhz
            st.lastWasLowered = chosenKhz < standardKhz
            val chosenMhz = snapFreqMhzLocked(compId, (chosenKhz / 1000L).toInt())
            st.lastBucketKey = componentIdToIndex[compId]?.let { packKey(it, chosenMhz, utilBand(rawLoadPct)) } ?: -1L
            chosenPos = bestPos
        }

        logEvent?.let { (lvl, msg) -> onLog(lvl, "Efficiency", msg) }
        return chosenPos
    }

    private fun checkAndTriggerBackoffLocked(
        compId: String,
        st: SelectorDecisionState,
        standardTargetKhz: Long,
        rawLoadPct: Float,
        isGame: Boolean,
        fps: Float,
        targetRefreshHz: Float,
        nowMs: Long
    ): String? {
        if (!st.lastWasLowered || st.lastTargetKhz <= 0L) return null
        val fpsDropped = isGame && fps > 0f && targetRefreshHz >= 30f && fps < targetRefreshHz * 0.85f
        val saturated = rawLoadPct >= 92f && standardTargetKhz > st.lastTargetKhz
        if (!fpsDropped && !saturated) return null

        backoffUntilMs = nowMs + BACKOFF_DURATION_MS
        if (st.lastBucketKey >= 0L) {
            globalBuckets[st.lastBucketKey]?.let { b ->
                b.penaltyFactor = (b.penaltyFactor * 0.7f).coerceAtLeast(0.25f)
            }
            scenarios[liveActiveScenario]?.buckets?.get(st.lastBucketKey)?.let { b ->
                b.penaltyFactor = (b.penaltyFactor * 0.7f).coerceAtLeast(0.25f)
            }
        }
        st.exploreUntilMs = 0L
        dirty = true
        return if (nowMs - lastBackoffLogMs >= 4_000L) {
            lastBackoffLogMs = nowMs
            val reason = if (fpsDropped) "FPS ${fps.roundToInt()} < ${(targetRefreshHz * 0.85f).roundToInt()}" else "$compId load ${rawLoadPct.roundToInt()}%"
            "Regression ($reason); backing off ${BACKOFF_DURATION_MS / 1000}s and lowering bucket confidence."
        } else null
    }

    fun onEfficiencySettingChanged(enabled: Boolean, mode: EfficiencyMode) {
        synchronized(modelLock) {
            liveEfficiencyEnabled = enabled
            if (!enabled) {
                selectorStates.clear()
                backoffUntilMs = 0L
                lastModeLogKey = null
                smoothedSavingsPct = 0f
            } else {
                lastModeLogKey = "${mode.name}"
            }
        }
        if (uiVisible) refreshUiSnapshotNow()
    }

    fun refreshUiSnapshotNow() {
        val nowMs = System.currentTimeMillis()
        val snap = synchronized(modelLock) {
            val comps = componentMetaById.values.map { meta ->
                val lFreq = liveCompFreqMhz[meta.componentId] ?: 0
                val lUtil = liveCompUtilPct[meta.componentId] ?: 0f
                val (lMw, lConf) = if (lFreq > 0) {
                    val prior = computePriorMwLocked(meta.componentId, lFreq, lUtil)
                    val key = packKey(meta.index, snapFreqMhzLocked(meta.componentId, lFreq), utilBand(lUtil))
                    val (mw, conf01) = predictBucketMwLocked(key, prior, liveActiveScenario)
                    mw to (conf01 * 100f)
                } else 0f to 0f

                // Build compact power-vs-frequency curve across representative mid-band (band 2 = 25..50%)
                // or observed best confidence band for each known freq step (cap to ~8 representative points if huge)
                val stepList = meta.freqsMhz.toList()
                val sampledFreqs = if (stepList.size <= 10) stepList else {
                    val stride = max(1, stepList.size / 8)
                    stepList.filterIndexed { idx, _ -> idx % stride == 0 || idx == stepList.lastIndex }
                }
                val points = sampledFreqs.map { fMhz ->
                    val band = 2
                    val key = packKey(meta.index, fMhz, band)
                    val prior = computePriorMwLocked(meta.componentId, fMhz, bandMidUtil(band))
                    val gBucket = globalBuckets[key]
                    val (mw, conf01) = if (gBucket != null) {
                        predictBucketMwLocked(key, prior, liveActiveScenario)
                    } else {
                        // Fall back to best observed band at this frequency if band 2 isn't populated yet
                        var bestBucket: BucketStats? = null
                        var bestKey = key
                        for (b in 0..4) {
                            val k2 = packKey(meta.index, fMhz, b)
                            val gb = globalBuckets[k2]
                            if (gb != null && (bestBucket == null || gb.rawCount > bestBucket.rawCount)) {
                                bestBucket = gb
                                bestKey = k2
                            }
                        }
                        if (bestBucket != null) {
                            predictBucketMwLocked(bestKey, prior, liveActiveScenario)
                        } else {
                            prior to 0f
                        }
                    }
                    val count = (0..4).sumOf { b -> globalBuckets[packKey(meta.index, fMhz, b)]?.rawCount ?: 0 }
                    ComponentFreqCurvePoint(
                        freqMhz = fMhz,
                        predictedMw = mw,
                        confidencePct = (conf01 * 100f).coerceIn(0f, 100f),
                        samples = count
                    )
                }

                ComponentPowerSummary(
                    componentId = meta.componentId,
                    displayName = meta.displayName,
                    liveFreqMhz = lFreq,
                    liveUtilPct = lUtil,
                    livePredictedMw = lMw,
                    liveConfidencePct = lConf.coerceIn(0f, 100f),
                    curvePoints = points
                )
            }

            val errPct = if (liveMeasuredMw > 50f) {
                (abs(liveEstimatedMw - liveMeasuredMw) / liveMeasuredMw * 100f).coerceIn(0f, 999f)
            } else 0f

            val backoffRem = if (nowMs < backoffUntilMs) {
                ((backoffUntilMs - nowMs + 999L) / 1000L).toInt()
            } else 0

            PowerModelUiSnapshot(
                learningEnabled = liveLearningEnabled,
                efficiencyEnabled = liveEfficiencyEnabled,
                totalSamples = totalSamples,
                overallConfidencePct = computeOverallConfidenceLocked(),
                scenariosLearned = scenarios.size,
                lastSavedMs = lastSavedMs,
                baselineMw = baselineMw,
                liveMeasuredMw = liveMeasuredMw,
                liveEstimatedMw = liveEstimatedMw,
                liveErrorPct = errPct,
                activeScenario = liveActiveScenario,
                backoffActive = backoffRem > 0,
                backoffRemainingSec = backoffRem,
                efficiencySavingsPct = smoothedSavingsPct.coerceIn(0f, 60f),
                samplesSkippedCharging = samplesSkippedCharging,
                learningPausedCharging = lastTrainBatteryState == BatteryState.CHARGING || lastTrainBatteryState == BatteryState.FULL_PLUGGED,
                batteryState = lastTrainBatteryState,
                components = comps
            )
        }
        _uiSnapshot.value = snap
    }

    fun load() {
        if (!storageFile.exists()) return
        try {
            val raw = storageFile.readText()
            if (raw.isBlank()) return
            deserializeFromJsonString(raw)
        } catch (_: Throwable) {
        }
        if (uiVisible) refreshUiSnapshotNow()
    }

    fun flush() {
        val json = synchronized(modelLock) {
            if (!dirty) return
            lastSavedMs = System.currentTimeMillis()
            dirty = false
            serializeToJsonStringLocked()
        }
        writeJsonAtomically(json)
    }

    fun reset() {
        synchronized(modelLock) {
            globalBuckets.clear()
            scenarios.clear()
            selectorStates.clear()
            baselineMw = 650f
            totalSamples = 0L
            rollingMadMw = 600f
            dirty = false
            lastSavedMs = 0L
            prevHasSample = false
            prevSampleFreqMhz.clear()
            prevSampleUtilPct.clear()
            liveMeasuredMw = 0f
            liveEstimatedMw = 0f
            smoothedSavingsPct = 0f
            backoffUntilMs = 0L
            samplesSkippedCharging = 0L
            lastBatteryFlipMs = 0L
            lastTrainBatteryState = BatteryState.DISCHARGING
        }
        try {
            if (storageFile.exists()) storageFile.delete()
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            if (tmp.exists()) tmp.delete()
        } catch (_: Throwable) {
        }
        refreshUiSnapshotNow()
        onLog(LogLevel.INFO, "PowerModel", "Power model reset.")
    }

    fun exportJson(): String = synchronized(modelLock) { serializeToJsonStringLocked() }

    private fun writeJsonAtomically(jsonText: String) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${storageFile.name}.tmp")
        try {
            tmp.writeText(jsonText)
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Throwable) {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun serializeToJsonStringLocked(): String {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("savedAtMs", System.currentTimeMillis())
            put("baselineMw", baselineMw.toDouble())
            put("totalSamples", totalSamples)
            put("rollingMadMw", rollingMadMw.toDouble())

            val compArr = JSONArray()
            for ((compId, idx) in componentIdToIndex) {
                compArr.put(JSONObject().apply {
                    put("id", compId)
                    put("idx", idx)
                })
            }
            put("components", compArr)

            put("globalBuckets", bucketsToJsonArray(globalBuckets))

            val scenArr = JSONArray()
            for (s in scenarios.values) {
                scenArr.put(JSONObject().apply {
                    put("key", s.key)
                    put("lastAccessMs", s.lastAccessMs)
                    put("sampleCount", s.sampleCount)
                    put("buckets", bucketsToJsonArray(s.buckets))
                })
            }
            put("scenarios", scenArr)
        }
        return root.toString()
    }

    private fun bucketsToJsonArray(map: Map<Long, BucketStats>): JSONArray {
        val arr = JSONArray()
        for ((key, b) in map) {
            arr.put(JSONObject().apply {
                put("k", key)
                put("p", b.powerMw.toDouble())
                put("es", b.effSamples.toDouble())
                put("rc", b.rawCount)
                put("pf", b.penaltyFactor.toDouble())
                put("ls", b.lastSeenMs)
            })
        }
        return arr
    }

    private fun deserializeFromJsonString(raw: String) {
        val root = JSONObject(raw)
        synchronized(modelLock) {
            baselineMw = root.optDouble("baselineMw", 650.0).toFloat().coerceIn(150f, 3500f)
            totalSamples = root.optLong("totalSamples", 0L).coerceAtLeast(0L)
            rollingMadMw = root.optDouble("rollingMadMw", 600.0).toFloat().coerceIn(120f, 3500f)
            lastSavedMs = root.optLong("savedAtMs", 0L)

            val savedIdxToId = HashMap<Int, String>()
            val compArr = root.optJSONArray("components")
            if (compArr != null) {
                for (i in 0 until compArr.length()) {
                    val c = compArr.getJSONObject(i)
                    val id = c.optString("id")
                    val idx = c.optInt("idx", -1)
                    if (id.isNotBlank() && idx >= 0) {
                        savedIdxToId[idx] = id
                        getOrAssignCompIndexLocked(id)
                    }
                }
            }

            fun remapKey(oldKey: Long): Long {
                val oldCompIdx = (oldKey ushr 32).toInt()
                val rest = oldKey and 0xFFFFFFFFL
                val compId = savedIdxToId[oldCompIdx] ?: return oldKey
                val newIdx = componentIdToIndex[compId] ?: oldCompIdx
                return (newIdx.toLong() shl 32) or rest
            }

            globalBuckets.clear()
            val gArr = root.optJSONArray("globalBuckets")
            if (gArr != null) {
                for (i in 0 until gArr.length()) {
                    val o = gArr.getJSONObject(i)
                    val k = remapKey(o.getLong("k"))
                    globalBuckets[k] = BucketStats(
                        powerMw = o.optDouble("p", 300.0).toFloat().coerceIn(5f, 15_000f),
                        effSamples = o.optDouble("es", 0.0).toFloat().coerceAtLeast(0f),
                        rawCount = o.optInt("rc", 0).coerceAtLeast(0),
                        penaltyFactor = o.optDouble("pf", 1.0).toFloat().coerceIn(0.1f, 1f),
                        lastSeenMs = o.optLong("ls", 0L)
                    )
                }
            }

            scenarios.clear()
            val sArr = root.optJSONArray("scenarios")
            if (sArr != null) {
                for (i in 0 until min(sArr.length(), MAX_SCENARIOS)) {
                    val so = sArr.getJSONObject(i)
                    val sKey = so.optString("key").ifBlank { continue }
                    val sData = ScenarioData(
                        key = sKey,
                        lastAccessMs = so.optLong("lastAccessMs", 0L),
                        sampleCount = so.optLong("sampleCount", 0L)
                    )
                    val bArr = so.optJSONArray("buckets")
                    if (bArr != null) {
                        for (j in 0 until bArr.length()) {
                            val o = bArr.getJSONObject(j)
                            val k = remapKey(o.getLong("k"))
                            sData.buckets[k] = BucketStats(
                                powerMw = o.optDouble("p", 300.0).toFloat().coerceIn(5f, 15_000f),
                                effSamples = o.optDouble("es", 0.0).toFloat().coerceAtLeast(0f),
                                rawCount = o.optInt("rc", 0).coerceAtLeast(0),
                                penaltyFactor = o.optDouble("pf", 1.0).toFloat().coerceIn(0.1f, 1f),
                                lastSeenMs = o.optLong("ls", 0L)
                            )
                        }
                    }
                    scenarios[sKey] = sData
                }
            }
            dirty = false
        }
    }

    private fun predictPowerMwUnlocked(
        component: String,
        freqMhz: Int,
        utilPct: Float,
        scenarioKey: String
    ): Pair<Float, Float> {
        val snapped = snapFreqMhzLocked(component, freqMhz)
        val prior = computePriorMwLocked(component, snapped, utilPct)
        val idx = componentIdToIndex[component] ?: return prior to 0f
        val key = packKey(idx, snapped, utilBand(utilPct))
        val (mw, conf01) = predictBucketMwLocked(key, prior, scenarioKey)
        return mw to (conf01 * 100f)
    }

    private fun predictBucketMwLocked(
        key: Long,
        prior: Float,
        scenarioKey: String
    ): Pair<Float, Float> {
        val g = globalBuckets[key]
        val s = scenarios[scenarioKey]?.buckets?.get(key)
        if (g == null && s == null) return prior to 0f

        val confG = g?.let { bucketConfidence01(it) } ?: 0f
        val confS = s?.let { bucketConfidence01(it) } ?: 0f

        val blendedMw = when {
            g != null && s != null -> {
                val wS = (confS * 0.72f).coerceIn(0f, 0.85f)
                s.powerMw * wS + g.powerMw * (1f - wS)
            }
            s != null -> s.powerMw
            else -> g!!.powerMw
        }
        val combinedConf = max(confG, confS)
        return blendedMw to combinedConf.coerceIn(0f, 1f)
    }

    private fun interpolateComponentPowerLocked(
        compIdx: Int,
        targetFreqMhz: Int,
        targetBand: Int,
        prior: Float,
        scenarioKey: String
    ): Pair<Float, Float> {
        var nearestKey = -1L
        var nearestDist = Int.MAX_VALUE
        var nearestBucket: BucketStats? = null
        var nearestFreq = targetFreqMhz

        for ((k, b) in globalBuckets) {
            if ((k ushr 32).toInt() != compIdx) continue
            val kBand = (k and 0xFL).toInt()
            val kFreq = ((k ushr 4) and 0xFFFFFFFL).toInt()
            val dist = abs(kFreq - targetFreqMhz) + abs(kBand - targetBand) * 250
            if (dist < nearestDist) {
                nearestDist = dist
                nearestKey = k
                nearestBucket = b
                nearestFreq = kFreq
            }
        }
        if (nearestBucket == null || nearestKey < 0L) return prior to 0f
        val ratio = if (nearestFreq > 0) (targetFreqMhz.toFloat() / nearestFreq.toFloat()).pow(1.4f).coerceIn(0.35f, 2.5f) else 1f
        val (baseMw, baseConf) = predictBucketMwLocked(nearestKey, prior, scenarioKey)
        return (baseMw * ratio).coerceIn(5f, 15_000f) to (baseConf * INTERP_CONF_FACTOR)
    }

    private fun bucketConfidence01(b: BucketStats): Float {
        // Reaches ~0.60 around 8 effective samples, ~0.85 around 17 samples, ~0.96 around 29 samples.
        val rawConf = 1f - exp(-b.effSamples / CONF_TAU_SAMPLES)
        return (rawConf * b.penaltyFactor).coerceIn(0f, 1f)
    }

    private fun computeOverallConfidenceLocked(): Float {
        if (globalBuckets.isEmpty()) return 0f
        var sum = 0f
        var weight = 0f
        for (b in globalBuckets.values) {
            val w = b.effSamples.coerceIn(1f, 30f)
            sum += bucketConfidence01(b) * w
            weight += w
        }
        return if (weight > 0f) (sum / weight * 100f).coerceIn(0f, 100f) else 0f
    }

    private fun getOrCreateScenarioLocked(key: String, nowMs: Long): ScenarioData {
        val existing = scenarios[key]
        if (existing != null) return existing
        if (scenarios.size >= MAX_SCENARIOS) {
            var evictKey: String? = null
            var oldestTime = Long.MAX_VALUE
            for ((k, v) in scenarios) {
                if (k == "idle") continue
                if (v.lastAccessMs < oldestTime) {
                    oldestTime = v.lastAccessMs
                    evictKey = k
                }
            }
            if (evictKey == null) evictKey = scenarios.keys.firstOrNull()
            if (evictKey != null) scenarios.remove(evictKey)
        }
        return ScenarioData(key = key, lastAccessMs = nowMs).also { scenarios[key] = it }
    }

    private fun getOrAssignCompIndexLocked(componentId: String): Int {
        return componentIdToIndex.getOrPut(componentId) { componentIdToIndex.size }
    }

    private fun ensureMetaExistsLocked(componentId: String, displayName: String, isGpu: Boolean, fMhz: Int, cores: Int) {
        if (componentMetaById.containsKey(componentId)) return
        val idx = getOrAssignCompIndexLocked(componentId)
        val freqs = if (fMhz > 0) intArrayOf(fMhz) else IntArray(0)
        componentMetaById[componentId] = ComponentMeta(
            index = idx,
            componentId = componentId,
            displayName = displayName,
            isGpu = isGpu,
            freqsMhz = freqs,
            maxFreqMhz = max(fMhz, if (isGpu) 1000 else 2200),
            coreCount = cores
        )
    }

    private fun snapFreqMhzLocked(componentId: String, rawMhz: Int): Int {
        if (rawMhz <= 0) return 0
        val freqs = componentMetaById[componentId]?.freqsMhz ?: return rawMhz
        if (freqs.isEmpty()) return rawMhz
        var best = freqs[0]
        var bestDist = abs(best - rawMhz)
        for (i in 1 until freqs.size) {
            val d = abs(freqs[i] - rawMhz)
            if (d < bestDist) {
                bestDist = d
                best = freqs[i]
            }
        }
        return best
    }

    private fun packBucketKeyLocked(componentId: String, freqMhz: Int, band: Int): Long {
        val idx = getOrAssignCompIndexLocked(componentId)
        return packKey(idx, freqMhz, band)
    }

    private fun packKey(compIdx: Int, freqMhz: Int, band: Int): Long {
        val f = freqMhz.coerceIn(0, 0x0FFFFFFF)
        val b = band.coerceIn(0, 4)
        return (compIdx.toLong() shl 32) or (f.toLong() shl 4) or b.toLong()
    }

    private fun computePriorMwLocked(componentId: String, freqMhz: Int, utilPct: Float): Float {
        val meta = componentMetaById[componentId]
        val maxF = (meta?.maxFreqMhz ?: if (componentId == "gpu") 1000 else 2200).coerceAtLeast(1)
        val fRatio = (freqMhz.toFloat() / maxF.toFloat()).coerceIn(0.15f, 1.2f)
        val uRatio = (utilPct.coerceIn(0f, 100f) / 100f)
        val baseMaxMw = if (meta?.isGpu == true || componentId == "gpu") {
            1600f
        } else {
            val cores = (meta?.coreCount ?: 2).coerceIn(1, 8)
            450f + cores * 220f
        }
        return (baseMaxMw * fRatio.pow(1.65f) * (0.18f + 0.82f * uRatio)).coerceIn(15f, 10_000f)
    }

    private fun Float.format2(): String = "%.2f".format(Locale.US, this)
}
