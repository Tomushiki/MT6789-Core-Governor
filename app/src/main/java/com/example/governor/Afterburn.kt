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

import com.example.model.AfterburnConfig
import com.example.model.AfterburnState

/** Result of one Afterburn decision. level is 0..1 (share of the power cap that is bypassed). */
data class AfterburnDecision(val level: Float, val state: AfterburnState) {
    val active: Boolean get() = level > 0.001f
}

/**
 * v3.0.0 Afterburn decision (pure, no Android dependencies).
 *
 * The thermal and battery conditions are decided together, not separately:
 *  - A low battery raises the temperature margin that is required (+2 C), so a nearly flat phone
 *    needs more thermal headroom before it boosts at all.
 *  - Thermal headroom scales the boost: just meeting the margin gives half strength, 4 C more
 *    headroom gives full strength.
 *  - A low battery scales the boost down (never up). At or below 5% there is no boost.
 *  - Charging ignores the battery condition. Either condition can be switched off; the other still works.
 * Voltage sag (optional) scales the boost down from 150 mV of load drop and stops it at 400 mV.
 * Thermal hysteresis (1.5 C) stops the boost from flapping on and off.
 */
object AfterburnLogic {
    const val LOW_BATTERY_EXTRA_MARGIN_C = 2f
    const val THERMAL_RAMP_C = 4f
    const val THERMAL_HYST_C = 1.5f
    const val BATTERY_CUTOFF_PCT = 5
    const val SAG_SOFT_MV = 150f
    const val SAG_HARD_MV = 400f

    fun decide(
        cfg: AfterburnConfig,
        appSelected: Boolean,
        tempC: Float,
        throttleStartC: Float,
        batteryPct: Int,
        pluggedOrCharging: Boolean,
        wasActive: Boolean,
        sagMv: Float = 0f
    ): AfterburnDecision {
        if (!cfg.enabled) return AfterburnDecision(0f, AfterburnState.OFF)
        if (!appSelected) return AfterburnDecision(0f, AfterburnState.IDLE)

        val margin = throttleStartC - tempC
        val battKnown = batteryPct in 0..100
        val battLow = cfg.batteryConditionEnabled && !pluggedOrCharging && battKnown &&
            batteryPct < cfg.batteryThresholdPct

        // Battery cutoff first (nothing left to spend).
        if (cfg.batteryConditionEnabled && !pluggedOrCharging && battKnown && batteryPct <= BATTERY_CUTOFF_PCT) {
            return AfterburnDecision(0f, AfterburnState.BLOCKED_BATTERY)
        }

        // Voltage sag: a weak or loaded cell sags hard under heavy draw, so spend less when it does.
        var sagScale = 1f
        if (cfg.voltageSagEnabled && !pluggedOrCharging && sagMv > SAG_SOFT_MV) {
            if (sagMv >= SAG_HARD_MV) return AfterburnDecision(0f, AfterburnState.BLOCKED_BATTERY)
            sagScale = (1f - 0.7f * ((sagMv - SAG_SOFT_MV) / (SAG_HARD_MV - SAG_SOFT_MV))).coerceIn(0.3f, 1f)
        }

        var thermalScale = 1f
        if (cfg.thermalConditionEnabled) {
            val required = cfg.thermalMarginC.coerceIn(3f, 8f) +
                (if (battLow) LOW_BATTERY_EXTRA_MARGIN_C else 0f)
            val needed = if (wasActive) required - THERMAL_HYST_C else required
            if (margin < needed) return AfterburnDecision(0f, AfterburnState.BLOCKED_THERMAL)
            val headroom = (margin - required).coerceAtLeast(0f)
            thermalScale = 0.5f + 0.5f * (headroom / THERMAL_RAMP_C).coerceIn(0f, 1f)
        }

        var batteryScale = 1f
        if (battLow) {
            val thr = cfg.batteryThresholdPct.coerceAtLeast(BATTERY_CUTOFF_PCT + 1)
            val frac = ((thr - batteryPct).toFloat() / (thr - BATTERY_CUTOFF_PCT).toFloat()).coerceIn(0f, 1f)
            batteryScale = 0.6f - 0.4f * frac // 0.6 at the threshold, 0.2 near the cutoff
        }

        val level = (cfg.strength.factor * thermalScale * batteryScale * sagScale).coerceIn(0f, 1f)
        val state = if (battLow || sagScale < 0.999f) AfterburnState.REDUCED else AfterburnState.BOOSTING
        return AfterburnDecision(level, state)
    }

    /** Moves a power cap toward 100% by the boost level (level 1 = cap fully bypassed). */
    fun relieveCap(capPct: Float, level: Float): Float {
        if (level <= 0f || capPct >= 100f) return capPct
        return (capPct + (100f - capPct) * level.coerceIn(0f, 1f)).coerceIn(0f, 100f)
    }
}
