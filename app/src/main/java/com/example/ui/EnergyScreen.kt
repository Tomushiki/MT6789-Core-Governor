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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.governor.ComponentFreqCurvePoint
import com.example.governor.ComponentPowerSummary
import com.example.governor.PowerModelUiSnapshot
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showResetDialog by remember { mutableStateOf(false) }

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                            snapshot.backoffActive -> "BACKOFF ${snapshot.backoffRemainingSec}s"
                            config.efficiencyLearningEnabled && isRunning -> "EFFICIENCY ON"
                            config.efficiencyLearningEnabled -> "EFFICIENCY ARMED"
                            config.powerModelLearningEnabled && isRunning -> "LEARNING"
                            config.powerModelLearningEnabled -> "LEARN READY"
                            else -> "PAUSED"
                        },
                        color = activeAccent,
                        containerColor = activeAccent.copy(alpha = 0.16f)
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
                        Text(
                            text = "${"%.2f".format(Locale.US, snapshot.liveEstimatedMw / 1000f)} W  vs  ${"%.2f".format(Locale.US, snapshot.liveMeasuredMw / 1000f)} W",
                            style = MonoMetricStyle.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val errText = if (snapshot.liveMeasuredMw > 50f) {
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
                Text(
                    text = "Scenario: ${snapshot.activeScenario} • Baseline: ${snapshot.baselineMw.roundToInt()} mW • Saved: $savedLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
