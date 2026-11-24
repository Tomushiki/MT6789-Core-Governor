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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.AppClass
import com.example.governor.AppControl
import com.example.governor.AppControlInfo
import com.example.governor.AppDemandRow
import com.example.governor.AppOverride
import com.example.governor.RefreshUiState
import com.example.model.GovernorConfig
import com.example.model.SmartAppMode
import com.example.ui.components.EcuCard
import java.util.Locale
import kotlin.math.roundToInt

private fun fmtDur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun localizedOverrideName(override: AppOverride): String = when (override) {
    AppOverride.LIGHT -> stringResource(R.string.smart_override_light)
    AppOverride.MODERATE -> stringResource(R.string.smart_override_moderate)
    AppOverride.HEAVY -> stringResource(R.string.smart_override_heavy)
    AppOverride.NEVER -> stringResource(R.string.smart_override_never)
    AppOverride.NONE -> ""
}

@Composable
private fun localizedEffect(raw: String): String = when {
    raw == "not controlled" -> stringResource(R.string.smart_effect_not_controlled)
    raw == "learning" -> stringResource(R.string.smart_effect_learning)
    raw == "no change" -> stringResource(R.string.smart_effect_no_change)
    raw == "warm-up (full ceiling)" -> stringResource(R.string.smart_effect_warmup)
    raw == "boost hold (full ceiling)" -> stringResource(R.string.smart_effect_boost_hold)
    raw.startsWith("ceiling lowered ") -> {
        val pct = raw.removePrefix("ceiling lowered ").removeSuffix("%").trim().toIntOrNull()
        if (pct != null) stringResource(R.string.smart_effect_lowered, pct) else raw
    }
    raw.startsWith("floor raised to ") -> {
        val pct = raw.removePrefix("floor raised to ").removeSuffix("%").trim().toIntOrNull()
        if (pct != null) stringResource(R.string.smart_effect_raised, pct) else raw
    }
    else -> raw
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
                    Text(stringResource(R.string.smart_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.smart_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.smartAppProfilesEnabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SmartAppMode.entries.forEach { m ->
                    val modeLabel = when (m) {
                        SmartAppMode.CONSERVATIVE -> stringResource(R.string.smart_mode_conservative)
                        SmartAppMode.BALANCED -> stringResource(R.string.smart_mode_balanced)
                        SmartAppMode.AGGRESSIVE -> stringResource(R.string.smart_mode_aggressive)
                    }
                    FilterChip(
                        selected = config.smartAppMode == m,
                        onClick = { onMode(m) },
                        label = { Text(modeLabel) }
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    if (config.smartAppProfilesEnabled) {
                        stringResource(R.string.smart_empty_on)
                    } else {
                        stringResource(R.string.smart_empty_off)
                    },
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
        AppControl.AUTO -> stringResource(R.string.smart_ctrl_auto)
        AppControl.LOCKED -> stringResource(R.string.smart_ctrl_locked)
        AppControl.OVERRIDE -> stringResource(R.string.smart_ctrl_override, localizedOverrideName(r.override))
    }
    val clsLabel = when (r.cls) {
        AppClass.UNKNOWN -> stringResource(R.string.smart_class_learning)
        AppClass.LIGHT -> stringResource(R.string.smart_class_light)
        AppClass.MODERATE -> stringResource(R.string.smart_class_moderate)
        AppClass.HEAVY -> stringResource(R.string.smart_class_heavy)
        AppClass.NOISY -> stringResource(R.string.smart_class_noisy)
    }
    val wattsStr = if (r.watts > 0f) String.format(Locale.US, "%.2f W", r.watts) else "- W"
    val samplesStr = pluralStringResource(R.plurals.plural_samples, r.samples, r.samples)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                clsLabel,
                style = MaterialTheme.typography.labelMedium,
                color = when (r.cls) {
                    AppClass.LIGHT -> MaterialTheme.colorScheme.primary
                    AppClass.HEAVY, AppClass.NOISY -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            stringResource(R.string.smart_row_metrics, r.typCpuPct, r.peakCpuPct, r.typGpuPct, r.peakGpuPct, wattsStr),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.smart_row_meta, r.confidencePct, mode, samplesStr, fmtDur(r.fgSec), localizedEffect(r.effect)),
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
        AppControl.AUTO -> stringResource(R.string.smart_profile_auto)
        AppControl.LOCKED -> stringResource(R.string.smart_profile_locked)
        AppControl.OVERRIDE -> if (cur.override == AppOverride.NEVER) {
            stringResource(R.string.smart_profile_never)
        } else {
            stringResource(R.string.smart_profile_override, localizedOverrideName(cur.override))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.smart_reset_title)) },
            text = { Text(stringResource(R.string.smart_reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReset() }) {
                    Text(stringResource(R.string.common_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    Column {
        TextButton(onClick = { open = true }) { Text(text, style = MaterialTheme.typography.labelSmall) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_auto)) }, onClick = { open = false; onSet(AppControl.AUTO, AppOverride.NONE) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_locked)) }, onClick = { open = false; onSet(AppControl.LOCKED, AppOverride.NONE) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_light)) }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.LIGHT) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_moderate)) }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.MODERATE) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_heavy)) }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.HEAVY) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_never)) }, onClick = { open = false; onSet(AppControl.OVERRIDE, AppOverride.NEVER) })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_menu_reset)) }, onClick = { open = false; confirm = true })
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
    val localizedNote = if (state.note == "Only one refresh rate is supported - feature unavailable.") {
        stringResource(R.string.refresh_single_rate_note)
    } else {
        state.note
    }
    val localizedMethod = when (state.method) {
        "not detected yet" -> stringResource(R.string.refresh_method_not_detected)
        "failed - auto-disabled" -> stringResource(R.string.refresh_method_auto_disabled)
        else -> state.method
    }
    EcuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.refresh_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.refresh_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.adaptiveRefreshEnabled, onCheckedChange = onToggle, enabled = !unavailable)
            }
            if (unavailable) {
                Text(localizedNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.supported.size >= 2) {
                Text(stringResource(R.string.refresh_idle_rate), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = config.adaptiveIdleHz == 0, onClick = { onHz(0, null, null) }, label = { Text(stringResource(R.string.common_auto)) })
                    state.supported.forEach { hz ->
                        FilterChip(selected = config.adaptiveIdleHz == hz, onClick = { onHz(hz, null, null) }, label = { Text(stringResource(R.string.refresh_hz, hz)) })
                    }
                }
                Text(stringResource(R.string.refresh_active_rate), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = config.adaptiveActiveHz == 0, onClick = { onHz(null, 0, null) }, label = { Text(stringResource(R.string.common_auto)) })
                    state.supported.forEach { hz ->
                        FilterChip(selected = config.adaptiveActiveHz == hz, onClick = { onHz(null, hz, null) }, label = { Text(stringResource(R.string.refresh_hz, hz)) })
                    }
                }
                Text(
                    stringResource(R.string.refresh_idle_delay, String.format(Locale.US, "%.1f", config.adaptiveIdleDelayMs / 1000f)),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = config.adaptiveIdleDelayMs / 1000f,
                    onValueChange = { onHz(null, null, ((it * 10f).roundToInt() * 100).coerceIn(500, 5000)) },
                    valueRange = 0.5f..5f
                )
                val total = state.shareMs.sumOf { it.second }.coerceAtLeast(1L)
                val share = state.shareMs.joinToString("  ") { "${it.first}Hz ${(it.second * 100 / total)}%" }
                Text(
                    stringResource(
                        R.string.refresh_stats,
                        state.currentHz,
                        localizedMethod,
                        if (share.isBlank()) "-" else share,
                        state.switches,
                        state.touchSource
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onTest) { Text(stringResource(R.string.refresh_test_btn)) }
            }
        }
    }
}
