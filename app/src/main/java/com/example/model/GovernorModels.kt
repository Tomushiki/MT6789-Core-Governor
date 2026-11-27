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

package com.example.model

import androidx.annotation.StringRes
import com.example.R
import com.example.service.OverlayStyle

enum class GovernorMode {
    NORMAL,
    GAME
}

enum class ModeOverride {
    AUTO,
    FORCE_NORMAL,
    FORCE_GAME
}

enum class ThrottleCurveShape {
    LINEAR,
    GENTLE,
    AGGRESSIVE
}

enum class PowerUnitScale {
    AUTO,
    UA_UV,
    MA_MV,
    UA_MV
}

enum class PowerPolarity {
    AUTO_ABS,
    DISCHARGE_NEGATIVE,
    DISCHARGE_POSITIVE,
    INVERT_SIGN
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

enum class SmartAppMode(val label: String, @StringRes val labelRes: Int) {
    CONSERVATIVE("Conservative", R.string.smart_mode_conservative),
    BALANCED("Balanced", R.string.smart_mode_balanced),
    AGGRESSIVE("Aggressive", R.string.smart_mode_aggressive)
}

enum class EfficiencyMode {
    BALANCED,
    AGGRESSIVE_SAVER,
    PERFORMANCE_FIRST
}

enum class ExplorationRate(val baseProb: Float) {
    LOW(0.015f),
    MEDIUM(0.04f),
    HIGH(0.08f)
}

enum class BatteryState {
    DISCHARGING,
    CHARGING,
    FULL_PLUGGED,
    UNKNOWN;

