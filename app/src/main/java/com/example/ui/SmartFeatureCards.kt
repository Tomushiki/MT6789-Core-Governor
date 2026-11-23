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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.governor.AppClass
import com.example.governor.AppControl
import com.example.governor.AppControlInfo
import com.example.governor.AppDemandRow
import com.example.governor.AppOverride
import com.example.governor.RefreshUiState
import com.example.model.GovernorConfig
import com.example.model.SmartAppMode
import com.example.ui.components.EcuCard
import kotlin.math.roundToInt

private fun fmtDur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
fun AppDemandCard(
    config: GovernorConfig,
    rows: List<AppDemandRow>,
    labels: Map<String, String>,
    onToggle: (Boolean) -> Unit,
    onMode: (SmartAppMode) -> Unit
) {
    EcuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Smart app profiles", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Learns what each app asks of every CPU cluster and the GPU. Learning only runs while this is on and needs hours of use. Thermal, caps, pins and Game mode always win.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.smartAppProfilesEnabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SmartAppMode.entries.forEach { m ->
                    FilterChip(
                        selected = config.smartAppMode == m,
                        onClick = { onMode(m) },
                        label = { Text(m.label) }
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    if (config.smartAppProfilesEnabled) "No data yet - use your apps normally." else "Off. Enable to start learning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                rows.forEach { r -> DemandRowView(r, labels[r.pkg] ?: r.pkg) }
            }
        }
    }
}

@Composable
private fun DemandRowView(r: AppDemandRow, label: String) {
    val mode = when (r.control) {
        AppControl.AUTO -> "Auto"
        AppControl.LOCKED -> "Locked"
        AppControl.OVERRIDE -> "Override " + (if (r.override == AppOverride.NEVER) "never" else r.override.name.lowercase())
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (r.cls == AppClass.UNKNOWN) "LEARNING" else r.cls.name,
                style = MaterialTheme.typography.labelMedium,
                color = when (r.cls) {
                    AppClass.LIGHT -> MaterialTheme.colorScheme.primary
                    AppClass.HEAVY, AppClass.NOISY -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            "CPU ${r.typCpuPct}% typ / ${r.peakCpuPct}% peak   GPU ${r.typGpuPct}% / ${r.peakGpuPct}%   " +
                (if (r.watts > 0f) "%.2f W".format(r.watts) else "- W"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "conf ${r.confidencePct}% | $mode | ${r.samples} samples | ${fmtDur(r.fgSec)} | ${r.effect}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Small "Smart profile" control shown on every Apps tab row. */
@Composable
fun SmartProfileControl(
    pkg: String,
    info: AppControlInfo?,
    onSet: (AppControl, AppOverride) -> Unit,
    onReset: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val cur = info ?: AppControlInfo()
    val text = when (cur.control) {
        AppControl.AUTO -> "Smart profile: Auto"
        AppControl.LOCKED -> "Smart profile: Locked"
        AppControl.OVERRIDE -> "Smart profile: " + (if (cur.override == AppOverride.NEVER) "Never touch" else "Override ${cur.override.name.lowercase()}")
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset learned data?") },
            text = { Text("Deletes everything learned for this app. It will start learning again from shared knowledge.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
    Column {
        TextButton(onClick = { open = true }) { Text(text, style = MaterialTheme.typography.labelSmall) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto (learn + act)") }, onClick = { open = false; onSet(AppControl.AUTO, AppOverride.NONE) })
            DropdownMenuItem(text = { Text("Locked (freeze learned profile)") }, onClick = { open = false; onSet(AppControl.LOCKED, AppOverride.NONE) })
            DropdownMenuItem(text = { Text("Override: Light") }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.LIGHT) })
            DropdownMenuItem(text = { Text("Override: Moderate") }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.MODERATE) })
            DropdownMenuItem(text = { Text("Override: Heavy") }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.HEAVY) })
            DropdownMenuItem(text = { Text("Never touch this app") }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.NEVER) })
            DropdownMenuItem(text = { Text("Reset learned data...") }, onClick = { open = false; confirm = true })
        }
    }
}

@Composable
fun AdaptiveRefreshCard(
    config: GovernorConfig,
    state: RefreshUiState,
    onToggle: (Boolean) -> Unit,
    onHz: (idle: Int?, active: Int?, delayMs: Int?) -> Unit,
    onTest: () -> Unit
) {
    val unavailable = state.supported.size < 2 && state.note.isNotBlank()
    EcuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Adaptive refresh rate", style = MaterialTheme.typography.titleSmall)
                        Text("EXPERIMENTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "Experimental: drops the panel to the lowest rate when idle and boosts on touch. Your original refresh-rate settings are saved and restored when you turn this off or stop the service. Needs root; behaviour depends on the ROM.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.adaptiveRefreshEnabled, onCheckedChange = onToggle, enabled = !unavailable)
            }
            if (unavailable) {
                Text(state.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.supported.size >= 2) {
                Text("Idle rate (0 = lowest)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = config.adaptiveIdleHz == 0, onClick = { onHz(0, null, null) }, label = { Text("Auto") })
                    state.supported.forEach { hz ->
                        FilterChip(selected = config.adaptiveIdleHz == hz, onClick = { onHz(hz, null, null) }, label = { Text("$hz Hz") })
                    }
                }
                Text("Active rate (0 = highest)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = config.adaptiveActiveHz == 0, onClick = { onHz(null, 0, null) }, label = { Text("Auto") })
                    state.supported.forEach { hz ->
                        FilterChip(selected = config.adaptiveActiveHz == hz, onClick = { onHz(null, hz, null) }, label = { Text("$hz Hz") })
                    }
                }
                Text("Idle delay: ${"%.1f".format(config.adaptiveIdleDelayMs / 1000f)} s", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = config.adaptiveIdleDelayMs / 1000f,
                    onValueChange = { onHz(null, null, ((it * 10f).roundToInt() * 100).coerceIn(500, 5000)) },
                    valueRange = 0.5f..5f
                )
                val total = state.shareMs.sumOf { it.second }.coerceAtLeast(1L)
                val share = state.shareMs.joinToString("  ") { "${it.first}Hz ${(it.second * 100 / total)}%" }
                Text(
                    "Now: ${state.currentHz} Hz | method: ${state.method}\nToday: ${if (share.isBlank()) "-" else share} | switches: ${state.switches}\nTouch source: ${state.touchSource}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onTest) { Text("Test switch") }
            }
        }
    }
}
