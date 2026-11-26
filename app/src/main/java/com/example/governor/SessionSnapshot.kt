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

import com.example.model.EffectiveGameConfig
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import java.util.Locale

data class SnapshotFieldEntry(
    val key: String,
    val displayValue: String
)

/**
 * Compact settings snapshot captured at the start of a game session so sessions can be
 * explained, compared, and fed into per-game tweak impact analysis.
 */
data class SessionTweakSnapshot(
    val cpuHeadroomPct: Float,
    val cpuRampUp: Float,
    val cpuRampDown: Float,
    val gpuHeadroomPct: Float,
    val gpuRampUp: Float,
    val gpuRampDown: Float,
    val cpuRangesSummary: String,
    val gpuRangeSummary: String,
    val tempThrottleEnabled: Boolean,
    val tempStartC: Float,
    val tempFullC: Float,
    val tempCpuRedPct: Float,
    val tempGpuRedPct: Float,
    val tempCurve: String,
    val tempReaction: Float,
    val powerThrottleEnabled: Boolean,
    val powerLimitWatts: Float,
    val powerSustainMs: Long,
    val powerStepPct: Float,
    val smoothPowerLimit: Boolean,
    val afterburnEnabled: Boolean,
    val afterburnPkgEnabled: Boolean,
    val afterburnStrength: String,
    val ramPinMax: Boolean,
    val ramPinGameOnly: Boolean,
    val stutterHunterEnabled: Boolean,
    val stutterHunterMode: String,
    val gameThreadControllerEnabled: Boolean,
    val adaptiveRefreshEnabled: Boolean,
    val adaptiveIdleHz: Int,
    val adaptiveActiveHz: Int,
    val perGameOverrideActive: Boolean = false,
    val autoTuneActive: Boolean = false,
    val autoTuneTrialId: Int = 0
) {
    fun toEntries(): List<SnapshotFieldEntry> {
        val l = Locale.US
        fun fmtF(v: Float, dec: Int = 1) = String.format(l, "%.${dec}f", v)
        val afterburnText = if (!afterburnEnabled) {
            "Off"
        } else if (!afterburnPkgEnabled) {
            "On (not selected for app)"
        } else {
            afterburnStrength
        }
        val refreshText = if (!adaptiveRefreshEnabled) {
            "Off"
        } else {
            val idle = if (adaptiveIdleHz > 0) "${adaptiveIdleHz}Hz" else "Auto"
            val act = if (adaptiveActiveHz > 0) "${adaptiveActiveHz}Hz" else "Auto"
            "$idle-$act"
        }
        val ramText = if (!ramPinMax) "Off" else if (ramPinGameOnly) "Game only" else "Always"
        val hunterText = if (!stutterHunterEnabled) "Off" else stutterHunterMode
        val autoTuneText = if (!autoTuneActive) {
            "Off"
        } else if (autoTuneTrialId > 0) {
            "Trial #$autoTuneTrialId"
        } else {
            "Active"
        }
        return listOf(
            SnapshotFieldEntry(KEY_CPU_HEADROOM, "${fmtF(cpuHeadroomPct, 0)}%"),
            SnapshotFieldEntry(KEY_CPU_RAMP_UP, fmtF(cpuRampUp, 2)),
            SnapshotFieldEntry(KEY_CPU_RAMP_DOWN, fmtF(cpuRampDown, 2)),
            SnapshotFieldEntry(KEY_GPU_HEADROOM, "${fmtF(gpuHeadroomPct, 0)}%"),
            SnapshotFieldEntry(KEY_GPU_RAMP_UP, fmtF(gpuRampUp, 2)),
            SnapshotFieldEntry(KEY_GPU_RAMP_DOWN, fmtF(gpuRampDown, 2)),
            SnapshotFieldEntry(KEY_CPU_RANGES, cpuRangesSummary.ifBlank { "-" }),
            SnapshotFieldEntry(KEY_GPU_RANGE, gpuRangeSummary.ifBlank { "-" }),
            SnapshotFieldEntry(
                KEY_TEMP_THROTTLE,
                if (tempThrottleEnabled) "${fmtF(tempStartC, 1)}-${fmtF(tempFullC, 1)} C" else "Off"
            ),
            SnapshotFieldEntry(
                KEY_TEMP_REDUCTION,
                if (tempThrottleEnabled) "${fmtF(tempCpuRedPct, 0)}%/${fmtF(tempGpuRedPct, 0)}%" else "Off"
            ),
            SnapshotFieldEntry(KEY_TEMP_CURVE, if (tempThrottleEnabled) tempCurve else "Off"),
            SnapshotFieldEntry(KEY_TEMP_REACTION, if (tempThrottleEnabled) fmtF(tempReaction, 2) else "Off"),
            SnapshotFieldEntry(
                KEY_POWER_LIMIT,
                if (powerThrottleEnabled) "${fmtF(powerLimitWatts, 1)} W" else "Off"
            ),
            SnapshotFieldEntry(
                KEY_POWER_TIMING,
                if (powerThrottleEnabled) "${powerSustainMs}ms / ${fmtF(powerStepPct, 1)}%" else "Off"
            ),
            SnapshotFieldEntry(KEY_SMOOTH_POWER, if (smoothPowerLimit) "On" else "Off"),
            SnapshotFieldEntry(KEY_AFTERBURN, afterburnText),
            SnapshotFieldEntry(KEY_RAM_PIN, ramText),
            SnapshotFieldEntry(KEY_STUTTER_HUNTER, hunterText),
            SnapshotFieldEntry(KEY_GAME_THREADS, if (gameThreadControllerEnabled) "On" else "Off"),
            SnapshotFieldEntry(KEY_ADAPTIVE_REFRESH, refreshText),
            SnapshotFieldEntry(KEY_PER_GAME_OVERRIDE, if (perGameOverrideActive) "Yes" else "Global"),
            SnapshotFieldEntry(KEY_AUTO_TUNE, autoTuneText)
        )
    }

    fun toDisplayMap(): Map<String, String> = toEntries().associate { it.key to it.displayValue }

    fun encode(): String {
        val l = Locale.US
        fun clean(s: String) = s.replace('|', '_').replace(';', ',').replace('=', ':').replace('\n', ' ').replace('\r', ' ')
        fun f1(v: Float) = String.format(l, "%.1f", v)
        fun f2(v: Float) = String.format(l, "%.2f", v)
        fun b(v: Boolean) = if (v) "1" else "0"
        return listOf(
            "ch=${f1(cpuHeadroomPct)}",
            "cru=${f2(cpuRampUp)}",
            "crd=${f2(cpuRampDown)}",
            "gh=${f1(gpuHeadroomPct)}",
            "gru=${f2(gpuRampUp)}",
            "grd=${f2(gpuRampDown)}",
            "cr=${clean(cpuRangesSummary)}",
            "gr=${clean(gpuRangeSummary)}",
            "te=${b(tempThrottleEnabled)}",
            "ts=${f1(tempStartC)}",
            "tf=${f1(tempFullC)}",
            "tcr=${f1(tempCpuRedPct)}",
            "tgr=${f1(tempGpuRedPct)}",
            "tc=${clean(tempCurve)}",
            "tr=${f2(tempReaction)}",
            "pe=${b(powerThrottleEnabled)}",
            "pw=${f2(powerLimitWatts)}",
            "ps=$powerSustainMs",
            "pst=${f1(powerStepPct)}",
            "sm=${b(smoothPowerLimit)}",
            "abe=${b(afterburnEnabled)}",
            "abp=${b(afterburnPkgEnabled)}",
            "abs=${clean(afterburnStrength)}",
            "rp=${b(ramPinMax)}",
            "rpg=${b(ramPinGameOnly)}",
            "she=${b(stutterHunterEnabled)}",
            "shm=${clean(stutterHunterMode)}",
            "gtc=${b(gameThreadControllerEnabled)}",
            "are=${b(adaptiveRefreshEnabled)}",
            "ari=$adaptiveIdleHz",
            "ara=$adaptiveActiveHz",
            "pgo=${b(perGameOverrideActive)}",
            "at=${b(autoTuneActive)}",
            "att=$autoTuneTrialId"
        ).joinToString(";")
    }

    companion object {
        const val KEY_CPU_HEADROOM = "cpu_headroom"
        const val KEY_CPU_RAMP_UP = "cpu_ramp_up"
        const val KEY_CPU_RAMP_DOWN = "cpu_ramp_down"
        const val KEY_GPU_HEADROOM = "gpu_headroom"
        const val KEY_GPU_RAMP_UP = "gpu_ramp_up"
        const val KEY_GPU_RAMP_DOWN = "gpu_ramp_down"
        const val KEY_CPU_RANGES = "cpu_ranges"
        const val KEY_GPU_RANGE = "gpu_range"
        const val KEY_TEMP_THROTTLE = "temp_throttle"
        const val KEY_TEMP_REDUCTION = "temp_reduction"
        const val KEY_TEMP_CURVE = "temp_curve"
        const val KEY_TEMP_REACTION = "temp_reaction"
        const val KEY_POWER_LIMIT = "power_limit"
        const val KEY_POWER_TIMING = "power_timing"
        const val KEY_SMOOTH_POWER = "smooth_power"
        const val KEY_AFTERBURN = "afterburn"
        const val KEY_RAM_PIN = "ram_pin"
        const val KEY_STUTTER_HUNTER = "stutter_hunter"
        const val KEY_GAME_THREADS = "game_threads"
        const val KEY_ADAPTIVE_REFRESH = "adaptive_refresh"
        const val KEY_PER_GAME_OVERRIDE = "per_game_override"
        const val KEY_AUTO_TUNE = "auto_tune"

        private fun formatCpuRanges(ranges: Map<Int, FreqRange>): String {
            if (ranges.isEmpty()) return "-"
            return ranges.entries.sortedBy { it.key }.joinToString(",") { (id, r) ->
                "P$id:${r.minKhz / 1000L}-${r.maxKhz / 1000L}M"
            }
        }

        private fun formatGpuRange(range: FreqRange?): String {
            if (range == null) return "-"
            return "${range.minKhz / 1000L}-${range.maxKhz / 1000L}M"
        }

        fun capture(
            cfg: GovernorConfig,
            effective: EffectiveGameConfig,
            packageName: String
        ): SessionTweakSnapshot {
            val gm = effective.gameMode
            val tt = effective.tempThrottle
            val pt = effective.powerThrottle
            val pkgInBoost = packageName.isNotBlank() && packageName in cfg.boostPackages
            return SessionTweakSnapshot(
                cpuHeadroomPct = gm.headroomPct,
                cpuRampUp = gm.rampUpSpeed,
                cpuRampDown = gm.rampDownSpeed,
                gpuHeadroomPct = gm.gpuHeadroomPct,
                gpuRampUp = gm.gpuRampUpSpeed,
                gpuRampDown = gm.gpuRampDownSpeed,
                cpuRangesSummary = formatCpuRanges(gm.cpuRangesKhz),
                gpuRangeSummary = formatGpuRange(gm.gpuRangeKhz),
                tempThrottleEnabled = tt.enabled,
                tempStartC = tt.startTempC,
                tempFullC = tt.fullTempC,
                tempCpuRedPct = tt.cpuMaxReductionPct,
                tempGpuRedPct = tt.gpuMaxReductionPct,
                tempCurve = tt.curveShape.name,
                tempReaction = tt.reactionSpeed,
                powerThrottleEnabled = pt.enabled,
                powerLimitWatts = pt.limitWatts,
                powerSustainMs = pt.sustainMs,
                powerStepPct = pt.stepPct,
                smoothPowerLimit = pt.smoothPowerLimit,
                afterburnEnabled = cfg.afterburn.enabled,
                afterburnPkgEnabled = pkgInBoost,
                afterburnStrength = effective.afterburnStrength.label,
                ramPinMax = cfg.ramPinMax,
                ramPinGameOnly = cfg.ramPinGameOnly,
                stutterHunterEnabled = cfg.stutterHunter.enabled,
                stutterHunterMode = cfg.stutterHunter.mode.name,
                gameThreadControllerEnabled = cfg.gameThreadController.enabled,
                adaptiveRefreshEnabled = cfg.adaptiveRefreshEnabled,
                adaptiveIdleHz = cfg.adaptiveIdleHz,
                adaptiveActiveHz = cfg.adaptiveActiveHz,
                perGameOverrideActive = effective.hasPerGameOverride,
                autoTuneActive = effective.autoTuneActive,
                autoTuneTrialId = effective.autoTuneTrialId
            )
        }

        fun decode(raw: String?): SessionTweakSnapshot? {
            if (raw.isNullOrBlank()) return null
            val map = HashMap<String, String>()
            for (part in raw.split(';')) {
                val idx = part.indexOf(':')
                val eq = part.indexOf('=')
                val sep = if (eq >= 0) eq else idx
                if (sep <= 0) continue
                map[part.substring(0, sep)] = part.substring(sep + 1)
            }
            if (!map.containsKey("ch") || !map.containsKey("pw")) return null
            fun fl(k: String, d: Float) = map[k]?.toFloatOrNull() ?: d
            fun lg(k: String, d: Long) = map[k]?.toLongOrNull() ?: d
            fun it(k: String, d: Int) = map[k]?.toIntOrNull() ?: d
            fun bl(k: String, d: Boolean) = when (map[k]) {
                "1", "true" -> true
                "0", "false" -> false
                else -> d
            }
            return SessionTweakSnapshot(
                cpuHeadroomPct = fl("ch", 22f),
                cpuRampUp = fl("cru", 0.92f),
                cpuRampDown = fl("crd", 0.04f),
                gpuHeadroomPct = fl("gh", 22f),
                gpuRampUp = fl("gru", 0.92f),
                gpuRampDown = fl("grd", 0.05f),
                cpuRangesSummary = map["cr"].orEmpty(),
                gpuRangeSummary = map["gr"].orEmpty(),
                tempThrottleEnabled = bl("te", true),
                tempStartC = fl("ts", 42f),
                tempFullC = fl("tf", 56f),
                tempCpuRedPct = fl("tcr", 55f),
                tempGpuRedPct = fl("tgr", 65f),
                tempCurve = map["tc"] ?: "LINEAR",
                tempReaction = fl("tr", 0.35f),
                powerThrottleEnabled = bl("pe", true),
                powerLimitWatts = fl("pw", 6f),
                powerSustainMs = lg("ps", 2000L),
                powerStepPct = fl("pst", 3f),
                smoothPowerLimit = bl("sm", true),
                afterburnEnabled = bl("abe", false),
                afterburnPkgEnabled = bl("abp", false),
                afterburnStrength = map["abs"] ?: "Medium",
                ramPinMax = bl("rp", false),
                ramPinGameOnly = bl("rpg", true),
                stutterHunterEnabled = bl("she", false),
                stutterHunterMode = map["shm"] ?: "NOTIFY",
                gameThreadControllerEnabled = bl("gtc", false),
                adaptiveRefreshEnabled = bl("are", false),
                adaptiveIdleHz = it("ari", 0),
                adaptiveActiveHz = it("ara", 0),
                perGameOverrideActive = bl("pgo", false),
                autoTuneActive = bl("at", false),
                autoTuneTrialId = it("att", 0)
            )
        }
    }
}