    val isPluggedOrCharging: Boolean
        get() = this == CHARGING || this == FULL_PLUGGED
}

data class CpuPolicySpec(
    val policyId: Int,
    val name: String,
    val path: String,
    val relatedCpus: List<Int>,
    val availableFreqsKhz: List<Long>,
    val origGovernor: String,
    val origMinFreqKhz: Long,
    val origMaxFreqKhz: Long
)

data class GpuOpp(
    val oppIndex: Int,
    val freqKhz: Long,
    val voltRaw: Long? = null
)

data class GpuSpec(
    val discovered: Boolean = false,
    val oppTableSourcePath: String? = null,
    val detectedOrderNote: String = "Not probed",
    val gpufreqStatusPath: String? = null,
    val gpufreqFixOppPath: String? = null,
    val gedLoadCandidatePaths: List<String> = emptyList(),
    /**
     * Sorted lowest-to-highest frequency. All governor position math runs on 0..lastIndex,
     * converting to oppTable[pos].oppIndex only when writing to fix_target_opp_index.
     */
    val oppTable: List<GpuOpp> = emptyList(),
    val availableFreqsKhz: List<Long> = emptyList()
)

data class ThermalSensorSpec(
    val id: String,
    val label: String,
    val path: String,
    val category: String,
    val divisorToCelsius: Float,
    val initialTempC: Float = 0f
)

data class BatteryPowerSpec(
    val currentPath: String? = null,
    val voltagePath: String? = null,
    val statusPath: String? = null,
    val chargeFullPath: String? = null,
    val chargeFullDesignPath: String? = null,
    val cycleCountPath: String? = null
)

data class DdrSpec(
    val basePath: String,
    val curFreqPath: String,
    val minFreqPath: String,
    val availableHz: List<Long>
)

data class HardwareSpec(
    val discoveredAtMs: Long = 0L,
    val rootGranted: Boolean = false,
    val cpuPolicies: List<CpuPolicySpec> = emptyList(),
    val gpu: GpuSpec = GpuSpec(),
    val thermalSensors: List<ThermalSensorSpec> = emptyList(),
    val batteryPower: BatteryPowerSpec = BatteryPowerSpec(),
    val discoveryNotes: List<String> = emptyList(),
    val ddr: DdrSpec? = null
)

data class FreqRange(
    val minKhz: Long,
    val maxKhz: Long
)

data class ModeProfile(
    val cpuRangesKhz: Map<Int, FreqRange> = emptyMap(),
    val gpuRangeKhz: FreqRange? = null,
    val headroomPct: Float = 12f,
    val rampUpSpeed: Float = 0.75f,
    val rampDownSpeed: Float = 0.14f,
    val gpuHeadroomPct: Float = 12f,
    val gpuRampUpSpeed: Float = 0.75f,
    val gpuRampDownSpeed: Float = 0.14f,
    val launchBurstEnabled: Boolean = true,
    val launchBurstDurationMs: Long = 1500L,
    val launchSpikeThresholdPct: Float = 38f
)

data class TempThrottleConfig(
    val enabled: Boolean = true,
    val startTempC: Float = 42f,
    val fullTempC: Float = 56f,
    val cpuMaxReductionPct: Float = 55f,
    val gpuMaxReductionPct: Float = 65f,
    val curveShape: ThrottleCurveShape = ThrottleCurveShape.LINEAR,
    val reactionSpeed: Float = 0.35f,
    val hysteresisC: Float = 2.0f,
    val chargingAware: Boolean = false,
    val chargingStartOffsetC: Float = 2.0f
)

data class PowerThrottleConfig(
    val enabled: Boolean = true,
    val limitWatts: Float = 6.0f,
    val sustainMs: Long = 2000L,
    val stepPct: Float = 3.0f,
    val releaseStepPct: Float = 1.0f,
    val averagingSamples: Int = 10,
    val unitScale: PowerUnitScale = PowerUnitScale.AUTO,
    val polarity: PowerPolarity = PowerPolarity.AUTO_ABS,
    val componentAware: Boolean = true,
    /** v3.8.0: spread the cap between two adjacent frequency steps (duty cycle). v3.8.3: ON by default for fresh installs only (saved configs keep their value); also smooths thermal caps. */
    val smoothPowerLimit: Boolean = true
)

enum class BoostStrength(val label: String, val factor: Float, @StringRes val labelRes: Int) {
    LIGHT("Light", 0.45f, R.string.boost_strength_light),
    MEDIUM("Medium", 0.75f, R.string.boost_strength_medium),
    MAX("Max", 1.0f, R.string.boost_strength_max)
}

enum class AfterburnState(val label: String, @StringRes val labelRes: Int) {
    OFF("Off", R.string.afterburn_state_off),
    IDLE("Idle", R.string.afterburn_state_idle),
    BOOSTING("Boosting", R.string.afterburn_state_boosting),
    REDUCED("Reduced", R.string.afterburn_state_reduced),
    BLOCKED_THERMAL("Blocked: heat", R.string.afterburn_state_blocked_thermal),
    BLOCKED_BATTERY("Blocked: battery", R.string.afterburn_state_blocked_battery)
}

/** v3.0.0 Afterburn: temporarily relaxes power-cap banding while the conditions below are met. */
data class AfterburnConfig(
    val enabled: Boolean = false,
    val strength: BoostStrength = BoostStrength.MEDIUM,
    val thermalConditionEnabled: Boolean = true,
    /** Boost only while the temperature is at least this many degrees C below the thermal throttle start (3..8). */
    val thermalMarginC: Float = 5f,
    val batteryConditionEnabled: Boolean = true,
    /** Below this battery % (discharging) the boost is reduced. */
    val batteryThresholdPct: Int = 40,
    /** Boost less when the battery voltage sags a lot under load (uses the learned internal resistance). */
    val voltageSagEnabled: Boolean = true,
    /** Show a brief neon overlay pill when Afterburn engages for a selected app. */
    val toastEnabled: Boolean = true
)

enum class StutterHunterMode(@StringRes val labelRes: Int) {
    NOTIFY(R.string.hunter_mode_notify),
    STOP(R.string.hunter_mode_stop)
}

data class StutterHunterConfig(
    val enabled: Boolean = false,
    val mode: StutterHunterMode = StutterHunterMode.NOTIFY,
    val dipPct: Int = 75,
    val cpuThresholdPct: Int = 15,
    val whitelist: Set<String> = emptySet()
)

data class ThreadAffinityMapping(
    val pattern: String,
    val cpuMask: String,
    val exactMatch: Boolean = false,
    val packageName: String = ""
)

data class ThreadObservationEntry(
    val threadName: String,
    val lastKnownTid: Int = 0,
    val lastObservedAtMs: Long = 0L,
    val observationCount: Int = 1
)

data class GameThreadHistoryRecord(
    val packageName: String,
    val displayName: String = "",
    val lastProcessName: String = "",
    val lastObservedAtMs: Long = 0L,
    val threads: List<ThreadObservationEntry> = emptyList()
)

data class GameThreadControllerConfig(
    val enabled: Boolean = false,
    val autoClassify: Boolean = false,
    val selectedGamePackage: String = "",
    val lastObservedGamePackage: String = "",
    val mappings: List<ThreadAffinityMapping> = emptyList(),
    val threadHistory: Map<String, GameThreadHistoryRecord> = emptyMap()
)

data class CompositeScoreWeights(
    val p5FpsPct: Int = 35,
    val avgFpsPct: Int = 25,
    val fpsPerWattPct: Int = 20,
    val tempPct: Int = 10,
    val stabilityPct: Int = 10,
    val tempThresholdC: Float = 45f
)

data class PerGameProfile(
    val headroomPct: Float? = null,
    val rampUpSpeed: Float? = null,
    val rampDownSpeed: Float? = null,
    val gpuHeadroomPct: Float? = null,
    val gpuRampUpSpeed: Float? = null,
    val gpuRampDownSpeed: Float? = null,
    val cpuRangesKhz: Map<Int, FreqRange>? = null,
    val gpuRangeKhz: FreqRange? = null,
    val powerLimitWatts: Float? = null,
    val tempStartC: Float? = null,
    val tempFullC: Float? = null,
    val afterburnStrength: BoostStrength? = null
) {
    val isEmpty: Boolean
        get() = headroomPct == null &&
            rampUpSpeed == null &&
            rampDownSpeed == null &&
            gpuHeadroomPct == null &&
            gpuRampUpSpeed == null &&
            gpuRampDownSpeed == null &&
            cpuRangesKhz == null &&
            gpuRangeKhz == null &&
            powerLimitWatts == null &&
            tempStartC == null &&
            tempFullC == null &&
            afterburnStrength == null
}

enum class AutoTuneMode(@StringRes val labelRes: Int) {
    SUGGEST_ONLY(R.string.autotune_mode_suggest),
    AUTO_APPLY(R.string.autotune_mode_auto)
}

enum class AutoTuneParam(@StringRes val labelRes: Int) {
    POWER_LIMIT_WATTS(R.string.autotune_param_power_limit),
    TEMP_START_C(R.string.autotune_param_temp_start),
    HEADROOM_PCT(R.string.autotune_param_cpu_headroom),
    GPU_HEADROOM_PCT(R.string.autotune_param_gpu_headroom),
    RAMP_DOWN_SPEED(R.string.autotune_param_cpu_ramp_down),
    GPU_RAMP_DOWN_SPEED(R.string.autotune_param_gpu_ramp_down),
    RAMP_UP_SPEED(R.string.autotune_param_cpu_ramp_up),
    GPU_RAMP_UP_SPEED(R.string.autotune_param_gpu_ramp_up)
}

enum class AutoTuneTrialStatus(@StringRes val labelRes: Int) {
    PENDING(R.string.autotune_status_pending),
    BASELINE(R.string.autotune_status_baseline),
    KEPT(R.string.autotune_status_kept),
    REVERTED(R.string.autotune_status_reverted),
    REJECTED_SAFETY(R.string.autotune_status_rejected_safety),
    IGNORED(R.string.autotune_status_ignored)
}

data class AutoTuneCandidateSpec(
    val param: AutoTuneParam,
    val direction: Int,
    val value: Float,
    val key: String
)

data class AutoTuneTrialRecord(
    val trialId: Int,
    val timestampMs: Long,
    val sessionFileName: String = "",
    val param: AutoTuneParam? = null,
    val oldValue: String = "",
    val newValue: String = "",
    val score: Float = Float.NaN,
    val baselineScore: Float = Float.NaN,
    val bestScoreBefore: Float = Float.NaN,
    val avgFps: Float = Float.NaN,
    val p5Fps: Float = Float.NaN,
    val avgWatts: Float = Float.NaN,
    val maxTempC: Float = Float.NaN,
    val status: AutoTuneTrialStatus,
    val reason: String = "",
    val reasonCode: String = "",
    val candidateKey: String = ""
)

data class PerGameAutoTuneState(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val mode: AutoTuneMode = AutoTuneMode.SUGGEST_ONLY,
    val minSessionMinutes: Int = 8,
    val rewardMarginPct: Float = 3f,
    val lockedParams: Set<AutoTuneParam> = emptySet(),
    val baselineCaptured: Boolean = false,
    val baselineProfile: PerGameProfile = PerGameProfile(),
    val userOriginalProfile: PerGameProfile? = null,
    val bestProfile: PerGameProfile = PerGameProfile(),
    val candidateProfile: PerGameProfile? = null,
    val pendingCandidate: AutoTuneCandidateSpec? = null,
    val pendingTrialId: Int = 0,
    val pendingSuggestion: AutoTuneTrialRecord? = null,
    val baselineScore: Float = Float.NaN,
    val baselineP5Fps: Float = Float.NaN,
    val bestScore: Float = Float.NaN,
    val nextTrialId: Int = 1,
    val paramCursor: Int = 0,
    val consecutiveNoImproveParams: Int = 0,
    val converged: Boolean = false,
    val badCandidateKeys: Set<String> = emptySet(),
    val triedCandidateKeys: Set<String> = emptySet(),
    val trials: List<AutoTuneTrialRecord> = emptyList()
)

data class EffectiveGameConfig(
    val packageName: String,
    val gameMode: ModeProfile,
    val tempThrottle: TempThrottleConfig,
    val powerThrottle: PowerThrottleConfig,
    val afterburnStrength: BoostStrength,
    val hasPerGameOverride: Boolean,
    val autoTuneActive: Boolean,
    val autoTuneTrialId: Int
)

data class GovernorConfig(
    val pollIntervalMs: Long = 150L,
    val adaptivePollingEnabled: Boolean = true,
    val startOnBoot: Boolean = false,
    val protectFromKillEnabled: Boolean = true,
    val cpuLoadUsesBusiestCore: Boolean = true,
    val screenOffSavingEnabled: Boolean = false,
    val screenOffDelaySec: Int = 5,
    val screenOffExemptMusic: Boolean = true,
    val screenOffExemptCharging: Boolean = false,
    val overlayEnabled: Boolean = false,
    val overlayClickThrough: Boolean = false,
    val overlayGameOnly: Boolean = false,
    val overlayExpanded: Boolean = false,
    val overlayDetailed: Boolean = false,
    val overlayOpacityPct: Int = 85,
    val overlayBgOpacityPct: Int = 100,
    val overlayCompactScalePct: Int = 100,
    val overlayExpandedScalePct: Int = 100,
    // Landscape overlay sizes (-1 sentinel = same as portrait so existing configs keep current size).
    val overlayCompactScalePctL: Int = -1,
    val overlayExpandedScalePctL: Int = -1,
    // v3.7.0: the detailed overlay has its own size (-1 sentinel = follow the expanded size, as before).
    val overlayDetailedScalePct: Int = -1,
    val overlayDetailedScalePctL: Int = -1,
    // v3.7.1: per-style background opacity (-1 = follow the main overlayBgOpacityPct).
    val overlayCompactBgOpacityPct: Int = -1,
    val overlayExpandedBgOpacityPct: Int = -1,
    val overlayDetailedBgOpacityPct: Int = -1,
    // v3.8.0: per-style text size (100 = as before; label/value text only, not the ring-centre figure).
    val overlayCompactTextScalePct: Int = 100,
    val overlayExpandedTextScalePct: Int = 100,
    val overlayDetailedTextScalePct: Int = 100,
    val overlayCompactXPm: Int = 40,
    val overlayCompactYPm: Int = 60,
    val overlayExpandedXPm: Int = 40,
    val overlayExpandedYPm: Int = 60,
    val overlayDetailedXPm: Int = 40,
    val overlayDetailedYPm: Int = 60,
    // Landscape positions are stored separately from portrait ones (suffix L).
    val overlayCompactXPmL: Int = 40,
    val overlayCompactYPmL: Int = 60,
    val overlayExpandedXPmL: Int = 40,
    val overlayExpandedYPmL: Int = 60,
    val overlayDetailedXPmL: Int = 40,
    val overlayDetailedYPmL: Int = 60,
    val overlayX: Int = 24,
    val overlayY: Int = 120,
    val ramPinMax: Boolean = false,
    val ramPinGameOnly: Boolean = true,
    val safetyFloorPct: Float = 20f,
    val selectedTempSensorPath: String = "",
    val modeOverride: ModeOverride = ModeOverride.AUTO,
    val normalMode: ModeProfile = ModeProfile(
        headroomPct = 10f,
        rampUpSpeed = 0.80f,
        rampDownSpeed = 0.16f,
        gpuHeadroomPct = 12f,
        gpuRampUpSpeed = 0.80f,
        gpuRampDownSpeed = 0.15f,
        launchBurstEnabled = true,
        launchBurstDurationMs = 1400L,
        launchSpikeThresholdPct = 35f
    ),
    val gameMode: ModeProfile = ModeProfile(
        headroomPct = 22f,
        rampUpSpeed = 0.92f,
        rampDownSpeed = 0.04f,
        gpuHeadroomPct = 22f,
        gpuRampUpSpeed = 0.92f,
        gpuRampDownSpeed = 0.05f,
        launchBurstEnabled = false,
        launchBurstDurationMs = 0L,
        launchSpikeThresholdPct = 50f
    ),
    val tempThrottle: TempThrottleConfig = TempThrottleConfig(),
    val powerThrottle: PowerThrottleConfig = PowerThrottleConfig(),
    val powerModelLearningEnabled: Boolean = true,
    val efficiencyLearningEnabled: Boolean = false,
    val efficiencyMode: EfficiencyMode = EfficiencyMode.BALANCED,
    val minConfidencePct: Int = 60,
    /** v4.0.6: average model confidence the smooth power limit needs before it switches between two steps. */
    val smoothMinConfidencePct: Int = 40,
    /** v4.0.9: shortest time the smooth limit stays on one of its two steps (ms). 50 = the behaviour before 4.0.9. */
    val smoothSliceMs: Int = 50,
    /** v4.0.9: learn the slice length automatically (one CPU cluster at a time, long game sessions only). */
    val smoothSliceAuto: Boolean = false,
    /** v4.1.0: when the kernel overrides the smooth limiter's last write, write it again (rate-limited, never during a thermal cap). */
    val smoothFixDrift: Boolean = false,
    /** v4.0.9: slices the learner settled on, as "cpu_0:40,cpu_3:50". */
    val smoothLearnedSlices: String = "",
    val explorationRate: ExplorationRate = ExplorationRate.LOW,
    val batteryEstimationEnabled: Boolean = true,
    val batteryDesignCapacityOverrideMah: Int = 0,
    val gamePackages: Set<String> = emptySet(),
    // v2.7.0 Smart app profiles (opt-in, default OFF)
    val smartAppProfilesEnabled: Boolean = false,
    val smartAppMode: SmartAppMode = SmartAppMode.BALANCED,
    // v2.7.0 Adaptive refresh rate (default OFF). 0 = auto (lowest / highest supported)
    val adaptiveRefreshEnabled: Boolean = false,
    val adaptiveIdleHz: Int = 0,
    val adaptiveActiveHz: Int = 0,
    val adaptiveIdleDelayMs: Int = 1500,
    // v2.7.1 Screen-off power analysis (passive, default ON)
    val screenOffAnalysisEnabled: Boolean = true,
    // v3.0.0 Afterburn (default OFF); boostPackages = apps/games it is allowed to work on
    val afterburn: AfterburnConfig = AfterburnConfig(),
    val boostPackages: Set<String> = emptySet(),
    // v3.7.2: optional Afterburn strength per boosted app (an app without an entry uses afterburn.strength).
    val boostStrengthByPkg: Map<String, BoostStrength> = emptyMap(),
    val stutterHunter: StutterHunterConfig = StutterHunterConfig(),
    val gameThreadController: GameThreadControllerConfig = GameThreadControllerConfig(),
    // v4.0.0 Per-game override profiles, per-game auto-tune state, and composite score weights.
    val perGameProfiles: Map<String, PerGameProfile> = emptyMap(),
    val autoTuneByPkg: Map<String, PerGameAutoTuneState> = emptyMap(),
    val scoreWeights: CompositeScoreWeights = CompositeScoreWeights()
)

data class PresetItem(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val config: GovernorConfig
)

data class ClusterLiveState(
    val policyId: Int,
    val name: String,
    val relatedCpus: List<Int>,
    val loadPct: Float,
    val smoothedLoadPct: Float,
    val targetFreqKhz: Long,
    val actualFreqKhz: Long,
    val governor: String,
    val allowedMinKhz: Long,
    val allowedMaxKhz: Long,
    val cappedMaxKhz: Long,
    val minAvailableKhz: Long,
    val maxAvailableKhz: Long
)

data class GpuLiveState(
    val available: Boolean = false,
    val loadPct: Float = 0f,
    val smoothedLoadPct: Float = 0f,
    val loadSourcePath: String = "",
    val targetSortedPos: Int = -1,
    val targetOppIndex: Int = -1,
    val targetFreqKhz: Long = 0L,
    val currentOppIndex: Int = -1,
    val currentFreqKhz: Long = 0L,
    val rawGpufreqStatus: String = "",
    val allowedMinPos: Int = 0,
    val allowedMaxPos: Int = 0,
    val cappedMaxPos: Int = 0,
    val allowedMinKhz: Long = 0L,
    val allowedMaxKhz: Long = 0L,
    val cappedMaxKhz: Long = 0L,
    val minAvailableKhz: Long = 0L,
    val maxAvailableKhz: Long = 0L
)

data class TelemetrySample(
    val timestampMs: Long,
    val tempC: Float,
    val watts: Float,
    val avgCpuLoadPct: Float,
    val gpuLoadPct: Float,
    val effectiveCpuCapPct: Float,
    val effectiveGpuCapPct: Float
)

data class TelemetryState(
    val isRunning: Boolean = false,
    val isDiscovering: Boolean = false,
    val rootGranted: Boolean = false,
    val activeMode: GovernorMode = GovernorMode.NORMAL,
    val modeOverride: ModeOverride = ModeOverride.AUTO,
    val foregroundPackage: String = "",
    val burstActive: Boolean = false,
    val burstRemainingMs: Long = 0L,
    val clusters: List<ClusterLiveState> = emptyList(),
    val gpu: GpuLiveState = GpuLiveState(),
    val totalCpuLoadPct: Float = 0f,
    val coreLoadsPct: Map<Int, Float> = emptyMap(),
    val ddrFreqMhz: Long = 0L,
    val ddrSupported: Boolean = false,
    val ddrPinned: Boolean = false,
    val fps: Float = -1f,
    val temperatureC: Float = 0f,
    val tempSensorLabel: String = "Unassigned",
    val watts: Float = 0f,
    /** v3.8.1: fast discharge watts (about 2 samples of smoothing) for the overlay; [watts] stays the slow average the limiter uses. */
    val liveWatts: Float = 0f,
    val chargeWatts: Float = 0f,
    val signedWatts: Float = 0f,
    val batteryState: BatteryState = BatteryState.UNKNOWN,
    val batteryPct: Int = -1,
    val batteryTempC: Float = 0f,
    val batteryVoltageMv: Float = 0f,
    val batteryHealthLabel: String = "n/a",
    val batteryCycleCount: Int = -1,
    val chargeCounterMah: Float = -1f,
    val rawCurrentMa: Float = 0f,
    val rawVoltageMv: Float = 0f,
    val tempThrottleActive: Boolean = false,
    val tempCpuCapPct: Float = 100f,
    val tempGpuCapPct: Float = 100f,
    val powerThrottleActive: Boolean = false,
    val powerCapPct: Float = 100f,
    val afterburnState: AfterburnState = AfterburnState.OFF,
    val afterburnLevelPct: Int = 0,
    val truePct: Float = -1f,
    val trueHeadroomPct: Float = 0f,
    val batteryChemistry: String = "Detecting",
    val batterySagMv: Float = 0f,
    val batteryResistanceMohm: Float = 0f,
    val truthConfidencePct: Int = 0,
    val calChargeDone: Boolean = false,
    val calDischargeDone: Boolean = false,
    val calRunning: String = "",
    val fullChargeMv: Float = 0f,
    val fullChargeMah: Float = 0f,
    val effectiveCpuCapPct: Float = 100f,
    val effectiveGpuCapPct: Float = 100f,
    val lastPollDurationMs: Long = 0L,
    val history: List<TelemetrySample> = emptyList()
)

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)

