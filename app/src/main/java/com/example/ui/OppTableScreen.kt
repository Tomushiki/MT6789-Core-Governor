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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.governor.SysfsParsers
import com.example.model.HardwareSpec
import com.example.model.TelemetryState
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle

@Composable
fun OppTableScreen(
    hardwareSpec: HardwareSpec,
    telemetry: TelemetryState,
    onRefreshTable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val gpuSpec = hardwareSpec.gpu
    val gpuLive = telemetry.gpu

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live gpufreq_status Raw Debug Card
        item {
            EcuCard(accentBorder = ext.gpuAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIVE /proc/gpufreqv2/gpufreq_status",
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.gpuAccent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val oppLabel = if (gpuLive.currentOppIndex >= 0) "OPP ${gpuLive.currentOppIndex}" else "OPP --"
                        val freqLabel = if (gpuLive.currentFreqKhz > 0L) {
                            "${gpuLive.currentFreqKhz / 1000} MHz (${gpuLive.currentFreqKhz} kHz)"
                        } else {
                            "-- MHz"
                        }
                        Text(
                            text = "Parsed: $oppLabel • $freqLabel",
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val raw = gpuLive.rawGpufreqStatus.ifBlank { "(empty)" }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("gpufreq_status", raw))
                            Toast.makeText(context, "Copied gpufreq_status", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_gpufreq_status_opp_screen")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy raw status", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = gpuLive.rawGpufreqStatus.ifBlank {
                        "Tap Refresh or start CoreGovernor to inspect live /proc/gpufreqv2/gpufreq_status output."
                    },
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp)
                        .testTag("raw_gpufreq_status_text")
                )
            }
        }

        // 2. OPP Table Source & Order Info Card
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GPU OPP TABLE (${gpuSpec.oppTable.size} ENTRIES)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Source: ${gpuSpec.oppTableSourcePath ?: "Not discovered"}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Order: ${gpuSpec.detectedOrderNote}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "GED Load: ${gpuLive.loadSourcePath.ifBlank { gpuSpec.gedLoadCandidatePaths.joinToString().ifBlank { "none" } }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onRefreshTable,
                        modifier = Modifier.testTag("refresh_opp_table_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh OPP table")
                    }
                }
            }
        }

        // 3. Full Sorted OPP List (Position 0 = lowest -> Last = highest)
        if (gpuSpec.oppTable.isEmpty()) {
            item {
                EcuCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No GPU OPP entries parsed yet. Ensure root is granted and refresh.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(onClick = onRefreshTable) {
                                Text("Probe OPP Table")
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = gpuSpec.oppTable,
                key = { pos, opp -> "${pos}_${opp.oppIndex}_${opp.freqKhz}" }
            ) { pos, opp ->
                val isCurrent = gpuLive.currentOppIndex == opp.oppIndex
                val isTarget = gpuLive.targetOppIndex == opp.oppIndex
                val inAllowedRange = pos in gpuLive.allowedMinPos..gpuLive.allowedMaxPos
                val isCappedOut = gpuLive.available && pos > gpuLive.cappedMaxPos && inAllowedRange

                val border = when {
                    isTarget -> MaterialTheme.colorScheme.primary
                    isCurrent -> ext.gpuAccent
                    else -> null
                }

                EcuCard(accentBorder = border) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "#${pos.toString().padStart(2, '0')}",
                                style = MonoMetricStyle.copy(
                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text(
                                    text = "${opp.freqKhz / 1000} MHz  (${opp.freqKhz} kHz)",
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Real OPP Index: [${opp.oppIndex}]  •  Volt: ${SysfsParsers.formatVoltMv(opp.voltRaw)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isCurrent) {
                                StatusPill(
                                    label = "LIVE",
                                    color = ext.gpuAccent,
                                    containerColor = ext.warningContainer
                                )
                            }
                            if (isTarget) {
                                StatusPill(
                                    label = "PINNED",
                                    color = MaterialTheme.colorScheme.primary,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else if (isCappedOut) {
                                StatusPill(
                                    label = "CAPPED",
                                    color = ext.throttle,
                                    containerColor = ext.throttleContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
