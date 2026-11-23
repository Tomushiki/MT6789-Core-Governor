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

enum class SmartAppMode(val label: String) {
    CONSERVATIVE("Conservative"),
    BALANCED("Balanced"),
    AGGRESSIVE("Aggressive")
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
    val componentAware: Boolean = true
)

data class GovernorConfig(
    val pollIntervalMs: Long = 150L,
    val startOnBoot: Boolean = false,
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
    val explorationRate: ExplorationRate = ExplorationRate.LOW,
    val batteryEstimationEnabled: Boolean = true,
    val batteryDesignCapacityOverrideMah: Int = 0,
    val gamePackages: Set<String> = emptySet(),
    // v2.7.0 Smart app profiles (opt-in, default OFF)
    val smartAppProfilesEnabled: Boolean = false,
    val smartAppMode: SmartAppMode = SmartAppMode.BALANCED,
    // v2.7.0 Adaptive refresh rate (EXPERIMENTAL, default OFF). 0 = auto (lowest / highest supported)
    val adaptiveRefreshEnabled: Boolean = false,
    val adaptiveIdleHz: Int = 0,
    val adaptiveActiveHz: Int = 0,
    val adaptiveIdleDelayMs: Int = 1500
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

/** Effective compact overlay scale % for the given orientation (-1 sentinel falls back to portrait). */
fun GovernorConfig.overlayCompactScaleFor(landscape: Boolean): Int {
    val raw = if (landscape && overlayCompactScalePctL >= 0) overlayCompactScalePctL else overlayCompactScalePct
    return raw.coerceIn(50, 200)
}

/** Effective expanded/detailed overlay scale % for the given orientation (-1 sentinel falls back to portrait). */
fun GovernorConfig.overlayExpandedScaleFor(landscape: Boolean): Int {
    val raw = if (landscape && overlayExpandedScalePctL >= 0) overlayExpandedScalePctL else overlayExpandedScalePct
    return raw.coerceIn(50, 200)
}

/** Effective overlay scale % for the active style and orientation. */
fun GovernorConfig.overlayScalePct(landscape: Boolean): Int {
    return if (overlayExpanded || overlayDetailed) {
        overlayExpandedScaleFor(landscape)
    } else {
        overlayCompactScaleFor(landscape)
    }
}

/** Returns a copy with compact and/or expanded scale % updated for one orientation (null keeps old value). */
fun GovernorConfig.withOverlayScalePct(
    landscape: Boolean,
    compactPct: Int? = null,
    expandedPct: Int? = null
): GovernorConfig {
    val nextCompact = compactPct?.coerceIn(50, 200)
    val nextExpanded = expandedPct?.coerceIn(50, 200)
    return if (landscape) {
        copy(
            overlayCompactScalePctL = nextCompact ?: overlayCompactScalePctL,
            overlayExpandedScalePctL = nextExpanded ?: overlayExpandedScalePctL
        )
    } else {
        copy(
            overlayCompactScalePct = nextCompact ?: overlayCompactScalePct,
            overlayExpandedScalePct = nextExpanded ?: overlayExpandedScalePct
        )
    }
}
