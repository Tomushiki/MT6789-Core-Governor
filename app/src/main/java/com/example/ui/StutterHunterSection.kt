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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.HunterActionEntry
import com.example.governor.HunterActionResult
import com.example.governor.HunterPhase
import com.example.governor.StutterHunterStatus
import com.example.model.GovernorConfig
import com.example.model.InstalledAppInfo
import com.example.model.StutterHunterMode
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Stutter Hunter card (Tuning > Power). */
@Composable
fun StutterHunterSection(
    config: GovernorConfig,
    status: StutterHunterStatus,
    installedApps: List<InstalledAppInfo>,
    onUpdateStutterHunter: (
        enabled: Boolean?,
        mode: StutterHunterMode?,
        dipPct: Int?,
        cpuThresholdPct: Int?
    ) -> Unit,
    onToggleWhitelist: (String) -> Unit
) {
    val ext = LocalExtendedColors.current
    val h = config.stutterHunter
    val appLabels = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
    var showWhitelistDialog by rememberSaveable { mutableStateOf(false) }

    val effectivePhase = if (!h.enabled) HunterPhase.OFF else status.phase
    val stateColor = when (effectivePhase) {
        HunterPhase.WATCHING -> ext.optimal
        HunterPhase.LEARNING -> MaterialTheme.colorScheme.primary
        HunterPhase.CHECKING, HunterPhase.STOPPED -> ext.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (effectivePhase) {
        HunterPhase.OFF -> stringResource(R.string.hunter_status_off)
        HunterPhase.IDLE -> stringResource(R.string.hunter_status_idle)
        HunterPhase.LEARNING -> stringResource(R.string.hunter_status_learning, status.learningPct)
        HunterPhase.WATCHING -> stringResource(R.string.hunter_status_watching, status.baselineFps)
        HunterPhase.CHECKING -> stringResource(R.string.hunter_status_checking)
        HunterPhase.STOPPED -> {
            val targetLabel = appLabels[status.lastTargetPackage] ?: status.lastTargetPackage
            stringResource(R.string.hunter_status_stopped, targetLabel)
        }
    }

    val accent = when (effectivePhase) {
        HunterPhase.WATCHING -> ext.optimal
        HunterPhase.CHECKING, HunterPhase.STOPPED -> ext.warning
        else -> null
    }

    EcuCard(accentBorder = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.hunter_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = statusText,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = stateColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("hunter_status_text")
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.hunter_enable_title),
            explanation = stringResource(R.string.hunter_enable_desc),
            checked = h.enabled,
            onCheckedChange = { onUpdateStutterHunter(it, null, null, null) },
            testTag = "hunter_enable_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.hunter_mode_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StutterHunterMode.entries.forEach { mode ->
                FilterChip(
                    selected = h.mode == mode,
                    onClick = { onUpdateStutterHunter(null, mode, null, null) },
                    label = { Text(stringResource(mode.labelRes)) },
                    enabled = h.enabled,
                    modifier = Modifier.testTag("hunter_mode_${mode.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = stringResource(R.string.hunter_dip_title),
            valueText = stringResource(R.string.hunter_dip_val, h.dipPct),
            explanation = stringResource(R.string.hunter_dip_desc),
            value = h.dipPct.toFloat(),
            valueRange = 60f..90f,
            steps = 29,
            enabled = h.enabled,
            onValueChange = { onUpdateStutterHunter(null, null, it.roundToInt().coerceIn(60, 90), null) },
            testTag = "hunter_dip_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = stringResource(R.string.hunter_cpu_title),
            valueText = stringResource(R.string.hunter_cpu_val, h.cpuThresholdPct),
            explanation = stringResource(R.string.hunter_cpu_desc),
            value = h.cpuThresholdPct.toFloat(),
            valueRange = 10f..40f,
            steps = 29,
            enabled = h.enabled,
            onValueChange = { onUpdateStutterHunter(null, null, null, it.roundToInt().coerceIn(10, 40)) },
            testTag = "hunter_cpu_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.hunter_whitelist_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            OutlinedButton(
                onClick = { showWhitelistDialog = true },
                modifier = Modifier.testTag("hunter_whitelist_button")
            ) {
                Text(stringResource(R.string.hunter_whitelist_btn, h.whitelist.size))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.hunter_actions_title),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (status.actions.isEmpty()) {
            Text(
                text = stringResource(R.string.hunter_actions_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                status.actions.forEach { action ->
                    HunterActionRow(
                        action = action,
                        timeText = timeFmt.format(Date(action.timestampMs)),
                        appLabels = appLabels
                    )
                }
            }
        }
    }

    if (showWhitelistDialog) {
        HunterWhitelistDialog(
            installedApps = installedApps,
            whitelist = h.whitelist,
            onToggleWhitelist = onToggleWhitelist,
            onDismiss = { showWhitelistDialog = false }
        )
    }
}

@Composable
private fun HunterActionRow(
    action: HunterActionEntry,
    timeText: String,
    appLabels: Map<String, String>
) {
    val gameLabel = appLabels[action.gamePackage] ?: action.gamePackage
    val culpritLabel = appLabels[action.culpritPackage] ?: action.culpritPackage
    val resultText = when (action.result) {
        HunterActionResult.GOVERNOR_THERMAL -> stringResource(R.string.hunter_res_gov_thermal)
        HunterActionResult.GOVERNOR_BATTERY -> stringResource(R.string.hunter_res_gov_battery)
        HunterActionResult.REPORTED -> stringResource(R.string.hunter_res_reported)
        HunterActionResult.STOPPED_CHECKING -> stringResource(R.string.hunter_res_checking)
        HunterActionResult.RECOVERED -> stringResource(R.string.hunter_res_recovered)
        HunterActionResult.DID_NOT_HELP -> stringResource(R.string.hunter_res_did_not_help)
        HunterActionResult.STOP_FAILED -> stringResource(R.string.hunter_res_stop_failed)
    }
    val fpsLine = if (action.fpsAfter != null) {
        stringResource(R.string.hunter_action_fps_delta, resultText, action.fpsBefore, action.fpsAfter)
    } else {
        stringResource(R.string.hunter_action_fps_single, resultText, action.fpsBefore)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        val isGovernor = action.result == HunterActionResult.GOVERNOR_THERMAL ||
            action.result == HunterActionResult.GOVERNOR_BATTERY
        Text(
            text = if (isGovernor) {
                stringResource(R.string.hunter_action_line_governor, timeText, gameLabel)
            } else {
                stringResource(
                    R.string.hunter_action_line,
                    timeText,
                    gameLabel,
                    culpritLabel,
                    action.cpuPct.roundToInt()
                )
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = fpsLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HunterWhitelistDialog(
    installedApps: List<InstalledAppInfo>,
    whitelist: Set<String>,
    onToggleWhitelist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val nonSystemApps = remember(installedApps, query, whitelist) {
        installedApps
            .filter { app ->
                !app.isSystemApp && (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> { it.packageName in whitelist }
                    .thenBy { it.label.lowercase() }
            )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hunter_whitelist_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hunter_whitelist_search"),
                    placeholder = { Text(stringResource(R.string.hunter_whitelist_search)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.apps_search_cd)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (nonSystemApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hunter_whitelist_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(nonSystemApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in whitelist
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleWhitelist(app.packageName) }
                                    .padding(vertical = 6.dp)
                                    .testTag("hunter_wl_item_${app.packageName}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleWhitelist(app.packageName) },
                                    modifier = Modifier.testTag("hunter_wl_cb_${app.packageName}")
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("hunter_whitelist_close")
            ) {
                Text(stringResource(R.string.common_ok))
            }
        }
    )
}