/** Saved overlay position (permille of free screen space) for the active style and orientation. */
fun GovernorConfig.overlayPosPm(landscape: Boolean): Pair<Int, Int> {
    val (x, y) = when {
        overlayDetailed -> if (landscape) overlayDetailedXPmL to overlayDetailedYPmL else overlayDetailedXPm to overlayDetailedYPm
        overlayExpanded -> if (landscape) overlayExpandedXPmL to overlayExpandedYPmL else overlayExpandedXPm to overlayExpandedYPm
        else -> if (landscape) overlayCompactXPmL to overlayCompactYPmL else overlayCompactXPm to overlayCompactYPm
    }
    return x.coerceIn(0, 1000) to y.coerceIn(0, 1000)
}

/** Returns a copy with the active style's position for one orientation changed (null keeps the old value). */
fun GovernorConfig.withOverlayPosPm(landscape: Boolean, xPm: Int?, yPm: Int?): GovernorConfig {
    val (cx, cy) = overlayPosPm(landscape)
    val x = (xPm ?: cx).coerceIn(0, 1000)
    val y = (yPm ?: cy).coerceIn(0, 1000)
    return when {
        overlayDetailed -> if (landscape) copy(overlayDetailedXPmL = x, overlayDetailedYPmL = y) else copy(overlayDetailedXPm = x, overlayDetailedYPm = y)
        overlayExpanded -> if (landscape) copy(overlayExpandedXPmL = x, overlayExpandedYPmL = y) else copy(overlayExpandedXPm = x, overlayExpandedYPm = y)
        else -> if (landscape) copy(overlayCompactXPmL = x, overlayCompactYPmL = y) else copy(overlayCompactXPm = x, overlayCompactYPm = y)
    }
}

