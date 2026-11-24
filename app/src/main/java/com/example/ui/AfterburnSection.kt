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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.BoostStrength
import com.example.model.GovernorConfig
import com.example.model.AfterburnState
import com.example.model.TelemetryState
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/** v3.0.0 Afterburn card (Tuning > Power). Which apps it works on is chosen in the Apps tab. */
@Composable
fun AfterburnSection(
    config: GovernorConfig,
    telemetryFlow: StateFlow<TelemetryState>,
    onUpdateAfterburn: (
        enabled: Boolean?,
        strength: BoostStrength?,
        thermalConditionEnabled: Boolean?,
        thermalMarginC: Float?,
        batteryConditionEnabled: Boolean?,
        batteryThresholdPct: Int?,
        voltageSagEnabled: Boolean?
    ) -> Unit
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val ext = LocalExtendedColors.current
    val b = config.afterburn
    val stateColor = when (telemetry.afterburnState) {
        AfterburnState.BOOSTING -> ext.optimal
        AfterburnState.REDUCED -> ext.warning
        AfterburnState.BLOCKED_THERMAL, AfterburnState.BLOCKED_BATTERY -> ext.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val liveLabel = if (!b.enabled) {
        "OFF"
    } else {
        val s = telemetry.afterburnState.label.uppercase(Locale.US)
        if (telemetry.afterburnLevelPct > 0) "$s ${telemetry.afterburnLevelPct}%" else s
    }

    EcuCard(accentBorder = if (b.enabled && telemetry.afterburnLevelPct > 0) ext.optimal else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AFTERBURN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = liveLabel,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = stateColor
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = "Enable Afterburn",
            explanation = "While a selected app (Apps tab > Afterburn list) is in front and the conditions below are met, the power-throttle cap is temporarily bypassed. Thermal throttling always stays in charge.",
            checked = b.enabled,
            onCheckedChange = { onUpdateAfterburn(it, null, null, null, null, null, null) },
            testTag = "afterburn_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Boost strength",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoostStrength.values().forEach { st ->
                FilterChip(
                    selected = b.strength == st,
                    onClick = { onUpdateAfterburn(null, st, null, null, null, null, null) },
                    label = { Text(st.label) },
                    enabled = b.enabled,
                    modifier = Modifier.testTag("afterburn_strength_${st.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = "Thermal condition",
            explanation = "Only boost when the temperature is at least this many degrees below your thermal throttle start (${"%.0f".format(Locale.US, config.tempThrottle.startTempC)} C). More headroom means a stronger boost.",
            checked = b.thermalConditionEnabled,
            onCheckedChange = { onUpdateAfterburn(null, null, it, null, null, null, null) },
            testTag = "afterburn_thermal_switch"
        )
        SettingSliderRow(
            title = "Required temperature margin",
            valueText = "${"%.0f".format(Locale.US, b.thermalMarginC)} C below throttle",
            explanation = "3 to 8 C. When the battery is low this margin is raised by 2 C, so the two conditions decide together.",
            value = b.thermalMarginC,
            valueRange = 3f..8f,
            steps = 4,
            enabled = b.enabled && b.thermalConditionEnabled,
            onValueChange = { onUpdateAfterburn(null, null, null, it.roundToInt().toFloat(), null, null, null) },
            testTag = "afterburn_margin_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = "Battery condition",
            explanation = "Boost less when the battery is below the threshold (discharging). Close to empty there is no boost. Charging ignores this condition.",
            checked = b.batteryConditionEnabled,
            onCheckedChange = { onUpdateAfterburn(null, null, null, null, it, null, null) },
            testTag = "afterburn_battery_switch"
        )
        SettingSliderRow(
            title = "Low battery threshold",
            valueText = "${b.batteryThresholdPct}%",
            explanation = "Below this level the boost is reduced.",
            value = b.batteryThresholdPct.toFloat(),
            valueRange = 10f..90f,
            steps = 15,
            enabled = b.enabled && b.batteryConditionEnabled,
            onValueChange = { onUpdateAfterburn(null, null, null, null, null, ((it / 5f).roundToInt() * 5), null) },
            testTag = "afterburn_battery_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = "Voltage sag awareness",
            explanation = "Boost less when the battery voltage drops a lot under load (learned from the battery's own behaviour). Protects a weak or cold cell. Stops the boost at about 400 mV of sag.",
            checked = b.voltageSagEnabled,
            onCheckedChange = { onUpdateAfterburn(null, null, null, null, null, null, it) },
            testTag = "afterburn_sag_switch"
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Active on ${config.boostPackages.size} app(s). Now: ${"%.1f".format(Locale.US, telemetry.temperatureC)} C, battery ${if (telemetry.batteryPct >= 0) "${telemetry.batteryPct}%" else "n/a"}${if (telemetry.truePct >= 0f) " (real ~${telemetry.truePct.roundToInt()}%)" else ""}, sag ${telemetry.batterySagMv.roundToInt()} mV.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
