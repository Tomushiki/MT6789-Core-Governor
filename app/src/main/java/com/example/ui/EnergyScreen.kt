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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.BatteryEstimatorUiSnapshot
import com.example.governor.ComponentFreqCurvePoint
import com.example.governor.ComponentPowerSummary
import com.example.governor.PowerModelUiSnapshot
import com.example.model.BatteryState
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
import com.example.model.GovernorConfig
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnergyScreen(
    snapshot: PowerModelUiSnapshot,
    batterySnapshot: BatteryEstimatorUiSnapshot,
    config: GovernorConfig,
    isRunning: Boolean,
    onUpdateSettings: (
        learningEnabled: Boolean?,
        efficiencyEnabled: Boolean?,
        efficiencyMode: EfficiencyMode?,
        minConfidencePct: Int?,
        explorationRate: ExplorationRate?
    ) -> Unit,
    onResetModel: () -> Unit,
    onExportJson: () -> String,
    onUpdateBatterySettings: (
        estimationEnabled: Boolean?,
        designCapacityOverrideMah: Int?
    ) -> Unit,
    onResetBatteryEstimate: () -> Unit,
    modifier: Modifier = Modifier,
    telemetryFlow: kotlinx.coroutines.flow.StateFlow<com.example.model.TelemetryState>? = null,
    batteryHistory: List<com.example.governor.BatteryHistoryPoint> = emptyList(),
    onClearBatteryHistory: () -> Unit = {},
    extraContent: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showResetBatteryDialog by remember { mutableStateOf(false) }
    var showDesignOverrideDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.energy_reset_model_title)) },
            text = { Text(stringResource(R.string.energy_reset_model_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetModel()
                        Toast.makeText(
                            context,
                            context.getString(R.string.energy_reset_model_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.testTag("confirm_reset_power_model")
                ) {
                    Text(stringResource(R.string.common_reset), color = ext.throttle)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showResetBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showResetBatteryDialog = false },
            title = { Text(stringResource(R.string.energy_reset_batt_title)) },
            text = { Text(stringResource(R.string.energy_reset_batt_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetBatteryDialog = false
                        onResetBatteryEstimate()
                        Toast.makeText(
                            context,
                            context.getString(R.string.energy_reset_batt_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.testTag("confirm_reset_battery_estimate")
                ) {
                    Text(stringResource(R.string.common_reset), color = ext.throttle)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetBatteryDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showDesignOverrideDialog) {
        var overrideInput by remember(config.batteryDesignCapacityOverrideMah) {
            mutableStateOf(
                if (config.batteryDesignCapacityOverrideMah > 0) {
                    config.batteryDesignCapacityOverrideMah.toString()
                } else {
                    ""
                }
            )
        }
        AlertDialog(
            onDismissRequest = { showDesignOverrideDialog = false },
            title = { Text(stringResource(R.string.energy_design_override_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.energy_design_override_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = overrideInput,
                        onValueChange = { raw ->
                            overrideInput = raw.filter { it.isDigit() }.take(6)
                        },
                        label = { Text(stringResource(R.string.energy_design_override_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_design_capacity_mah")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = overrideInput.trim().toIntOrNull()?.coerceIn(0, 30_000) ?: 0
                        onUpdateBatterySettings(null, parsed)
                        showDesignOverrideDialog = false
                    },
                    modifier = Modifier.testTag("confirm_design_capacity_override")
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            onUpdateBatterySettings(null, 0)
                            showDesignOverrideDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.energy_design_auto_btn))
                    }
                    TextButton(onClick = { showDesignOverrideDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Battery overview: plain-language status, history graphs, health, sessions (v4.2.0)
        item {
            BatteryOverview(
                batt = batterySnapshot,
                history = batteryHistory,
                nowMs = batteryHistory.lastOrNull()?.timeMs ?: System.currentTimeMillis()
            )
        }
        // 0b. The old detailed battery numbers, tucked away for people who want them
        item {
            var showTech by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showTech = !showTech },
                    modifier = Modifier.fillMaxWidth().testTag("toggle_battery_technical")
                ) {
                    Text(
                        if (showTech) stringResource(R.string.bo_tech_hide) else stringResource(R.string.bo_tech_show)
                    )
                }
                if (showTech) {
                    BatteryCard(
                        batt = batterySnapshot,
                        config = config,
                        telemetryFlow = telemetryFlow,
                        onToggleEstimation = { onUpdateBatterySettings(it, null) },
                        onEditDesignOverride = { showDesignOverrideDialog = true },
                        onResetBatteryEstimate = { showResetBatteryDialog = true }
                    )
                    TextButton(onClick = onClearBatteryHistory, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bo_clear_history), color = ext.throttle)
                    }
                }
            }
        }
        // 1. Model Status & Live Comparison Card
        item {
            val activeAccent = when {
                snapshot.backoffActive -> ext.warning
                config.efficiencyLearningEnabled -> ext.optimal
                config.powerModelLearningEnabled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            EcuCard(accentBorder = activeAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Eco,
                            contentDescription = stringResource(R.string.energy_model_cd),
                            tint = activeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.energy_model_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = when {
                            snapshot.learningPausedCharging && isRunning -> stringResource(R.string.energy_pill_paused_chg)
                            snapshot.backoffActive -> stringResource(R.string.energy_pill_backoff, snapshot.backoffRemainingSec)
                            config.efficiencyLearningEnabled && isRunning -> stringResource(R.string.energy_pill_eff_on)
                            config.efficiencyLearningEnabled -> stringResource(R.string.energy_pill_eff_armed)
                            config.powerModelLearningEnabled && isRunning -> stringResource(R.string.energy_pill_learning)
                            config.powerModelLearningEnabled -> stringResource(R.string.energy_pill_learn_ready)
                            else -> stringResource(R.string.energy_pill_paused)
                        },
                        color = if (snapshot.learningPausedCharging) ext.warning else activeAccent,
                        containerColor = (if (snapshot.learningPausedCharging) ext.warning else activeAccent).copy(alpha = 0.16f)
                    )
                }

                if (snapshot.learningPausedCharging) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.energy_paused_chg_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.warning
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricStatBlock(
                        label = stringResource(R.string.energy_stat_samples),
                        value = "${snapshot.totalSamples}",
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatBlock(
                        label = stringResource(R.string.energy_stat_confidence),
                        value = "${snapshot.overallConfidencePct.roundToInt()}%",
                        valueColor = if (snapshot.overallConfidencePct >= config.minConfidencePct) ext.optimal else ext.warning,
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatBlock(
                        label = stringResource(R.string.energy_stat_scenarios),
                        value = "${snapshot.scenariosLearned}/24",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.energy_est_vs_meas),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val measText = if (snapshot.batteryState.isPluggedOrCharging) {
                            stringResource(R.string.energy_meas_chg)
                        } else {
                            "${"%.2f".format(Locale.US, snapshot.liveMeasuredMw / 1000f)} W"
                        }
                        val estText = "%.2f".format(Locale.US, snapshot.liveEstimatedMw / 1000f)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = stringResource(R.string.energy_est_vs_meas_val, estText, measText),
                                style = MonoMetricStyle.copy(
                                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val errText = if (!snapshot.batteryState.isPluggedOrCharging && snapshot.liveMeasuredMw > 50f) {
                            stringResource(R.string.energy_err_pct, snapshot.liveErrorPct.roundToInt())
                        } else "--"
                        Text(
                            text = errText,
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                            color = if (snapshot.liveErrorPct <= 15f) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (config.efficiencyLearningEnabled && snapshot.efficiencySavingsPct >= 0.5f) {
                            Text(
                                text = stringResource(R.string.energy_est_saved, snapshot.efficiencySavingsPct.roundToInt()),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = ext.optimal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                val savedLabel = if (snapshot.lastSavedMs > 0L) {
                    timeFormatter.format(Date(snapshot.lastSavedMs))
                } else stringResource(R.string.energy_not_yet)
                val skipNote = if (snapshot.samplesSkippedCharging > 0L) {
                    stringResource(R.string.energy_skipped_chg, snapshot.samplesSkippedCharging)
                } else ""
                Text(
                    text = stringResource(
                        R.string.energy_scenario_line,
                        snapshot.activeScenario,
                        snapshot.baselineMw.roundToInt(),
                        savedLabel,
                        skipNote
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Controls Card
        item {
            EcuCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.energy_eff_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.energy_eff_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.energy_learn_title),
                    explanation = stringResource(R.string.energy_learn_desc),
                    checked = config.powerModelLearningEnabled,
                    onCheckedChange = { onUpdateSettings(it, null, null, null, null) },
                    testTag = "switch_learn_power_model"
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.energy_eff_title),
                    explanation = stringResource(R.string.energy_eff_desc),
                    checked = config.efficiencyLearningEnabled,
                    onCheckedChange = { onUpdateSettings(null, it, null, null, null) },
                    testTag = "switch_efficiency_governor"
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.energy_opt_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EfficiencyMode.entries.forEach { mode ->
                        val label = when (mode) {
                            EfficiencyMode.BALANCED -> stringResource(R.string.energy_mode_balanced)
                            EfficiencyMode.AGGRESSIVE_SAVER -> stringResource(R.string.energy_mode_saver)
                            EfficiencyMode.PERFORMANCE_FIRST -> stringResource(R.string.energy_mode_perf)
                        }
                        FilterChip(
                            selected = config.efficiencyMode == mode,
                            onClick = { onUpdateSettings(null, null, mode, null, null) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("chip_eff_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                SettingSliderRow(
                    title = stringResource(R.string.energy_min_conf),
                    valueText = "${config.minConfidencePct}%",
                    explanation = stringResource(R.string.energy_min_conf_desc),
                    value = config.minConfidencePct.toFloat(),
                    valueRange = 20f..95f,
                    steps = 14,
                    onValueChange = { raw ->
                        val snapped = (raw / 5f).roundToInt() * 5
                        onUpdateSettings(null, null, null, snapped, null)
                    },
                    testTag = "slider_min_confidence"
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.energy_explore_rate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.energy_explore_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExplorationRate.entries.forEach { rate ->
                        val label = when (rate) {
                            ExplorationRate.LOW -> stringResource(R.string.energy_explore_low)
                            ExplorationRate.MEDIUM -> stringResource(R.string.energy_explore_med)
                            ExplorationRate.HIGH -> stringResource(R.string.energy_explore_high)
                        }
                        FilterChip(
                            selected = config.explorationRate == rate,
                            onClick = { onUpdateSettings(null, null, null, null, rate) },
                            label = { Text(label) },
                            enabled = config.efficiencyLearningEnabled,
                            modifier = Modifier.testTag("chip_explore_${rate.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val json = onExportJson()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("CoreGovernor Power Model JSON", json))
                            Toast.makeText(
                                context,
                                context.getString(R.string.energy_model_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_export_power_model")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.energy_export_json))
                    }
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.throttle),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_reset_power_model")
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.energy_reset_model_btn))
                    }
                }
            }
        }

        // 3. Per-Component Power vs Frequency Curves
        if (snapshot.components.isEmpty()) {
            item {
                EcuCard {
                    Text(
                        text = stringResource(R.string.energy_no_hw),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(snapshot.components, key = { it.componentId }) { comp ->
                ComponentPowerCard(
                    comp = comp,
                    minConfidencePct = config.minConfidencePct
                )
            }
        }

        extraContent?.invoke(this)
    }
}

private fun formatMinutesEta(totalMin: Int): String {
    if (totalMin < 0) return "--"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun BatteryCard(
    batt: BatteryEstimatorUiSnapshot,
    config: GovernorConfig,
    telemetryFlow: kotlinx.coroutines.flow.StateFlow<com.example.model.TelemetryState>? = null,
    onToggleEstimation: (Boolean) -> Unit,
    onEditDesignOverride: () -> Unit,
    onResetBatteryEstimate: () -> Unit
) {
    val ext = LocalExtendedColors.current
    val stateAccent = when (batt.batteryState) {
        BatteryState.CHARGING, BatteryState.FULL_PLUGGED -> ext.optimal
        BatteryState.DISCHARGING -> MaterialTheme.colorScheme.primary
        BatteryState.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val stateLabel = when (batt.batteryState) {
        BatteryState.CHARGING -> stringResource(R.string.batt_state_charging)
        BatteryState.FULL_PLUGGED -> stringResource(R.string.batt_state_plugged_full)
        BatteryState.DISCHARGING -> stringResource(R.string.batt_state_discharging)
        BatteryState.UNKNOWN -> stringResource(R.string.batt_state_idle)
    }
    val pctLabel = if (batt.batteryPct in 0..100) "${batt.batteryPct}%" else "--%"

    EcuCard(accentBorder = stateAccent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = stringResource(R.string.batt_estimator_cd),
                    tint = stateAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.batt_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                label = "$pctLabel • $stateLabel",
                color = stateAccent,
                containerColor = stateAccent.copy(alpha = 0.16f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val capText = if (batt.combinedCapacityMah > 0f) {
            "${batt.combinedCapacityMah.roundToInt()} mAh"
        } else "--"
        val whText = if (batt.combinedCapacityWh > 0f) {
            "${"%.1f".format(Locale.US, batt.combinedCapacityWh)} Wh"
        } else "--"
        val sohText = if (batt.sohPct > 0f) {
            "${"%.1f".format(Locale.US, batt.sohPct)}%"
        } else "--"
        val sohColor = when {
            batt.sohPct <= 0f -> MaterialTheme.colorScheme.onSurfaceVariant
            batt.sohPct >= 85f -> ext.optimal
            batt.sohPct >= 75f -> ext.warning
            else -> ext.throttle
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricStatBlock(
                label = stringResource(R.string.batt_stat_est_cap),
                value = capText,
                modifier = Modifier.weight(1.1f)
            )
            MetricStatBlock(
                label = stringResource(R.string.batt_stat_energy),
                value = whText,
                modifier = Modifier.weight(0.8f)
            )
            MetricStatBlock(
                label = stringResource(R.string.energy_stat_confidence),
                value = if (batt.totalValidSegments > 0) "${batt.confidencePct}%" else "--",
                valueColor = if (batt.confidencePct >= 50) ext.optimal else ext.warning,
                modifier = Modifier.weight(0.8f)
            )
            MetricStatBlock(
                label = stringResource(R.string.batt_stat_soh),
                value = sohText,
                valueColor = sohColor,
                modifier = Modifier.weight(0.75f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (batt.totalValidSegments == 0) {
            val dirWord = if (batt.activeSegmentState == BatteryState.CHARGING || batt.batteryState == BatteryState.CHARGING) {
                stringResource(R.string.batt_dir_charge)
            } else {
                stringResource(R.string.batt_dir_discharge)
            }
            val progressNote = if (batt.activeSegmentDeltaPct > 0) {
                stringResource(R.string.batt_collecting_progress, batt.activeSegmentDeltaPct, batt.neededPctToEstimate)
            } else {
                ""
            }
            Text(
                text = stringResource(R.string.batt_collecting_info, batt.neededPctToEstimate, dirWord, progressNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val disText = if (batt.dischargeSegmentCount > 0) {
                stringResource(R.string.batt_seg_item, batt.dischargeCapacityMah.roundToInt(), batt.dischargeSegmentCount)
            } else "--"
            val chgText = if (batt.chargeSegmentCount > 0) {
                stringResource(R.string.batt_seg_item, batt.chargeCapacityMah.roundToInt(), batt.chargeSegmentCount)
            } else "--"
            Text(
                text = stringResource(R.string.batt_est_summary, disText, chgText),
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (batt.hasDirectionDisagreement) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.batt_disagree_note, batt.directionDisagreementPct.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.warning
                )
            }
            Text(
                text = stringResource(R.string.batt_net_current_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (batt.chargeCounterEstimateMah > 0f) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.batt_gauge_opinion,
                    batt.chargeCounterEstimateMah.roundToInt(),
                    batt.chargeCounterMah.roundToInt(),
                    pctLabel
                ),
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(10.dp))

        val livePwrText = when (batt.batteryState) {
            BatteryState.CHARGING -> stringResource(R.string.batt_live_in, "%.2f".format(Locale.US, batt.livePowerInWatts))
            BatteryState.FULL_PLUGGED -> stringResource(R.string.monitor_plug_val)
            BatteryState.DISCHARGING -> stringResource(R.string.batt_live_out, "%.2f".format(Locale.US, batt.livePowerOutWatts))
            BatteryState.UNKNOWN -> "--"
        }
        val remWhText = if (batt.remainingWh > 0f) "${"%.1f".format(Locale.US, batt.remainingWh)} Wh" else "--"
        val etaText = when {
            batt.batteryState == BatteryState.CHARGING && batt.timeToFullMin >= 0 ->
                stringResource(R.string.batt_eta_full, formatMinutesEta(batt.timeToFullMin))
            batt.batteryState == BatteryState.DISCHARGING && batt.timeRemainingMin >= 0 ->
                stringResource(R.string.batt_eta_left, formatMinutesEta(batt.timeRemainingMin))
            else -> "--"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricStatBlock(label = stringResource(R.string.batt_stat_live_power), value = livePwrText, modifier = Modifier.weight(1f))
            MetricStatBlock(label = stringResource(R.string.batt_stat_remaining), value = remWhText, modifier = Modifier.weight(0.8f))
            MetricStatBlock(label = stringResource(R.string.batt_stat_est_time), value = etaText, modifier = Modifier.weight(0.95f))
        }

        Spacer(modifier = Modifier.height(8.dp))
        val designStr = when {
            config.batteryDesignCapacityOverrideMah > 0 ->
                stringResource(R.string.batt_design_manual, config.batteryDesignCapacityOverrideMah)
            batt.designCapacityMah > 0 -> "${batt.designCapacityMah} mAh"
            batt.sysfsDesignCapacityMah > 0 ->
                stringResource(R.string.batt_design_wrong, batt.sysfsDesignCapacityMah)
            else -> "--"
        }
        val cyclesStr = if (batt.cycleCount >= 0) "${batt.cycleCount}" else "--"
        val tempStr = if (batt.batteryTempC != 0f) "${"%.1f".format(Locale.US, batt.batteryTempC)}°C" else "--"
        val voltStr = if (batt.batteryVoltageMv > 0f) "${"%.2f".format(Locale.US, batt.batteryVoltageMv / 1000f)}V" else "--"
        val kernelFullNote = if (batt.sysfsFullCapacityMah > 0) stringResource(R.string.batt_kernel_full, batt.sysfsFullCapacityMah) else ""
        val healthLabel = if (batt.batteryHealthLabel == "n/a") stringResource(R.string.common_na) else batt.batteryHealthLabel
        Text(
            text = stringResource(R.string.batt_design_cycles, designStr, kernelFullNote, cyclesStr),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.batt_temp_volt_health, tempStr, voltStr, healthLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (telemetryFlow != null) {
            BatteryTruthLine(telemetryFlow)
        }

        Spacer(modifier = Modifier.height(6.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = stringResource(
                    R.string.batt_session_line,
                    String.format(Locale.US, "%.2f", batt.sessionWhOut),
                    String.format(Locale.US, "%.0f", batt.sessionMahOut),
                    String.format(Locale.US, "%.2f", batt.sessionWhIn),
                    String.format(Locale.US, "%.0f", batt.sessionMahIn)
                ),
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    R.string.batt_lifetime_line,
                    String.format(Locale.US, "%.1f", batt.lifetimeWhOut),
                    String.format(Locale.US, "%.0f", batt.lifetimeMahOut),
                    String.format(Locale.US, "%.1f", batt.lifetimeWhIn),
                    String.format(Locale.US, "%.0f", batt.lifetimeMahIn)
                ),
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (batt.chargeBandLifetimeSec.any { it >= 1f }) {
            Spacer(modifier = Modifier.height(8.dp))
            val bandLabels = listOf("<5 W", "5-10 W", "10-15 W", "15-20 W", "20-25 W", "25+ W")
            fun bandText(secs: List<Float>): String =
                secs.indices.filter { secs[it] >= 1f && it < bandLabels.size }
                    .joinToString("  •  ") { "${bandLabels[it]} ${formatChargeBandTime(secs[it])}" }
                    .ifEmpty { "--" }
            Text(
                text = stringResource(R.string.batt_chg_bands_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = stringResource(R.string.batt_chg_bands_session, bandText(batt.chargeBandSessionSec)),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.batt_chg_bands_lifetime, bandText(batt.chargeBandLifetimeSec)),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val peakVoltText = if (batt.peakChargeVoltageMv > 0f) {
                String.format(Locale.US, "%.2f V", batt.peakChargeVoltageMv / 1000f)
            } else "--"
            val trustText = if (batt.chargeTrustPct >= 0) "${batt.chargeTrustPct}%" else "--"
            Text(
                text = stringResource(R.string.batt_chg_peak_trust, peakVoltText, trustText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (batt.recentSegments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.batt_recent_segments, batt.recentSegments.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                batt.recentSegments.take(4).forEach { seg ->
                    val dirTag = if (seg.direction == BatteryState.DISCHARGING) {
                        stringResource(R.string.batt_seg_dir_disch)
                    } else {
                        stringResource(R.string.batt_seg_dir_chg)
                    }
                    val durMin = (seg.durationMs / 60_000L).coerceAtLeast(1L).toInt()
                    Text(
                        text = stringResource(
                            R.string.batt_seg_line,
                            dirTag,
                            seg.startPct,
                            seg.endPct,
                            durMin,
                            String.format(Locale.US, "%.0f", seg.estCapacityMah),
                            String.format(Locale.US, "%.1f", seg.estCapacityWh)
                        ),
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (seg.direction == BatteryState.CHARGING && seg.avgWatts > 0f) {
                        Text(
                            text = stringResource(
                                R.string.batt_seg_chg_extra,
                                String.format(Locale.US, "%.1f", seg.avgWatts),
                                String.format(Locale.US, "%.2f", seg.peakVoltageMv / 1000f)
                            ),
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.batt_est_switch_title),
            explanation = stringResource(R.string.batt_est_switch_desc),
            checked = config.batteryEstimationEnabled,
            onCheckedChange = onToggleEstimation,
            testTag = "switch_battery_estimation"
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onEditDesignOverride,
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_edit_design_capacity")
            ) {
                Text(
                    text = if (config.batteryDesignCapacityOverrideMah > 0) {
                        stringResource(R.string.batt_design_btn_set, config.batteryDesignCapacityOverrideMah)
                    } else {
                        stringResource(R.string.batt_design_btn_auto)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onResetBatteryEstimate,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.throttle),
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_reset_battery_estimate")
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.batt_reset_est_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetricStatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = MonoMetricStyle.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    fontWeight = FontWeight.Bold
                ),
                color = valueColor
            )
        }
    }
}

@Composable
private fun ComponentPowerCard(
    comp: ComponentPowerSummary,
    minConfidencePct: Int
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val accent = if (comp.componentId == "gpu") ext.gpuAccent else primary
    val confident = comp.liveConfidencePct >= minConfidencePct

    EcuCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = comp.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val liveLine = if (comp.liveFreqMhz > 0) {
                    stringResource(
                        R.string.comp_live_line,
                        comp.liveFreqMhz,
                        comp.liveUtilPct.roundToInt(),
                        comp.livePredictedMw.roundToInt()
                    )
                } else {
                    stringResource(R.string.comp_idle_sampling)
                }
                Text(
                    text = liveLine,
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                label = stringResource(R.string.comp_conf_pill, comp.liveConfidencePct.roundToInt()),
                color = if (confident) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = if (confident) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (comp.curvePoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PowerCurveChart(
                    points = comp.curvePoints,
                    minConfidencePct = minConfidencePct,
                    accentColor = accent,
                    gridColor = ext.chartGrid
                )
                Spacer(modifier = Modifier.height(8.dp))
                comp.curvePoints.forEach { pt ->
                    val rowAlpha = if (pt.confidencePct >= minConfidencePct) 1f else 0.52f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(rowAlpha)
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${pt.freqMhz} MHz",
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${pt.predictedMw.roundToInt()} mW",
                            style = MonoMetricStyle.copy(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = accent
                        )
                        Text(
                            text = "${pt.confidencePct.roundToInt()}% (${pluralStringResource(R.plurals.plural_samples, pt.samples.toInt(), pt.samples.toInt())})",
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerCurveChart(
    points: List<ComponentFreqCurvePoint>,
    minConfidencePct: Int,
    accentColor: Color,
    gridColor: Color
) {
    if (points.size < 2) return
    val maxMw = (points.maxOfOrNull { it.predictedMw } ?: 1000f).coerceAtLeast(300f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val y = size.height * frac
            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { idx, pt ->
            val x = idx * stepX
            val y = size.height * (1f - (pt.predictedMw / maxMw).coerceIn(0f, 1f))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = accentColor.copy(alpha = 0.7f),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
        points.forEachIndexed { idx, pt ->
            val x = idx * stepX
            val y = size.height * (1f - (pt.predictedMw / maxMw).coerceIn(0f, 1f))
            val dotAlpha = if (pt.confidencePct >= minConfidencePct) 1f else 0.35f
            drawCircle(
                color = accentColor.copy(alpha = dotAlpha),
                radius = if (pt.confidencePct >= minConfidencePct) 5.5f else 4f,
                center = Offset(x, y)
            )
        }
    }
}


/** v3.1.0: real battery level graded from voltage and current behaviour (own composable so only this line redraws). */
@Composable
private fun BatteryTruthLine(telemetryFlow: kotlinx.coroutines.flow.StateFlow<com.example.model.TelemetryState>) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    val ext = LocalExtendedColors.current
    val line1 = if (t.truePct >= 0f) {
        val head = t.trueHeadroomPct
        val tag = when {
            head >= 3f -> stringResource(R.string.batt_truth_headroom, head.roundToInt())
            head <= -3f -> stringResource(R.string.batt_truth_below, head.roundToInt())
            else -> stringResource(R.string.batt_truth_matches)
        }
        stringResource(R.string.batt_truth_real_level, t.truePct.roundToInt(), tag)
    } else {
        stringResource(R.string.batt_truth_grading)
    }
    Text(
        text = line1,
        style = MaterialTheme.typography.bodySmall,
        color = if (t.truePct >= 0f && t.trueHeadroomPct >= 3f) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(
            R.string.batt_truth_diag,
            t.batteryChemistry,
            t.batteryResistanceMohm.roundToInt(),
            t.batterySagMv.roundToInt(),
            t.truthConfidencePct
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val calText = when {
        t.calChargeDone && t.calDischargeDone -> {
            val mahSuffix = if (t.fullChargeMah > 0f) {
                stringResource(R.string.batt_truth_cal_charged_mah, t.fullChargeMah.roundToInt())
            } else ""
            stringResource(R.string.batt_truth_cal_both, t.fullChargeMv.roundToInt(), mahSuffix)
        }
        t.calRunning == "charge" -> stringResource(R.string.batt_truth_cal_running_chg)
        t.calRunning == "discharge" -> stringResource(R.string.batt_truth_cal_running_disch)
        t.calChargeDone -> stringResource(R.string.batt_truth_cal_chg_done, t.fullChargeMv.roundToInt())
        t.calDischargeDone -> stringResource(R.string.batt_truth_cal_disch_done)
        else -> stringResource(R.string.batt_truth_cal_none)
    }
    Text(
        text = calText,
        style = MaterialTheme.typography.labelSmall,
        color = if (t.calChargeDone && t.calDischargeDone) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Compact duration for the charge-power-band lines: 45s, 12m, 1h 05m. */
private fun formatChargeBandTime(sec: Float): String {
    val total = sec.toInt().coerceAtLeast(0)
    return when {
        total < 60 -> "${total}s"
        total < 3600 -> "${total / 60}m"
        else -> String.format(Locale.US, "%dh %02dm", total / 3600, (total % 3600) / 60)
    }
}