/** Resets every style's saved overlay position, for both portrait and landscape, to the defaults. */
fun GovernorConfig.withOverlayPositionsReset(): GovernorConfig = copy(
    overlayCompactXPm = 40, overlayCompactYPm = 60,
    overlayExpandedXPm = 40, overlayExpandedYPm = 60,
    overlayDetailedXPm = 40, overlayDetailedYPm = 60,
    overlayCompactXPmL = 40, overlayCompactYPmL = 60,
    overlayExpandedXPmL = 40, overlayExpandedYPmL = 60,
    overlayDetailedXPmL = 40, overlayDetailedYPmL = 60
)

/** Effective compact overlay scale % for the given orientation (-1 sentinel falls back to portrait). */
fun GovernorConfig.overlayCompactScaleFor(landscape: Boolean): Int {
    val raw = if (landscape && overlayCompactScalePctL >= 0) overlayCompactScalePctL else overlayCompactScalePct
    return raw.coerceIn(25, 200)
}

/** Effective expanded overlay scale % for the given orientation (-1 sentinel falls back to portrait). */
fun GovernorConfig.overlayExpandedScaleFor(landscape: Boolean): Int {
    val raw = if (landscape && overlayExpandedScalePctL >= 0) overlayExpandedScalePctL else overlayExpandedScalePct
    return raw.coerceIn(25, 200)
}

