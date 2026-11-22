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

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.HardwareSpec
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.PresetItem
import com.example.model.TelemetryState
import com.example.model.ThrottleCurveShape
import com.example.ui.components.DiscreteFreqSlider
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import kotlin.math.roundToInt

private enum class TuningCategory(val label: String) {
    ALL("All"),
    RANGES("Ranges"),
    CONTROL("Control"),
    THERMAL("Thermal"),
    POWER("Power"),
    PRESETS("Presets & HW")
}

@Composable
fun TuningScreen(
    config: GovernorConfig,
    hardwareSpec: HardwareSpec,
    telemetry: TelemetryState,
    presets: List<PresetItem>,
    onUpdatePollInterval: (Long) -> Unit,
    onUpdateSafetyFloor: (Float) -> Unit,
    onUpdateStartOnBoot: (Boolean) -> Unit,
    onUpdateClusterRange: (GovernorMode, Int, FreqRange) -> Unit,
    onUpdateGpuRange: (GovernorMode, FreqRange) -> Unit,
    onUpdateModeScalingParams: (
        mode: GovernorMode,
        headroomPct: Float?,
        rampUpSpeed: Float?,
        rampDownSpeed: Float?,
        gpuHeadroomPct: Float?,
        gpuRampUpSpeed: Float?,
        gpuRampDownSpeed: Float?,
        launchBurstEnabled: Boolean?,
        launchBurstDurationMs: Long?,
        launchSpikeThresholdPct: Float?
    ) -> Unit,
    onSelectTempSensor: (String) -> Unit,
    onUpdateTempThrottle: (
        enabled: Boolean?,
        startTempC: Float?,
        fullTempC: Float?,
        cpuMaxReductionPct: Float?,
        gpuMaxReductionPct: Float?,
        curveShape: ThrottleCurveShape?,
        reactionSpeed: Float?,
        hysteresisC: Float?
    ) -> Unit,
    onUpdatePowerThrottle: (
        enabled: Boolean?,
        limitWatts: Float?,
        sustainMs: Long?,
        stepPct: Float?,
        releaseStepPct: Float?,
        averagingSamples: Int?,
        unitScale: PowerUnitScale?,
        polarity: PowerPolarity?
    ) -> Unit,
    onSavePreset: (String) -> Unit,
    onLoadPreset: (PresetItem) -> Unit,
    onDeletePreset: (PresetItem) -> Unit,
    onExportJson: (android.net.Uri) -> Boolean,
    onImportJson: (android.net.Uri) -> Boolean,
    onRediscoverHardware: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(TuningCategory.ALL) }
    var editingMode by remember { mutableStateOf(GovernorMode.NORMAL) }
    var presetNameInput by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = onExportJson(uri)
            Toast.makeText(
                context,
                if (ok) "Exported presets & config JSON" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = onImportJson(uri)
            Toast.makeText(
                context,
                if (ok) "Imported JSON configuration" else "Invalid JSON file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TuningCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat.label) },
                    modifier = Modifier.testTag("tuning_cat_${cat.name.lowercase()}")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Frequency Ranges (Normal vs Game Mode)
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.RANGES) {
                item {
                    val activeProfile = if (editingMode == GovernorMode.NORMAL) config.normalMode else config.gameMode
                    EcuCard {
                        Text(
                            text = "FREQUENCY RANGES BY MODE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GovernorMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = editingMode == mode,
                                    onClick = { editingMode = mode },
                                    label = {
                                        Text(if (mode == GovernorMode.NORMAL) "Normal Apps" else "Games Mode")
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("edit_mode_${mode.name.lowercase()}")
                                )
                            }
                        }
                        Text(
                            text = if (editingMode == GovernorMode.NORMAL) {
                                "Normal mode uses moderate max clocks for cool daily usage."
                            } else {
                                "Game mode keeps clocks pinned closer to your chosen ceiling until throttling starts."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (hardwareSpec.cpuPolicies.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Grant root access and tap Discover Hardware to configure per-cluster frequencies.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            hardwareSpec.cpuPolicies.forEach { policy ->
                                val freqs = policy.availableFreqsKhz
                                if (freqs.isNotEmpty()) {
                                    val range = activeProfile.cpuRangesKhz[policy.policyId]
                                        ?: FreqRange(freqs.first(), freqs.last())
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Policy ${policy.policyId} • ${policy.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    DiscreteFreqSlider(
                                        title = "Minimum Frequency",
                                        freqsKhz = freqs,
                                        selectedKhz = range.minKhz,
                                        onSelectKhz = { newMin ->
                                            val clampedMax = range.maxKhz.coerceAtLeast(newMin)
                                            onUpdateClusterRange(editingMode, policy.policyId, FreqRange(newMin, clampedMax))
                                        },
                                        explanation = "Lowest clock this cluster is allowed to drop to in ${editingMode.name.lowercase()} mode.",
                                        testTag = "p${policy.policyId}_min_slider"
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    DiscreteFreqSlider(
                                        title = "Maximum Frequency",
                                        freqsKhz = freqs,
                                        selectedKhz = range.maxKhz,
                                        onSelectKhz = { newMax ->
                                            val clampedMin = range.minKhz.coerceAtMost(newMax)
                                            onUpdateClusterRange(editingMode, policy.policyId, FreqRange(clampedMin, newMax))
                                        },
                                        explanation = "Highest clock allowed before thermal/power caps in ${editingMode.name.lowercase()} mode.",
                                        testTag = "p${policy.policyId}_max_slider"
                                    )
                                }
                            }
                        }

                        if (hardwareSpec.gpu.availableFreqsKhz.isNotEmpty()) {
                            val gpuFreqs = hardwareSpec.gpu.availableFreqsKhz
                            val gpuRange = activeProfile.gpuRangeKhz ?: FreqRange(gpuFreqs.first(), gpuFreqs.last())
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Mali GPU Range (${gpuFreqs.size} OPPs)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            DiscreteFreqSlider(
                                title = "GPU Minimum Frequency",
                                freqsKhz = gpuFreqs,
                                selectedKhz = gpuRange.minKhz,
                                onSelectKhz = { newMin ->
                                    onUpdateGpuRange(editingMode, FreqRange(newMin, gpuRange.maxKhz.coerceAtLeast(newMin)))
                                },
                                explanation = "Lowest GPU OPP position allowed in ${editingMode.name.lowercase()} mode.",
                                testTag = "gpu_min_slider"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DiscreteFreqSlider(
                                title = "GPU Maximum Frequency",
                                freqsKhz = gpuFreqs,
                                selectedKhz = gpuRange.maxKhz,
                                onSelectKhz = { newMax ->
                                    onUpdateGpuRange(editingMode, FreqRange(gpuRange.minKhz.coerceAtMost(newMax), newMax))
                                },
                                explanation = "Highest GPU OPP position pinned via /proc/gpufreqv2/fix_target_opp_index.",
                                testTag = "gpu_max_slider"
                            )
                        }
                    }
                }
            }

            // 2. Core Control Loop & Mode Dynamics
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.CONTROL) {
                item {
                    val activeProfile = if (editingMode == GovernorMode.NORMAL) config.normalMode else config.gameMode
                    EcuCard {
                        Text(
                            text = "CONTROL LOOP & RESPONSE (${editingMode.name})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "Poll Interval",
                            valueText = "${config.pollIntervalMs} ms",
                            explanation = "How often the loop reads /proc/stat, GPU load, thermals, and updates clocks (100–250 ms recommended).",
                            value = config.pollIntervalMs.toFloat(),
                            valueRange = 80f..350f,
                            onValueChange = { onUpdatePollInterval(it.roundToInt().toLong()) },
                            testTag = "poll_interval_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "Safety Floor Cap",
                            valueText = "${config.safetyFloorPct.roundToInt()}%",
                            explanation = "Hard minimum ceiling percentage so thermal or power throttling can never stall the phone.",
                            value = config.safetyFloorPct,
                            valueRange = 10f..70f,
                            onValueChange = { onUpdateSafetyFloor(it.roundToInt().toFloat()) },
                            testTag = "safety_floor_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "CPU Load Headroom (${editingMode.name})",
                            valueText = "+${activeProfile.headroomPct.roundToInt()}%",
                            explanation = "Adds extra load margin before grading CPU frequencies to avoid sluggishness on sudden demand.",
                            value = activeProfile.headroomPct,
                            valueRange = 0f..40f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, it.roundToInt().toFloat(), null, null, null, null, null, null, null, null)
                            },
                            testTag = "headroom_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "CPU Ramp-Up Speed (${editingMode.name})",
                            valueText = "${(activeProfile.rampUpSpeed * 100).roundToInt()}%",
                            explanation = "Higher values jump CPU clocks upward fast when load spikes so frames never stutter.",
                            value = activeProfile.rampUpSpeed,
                            valueRange = 0.2f..1.0f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, it, null, null, null, null, null, null, null)
                            },
                            testTag = "ramp_up_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "CPU Ramp-Down Speed (${editingMode.name})",
                            valueText = "${(activeProfile.rampDownSpeed * 100).roundToInt()}%",
                            explanation = "Lower values hold higher CPU clocks briefly after load drops; keep very low in Games mode.",
                            value = activeProfile.rampDownSpeed,
                            valueRange = 0.02f..0.60f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, it, null, null, null, null, null, null)
                            },
                            testTag = "ramp_down_slider"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingSliderRow(
                            title = "GPU Load Headroom (${editingMode.name})",
                            valueText = "+${activeProfile.gpuHeadroomPct.roundToInt()}%",
                            explanation = "Raises effective GED GPU utilization before picking a position in the sorted OPP table.",
                            value = activeProfile.gpuHeadroomPct,
                            valueRange = 0f..40f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, it.roundToInt().toFloat(), null, null, null, null, null)
                            },
                            testTag = "gpu_headroom_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "GPU Ramp-Up Speed (${editingMode.name})",
                            valueText = "${(activeProfile.gpuRampUpSpeed * 100).roundToInt()}%",
                            explanation = "Controls how aggressively GPU OPP index climbs on shader/render load spikes.",
                            value = activeProfile.gpuRampUpSpeed,
                            valueRange = 0.2f..1.0f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, null, it, null, null, null, null)
                            },
                            testTag = "gpu_ramp_up_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = "GPU Ramp-Down Speed (${editingMode.name})",
                            valueText = "${(activeProfile.gpuRampDownSpeed * 100).roundToInt()}%",
                            explanation = "Controls how slowly GPU OPP index steps down after GPU load drops.",
                            value = activeProfile.gpuRampDownSpeed,
                            valueRange = 0.02f..0.60f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, null, null, it, null, null, null)
                            },
                            testTag = "gpu_ramp_down_slider"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingSwitchRow(
                            title = "Normal Mode Launch Burst",
                            explanation = "Briefly boosts to top allowed clock on app switch or sudden load spike, then settles back.",
                            checked = config.normalMode.launchBurstEnabled,
                            onCheckedChange = {
                                onUpdateModeScalingParams(GovernorMode.NORMAL, null, null, null, null, null, null, it, null, null)
                            },
                            testTag = "launch_burst_switch"
                        )

                        if (config.normalMode.launchBurstEnabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingSliderRow(
                                title = "Launch Burst Duration",
                                valueText = "${config.normalMode.launchBurstDurationMs} ms",
                                explanation = "Length of the launch burst window (up to 2000 ms) before settling to normal scaling.",
                                value = config.normalMode.launchBurstDurationMs.toFloat(),
                                valueRange = 200f..2500f,
                                onValueChange = {
                                    onUpdateModeScalingParams(GovernorMode.NORMAL, null, null, null, null, null, null, null, it.roundToInt().toLong(), null)
                                },
                                testTag = "launch_burst_duration_slider"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSliderRow(
                                title = "Load Spike Burst Trigger",
                                valueText = "+${config.normalMode.launchSpikeThresholdPct.roundToInt()}%",
                                explanation = "Single-poll CPU load jump that triggers a burst even within the same app.",
                                value = config.normalMode.launchSpikeThresholdPct,
                                valueRange = 15f..75f,
                                onValueChange = {
                                    onUpdateModeScalingParams(GovernorMode.NORMAL, null, null, null, null, null, null, null, null, it.roundToInt().toFloat())
                                },
                                testTag = "launch_spike_slider"
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        SettingSwitchRow(
                            title = "Start Automatically on Boot",
                            explanation = "Launches CoreGovernor foreground service automatically after Android finishes booting.",
                            checked = config.startOnBoot,
                            onCheckedChange = onUpdateStartOnBoot,
                            testTag = "boot_start_switch"
                        )
                    }
                }
            }

            // 3. Temperature Throttling
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.THERMAL) {
                item {
                    ThermalThrottleSection(
                        config = config,
                        hardwareSpec = hardwareSpec,
                        telemetry = telemetry,
                        onSelectTempSensor = onSelectTempSensor,
                        onUpdateTempThrottle = onUpdateTempThrottle
                    )
                }
            }

            // 4. Power Throttling
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.POWER) {
                item {
                    PowerThrottleSection(
                        config = config,
                        telemetry = telemetry,
                        onUpdatePowerThrottle = onUpdatePowerThrottle
                    )
                }
            }

            // 5. Presets, JSON Export/Import & Hardware Details
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.PRESETS) {
                item {
                    EcuCard {
                        Text(
                            text = "PRESETS & JSON BACKUP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = presetNameInput,
                                onValueChange = { presetNameInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("preset_name_input"),
                                placeholder = { Text("New preset name...") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (presetNameInput.isNotBlank()) {
                                        onSavePreset(presetNameInput)
                                        presetNameInput = ""
                                    }
                                },
                                modifier = Modifier.testTag("save_preset_button")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Save preset")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        presets.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = preset.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (preset.builtIn) "Built-in profile" else "User preset",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    OutlinedButton(
                                        onClick = { onLoadPreset(preset) },
                                        modifier = Modifier.testTag("load_preset_${preset.id}")
                                    ) {
                                        Text("Load")
                                    }
                                    if (!preset.builtIn) {
                                        IconButton(onClick = { onDeletePreset(preset) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete preset")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { exportLauncher.launch("coregovernor_config.json") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("export_json_button")
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export JSON")
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_json_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import JSON")
                            }
                        }
                    }
                }

                item {
                    EcuCard {
                        Text(
                            text = "DISCOVERED HARDWARE SUMMARY",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "CPU Policies: ${hardwareSpec.cpuPolicies.size} | GPU OPPs: ${hardwareSpec.gpu.oppTable.size} | Thermal Sensors: ${hardwareSpec.thermalSensors.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "OPP Table: ${hardwareSpec.gpu.oppTableSourcePath ?: "none"} (${hardwareSpec.gpu.detectedOrderNote})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "GED Load Nodes: ${hardwareSpec.gpu.gedLoadCandidatePaths.joinToString().ifBlank { "none" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Battery Nodes: ${hardwareSpec.batteryPower.currentPath ?: "none"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onRediscoverHardware,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tuning_rediscover_button")
                        ) {
                            Text("Re-Probe Hardware Nodes")
                        }
                    }
                }
            }
        }
    }
}
