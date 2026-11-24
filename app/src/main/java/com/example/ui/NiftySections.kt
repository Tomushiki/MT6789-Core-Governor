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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.SensorPinner
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.MonoMetricStyle

/**
 * "Nifty features" card: sensor pinning, ported from SwiftSense's Boost Sensors.
 * Keeps the chosen sensors (gyroscope by default) running at a fixed fast rate while the governor runs.
 */
@Composable
fun SensorPinSection(
    state: SensorPinner.State,
    isRunning: Boolean,
    onUpdate: (enabled: Boolean?, rate: SensorPinner.Rate?, toggleType: Int?, pauseWhenScreenOff: Boolean?, gameOnly: Boolean?) -> Unit
) {
    val cfg = state.config
    EcuCard {
        Text(
            text = stringResource(R.string.nifty_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.nifty_enable_title),
            explanation = stringResource(R.string.nifty_enable_desc),
            checked = cfg.enabled,
            onCheckedChange = { onUpdate(it, null, null, null, null) },
            testTag = "sensor_pin_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.nifty_rate_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (cfg.enabled) 1f else 0.38f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SensorPinner.Rate.entries.forEach { rate ->
                FilterChip(
                    selected = cfg.rate == rate,
                    onClick = { onUpdate(null, rate, null, null, null) },
                    label = { Text(stringResource(rate.labelRes)) },
                    enabled = cfg.enabled,
                    modifier = Modifier.testTag("sensor_pin_rate_${rate.key}")
                )
            }
        }
        Text(
            text = stringResource(R.string.nifty_rate_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.nifty_sensors_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (cfg.enabled) 1f else 0.38f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (state.available.isEmpty()) {
            Text(
                text = stringResource(R.string.nifty_no_sensors),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.available.forEach { info ->
                    val label = if (info.labelRes != 0) stringResource(info.labelRes) else info.label
                    FilterChip(
                        selected = info.type in cfg.sensorTypes,
                        onClick = { onUpdate(null, null, info.type, null, null) },
                        label = { Text(label) },
                        enabled = cfg.enabled,
                        modifier = Modifier.testTag("sensor_pin_type_${info.type}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        SettingSwitchRow(
            title = stringResource(R.string.nifty_pause_off_title),
            explanation = stringResource(R.string.nifty_pause_off_desc),
            checked = cfg.pauseWhenScreenOff,
            onCheckedChange = { onUpdate(null, null, null, it, null) },
            enabled = cfg.enabled,
            testTag = "sensor_pin_pause_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))
        SettingSwitchRow(
            title = stringResource(R.string.nifty_game_only_title),
            explanation = stringResource(R.string.nifty_game_only_desc),
            checked = cfg.gameOnly,
            onCheckedChange = { onUpdate(null, null, null, null, it) },
            enabled = cfg.enabled,
            testTag = "sensor_pin_game_only_switch"
        )

        if (cfg.enabled) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when {
                    state.active -> stringResource(R.string.nifty_status_active)
                    !isRunning -> stringResource(R.string.nifty_status_waiting)
                    cfg.gameOnly && !state.gameActive -> stringResource(R.string.nifty_status_game_wait)
                    else -> stringResource(R.string.nifty_status_paused)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.active) {
                state.available
                    .filter { it.type in cfg.sensorTypes }
                    .forEach { info ->
                        val label = if (info.labelRes != 0) stringResource(info.labelRes) else info.label
                        val hz = state.measuredHz[info.type]
                        val cap = if (info.maxHz > 0) stringResource(R.string.nifty_hw_cap, info.maxHz) else ""
                        val hzStr = hz?.let { "$it Hz" } ?: stringResource(R.string.nifty_measuring)
                        Text(
                            text = "$label: $hzStr$cap",
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
            }
        }
    }
}