/**
 * Effective detailed overlay scale %. A detailed size that was never set (-1) follows the expanded size,
 * so configs saved before v3.7.0 look exactly the same. Landscape falls back to the detailed portrait size
 * first, and then to the expanded size.
 */
fun GovernorConfig.overlayDetailedScaleFor(landscape: Boolean): Int {
    val raw = when {
        landscape && overlayDetailedScalePctL >= 0 -> overlayDetailedScalePctL
        overlayDetailedScalePct >= 0 -> overlayDetailedScalePct
        else -> return overlayExpandedScaleFor(landscape)
    }
    return raw.coerceIn(25, 200)
}

/** Effective overlay scale % for the active style and orientation. */
fun GovernorConfig.overlayScalePct(landscape: Boolean): Int {
    return if (overlayDetailed) {
        overlayDetailedScaleFor(landscape)
    } else if (overlayExpanded) {
        overlayExpandedScaleFor(landscape)
    } else {
        overlayCompactScaleFor(landscape)
    }
}

/** Returns a copy with compact, expanded and/or detailed scale % updated for one orientation (null keeps old value). */
fun GovernorConfig.withOverlayScalePct(
    landscape: Boolean,
    compactPct: Int? = null,
    expandedPct: Int? = null,
    detailedPct: Int? = null
): GovernorConfig {
    val nextCompact = compactPct?.coerceIn(25, 200)
    val nextExpanded = expandedPct?.coerceIn(25, 200)
    val nextDetailed = detailedPct?.coerceIn(25, 200)
    return if (landscape) {
        copy(
            overlayCompactScalePctL = nextCompact ?: overlayCompactScalePctL,
            overlayExpandedScalePctL = nextExpanded ?: overlayExpandedScalePctL,
            overlayDetailedScalePctL = nextDetailed ?: overlayDetailedScalePctL
        )
    } else {
        copy(
            overlayCompactScalePct = nextCompact ?: overlayCompactScalePct,
            overlayExpandedScalePct = nextExpanded ?: overlayExpandedScalePct,
            overlayDetailedScalePct = nextDetailed ?: overlayDetailedScalePct
        )
    }
}

