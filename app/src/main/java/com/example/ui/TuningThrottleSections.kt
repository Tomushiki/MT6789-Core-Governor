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
import androidx.compose.ui.unit.dp
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
            text = "TEMPERATURE THROTTLING",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = "Enable Temperature Throttling",
            explanation = "Gradually lowers CPU and GPU max frequency caps as the chosen thermal sensor heats up.",
            checked = t.enabled,
            onCheckedChange = { onUpdateTempThrottle(it, null, null, null, null, null, null, null, null, null) },
            testTag = "temp_throttle_switch"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Sensor Selector
        Text(
            text = "Driving Temperature Sensor",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        val selectedLabel = hardwareSpec.thermalSensors.firstOrNull { it.path == config.selectedTempSensorPath }?.label
            ?: hardwareSpec.thermalSensors.firstOrNull()?.label
            ?: "No sensors discovered yet"
        Box {
            OutlinedButton(
                onClick = { sensorMenuExpanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("temp_sensor_dropdown")
            ) {
                Text(
                    text = "$selectedLabel (${"%.1f".format(telemetry.temperatureC)} °C)",
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
                            Text("${sensor.label} • ${"%.1f".format(sensor.initialTempC)}°C")
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
            text = "Selects whether battery, SoC, or CPU thermal zone drives the thermal curve.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        SettingSliderRow(
            title = "Throttle Start Temperature",
            valueText = "${t.startTempC.roundToInt()} °C",
            explanation = "Temperature where cap reduction begins smoothly from 100%.",
            value = t.startTempC,
            valueRange = 30f..65f,
            onValueChange = { onUpdateTempThrottle(null, it.roundToInt().toFloat(), null, null, null, null, null, null, null, null) },
            testTag = "temp_start_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Full-Strength Temperature",
            valueText = "${t.fullTempC.roundToInt()} °C",
            explanation = "Temperature where thermal throttling reaches its full configured reduction.",
            value = t.fullTempC,
            valueRange = (t.startTempC + 2f)..85f,
            onValueChange = { onUpdateTempThrottle(null, null, it.roundToInt().toFloat(), null, null, null, null, null, null, null) },
            testTag = "temp_full_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "CPU Max Reduction",
            valueText = "-${t.cpuMaxReductionPct.roundToInt()}%",
            explanation = "Maximum percentage cut applied to CPU clusters at full-strength temperature.",
            value = t.cpuMaxReductionPct,
            valueRange = 5f..85f,
            onValueChange = { onUpdateTempThrottle(null, null, null, it.roundToInt().toFloat(), null, null, null, null, null, null) },
            testTag = "temp_cpu_strength_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "GPU Max Reduction",
            valueText = "-${t.gpuMaxReductionPct.roundToInt()}%",
            explanation = "Maximum percentage cut applied to Mali GPU frequency range at full-strength temperature.",
            value = t.gpuMaxReductionPct,
            valueRange = 5f..85f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, it.roundToInt().toFloat(), null, null, null, null, null) },
            testTag = "temp_gpu_strength_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Throttle Curve Shape",
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThrottleCurveShape.entries.forEach { shape ->
                FilterChip(
                    selected = t.curveShape == shape,
                    onClick = { onUpdateTempThrottle(null, null, null, null, null, shape, null, null, null, null) },
                    label = { Text(shape.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("curve_${shape.name.lowercase()}")
                )
            }
        }
        Text(
            text = "Gentle delays heavy cuts until hot; Aggressive trims clocks early near start temp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = "Thermal Reaction Speed",
            valueText = "${(t.reactionSpeed * 100).roundToInt()}%",
            explanation = "Controls how quickly the thermal cap adapts to temperature changes.",
            value = t.reactionSpeed,
            valueRange = 0.05f..1.0f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, null, null, it, null, null, null) },
            testTag = "temp_reaction_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Recovery Hysteresis",
            valueText = "${"%.1f".format(t.hysteresisC)} °C",
            explanation = "Temperature must cool down by this margin before the thermal cap relaxes.",
            value = t.hysteresisC,
            valueRange = 0.5f..6.0f,
            onValueChange = { onUpdateTempThrottle(null, null, null, null, null, null, null, it, null, null) },
            testTag = "temp_hysteresis_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = "Charging-aware thermal",
            explanation = "Lowers throttle start temp slightly while charging or plugged in to account for charger heat.",
            checked = t.chargingAware,
            onCheckedChange = { onUpdateTempThrottle(null, null, null, null, null, null, null, null, it, null) },
            testTag = "temp_charging_aware_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        val chgOffset = t.chargingStartOffsetC.coerceIn(0f, 5f)
        val effectiveChgStart = (t.startTempC - chgOffset).coerceAtLeast(20f)
        SettingSliderRow(
            title = "Charging start offset",
            valueText = "-${String.format(Locale.US, "%.1f", chgOffset)} °C",
            explanation = if (t.chargingAware) {
                "Starts thermal throttling at ${"%.1f".format(Locale.US, effectiveChgStart)} °C when charging/plugged."
            } else {
                "Enable charging-aware thermal above to offset start temperature when plugged in."
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
                text = "POWER THROTTLING (GENTLE)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            val livePwrLabel = when (telemetry.batteryState) {
                BatteryState.CHARGING -> "\u26A1+${"%.2f".format(Locale.US, telemetry.chargeWatts)} W"
                BatteryState.FULL_PLUGGED -> "PLUG 0.0 W"
                BatteryState.DISCHARGING -> "Live: ${"%.2f".format(Locale.US, telemetry.watts)} W"
                BatteryState.UNKNOWN -> "Live: -- W"
            }
            Text(
                text = livePwrLabel,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = if (telemetry.powerThrottleActive) ext.warning else ext.optimal
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = "Enable Power Throttling",
            explanation = "Gently steps down clock caps when sustained wattage stays above your budget to prevent heat buildup.",
            checked = p.enabled,
            onCheckedChange = { onUpdatePowerThrottle(it, null, null, null, null, null, null, null, null) },
            testTag = "power_throttle_switch"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = "Smart split by component",
            explanation = "When power is over the limit, throttle the heavier consumer (CPU or GPU, and the busiest CPU cluster) more.",
            checked = p.componentAware,
            onCheckedChange = { onUpdatePowerThrottle(null, null, null, null, null, null, null, null, it) },
            testTag = "power_component_aware_switch"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            title = "Power Budget Limit",
            valueText = "${"%.1f".format(p.limitWatts)} W",
            explanation = "Average device wattage threshold above which the sustain timer begins counting.",
            value = p.limitWatts,
            valueRange = 2.0f..15.0f,
            onValueChange = { onUpdatePowerThrottle(null, it, null, null, null, null, null, null, null) },
            testTag = "power_limit_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Sustain Time Above Limit",
            valueText = "${"%.1f".format(p.sustainMs / 1000f)} s",
            explanation = "How long draw must stay above the watt limit before reducing the cap by one step.",
            value = p.sustainMs.toFloat(),
            valueRange = 500f..6000f,
            onValueChange = { onUpdatePowerThrottle(null, null, it.roundToLong(), null, null, null, null, null, null) },
            testTag = "power_sustain_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Throttle Step Size",
            valueText = "-${"%.1f".format(p.stepPct)}%",
            explanation = "Small percentage cut applied per step while power remains over budget.",
            value = p.stepPct,
            valueRange = 1.0f..10.0f,
            onValueChange = { onUpdatePowerThrottle(null, null, null, it, null, null, null, null, null) },
            testTag = "power_step_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Slow Release Step",
            valueText = "+${"%.1f".format(p.releaseStepPct)}%",
            explanation = "Gradual cap recovery per cycle once wattage drops safely below the budget.",
            value = p.releaseStepPct,
            valueRange = 0.3f..5.0f,
            onValueChange = { onUpdatePowerThrottle(null, null, null, null, it, null, null, null, null) },
            testTag = "power_release_slider"
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingSliderRow(
            title = "Wattage Averaging Window",
            valueText = "${p.averagingSamples} samples",
            explanation = "Smooths brief battery current spikes over N poll cycles before comparing against limit.",
            value = p.averagingSamples.toFloat(),
            valueRange = 2f..30f,
            steps = 27,
            onValueChange = { onUpdatePowerThrottle(null, null, null, null, null, it.roundToInt(), null, null, null) },
            testTag = "power_window_slider"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Current & Voltage Calibration",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "Raw sensor: ${telemetry.rawCurrentMa.toInt()} mA @ ${(telemetry.rawVoltageMv / 1000f).let { "%.3f".format(it) }} V",
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PowerUnitScale.entries.forEach { scale ->
                val label = when (scale) {
                    PowerUnitScale.AUTO -> "Auto"
                    PowerUnitScale.UA_UV -> "µA/µV"
                    PowerUnitScale.MA_MV -> "mA/mV"
                    PowerUnitScale.UA_MV -> "µA/mV"
                }
                FilterChip(
                    selected = p.unitScale == scale,
                    onClick = { onUpdatePowerThrottle(null, null, null, null, null, null, scale, null, null) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
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
                val label = when (pol) {
                    PowerPolarity.AUTO_ABS -> "|I| Abs"
                    PowerPolarity.DISCHARGE_NEGATIVE -> "Disch -"
                    PowerPolarity.DISCHARGE_POSITIVE -> "Disch +"
                    PowerPolarity.INVERT_SIGN -> "Invert"
                }
                FilterChip(
                    selected = p.polarity == pol,
                    onClick = { onUpdatePowerThrottle(null, null, null, null, null, null, null, pol, null) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "Match your kernel's sysfs unit scale and discharge sign convention so live watts read accurately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
