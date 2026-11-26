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

package com.example.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.governor.ComparisonMetricKind
import com.example.governor.ComparisonWarning
import com.example.governor.ImpactConfidence
import com.example.governor.SessionTweakSnapshot

@StringRes
fun snapshotFieldLabelRes(key: String): Int = when (key) {
    SessionTweakSnapshot.KEY_CPU_HEADROOM -> R.string.snap_cpu_headroom
    SessionTweakSnapshot.KEY_CPU_RAMP_UP -> R.string.snap_cpu_ramp_up
    SessionTweakSnapshot.KEY_CPU_RAMP_DOWN -> R.string.snap_cpu_ramp_down
    SessionTweakSnapshot.KEY_GPU_HEADROOM -> R.string.snap_gpu_headroom
    SessionTweakSnapshot.KEY_GPU_RAMP_UP -> R.string.snap_gpu_ramp_up
    SessionTweakSnapshot.KEY_GPU_RAMP_DOWN -> R.string.snap_gpu_ramp_down
    SessionTweakSnapshot.KEY_CPU_RANGES -> R.string.snap_cpu_ranges
    SessionTweakSnapshot.KEY_GPU_RANGE -> R.string.snap_gpu_range
    SessionTweakSnapshot.KEY_TEMP_THROTTLE -> R.string.snap_temp_throttle
    SessionTweakSnapshot.KEY_TEMP_REDUCTION -> R.string.snap_temp_reduction
    SessionTweakSnapshot.KEY_TEMP_CURVE -> R.string.snap_temp_curve
    SessionTweakSnapshot.KEY_TEMP_REACTION -> R.string.snap_temp_reaction
    SessionTweakSnapshot.KEY_POWER_LIMIT -> R.string.snap_power_limit
    SessionTweakSnapshot.KEY_POWER_TIMING -> R.string.snap_power_timing
    SessionTweakSnapshot.KEY_SMOOTH_POWER -> R.string.snap_smooth_power
    SessionTweakSnapshot.KEY_AFTERBURN -> R.string.snap_afterburn
    SessionTweakSnapshot.KEY_RAM_PIN -> R.string.snap_ram_pin
    SessionTweakSnapshot.KEY_STUTTER_HUNTER -> R.string.snap_stutter_hunter
    SessionTweakSnapshot.KEY_GAME_THREADS -> R.string.snap_game_threads
    SessionTweakSnapshot.KEY_ADAPTIVE_REFRESH -> R.string.snap_adaptive_refresh
    SessionTweakSnapshot.KEY_PER_GAME_OVERRIDE -> R.string.snap_per_game_override
    SessionTweakSnapshot.KEY_AUTO_TUNE -> R.string.snap_auto_tune
    else -> R.string.snap_unknown
}

@StringRes
fun comparisonMetricLabelRes(kind: ComparisonMetricKind): Int = when (kind) {
    ComparisonMetricKind.SCORE -> R.string.cmp_metric_score
    ComparisonMetricKind.AVG_FPS -> R.string.cmp_metric_avg_fps
    ComparisonMetricKind.P5_FPS -> R.string.cmp_metric_p5_fps
    ComparisonMetricKind.MIN_FPS -> R.string.cmp_metric_min_fps
    ComparisonMetricKind.FPS_PER_WATT -> R.string.cmp_metric_fpw
    ComparisonMetricKind.WORK_PER_WATT -> R.string.cmp_metric_wpw
    ComparisonMetricKind.AVG_TEMP -> R.string.cmp_metric_avg_temp
    ComparisonMetricKind.MAX_TEMP -> R.string.cmp_metric_max_temp
    ComparisonMetricKind.AVG_WATTS -> R.string.cmp_metric_avg_watts
    ComparisonMetricKind.PEAK_WATTS -> R.string.cmp_metric_peak_watts
    ComparisonMetricKind.ENERGY_WH -> R.string.cmp_metric_energy
    ComparisonMetricKind.STABILITY_PCT -> R.string.cmp_metric_stability
}

@StringRes
fun comparisonWarningLabelRes(warning: ComparisonWarning): Int = when (warning) {
    ComparisonWarning.DIFFERENT_GAMES -> R.string.cmp_warn_diff_games
    ComparisonWarning.INCLUDES_CHARGING -> R.string.cmp_warn_charging
    ComparisonWarning.DURATION_MISMATCH -> R.string.cmp_warn_duration
    ComparisonWarning.SHORT_SESSION -> R.string.cmp_warn_short
    ComparisonWarning.MID_SESSION_CHANGES -> R.string.cmp_warn_mid_changes
    ComparisonWarning.MISSING_SNAPSHOT -> R.string.cmp_warn_no_snapshot
}

@StringRes
fun impactConfidenceLabelRes(conf: ImpactConfidence): Int = when (conf) {
    ImpactConfidence.LOW -> R.string.rating_conf_low
    ImpactConfidence.MEDIUM -> R.string.rating_conf_medium
    ImpactConfidence.HIGH -> R.string.rating_conf_high
}

@Composable
fun localizedSnapshotValue(raw: String): String = when (raw) {
    "Not recorded" -> stringResource(R.string.snap_not_recorded)
    "Off" -> stringResource(R.string.common_off)
    "On" -> stringResource(R.string.common_on)
    "Yes" -> stringResource(R.string.common_yes)
    "Global" -> stringResource(R.string.snap_val_global)
    "Active" -> stringResource(R.string.snap_val_active)
    "Game only" -> stringResource(R.string.snap_val_game_only)
    "Always" -> stringResource(R.string.snap_val_always)
    else -> raw
}

@Composable
fun autoTuneReasonText(code: String): String = when (code) {
    "baseline_recorded" -> stringResource(R.string.autotune_reason_baseline)
    "suggest_try_next" -> stringResource(R.string.autotune_reason_suggest)
    "beat_best_margin" -> stringResource(R.string.autotune_reason_kept)
    "below_margin" -> stringResource(R.string.autotune_reason_reverted)
    "ignored_charging" -> stringResource(R.string.autotune_reason_ignored_charging)
    "ignored_short_samples" -> stringResource(R.string.autotune_reason_ignored_samples)
    "ignored_short_duration" -> stringResource(R.string.autotune_reason_ignored_duration)
    "ignored_mid_changes" -> stringResource(R.string.autotune_reason_ignored_mid)
    "ignored_no_fps" -> stringResource(R.string.autotune_reason_ignored_fps)
    "safety_thermal_limit" -> stringResource(R.string.autotune_reason_safety_temp)
    "safety_power_throttle" -> stringResource(R.string.autotune_reason_safety_power)
    "safety_p5_collapse" -> stringResource(R.string.autotune_reason_safety_p5)
    else -> code
}