// ---- Per-style access (v3.7.0): every overlay style can be tuned without having to be the active one. ----

private fun GovernorConfig.viewedAs(style: OverlayStyle): GovernorConfig = copy(
    overlayExpanded = style == OverlayStyle.EXPANDED,
    overlayDetailed = style == OverlayStyle.DETAILED
)

/** Saved position (permille) of one specific style for one orientation. */
fun GovernorConfig.overlayPosPmFor(style: OverlayStyle, landscape: Boolean): Pair<Int, Int> =
    viewedAs(style).overlayPosPm(landscape)

/** Returns a copy with one specific style's position changed (null keeps the old axis); the active style is untouched. */
fun GovernorConfig.withOverlayPosPmFor(style: OverlayStyle, landscape: Boolean, xPm: Int?, yPm: Int?): GovernorConfig =
    viewedAs(style).withOverlayPosPm(landscape, xPm, yPm)
        .copy(overlayExpanded = overlayExpanded, overlayDetailed = overlayDetailed)

/** Resets one style's position, for both orientations, to the defaults. */
fun GovernorConfig.withOverlayPositionResetFor(style: OverlayStyle): GovernorConfig =
    withOverlayPosPmFor(style, false, 40, 60).withOverlayPosPmFor(style, true, 40, 60)

