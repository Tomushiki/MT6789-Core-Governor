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

import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.HardwareSpec
import com.example.model.ModeProfile
import com.example.model.ThrottleCurveShape

/**
 * Built-in preset reproducing one user's own tuning script (Dimensity 8300 class phone: CPU
 * clusters policy0 / policy4 / policy7).
 *
 * It is a PATCH on the user's current config, not a replacement: only the per-mode CPU/GPU maximums,
 * the temperature throttle and the temperature sensor are touched. Every value is snapped down to a
 * frequency that really exists on the device. It is only offered when [matches] is true, so other
 * devices never see it. No control logic lives here; it only produces a config.
 *
 * The user's script steps down at 60 / 65 / 72 C (GPU 1.2 GHz / 1.0 GHz / 601 MHz, CPU about
 * 1.8/1.6/1.8, 1.6/1.4/1.6 and 1.4/1.2/1.4 GHz). The app's temperature throttle is continuous, so
 * 60 C start, 72 C full and about 55 % (CPU) / 57 % (GPU) maximum reduction is the closest match.
 */
object D8300UserPreset {
    const val ID = "builtin_d8300_user"
    const val NAME = "Dimensity 8300 - Daily/Game (user preset)"

    private const val LITTLE = 0
    private const val BIG = 4
    private const val PRIME = 7
    private const val PRIME_MIN_MAX_KHZ = 2_800_000L
    private const val SENSOR_MARKER = "thermal_zone40/"

    private val NORMAL_CPU_KHZ = mapOf(LITTLE to 2_000_000L, BIG to 2_400_000L, PRIME to 2_800_000L)
    private val GAME_CPU_KHZ = mapOf(LITTLE to 2_000_000L, BIG to 2_600_000L, PRIME to 3_350_000L)
    private const val NORMAL_GPU_KHZ = 1_000_000L
    private const val GAME_GPU_KHZ = 1_400_000L

    /** True only for a phone with CPU policies 0, 4 and 7 whose prime cluster reaches at least 2.8 GHz. */
    fun matches(hw: HardwareSpec): Boolean {
        val byId = hw.cpuPolicies.associateBy { it.policyId }
        val ids = listOf(LITTLE, BIG, PRIME)
        if (!ids.all { byId[it]?.availableFreqsKhz?.isNotEmpty() == true }) return false
        val primeMax = byId.getValue(PRIME).availableFreqsKhz.maxOrNull() ?: return false
        return primeMax >= PRIME_MIN_MAX_KHZ
    }

    /** Returns the patched config, or null when this device does not match. */
    fun apply(cfg: GovernorConfig, hw: HardwareSpec): GovernorConfig? {
        if (!matches(hw)) return null
        val byId = hw.cpuPolicies.associateBy { it.policyId }
        val gpuFreqs = hw.gpu.availableFreqsKhz.sorted()

        fun patchMode(mode: ModeProfile, cpuCaps: Map<Int, Long>, gpuCap: Long, minFraction: Float, normal: Boolean): ModeProfile {
            val cpu = mode.cpuRangesKhz.toMutableMap()
            for ((id, target) in cpuCaps) {
                val freqs = byId.getValue(id).availableFreqsKhz.sorted()
                val cap = snapDown(freqs, target)
                val existingMin = cpu[id]?.minKhz ?: defaultMin(freqs, minFraction, normal)
                cpu[id] = FreqRange(minOf(existingMin, cap), cap)
            }
            var gpu = mode.gpuRangeKhz
            if (gpuFreqs.isNotEmpty()) {
                val cap = snapDown(gpuFreqs, gpuCap)
                val existingMin = gpu?.minKhz ?: defaultMin(gpuFreqs, minFraction, normal)
                gpu = FreqRange(minOf(existingMin, cap), cap)
            }
            return mode.copy(cpuRangesKhz = cpu, gpuRangeKhz = gpu)
        }

        val sensor = hw.thermalSensors.firstOrNull { it.path.contains(SENSOR_MARKER) }?.path
        return cfg.copy(
            normalMode = patchMode(cfg.normalMode, NORMAL_CPU_KHZ, NORMAL_GPU_KHZ, 0f, true),
            gameMode = patchMode(cfg.gameMode, GAME_CPU_KHZ, GAME_GPU_KHZ, 0.25f, false),
            tempThrottle = cfg.tempThrottle.copy(
                enabled = true,
                startTempC = 60f,
                fullTempC = 72f,
                curveShape = ThrottleCurveShape.LINEAR,
                cpuMaxReductionPct = 55f,
                gpuMaxReductionPct = 57f,
                hysteresisC = 2f
            ),
            selectedTempSensorPath = sensor ?: cfg.selectedTempSensorPath
        )
    }

    /** Largest value in the ascending [sorted] list that is <= [target]; the lowest value if none is. */
    internal fun snapDown(sorted: List<Long>, target: Long): Long =
        sorted.lastOrNull { it <= target } ?: sorted.first()

    /** Same defaults ConfigStorage.populateDefaultsForHardware uses when a minimum is not set yet. */
    private fun defaultMin(sorted: List<Long>, fraction: Float, normal: Boolean): Long {
        if (normal) return sorted.first()
        val idx = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
