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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            title = { Text("Reset learned power model?") },
            text = {
                Text("This clears all collected frequency power estimates and per-app scenarios. Re-learning takes a few hours of normal and gaming use.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetModel()
                        Toast.makeText(context, "Power model reset", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("confirm_reset_power_model")
                ) {
                    Text("Reset", color = ext.throttle)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showResetBatteryDialog = false },
            title = { Text("Reset battery estimate?") },
            text = {
                Text("This clears the learned discharge/charge capacity segments and restarts battery capacity estimation from scratch.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetBatteryDialog = false
                        onResetBatteryEstimate()
                        Toast.makeText(context, "Battery estimate reset", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("confirm_reset_battery_estimate")
                ) {
                    Text("Reset", color = ext.throttle)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetBatteryDialog = false }) {
                    Text("Cancel")
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
            title = { Text("Design capacity override (mAh)") },
            text = {
                Column {
                    Text(
                        text = "Enter your battery's rated design capacity in mAh, or leave 0 / empty for auto-detection from sysfs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = overrideInput,
                        onValueChange = { raw ->
                            overrideInput = raw.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("Design mAh (0 = auto)") },
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
                    Text("Save")
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
                        Text("Auto (0)")
                    }
                    TextButton(onClick = { showDesignOverrideDialog = false }) {
                        Text("Cancel")
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
        // 0. Battery Card (capacity, SoH, health, energy totals & estimation controls)
        item {
            BatteryCard(
                batt = batterySnapshot,
                config = config,
                onToggleEstimation = { onUpdateBatterySettings(it, null) },
                onEditDesignOverride = { showDesignOverrideDialog = true },
                onResetBatteryEstimate = { showResetBatteryDialog = true }
            )
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
                            contentDescription = "Power model status",
                            tint = activeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ON-DEVICE POWER MODEL",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = when {
                            snapshot.learningPausedCharging && isRunning -> "PAUSED: CHARGING"
                            snapshot.backoffActive -> "BACKOFF ${snapshot.backoffRemainingSec}s"
                            config.efficiencyLearningEnabled && isRunning -> "EFFICIENCY ON"
                            config.efficiencyLearningEnabled -> "EFFICIENCY ARMED"
                            config.powerModelLearningEnabled && isRunning -> "LEARNING"
                            config.powerModelLearningEnabled -> "LEARN READY"
                            else -> "PAUSED"
                        },
                        color = if (snapshot.learningPausedCharging) ext.warning else activeAccent,
                        containerColor = (if (snapshot.learningPausedCharging) ext.warning else activeAccent).copy(alpha = 0.16f)
                    )
                }

                if (snapshot.learningPausedCharging) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Learning paused: charging (settled discharging samples only)",
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
                        label = "SAMPLES",
                        value = "${snapshot.totalSamples}",
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatBlock(
                        label = "CONFIDENCE",
                        value = "${snapshot.overallConfidencePct.roundToInt()}%",
                        valueColor = if (snapshot.overallConfidencePct >= config.minConfidencePct) ext.optimal else ext.warning,
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatBlock(
                        label = "SCENARIOS",
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
                            text = "Estimated vs Measured",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val measText = if (snapshot.batteryState.isPluggedOrCharging) {
                            "0.00 W (chg)"
                        } else {
                            "${"%.2f".format(Locale.US, snapshot.liveMeasuredMw / 1000f)} W"
                        }
                        Text(
                            text = "${"%.2f".format(Locale.US, snapshot.liveEstimatedMw / 1000f)} W  vs  $measText",
                            style = MonoMetricStyle.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val errText = if (!snapshot.batteryState.isPluggedOrCharging && snapshot.liveMeasuredMw > 50f) {
                            "${snapshot.liveErrorPct.roundToInt()}% err"
                        } else "--"
                        Text(
                            text = errText,
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                            color = if (snapshot.liveErrorPct <= 15f) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (config.efficiencyLearningEnabled && snapshot.efficiencySavingsPct >= 0.5f) {
                            Text(
                                text = "Est. saved ~${snapshot.efficiencySavingsPct.roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = ext.optimal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                val savedLabel = if (snapshot.lastSavedMs > 0L) {
                    timeFormatter.format(Date(snapshot.lastSavedMs))
                } else "not yet"
                val skipNote = if (snapshot.samplesSkippedCharging > 0L) {
                    " • Skipped (chg): ${snapshot.samplesSkippedCharging}"
                } else ""
                Text(
                    text = "Scenario: ${snapshot.activeScenario} • Baseline: ${snapshot.baselineMw.roundToInt()} mW • Saved: $savedLabel$skipNote",
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
                        contentDescription = "Efficiency controls",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "EFFICIENCY GOVERNOR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "Learn power model",
                    explanation = "Passively learns device power vs frequency and load in the background while discharging. Takes hours of normal + game use for high confidence.",
                    checked = config.powerModelLearningEnabled,
                    onCheckedChange = { onUpdateSettings(it, null, null, null, null) },
                    testTag = "switch_learn_power_model"
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "Efficiency governor (opt-in)",
                    explanation = "Uses learned power/perf curves to pick lower-energy frequencies within your allowed min/max and thermal/power caps once confident.",
                    checked = config.efficiencyLearningEnabled,
                    onCheckedChange = { onUpdateSettings(null, it, null, null, null) },
                    testTag = "switch_efficiency_governor"
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Optimization Mode",
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
                            EfficiencyMode.BALANCED -> "Balanced"
                            EfficiencyMode.AGGRESSIVE_SAVER -> "Aggressive saver"
                            EfficiencyMode.PERFORMANCE_FIRST -> "Performance-first"
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
                    title = "Minimum model confidence",
                    valueText = "${config.minConfidencePct}%",
                    explanation = "Standard scaling is kept for any frequency bucket below this confidence threshold.",
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
                    text = "Exploration Rate",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Occasional safe adjacent-step probes while Efficiency governor is active.",
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
                            ExplorationRate.LOW -> "Low"
                            ExplorationRate.MEDIUM -> "Medium"
                            ExplorationRate.HIGH -> "High"
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
                            Toast.makeText(context, "Power model JSON copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_export_power_model")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON")
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
                        Text("Reset model")
                    }
                }
            }
        }

        // 3. Per-Component Power vs Frequency Curves
        if (snapshot.components.isEmpty()) {
            item {
                EcuCard {
                    Text(
                        text = "Hardware components not discovered yet. Start the governor or run discovery on Monitor to inspect learned power curves.",
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
        BatteryState.CHARGING -> "CHARGING"
        BatteryState.FULL_PLUGGED -> "PLUGGED FULL"
        BatteryState.DISCHARGING -> "DISCHARGING"
        BatteryState.UNKNOWN -> "IDLE"
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
                    contentDescription = "Battery estimator",
                    tint = stateAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BATTERY",
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
                label = "EST. CAPACITY",
                value = capText,
                modifier = Modifier.weight(1.1f)
            )
            MetricStatBlock(
                label = "ENERGY",
                value = whText,
                modifier = Modifier.weight(0.8f)
            )
            MetricStatBlock(
                label = "CONFIDENCE",
                value = if (batt.totalValidSegments > 0) "${batt.confidencePct}%" else "--",
                valueColor = if (batt.confidencePct >= 50) ext.optimal else ext.warning,
                modifier = Modifier.weight(0.8f)
            )
            MetricStatBlock(
                label = "SoH",
                value = sohText,
                valueColor = sohColor,
                modifier = Modifier.weight(0.75f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (batt.totalValidSegments == 0) {
            val dirWord = if (batt.activeSegmentState == BatteryState.CHARGING || batt.batteryState == BatteryState.CHARGING) {
                "charge"
            } else {
                "discharge"
            }
            val progressNote = if (batt.activeSegmentDeltaPct > 0) {
                " (${batt.activeSegmentDeltaPct}%/${batt.neededPctToEstimate}% captured so far)"
            } else {
                ""
            }
            Text(
                text = "Collecting data: needs about ${batt.neededPctToEstimate}% uninterrupted $dirWord (\u22653 min) for initial estimate$progressNote.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val disText = if (batt.dischargeSegmentCount > 0) {
                "${batt.dischargeCapacityMah.roundToInt()} mAh (${batt.dischargeSegmentCount} seg)"
            } else "--"
            val chgText = if (batt.chargeSegmentCount > 0) {
                "${batt.chargeCapacityMah.roundToInt()} mAh (${batt.chargeSegmentCount} seg)"
            } else "--"
            Text(
                text = "Discharge est: $disText • Charge est: $chgText",
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (batt.hasDirectionDisagreement) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Discharge vs charge differ by ${batt.directionDisagreementPct.roundToInt()}% (>12%); charger efficiency or gauge drift may vary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.warning
                )
            }
            Text(
                text = "Uses battery-side net current (screen, idle, and charger/load balance included).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (batt.chargeCounterEstimateMah > 0f) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Gauge second opinion: ~${batt.chargeCounterEstimateMah.roundToInt()} mAh (${batt.chargeCounterMah.roundToInt()} mAh left at $pctLabel)",
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(10.dp))

        val livePwrText = when (batt.batteryState) {
            BatteryState.CHARGING -> "\u26A1+${"%.2f".format(Locale.US, batt.livePowerInWatts)} W in"
            BatteryState.FULL_PLUGGED -> "PLUG 0.0 W"
            BatteryState.DISCHARGING -> "-${"%.2f".format(Locale.US, batt.livePowerOutWatts)} W out"
            BatteryState.UNKNOWN -> "--"
        }
        val remWhText = if (batt.remainingWh > 0f) "${"%.1f".format(Locale.US, batt.remainingWh)} Wh" else "--"
        val etaText = when {
            batt.batteryState == BatteryState.CHARGING && batt.timeToFullMin >= 0 -> "${formatMinutesEta(batt.timeToFullMin)} to full"
            batt.batteryState == BatteryState.DISCHARGING && batt.timeRemainingMin >= 0 -> "${formatMinutesEta(batt.timeRemainingMin)} left"
            else -> "--"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricStatBlock(label = "LIVE POWER", value = livePwrText, modifier = Modifier.weight(1f))
            MetricStatBlock(label = "REMAINING", value = remWhText, modifier = Modifier.weight(0.8f))
            MetricStatBlock(label = "EST. TIME", value = etaText, modifier = Modifier.weight(0.95f))
        }

        Spacer(modifier = Modifier.height(8.dp))
        val designStr = when {
            config.batteryDesignCapacityOverrideMah > 0 -> "${config.batteryDesignCapacityOverrideMah} mAh (manual)"
            batt.sysfsDesignCapacityMah > 0 -> "${batt.sysfsDesignCapacityMah} mAh"
            else -> "--"
        }
        val cyclesStr = if (batt.cycleCount >= 0) "${batt.cycleCount}" else "--"
        val tempStr = if (batt.batteryTempC != 0f) "${"%.1f".format(Locale.US, batt.batteryTempC)}°C" else "--"
        val voltStr = if (batt.batteryVoltageMv > 0f) "${"%.2f".format(Locale.US, batt.batteryVoltageMv / 1000f)}V" else "--"
        val kernelFullNote = if (batt.sysfsFullCapacityMah > 0) " • Kernel full ${batt.sysfsFullCapacityMah} mAh" else ""
        Text(
            text = "Design: $designStr$kernelFullNote • Cycles: $cyclesStr",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Temp: $tempStr • Voltage: $voltStr • Health: ${batt.batteryHealthLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = String.format(
                Locale.US,
                "Session: Out %.2f Wh (%.0f mAh)  •  In %.2f Wh (%.0f mAh)",
                batt.sessionWhOut, batt.sessionMahOut, batt.sessionWhIn, batt.sessionMahIn
            ),
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = String.format(
                Locale.US,
                "Lifetime: Out %.1f Wh (%.0f mAh)  •  In %.1f Wh (%.0f mAh)",
                batt.lifetimeWhOut, batt.lifetimeMahOut, batt.lifetimeWhIn, batt.lifetimeMahIn
            ),
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (batt.recentSegments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recent segments (${batt.recentSegments.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            batt.recentSegments.take(4).forEach { seg ->
                val dirTag = if (seg.direction == BatteryState.DISCHARGING) "DISCH" else "CHG"
                val durMin = (seg.durationMs / 60_000L).coerceAtLeast(1L)
                Text(
                    text = String.format(
                        Locale.US,
                        "• %s %d%%→%d%% (%dm): %.0f mAh / %.1f Wh",
                        dirTag, seg.startPct, seg.endPct, durMin, seg.estCapacityMah, seg.estCapacityWh
                    ),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = "Estimate battery capacity",
            explanation = "Tracks charge/discharge energy across uninterrupted percent steps (~8%+ and 3+ min) to estimate real battery health.",
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
                        "Design: ${config.batteryDesignCapacityOverrideMah} mAh"
                    } else {
                        "Design mAh (Auto)"
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
                Text("Reset estimate", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    "${comp.liveFreqMhz} MHz • ${comp.liveUtilPct.roundToInt()}% load -> ${comp.livePredictedMw.roundToInt()} mW"
                } else {
                    "Idle / not sampling"
                }
                Text(
                    text = liveLine,
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                label = "${comp.liveConfidencePct.roundToInt()}% conf",
                color = if (confident) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = if (confident) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (comp.curvePoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
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
                        text = "${pt.confidencePct.roundToInt()}% (${pt.samples})",
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