/** Effective scale % of one specific style for one orientation. */
fun GovernorConfig.overlayScaleFor(style: OverlayStyle, landscape: Boolean): Int = when (style) {
    OverlayStyle.COMPACT -> overlayCompactScaleFor(landscape)
    OverlayStyle.EXPANDED -> overlayExpandedScaleFor(landscape)
    OverlayStyle.DETAILED -> overlayDetailedScaleFor(landscape)
}

/** Returns a copy with one specific style's scale % changed for one orientation. */
fun GovernorConfig.withOverlayScalePctFor(style: OverlayStyle, landscape: Boolean, pct: Int): GovernorConfig = when (style) {
    OverlayStyle.COMPACT -> withOverlayScalePct(landscape, compactPct = pct)
    OverlayStyle.EXPANDED -> withOverlayScalePct(landscape, expandedPct = pct)
    OverlayStyle.DETAILED -> withOverlayScalePct(landscape, detailedPct = pct)
}

// ---- Per-style background opacity (v3.7.1) ----

/** Background opacity % of one style; a style that was never tuned (-1) follows the main background opacity. */
fun GovernorConfig.overlayBgOpacityFor(style: OverlayStyle): Int {
    val raw = when (style) {
        OverlayStyle.COMPACT -> overlayCompactBgOpacityPct
        OverlayStyle.EXPANDED -> overlayExpandedBgOpacityPct
        OverlayStyle.DETAILED -> overlayDetailedBgOpacityPct
    }
    return (if (raw >= 0) raw else overlayBgOpacityPct).coerceIn(0, 100)
}

