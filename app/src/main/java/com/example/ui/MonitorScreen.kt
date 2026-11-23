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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.BatteryState
import com.example.model.ClusterLiveState
import com.example.model.GovernorMode
import com.example.model.HardwareSpec
import com.example.model.ModeOverride
import com.example.model.TelemetryState
import com.example.ui.components.EcuCard
import com.example.ui.components.FrequencyRangeBar
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryHistoryGraph
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.util.Locale

@Composable
fun MonitorScreen(
    telemetry: TelemetryState,
    hardwareSpec: HardwareSpec,
    onToggleGovernor: () -> Unit,
    onSelectModeOverride: (ModeOverride) -> Unit,
    onRediscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Master Engine Control Card
        item {
            val activeAccent by animateColorAsState(
                targetValue = when {
                    !telemetry.rootGranted && hardwareSpec.discoveredAtMs > 0L -> ext.throttle
                    telemetry.isRunning && telemetry.activeMode == GovernorMode.GAME -> ext.warning
                    telemetry.isRunning -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                label = "engineBorder"
            )

            EcuCard(accentBorder = activeAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusPill(
                                label = when {
                                    telemetry.isDiscovering -> "PROBING HW"
                                    telemetry.isRunning -> "ACTIVE • ${telemetry.lastPollDurationMs}ms"
                                    else -> "IDLE"
                                },
                                color = if (telemetry.isRunning) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                                containerColor = if (telemetry.isRunning) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant
                            )
                            StatusPill(
                                label = if (telemetry.rootGranted) "ROOT OK" else "NO ROOT",
                                color = if (telemetry.rootGranted) MaterialTheme.colorScheme.primary else ext.throttle,
                                containerColor = if (telemetry.rootGranted) MaterialTheme.colorScheme.primaryContainer else ext.throttleContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (telemetry.isRunning) {
                                "Single Controller Online"
                            } else {
                                "Kernel Governors Untouched"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        val fgText = telemetry.foregroundPackage.ifBlank { "No foreground package yet" }
                        Text(
                            text = "App: $fgText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onRediscover,
                        modifier = Modifier.testTag("rediscover_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-discover hardware"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onToggleGovernor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("governor_toggle_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (telemetry.isRunning) ext.throttle else MaterialTheme.colorScheme.primary,
                        contentColor = if (telemetry.isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (telemetry.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (telemetry.isRunning) "STOP & RESTORE KERNEL" else "START COREGOVERNOR",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeOverride.entries.forEach { modeOpt ->
                        val selected = telemetry.modeOverride == modeOpt
                        val label = when (modeOpt) {
                            ModeOverride.AUTO -> "Auto (${telemetry.activeMode.name})"
                            ModeOverride.FORCE_NORMAL -> "Normal"
                            ModeOverride.FORCE_GAME -> "Game"
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectModeOverride(modeOpt) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mode_chip_${modeOpt.name.lowercase()}")
                        )
                    }
                }

                if (telemetry.burstActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusPill(
                        label = "LAUNCH BURST ACTIVE • ${telemetry.burstRemainingMs}ms",
                        color = ext.warning,
                        containerColor = ext.warningContainer
                    )
                }
            }
        }

        // 2. Root warning card if missing
        if (!telemetry.rootGranted && hardwareSpec.discoveredAtMs > 0L) {
            item {
                EcuCard(accentBorder = ext.throttle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Root required",
                            tint = ext.throttle
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Root Access Required",
                                style = MaterialTheme.typography.titleSmall,
                                color = ext.throttle
                            )
                            Text(
                                text = "Grant superuser (su) in Magisk / KernelSU / APatch, then tap refresh above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 3. Hero Telemetry Metrics
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EcuCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = "Temperature",
                            tint = if (telemetry.tempThrottleActive) ext.throttle else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "THERMAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${"%.1f".format(telemetry.temperatureC)} °C",
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                        color = if (telemetry.tempThrottleActive) ext.throttle else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = telemetry.tempSensorLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val tempStatus = if (telemetry.tempThrottleActive) {
                        "CAP: CPU ${telemetry.tempCpuCapPct.toInt()}% • GPU ${telemetry.tempGpuCapPct.toInt()}%"
                    } else {
                        "Temp Cap: Inactive (100%)"
                    }
                    Text(
                        text = tempStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.tempThrottleActive) ext.throttle else ext.optimal
                    )
                }

                EcuCard(modifier = Modifier.weight(1f)) {
                    val battState = telemetry.batteryState
                    val pwrTint = when {
                        battState == BatteryState.CHARGING || battState == BatteryState.FULL_PLUGGED -> ext.optimal
                        telemetry.powerThrottleActive -> ext.warning
                        battState == BatteryState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> ext.optimal
                    }
                    val pwrHeader = when (battState) {
                        BatteryState.CHARGING -> "CHARGING"
                        BatteryState.FULL_PLUGGED -> "PLUGGED"
                        BatteryState.DISCHARGING -> "POWER DRAW"
                        BatteryState.UNKNOWN -> "POWER DRAW"
                    }
                    val pwrValue = when (battState) {
                        BatteryState.CHARGING -> "\u26A1 +${"%.2f".format(Locale.US, telemetry.chargeWatts)} W"
                        BatteryState.FULL_PLUGGED -> "PLUG 0.0 W"
                        BatteryState.DISCHARGING -> "${"%.2f".format(Locale.US, telemetry.watts)} W"
                        BatteryState.UNKNOWN -> "-- W"
                    }
                    val pwrValueColor = when {
                        battState == BatteryState.CHARGING || battState == BatteryState.FULL_PLUGGED -> ext.optimal
                        telemetry.powerThrottleActive -> ext.warning
                        battState == BatteryState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Power status",
                            tint = pwrTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = pwrHeader,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = pwrValue,
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                        color = pwrValueColor
                    )
                    Text(
                        text = "${telemetry.rawCurrentMa.toInt()} mA @ ${(telemetry.rawVoltageMv / 1000f).let { "%.2f".format(Locale.US, it) }} V",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val (pwrStatus, pwrStatusColor) = when {
                        battState == BatteryState.CHARGING -> "Power Cap: Inactive (charging)" to ext.optimal
                        battState == BatteryState.FULL_PLUGGED -> "Power Cap: Inactive (full, plugged)" to ext.optimal
                        telemetry.powerThrottleActive -> "POWER CAP: ${telemetry.powerCapPct.toInt()}% (-${(100f - telemetry.powerCapPct).toInt()}%)" to ext.warning
                        battState == BatteryState.UNKNOWN -> "Waiting for reading…" to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> "Power Cap: Inactive (100%)" to ext.optimal
                    }
                    Text(
                        text = pwrStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = pwrStatusColor
                    )
                }
            }
        }

        // 4. Stacked Throttle Summary Banner
        if (telemetry.tempThrottleActive || telemetry.powerThrottleActive) {
            item {
                EcuCard(accentBorder = ext.throttle) {
                    Column {
                        Text(
                            text = "STACKED THROTTLE ACTIVE (LOWER CAP WINS)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.throttle
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "CPU Ceiling: ${telemetry.effectiveCpuCapPct.toInt()}%  •  GPU Ceiling: ${telemetry.effectiveGpuCapPct.toInt()}%",
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 5. Telemetry History Graph
        item {
            EcuCard {
                TelemetryHistoryGraph(history = telemetry.history)
            }
        }

        // 6. Per-Cluster CPU Cards
        if (telemetry.clusters.isNotEmpty()) {
            items(telemetry.clusters, key = { it.policyId }) { cluster ->
                CpuClusterCard(cluster = cluster)
            }
        } else if (hardwareSpec.cpuPolicies.isNotEmpty()) {
            items(hardwareSpec.cpuPolicies, key = { it.policyId }) { spec ->
                EcuCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = spec.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "gov: ${spec.origGovernor}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${spec.availableFreqsKhz.size} frequencies (${(spec.availableFreqsKhz.firstOrNull() ?: 0) / 1000}–${(spec.availableFreqsKhz.lastOrNull() ?: 0) / 1000} MHz)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 7. GPU OPP Telemetry & Raw Status Card
        item {
            GpuMonitorCard(telemetry = telemetry, hardwareSpec = hardwareSpec)
        }
    }
}

@Composable
private fun CpuClusterCard(cluster: ClusterLiveState) {
    val primary = MaterialTheme.colorScheme.primary
    EcuCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Policy ${cluster.policyId} • ${cluster.name}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Gov: ${cluster.governor} • Load ${cluster.loadPct.toInt()}% (smoothed ${cluster.smoothedLoadPct.toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${cluster.targetFreqKhz / 1000} MHz",
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                    color = primary
                )
                Text(
                    text = "actual ${cluster.actualFreqKhz / 1000} MHz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        FrequencyRangeBar(
            minAvailableKhz = cluster.minAvailableKhz,
            maxAvailableKhz = cluster.maxAvailableKhz,
            allowedMinKhz = cluster.allowedMinKhz,
            allowedMaxKhz = cluster.allowedMaxKhz,
            cappedMaxKhz = cluster.cappedMaxKhz,
            targetKhz = cluster.targetFreqKhz,
            accentColor = primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Range: ${cluster.allowedMinKhz / 1000}–${cluster.allowedMaxKhz / 1000} MHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Cap: ${cluster.cappedMaxKhz / 1000} MHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GpuMonitorCard(
    telemetry: TelemetryState,
    hardwareSpec: HardwareSpec
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val gpu = telemetry.gpu
    val gpuAccent = ext.gpuAccent
    var showRawStatus by remember { mutableStateOf(false) }

    EcuCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = gpuAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "GPU (gpufreqv2 OPP Pinning)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val loadSrcShort = gpu.loadSourcePath.substringAfterLast('/').ifBlank { "probe" }
                    Text(
                        text = "Load ${gpu.loadPct.toInt()}% (smooth ${gpu.smoothedLoadPct.toInt()}%) • $loadSrcShort",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val liveFreq = if (gpu.currentFreqKhz > 0L) gpu.currentFreqKhz else gpu.targetFreqKhz
                val oppBadge = if (gpu.currentOppIndex >= 0) "OPP ${gpu.currentOppIndex}" else "OPP --"
                Text(
                    text = "${liveFreq / 1000} MHz ($oppBadge)",
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                    color = gpuAccent
                )
                Text(
                    text = "target ${gpu.targetFreqKhz / 1000}M • idx ${gpu.targetOppIndex} (pos ${gpu.targetSortedPos})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (gpu.available && gpu.maxAvailableKhz > 0L) {
            Spacer(modifier = Modifier.height(10.dp))
            FrequencyRangeBar(
                minAvailableKhz = gpu.minAvailableKhz,
                maxAvailableKhz = gpu.maxAvailableKhz,
                allowedMinKhz = gpu.allowedMinKhz,
                allowedMaxKhz = gpu.allowedMaxKhz,
                cappedMaxKhz = gpu.cappedMaxKhz,
                targetKhz = gpu.targetFreqKhz,
                accentColor = gpuAccent
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Range: ${gpu.allowedMinKhz / 1000}–${gpu.allowedMaxKhz / 1000} MHz (${hardwareSpec.gpu.oppTable.size} OPPs)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Cap: pos ${gpu.cappedMaxPos} (${gpu.cappedMaxKhz / 1000}M)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hardwareSpec.gpu.detectedOrderNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { showRawStatus = !showRawStatus },
                modifier = Modifier.testTag("toggle_raw_gpufreq_status")
            ) {
                Icon(
                    imageVector = if (showRawStatus) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Raw gpufreq_status")
            }

            if (gpu.rawGpufreqStatus.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("gpufreq_status", gpu.rawGpufreqStatus))
                        Toast.makeText(context, "Copied gpufreq_status", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_raw_gpufreq_status_monitor")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy raw status", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        AnimatedVisibility(visible = showRawStatus) {
            Text(
                text = gpu.rawGpufreqStatus.ifBlank { "No gpufreq_status data read yet." },
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(10.dp)
            )
        }
    }
}
