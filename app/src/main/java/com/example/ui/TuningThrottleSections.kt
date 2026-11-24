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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BatteryState
import com.example.model.GovernorConfig
import com.example.model.HardwareSpec
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.TelemetryState
import kotlinx.coroutines.flow.StateFlow
import com.example.model.ThrottleCurveShape
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun ThermalThrottleSection(
    config: GovernorConfig,
    hardwareSpec: HardwareSpec,
    telemetryFlow: StateFlow<TelemetryState>,
    onSelectTempSensor: (String) -> Unit,
    onUpdateTempThrottle: (
        enabled: Boolean?,
        startTempC: Float?,
        fullTempC: Float?,
        cpuMaxReductionPct: Float?,
        gpuMaxReductionPct: Float?,
        curveShape: ThrottleCurveShape?,
        reactionSpeed: Float?,
        hysteresisC: Float?,
        chargingAware: Boolean?,
        chargingStartOffsetC: Float?
    ) -> Unit
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val ext = LocalExtendedColors.current
    val t = config.tempThrottle
    var sensorMenuExpanded by remember { mutableStateOf(false) }

    EcuCard(accentBorder = if (telemetry.tempThrottleActive) ext.throttle else null) {
        Text(
            text = stringResource(R.string.thermal_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.thermal_enable),
            explanation = stringResource(R.string.thermal_enable_desc),
            checked = t.enabled,
            onCheckedChange = { onUpdateTempThrottle(it, null, null, null, null, null, null, null, null, null) },
            testTag = "temp_throttle_switch"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Sensor Selector
        Text(
            text = stringResource(R.string.thermal_sensor_title),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        val selectedLabel = hardwareSpec.thermalSensors.firstOrNull { it.path == config.selectedTempSensorPath }?.label
            ?: hardwareSpec.thermalSensors.firstOrNull()?.label
            ?: stringResource(R.string.thermal_no_sensors)
        Box {
            OutlinedButton(
                onClick = { sensorMenuExpanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("temp_sensor_dropdown")
            ) {
                Text(
                    text = "$selectedLabel (${"%.1f".format(Locale.US, telemetry.temperatureC)} °C)",
                    maxLines = 1
                )
            }
            DropdownMenu(
                expanded = sensorMenuExpanded,
                onDismissRequest = { sensorMenuExpanded = false }
            ) {
                hardwareSpec.thermalSensors.forEach { sensor ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.thermal_sensor_item,
                                    sensor.label,
                                    "%.1f".format(Locale.US, sensor.initialTempC)
                                )
                            )
                        },
                        onClick = {
                            onSelectTempSensor(sensor.path)
                            sensorMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.thermal_sensor_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_start_temp),
            valueText = "${t.startTempC.roundToInt()} °C",
            explanation = stringResource(R.string.thermal_start_temp_desc),
            value = t.startTempC,
            valueRange = 30f..65f,
            onValueChange = { onUpdateTempThrottle(null, it.roundToInt().toFloat(), null, null, null, null, null, null, null, null) },
            testTag = "temp_start_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_full_temp),
            valueText = "${t.fullTempC.roundToInt()} °C",
            explanation = stringResource(R.string.thermal_full_temp_desc),
            value = t.fullTempC,
            valueRange = (t.startTempC + 2f)..85f,
            onValueChange = { onUpdateTempThrottle(null, null, it.roundToInt().toFloat(), null, null, null, null, null, null, null) },
            testTag = "temp_full_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_cpu_red),
            valueText = "-${t.cpuMaxReductionPct.roundToInt()}%",
            explanation = stringResource(R.string.thermal_cpu_red_desc),
            value = t.cpuMaxReductionPct,
            valueRange = 5f..85f,
            onValueChange = { onUpdateTempThrottle(null, null, null, it.roundToInt().toFloat(), null, null, null, null, null, null) },
            testTag = "temp_cpu_strength_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_gpu_red),
            valueText = "-${t.gpuMaxReductionPct.roundToInt()}%",
            explanation = stringResource(R.string.thermal_gpu_red_desc),
            value = t.gpuMaxReductionPct,
            valueRange = 5f..85f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, it.roundToInt().toFloat(), null, null, null, null, null) },
            testTag = "temp_gpu_strength_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.thermal_curve_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThrottleCurveShape.entries.forEach { shape ->
                val shapeRes = when (shape) {
                    ThrottleCurveShape.LINEAR -> R.string.thermal_curve_linear
                    ThrottleCurveShape.GENTLE -> R.string.thermal_curve_gentle
                    ThrottleCurveShape.AGGRESSIVE -> R.string.thermal_curve_aggressive
                }
                FilterChip(
                    selected = t.curveShape == shape,
                    onClick = { onUpdateTempThrottle(null, null, null, null, null, shape, null, null, null, null) },
                    label = { Text(stringResource(shapeRes), maxLines = 1) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("curve_${shape.name.lowercase()}")
                )
            }
        }
        Text(
            text = stringResource(R.string.thermal_curve_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_reaction_speed),
            valueText = "${(t.reactionSpeed * 100).roundToInt()}%",
            explanation = stringResource(R.string.thermal_reaction_desc),
            value = t.reactionSpeed,
            valueRange = 0.05f..1.0f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, null, null, it, null, null, null) },
            testTag = "temp_reaction_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.thermal_hysteresis),
            valueText = "${"%.1f".format(Locale.US, t.hysteresisC)} °C",
            explanation = stringResource(R.string.thermal_hysteresis_desc),
            value = t.hysteresisC,
            valueRange = 0.5f..6.0f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, null, null, null, it, null, null) },
            testTag = "temp_hysteresis_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = stringResource(R.string.thermal_chg_aware),
            explanation = stringResource(R.string.thermal_chg_aware_desc),
            checked = t.chargingAware,
            onCheckedChange = { onUpdateTempThrottle(null, null, null, null, null, null, null, null, it, null) },
            testTag = "temp_charging_aware_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        val chgOffset = t.chargingStartOffsetC.coerceIn(0f, 5f)
        val effectiveChgStart = (t.startTempC - chgOffset).coerceAtLeast(20f)
        SettingSliderRow(
            title = stringResource(R.string.thermal_chg_offset),
            valueText = "-${String.format(Locale.US, "%.1f", chgOffset)} °C",
            explanation = if (t.chargingAware) {
                stringResource(R.string.thermal_chg_offset_on, "%.1f".format(Locale.US, effectiveChgStart))
            } else {
                stringResource(R.string.thermal_chg_offset_off)
            },
            value = chgOffset,
            valueRange = 0f..5f,
            steps = 9,
            onValueChange = { raw ->
                val snapped = (raw * 2f).roundToInt() / 2f
                onUpdateTempThrottle(null, null, null, null, null, null, null, null, null, snapped)
            },
            enabled = t.chargingAware,
            testTag = "temp_charging_offset_slider"
        )
    }
}