/** Background opacity % of the style that is showing now. */
fun GovernorConfig.overlayActiveBgOpacityPct(): Int = overlayBgOpacityFor(
    when {
        overlayDetailed -> OverlayStyle.DETAILED
        overlayExpanded -> OverlayStyle.EXPANDED
        else -> OverlayStyle.COMPACT
    }
)

/** Returns a copy with one style's background opacity set (the other styles are untouched). */
fun GovernorConfig.withOverlayBgOpacityFor(style: OverlayStyle, pct: Int): GovernorConfig {
    val v = pct.coerceIn(0, 100)
    return when (style) {
        OverlayStyle.COMPACT -> copy(overlayCompactBgOpacityPct = v)
        OverlayStyle.EXPANDED -> copy(overlayExpandedBgOpacityPct = v)
        OverlayStyle.DETAILED -> copy(overlayDetailedBgOpacityPct = v)
    }
}

// ---- Per-style text size (v3.8.0) ----

const val OVERLAY_TEXT_SCALE_MIN_PCT = 70
const val OVERLAY_TEXT_SCALE_MAX_PCT = 160

fun GovernorConfig.overlayTextScaleFor(style: OverlayStyle): Int = when (style) {
    OverlayStyle.COMPACT -> overlayCompactTextScalePct
    OverlayStyle.EXPANDED -> overlayExpandedTextScalePct
    OverlayStyle.DETAILED -> overlayDetailedTextScalePct
}.coerceIn(OVERLAY_TEXT_SCALE_MIN_PCT, OVERLAY_TEXT_SCALE_MAX_PCT)

/** Returns a copy with one style's text size set (the other styles are untouched). */
fun GovernorConfig.withOverlayTextScaleFor(style: OverlayStyle, pct: Int): GovernorConfig {
    val v = pct.coerceIn(OVERLAY_TEXT_SCALE_MIN_PCT, OVERLAY_TEXT_SCALE_MAX_PCT)
    return when (style) {
        OverlayStyle.COMPACT -> copy(overlayCompactTextScalePct = v)
        OverlayStyle.EXPANDED -> copy(overlayExpandedTextScalePct = v)
        OverlayStyle.DETAILED -> copy(overlayDetailedTextScalePct = v)
    }
}

// ---- Per-app Afterburn strength (v3.7.2) ----

/** Afterburn strength used for one app: its own choice if it has one, otherwise the main setting. */
fun GovernorConfig.boostStrengthFor(packageName: String): BoostStrength =
    boostStrengthByPkg[packageName] ?: afterburn.strength

/** Returns a copy with one app's strength set; null removes it so the app follows the main setting again. */
fun GovernorConfig.withBoostStrengthFor(packageName: String, strength: BoostStrength?): GovernorConfig {
    if (packageName.isBlank()) return this
    val next = boostStrengthByPkg.toMutableMap()
    if (strength == null) next.remove(packageName) else next[packageName] = strength
    return copy(boostStrengthByPkg = next)
}