@Composable
fun PowerThrottleSection(
    config: GovernorConfig,
    telemetryFlow: StateFlow<TelemetryState>,
    onUpdatePowerThrottle: (
        enabled: Boolean?,
        limitWatts: Float?,
        sustainMs: Long?,
        stepPct: Float?,
        releaseStepPct: Float?,
        averagingSamples: Int?,
        unitScale: PowerUnitScale?,
        polarity: PowerPolarity?,
        componentAware: Boolean?
    ) -> Unit
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val ext = LocalExtendedColors.current
    val p = config.powerThrottle

    EcuCard(accentBorder = if (telemetry.powerThrottleActive) ext.warning else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.power_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            val livePwrLabel = when (telemetry.batteryState) {
                BatteryState.CHARGING -> "\u26A1+${"%.2f".format(Locale.US, telemetry.chargeWatts)} W"
                BatteryState.FULL_PLUGGED -> stringResource(R.string.monitor_plug_val)
                BatteryState.DISCHARGING -> stringResource(
                    R.string.power_live_disch,
                    "%.2f".format(Locale.US, telemetry.watts)
                )
                BatteryState.UNKNOWN -> stringResource(R.string.power_live_unknown)
            }
            Text(
                text = livePwrLabel,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = if (telemetry.powerThrottleActive) ext.warning else ext.optimal
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.power_enable),
            explanation = stringResource(R.string.power_enable_desc),
            checked = p.enabled,
            onCheckedChange = { onUpdatePowerThrottle(it, null, null, null, null, null, null, null, null) },
            testTag = "power_throttle_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.power_comp_aware),
            explanation = stringResource(R.string.power_comp_aware_desc),
            checked = p.componentAware,
            onCheckedChange = { onUpdatePowerThrottle(null, null, null, null, null, null, null, null, it) },
            testTag = "power_component_aware_switch"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = stringResource(R.string.power_limit),
            valueText = "${"%.1f".format(Locale.US, p.limitWatts)} W",
            explanation = stringResource(R.string.power_limit_desc),
            value = p.limitWatts,
            valueRange = 2.0f..15.0f,
            onValueChange = { onUpdatePowerThrottle(null, it, null, null, null, null, null, null, null) },
            testTag = "power_limit_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.power_sustain),
            valueText = stringResource(R.string.power_sustain_val, "%.1f".format(Locale.US, p.sustainMs / 1000f)),
            explanation = stringResource(R.string.power_sustain_desc),
            value = p.sustainMs.toFloat(),
            valueRange = 500f..6000f,
            onValueChange = { onUpdatePowerThrottle(null, null, it.roundToLong(), null, null, null, null, null, null) },
            testTag = "power_sustain_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.power_step),
            valueText = "-${"%.1f".format(Locale.US, p.stepPct)}%",
            explanation = stringResource(R.string.power_step_desc),
            value = p.stepPct,
            valueRange = 1.0f..10.0f,
            onValueChange = { onUpdatePowerThrottle(null, null, null, it, null, null, null, null, null) },
            testTag = "power_step_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.power_release),
            valueText = "+${"%.1f".format(Locale.US, p.releaseStepPct)}%",
            explanation = stringResource(R.string.power_release_desc),
            value = p.releaseStepPct,
            valueRange = 0.3f..5.0f,
            onValueChange = { onUpdatePowerThrottle(null, null, null, null, it, null, null, null, null) },
            testTag = "power_release_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = stringResource(R.string.power_window),
            valueText = pluralStringResource(R.plurals.plural_samples, p.averagingSamples, p.averagingSamples),
            explanation = stringResource(R.string.power_window_desc),
            value = p.averagingSamples.toFloat(),
            valueRange = 2f..30f,
            steps = 27,
            onValueChange = { onUpdatePowerThrottle(null, null, null, null, null, it.roundToInt(), null, null, null) },
            testTag = "power_window_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.power_cal_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(
                R.string.power_cal_raw,
                telemetry.rawCurrentMa.toInt(),
                "%.3f".format(Locale.US, telemetry.rawVoltageMv / 1000f)
            ),
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PowerUnitScale.entries.forEach { scale ->
                val labelRes = when (scale) {
                    PowerUnitScale.AUTO -> R.string.common_auto
                    PowerUnitScale.UA_UV -> R.string.power_scale_ua_uv
                    PowerUnitScale.MA_MV -> R.string.power_scale_ma_mv
                    PowerUnitScale.UA_MV -> R.string.power_scale_ua_mv
                }
                FilterChip(
                    selected = p.unitScale == scale,
                    onClick = { onUpdatePowerThrottle(null, null, null, null, null, null, scale, null, null) },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PowerPolarity.entries.forEach { pol ->
                val labelRes = when (pol) {
                    PowerPolarity.AUTO_ABS -> R.string.power_pol_abs
                    PowerPolarity.DISCHARGE_NEGATIVE -> R.string.power_pol_neg
                    PowerPolarity.DISCHARGE_POSITIVE -> R.string.power_pol_pos
                    PowerPolarity.INVERT_SIGN -> R.string.power_pol_invert
                }
                FilterChip(
                    selected = p.polarity == pol,
                    onClick = { onUpdatePowerThrottle(null, null, null, null, null, null, null, pol, null) },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = stringResource(R.string.power_cal_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
