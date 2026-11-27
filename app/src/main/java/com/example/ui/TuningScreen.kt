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

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.GameThreadInspectionResult
import com.example.governor.SensorPinner
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.overlayCompactScaleFor
import com.example.model.overlayExpandedScaleFor
import com.example.model.overlayPosPm
import com.example.model.overlayBgOpacityFor
import com.example.model.overlayTextScaleFor
import com.example.model.OVERLAY_TEXT_SCALE_MAX_PCT
import com.example.model.OVERLAY_TEXT_SCALE_MIN_PCT
import com.example.model.overlayPosPmFor
import com.example.model.overlayScaleFor
import com.example.model.GovernorMode
import com.example.model.HardwareSpec
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.PresetItem
import com.example.model.TelemetryState
import com.example.model.ThreadAffinityMapping
import com.example.model.ThrottleCurveShape
import com.example.service.BootState
import com.example.service.OverlaySizing
import com.example.service.OverlayStyle
import com.example.ui.components.DiscreteFreqSlider
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.MonoMetricStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class TuningCategory(@StringRes val labelRes: Int) {
    ALL(R.string.tuning_cat_all),
    RANGES(R.string.tuning_cat_ranges),
    CONTROL(R.string.tuning_cat_control),
    SCREEN_OFF(R.string.tuning_cat_screen_off),
    OVERLAY(R.string.tuning_cat_overlay),
    NIFTY(R.string.tuning_cat_nifty),
    THERMAL(R.string.tuning_cat_thermal),
    POWER(R.string.tuning_cat_power),
    GAME_THREADS(R.string.tuning_cat_game_threads),
    PRESETS(R.string.tuning_cat_presets)
}

/** Slow-changing telemetry facts the Tuning screen needs (a change here is rare, unlike a live tick). */
private data class TuningTelemetryInfo(
    val ddrSupported: Boolean,
    val isRunning: Boolean,
    val clusterCount: Int,
    val clusterCpuCount: Int
)

@Composable
fun TuningScreen(
    config: GovernorConfig,
    hardwareSpec: HardwareSpec,
    telemetryFlow: StateFlow<TelemetryState>,
    presets: List<PresetItem>,
    onUpdatePollInterval: (Long) -> Unit,
    onUpdateAdaptivePolling: (Boolean) -> Unit,
    onUpdateSafetyFloor: (Float) -> Unit,
    onUpdateStartOnBoot: (Boolean) -> Unit,
    onUpdateCpuLoadUsesBusiestCore: (Boolean) -> Unit,
    onUpdateScreenOffSaving: (
        enabled: Boolean?,
        delaySec: Int?,
        exemptMusic: Boolean?,
        exemptCharging: Boolean?
    ) -> Unit,
    onUpdateOverlay: (
        enabled: Boolean?,
        clickThrough: Boolean?,
        gameOnly: Boolean?,
        opacityPct: Int?,
        compactScalePct: Int?,
        expandedScalePct: Int?,
        bgOpacityPct: Int?,
        isLandscape: Boolean
    ) -> Unit,
    onUpdateOverlayPosition: (Int?, Int?, Boolean, Boolean) -> Unit,
    onUpdateOverlayStyle: (OverlayStyle, Boolean, Int?, Int?, Int?, Int?, Boolean) -> Unit,
    onSetOverlayTextScale: (OverlayStyle, Int) -> Unit,
    onUpdateRamPin: (Boolean?, Boolean?) -> Unit,
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
        hysteresisC: Float?,
        chargingAware: Boolean?,
        chargingStartOffsetC: Float?
    ) -> Unit,
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
    ) -> Unit,
    onSetSmoothPowerLimit: (Boolean) -> Unit,
    onSetSmoothMinConfidence: (Int) -> Unit,
    onSetSmoothSliceMs: (Int) -> Unit,
    onSetSmoothSliceAuto: (Boolean) -> Unit,
    onSetSmoothFixDrift: (Boolean) -> Unit,
    onUpdateAfterburn: (
        enabled: Boolean?,
        strength: com.example.model.BoostStrength?,
        thermalConditionEnabled: Boolean?,
        thermalMarginC: Float?,
        batteryConditionEnabled: Boolean?,
        batteryThresholdPct: Int?,
        voltageSagEnabled: Boolean?,
        toastEnabled: Boolean?
    ) -> Unit,
    onSetBoostStrength: (String, com.example.model.BoostStrength?) -> Unit,
    onSavePreset: (String) -> Unit,
    onLoadPreset: (PresetItem) -> Unit,
    onDeletePreset: (PresetItem) -> Unit,
    onExportJson: (android.net.Uri) -> Boolean,
    onImportJson: (android.net.Uri) -> Boolean,
    onRediscoverHardware: () -> Unit,
    sensorPin: SensorPinner.State,
    onUpdateSensorPin: (
        enabled: Boolean?,
        rate: SensorPinner.Rate?,
        toggleType: Int?,
        pauseWhenScreenOff: Boolean?,
        gameOnly: Boolean?
    ) -> Unit,
    protectionStatus: com.example.governor.ProtectionStatus = com.example.governor.ProtectionStatus(),
    onSetProtectFromKill: (Boolean) -> Unit = {},
    onReverifyProtection: () -> Unit = {},
    stutterHunterStatus: com.example.governor.StutterHunterStatus = com.example.governor.StutterHunterStatus(),
    installedApps: List<com.example.model.InstalledAppInfo> = emptyList(),
    onUpdateStutterHunter: (
        enabled: Boolean?,
        mode: com.example.model.StutterHunterMode?,
        dipPct: Int?,
        cpuThresholdPct: Int?
    ) -> Unit = { _, _, _, _ -> },
    onToggleHunterWhitelist: (String) -> Unit = {},
    gameThreadInspection: GameThreadInspectionResult = GameThreadInspectionResult(),
    isRefreshingGameThreads: Boolean = false,
    onRefreshGameThreads: () -> Unit = {},
    onSetGameThreadEnabled: (Boolean) -> Unit = {},
    onSetGameThreadAutoClassify: (Boolean) -> Unit = {},
    onSetGameThreadSelectedGame: (String) -> Unit = {},
    onUpsertThreadMappings: (List<ThreadAffinityMapping>) -> Unit = {},
    onUpdateThreadMapping: (Int, ThreadAffinityMapping) -> Unit = { _, _ -> },
    onRemoveThreadMapping: (Int) -> Unit = {},
    onRemoveHistoricalThread: (String, String) -> Unit = { _, _ -> },
    onClearGameThreadHistory: (String) -> Unit = {},
    onClearAllThreadHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Only the slow-changing bits of telemetry are read here, so this screen does not recompose on every tick.
    val tuningInfo by remember(telemetryFlow) {
        telemetryFlow
            .map { t ->
                TuningTelemetryInfo(
                    ddrSupported = t.ddrSupported,
                    isRunning = t.isRunning,
                    clusterCount = t.clusters.size,
                    clusterCpuCount = t.clusters.sumOf { it.relatedCpus.size }
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = telemetryFlow.value.let { t ->
            TuningTelemetryInfo(
                ddrSupported = t.ddrSupported,
                isRunning = t.isRunning,
                clusterCount = t.clusters.size,
                clusterCpuCount = t.clusters.sumOf { it.relatedCpus.size }
            )
        }
    )
    var selectedCategory by rememberSaveable { mutableStateOf(TuningCategory.ALL) }
    var editingMode by rememberSaveable { mutableStateOf(GovernorMode.NORMAL) }
    var presetNameInput by remember { mutableStateOf("") }
    var fineOverlayControl by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == TuningCategory.GAME_THREADS) {
            onRefreshGameThreads()
        }
    }

    BackHandler(enabled = selectedCategory != TuningCategory.ALL) {
        selectedCategory = TuningCategory.ALL
    }

    val editingModeLabel = stringResource(
        if (editingMode == GovernorMode.NORMAL) R.string.mode_normal else R.string.mode_game
    )
    val editingModeLower = editingModeLabel.lowercase(Locale.getDefault())

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = onExportJson(uri)
            Toast.makeText(
                context,
                context.getString(
                    if (ok) R.string.tuning_toast_exported else R.string.tuning_toast_export_failed
                ),
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
                context.getString(
                    if (ok) R.string.tuning_toast_imported else R.string.tuning_toast_import_failed
                ),
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
                    label = { Text(stringResource(cat.labelRes)) },
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
                            text = stringResource(R.string.tuning_ranges_header),
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
                                        Text(
                                            stringResource(
                                                if (mode == GovernorMode.NORMAL) R.string.tuning_mode_normal_apps
                                                else R.string.tuning_mode_games
                                            )
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("edit_mode_${mode.name.lowercase()}")
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                if (editingMode == GovernorMode.NORMAL) R.string.tuning_ranges_normal_desc
                                else R.string.tuning_ranges_game_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (hardwareSpec.cpuPolicies.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.tuning_ranges_no_policies),
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
                                        text = stringResource(R.string.monitor_policy_header, policy.policyId, policy.name),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    DiscreteFreqSlider(
                                        title = stringResource(R.string.tuning_min_freq),
                                        freqsKhz = freqs,
                                        selectedKhz = range.minKhz,
                                        onSelectKhz = { newMin ->
                                            val clampedMax = range.maxKhz.coerceAtLeast(newMin)
                                            onUpdateClusterRange(editingMode, policy.policyId, FreqRange(newMin, clampedMax))
                                        },
                                        explanation = stringResource(R.string.tuning_min_freq_desc, editingModeLower),
                                        testTag = "p${policy.policyId}_min_slider"
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    DiscreteFreqSlider(
                                        title = stringResource(R.string.tuning_max_freq),
                                        freqsKhz = freqs,
                                        selectedKhz = range.maxKhz,
                                        onSelectKhz = { newMax ->
                                            val clampedMin = range.minKhz.coerceAtMost(newMax)
                                            onUpdateClusterRange(editingMode, policy.policyId, FreqRange(clampedMin, newMax))
                                        },
                                        explanation = stringResource(R.string.tuning_max_freq_desc, editingModeLower),
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
                                text = stringResource(R.string.tuning_gpu_range_title, gpuFreqs.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            DiscreteFreqSlider(
                                title = stringResource(R.string.tuning_gpu_min_freq),
                                freqsKhz = gpuFreqs,
                                selectedKhz = gpuRange.minKhz,
                                onSelectKhz = { newMin ->
                                    onUpdateGpuRange(editingMode, FreqRange(newMin, gpuRange.maxKhz.coerceAtLeast(newMin)))
                                },
                                explanation = stringResource(R.string.tuning_gpu_min_desc, editingModeLower),
                                testTag = "gpu_min_slider"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DiscreteFreqSlider(
                                title = stringResource(R.string.tuning_gpu_max_freq),
                                freqsKhz = gpuFreqs,
                                selectedKhz = gpuRange.maxKhz,
                                onSelectKhz = { newMax ->
                                    onUpdateGpuRange(editingMode, FreqRange(gpuRange.minKhz.coerceAtMost(newMax), newMax))
                                },
                                explanation = stringResource(R.string.tuning_gpu_max_desc),
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
                    val modeCaps = editingModeLabel.uppercase(Locale.getDefault())
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_control_header, modeCaps),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_poll_interval),
                            valueText = stringResource(R.string.tuning_poll_interval_val, config.pollIntervalMs.toInt()),
                            explanation = stringResource(R.string.tuning_poll_interval_desc),
                            value = config.pollIntervalMs.toFloat(),
                            valueRange = 80f..350f,
                            onValueChange = { onUpdatePollInterval(it.roundToInt().toLong()) },
                            testTag = "poll_interval_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_adaptive_poll),
                            explanation = stringResource(R.string.tuning_adaptive_poll_desc),
                            checked = config.adaptivePollingEnabled,
                            onCheckedChange = onUpdateAdaptivePolling,
                            testTag = "adaptive_polling_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_safety_floor),
                            valueText = "${config.safetyFloorPct.roundToInt()}%",
                            explanation = stringResource(R.string.tuning_safety_floor_desc),
                            value = config.safetyFloorPct,
                            valueRange = 10f..70f,
                            onValueChange = { onUpdateSafetyFloor(it.roundToInt().toFloat()) },
                            testTag = "safety_floor_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_cpu_headroom, editingModeLabel),
                            valueText = "+${activeProfile.headroomPct.roundToInt()}%",
                            explanation = stringResource(R.string.tuning_cpu_headroom_desc),
                            value = activeProfile.headroomPct,
                            valueRange = 0f..40f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, it.roundToInt().toFloat(), null, null, null, null, null, null, null, null)
                            },
                            testTag = "headroom_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_cpu_ramp_up, editingModeLabel),
                            valueText = "${(activeProfile.rampUpSpeed * 100).roundToInt()}%",
                            explanation = stringResource(R.string.tuning_cpu_ramp_up_desc),
                            value = activeProfile.rampUpSpeed,
                            valueRange = 0.2f..1.0f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, it, null, null, null, null, null, null, null)
                            },
                            testTag = "ramp_up_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_cpu_ramp_down, editingModeLabel),
                            valueText = "${(activeProfile.rampDownSpeed * 100).roundToInt()}%",
                            explanation = stringResource(R.string.tuning_cpu_ramp_down_desc),
                            value = activeProfile.rampDownSpeed,
                            valueRange = 0.02f..0.60f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, it, null, null, null, null, null, null)
                            },
                            testTag = "ramp_down_slider"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_gpu_headroom, editingModeLabel),
                            valueText = "+${activeProfile.gpuHeadroomPct.roundToInt()}%",
                            explanation = stringResource(R.string.tuning_gpu_headroom_desc),
                            value = activeProfile.gpuHeadroomPct,
                            valueRange = 0f..40f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, it.roundToInt().toFloat(), null, null, null, null, null)
                            },
                            testTag = "gpu_headroom_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_gpu_ramp_up, editingModeLabel),
                            valueText = "${(activeProfile.gpuRampUpSpeed * 100).roundToInt()}%",
                            explanation = stringResource(R.string.tuning_gpu_ramp_up_desc),
                            value = activeProfile.gpuRampUpSpeed,
                            valueRange = 0.2f..1.0f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, null, it, null, null, null, null)
                            },
                            testTag = "gpu_ramp_up_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSliderRow(
                            title = stringResource(R.string.tuning_gpu_ramp_down, editingModeLabel),
                            valueText = "${(activeProfile.gpuRampDownSpeed * 100).roundToInt()}%",
                            explanation = stringResource(R.string.tuning_gpu_ramp_down_desc),
                            value = activeProfile.gpuRampDownSpeed,
                            valueRange = 0.02f..0.60f,
                            onValueChange = {
                                onUpdateModeScalingParams(editingMode, null, null, null, null, null, it, null, null, null)
                            },
                            testTag = "gpu_ramp_down_slider"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_launch_burst),
                            explanation = stringResource(R.string.tuning_launch_burst_desc),
                            checked = config.normalMode.launchBurstEnabled,
                            onCheckedChange = {
                                onUpdateModeScalingParams(GovernorMode.NORMAL, null, null, null, null, null, null, it, null, null)
                            },
                            testTag = "launch_burst_switch"
                        )

                        if (config.normalMode.launchBurstEnabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingSliderRow(
                                title = stringResource(R.string.tuning_launch_burst_dur),
                                valueText = stringResource(R.string.tuning_poll_interval_val, config.normalMode.launchBurstDurationMs.toInt()),
                                explanation = stringResource(R.string.tuning_launch_burst_dur_desc),
                                value = config.normalMode.launchBurstDurationMs.toFloat(),
                                valueRange = 200f..2500f,
                                onValueChange = {
                                    onUpdateModeScalingParams(GovernorMode.NORMAL, null, null, null, null, null, null, null, it.roundToInt().toLong(), null)
                                },
                                testTag = "launch_burst_duration_slider"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSliderRow(
                                title = stringResource(R.string.tuning_launch_spike),
                                valueText = "+${config.normalMode.launchSpikeThresholdPct.roundToInt()}%",
                                explanation = stringResource(R.string.tuning_launch_spike_desc),
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
                            title = stringResource(R.string.tuning_busiest_core),
                            explanation = stringResource(R.string.tuning_busiest_core_desc),
                            checked = config.cpuLoadUsesBusiestCore,
                            onCheckedChange = onUpdateCpuLoadUsesBusiestCore,
                            testTag = "cpu_load_busiest_switch"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_start_on_boot),
                            explanation = stringResource(R.string.tuning_start_on_boot_desc),
                            checked = config.startOnBoot,
                            onCheckedChange = onUpdateStartOnBoot,
                            testTag = "boot_start_switch"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        val bootContext = LocalContext.current
                        var restoreLastState by remember { mutableStateOf(BootState.restoreEnabled(bootContext)) }
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_restore_last_state),
                            explanation = stringResource(R.string.tuning_restore_last_state_desc),
                            checked = restoreLastState,
                            onCheckedChange = {
                                restoreLastState = it
                                BootState.setRestoreEnabled(bootContext, it)
                            },
                            testTag = "restore_last_state_switch"
                        )
                        val guardAt = remember { BootState.guardTrippedAt(bootContext) }
                        if (guardAt > 0L) {
                            Text(
                                text = stringResource(R.string.tuning_boot_guard_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2a-ter. Background keep-alive (battery optimization + root protection)
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.CONTROL) {
                item {
                    ProtectionCard(
                        enabled = config.protectFromKillEnabled,
                        status = protectionStatus,
                        onToggle = onSetProtectFromKill,
                        onReverify = onReverifyProtection
                    )
                }
                item { BatteryOptimizationCard() }
            }

            // 2a-bis. Memory (RAM)
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.CONTROL) {
                item {
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_ram_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (!tuningInfo.ddrSupported) {
                            Text(
                                text = stringResource(R.string.tuning_ram_unsupported),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SettingSwitchRow(
                                title = stringResource(R.string.tuning_ram_pin_max),
                                explanation = stringResource(R.string.tuning_ram_pin_max_desc),
                                checked = config.ramPinMax,
                                onCheckedChange = { onUpdateRamPin(it, null) },
                                testTag = "ram_pin_max_switch"
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            SettingSwitchRow(
                                title = stringResource(R.string.tuning_ram_game_only),
                                explanation = stringResource(R.string.tuning_ram_game_only_desc),
                                checked = config.ramPinGameOnly,
                                onCheckedChange = { onUpdateRamPin(null, it) },
                                enabled = config.ramPinMax,
                                testTag = "ram_pin_game_only_switch"
                            )
                        }
                    }
                }
            }

            // 2b. Screen-off power saving
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.SCREEN_OFF) {
                item {
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_screen_off_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_screen_off_enable),
                            explanation = stringResource(R.string.tuning_screen_off_enable_desc),
                            checked = config.screenOffSavingEnabled,
                            onCheckedChange = { onUpdateScreenOffSaving(it, null, null, null) },
                            testTag = "screen_off_saving_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSliderRow(
                            title = stringResource(R.string.tuning_screen_off_delay),
                            valueText = stringResource(R.string.tuning_screen_off_delay_val, config.screenOffDelaySec),
                            explanation = stringResource(R.string.tuning_screen_off_delay_desc),
                            value = config.screenOffDelaySec.toFloat(),
                            valueRange = 0f..60f,
                            steps = 59,
                            onValueChange = {
                                onUpdateScreenOffSaving(null, it.roundToInt().coerceIn(0, 60), null, null)
                            },
                            enabled = config.screenOffSavingEnabled,
                            testTag = "screen_off_delay_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_screen_off_music),
                            explanation = stringResource(R.string.tuning_screen_off_music_desc),
                            checked = config.screenOffExemptMusic,
                            onCheckedChange = { onUpdateScreenOffSaving(null, null, it, null) },
                            enabled = config.screenOffSavingEnabled,
                            testTag = "screen_off_exempt_music_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_screen_off_charging),
                            explanation = stringResource(R.string.tuning_screen_off_charging_desc),
                            checked = config.screenOffExemptCharging,
                            onCheckedChange = { onUpdateScreenOffSaving(null, null, null, it) },
                            enabled = config.screenOffSavingEnabled,
                            testTag = "screen_off_exempt_charging_switch"
                        )
                    }
                }
            }

            // v3.6.6 Charge sleep guard (Screen-off section)
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.SCREEN_OFF) {
                item { ChargeGuardCard() }
            }

            // 2c. Overlay
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.OVERLAY) {
                item {
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_overlay_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_overlay_enable),
                            explanation = stringResource(R.string.tuning_overlay_enable_desc),
                            checked = config.overlayEnabled,
                            onCheckedChange = { onUpdateOverlay(it, null, null, null, null, null, null, false) },
                            testTag = "overlay_enabled_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_overlay_click_through),
                            explanation = stringResource(R.string.tuning_overlay_click_through_desc),
                            checked = config.overlayClickThrough,
                            onCheckedChange = { onUpdateOverlay(null, it, null, null, null, null, null, false) },
                            enabled = config.overlayEnabled,
                            testTag = "overlay_click_through_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = stringResource(R.string.tuning_overlay_game_only),
                            explanation = stringResource(R.string.tuning_overlay_game_only_desc),
                            checked = config.overlayGameOnly,
                            onCheckedChange = { onUpdateOverlay(null, null, it, null, null, null, null, false) },
                            enabled = config.overlayEnabled,
                            testTag = "overlay_game_only_switch"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSliderRow(
                            title = stringResource(R.string.tuning_overlay_opacity),
                            valueText = "${config.overlayOpacityPct}%",
                            explanation = stringResource(R.string.tuning_overlay_opacity_desc),
                            value = config.overlayOpacityPct.toFloat(),
                            valueRange = 30f..100f,
                            steps = 69,
                            onValueChange = {
                                onUpdateOverlay(null, null, null, it.roundToInt().coerceIn(30, 100), null, null, null, false)
                            },
                            enabled = config.overlayEnabled,
                            testTag = "overlay_opacity_slider"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSliderRow(
                            title = stringResource(R.string.tuning_overlay_bg_opacity),
                            valueText = "${config.overlayBgOpacityPct}%",
                            explanation = stringResource(R.string.tuning_overlay_bg_opacity_desc),
                            value = config.overlayBgOpacityPct.toFloat(),
                            valueRange = 0f..100f,
                            steps = 99,
                            onValueChange = {
                                onUpdateOverlay(null, null, null, null, null, null, it.roundToInt().coerceIn(0, 100), false)
                            },
                            enabled = config.overlayEnabled,
                            testTag = "overlay_bg_opacity_slider"
                        )

                        val configuration = LocalConfiguration.current
                        val density = LocalDensity.current.density
                        val windowManager = remember(context) {
                            context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        }
                        val overlayStrings = remember(configuration, context) {
                            OverlaySizing.OverlayStrings.fromContext(context)
                        }
                        val (screenW, screenH) = remember(
                            configuration.orientation,
                            configuration.screenWidthDp,
                            configuration.screenHeightDp
                        ) {
                            OverlaySizing.getScreenSizePx(windowManager)
                        }
                        val detailedRowCount = remember(
                            tuningInfo.clusterCount,
                            tuningInfo.clusterCpuCount,
                            hardwareSpec.cpuPolicies.size,
                            hardwareSpec.cpuPolicies.sumOf { it.relatedCpus.size }
                        ) {
                            when {
                                tuningInfo.clusterCount > 0 -> 3 + tuningInfo.clusterCount + tuningInfo.clusterCpuCount
                                hardwareSpec.cpuPolicies.isNotEmpty() -> 3 + hardwareSpec.cpuPolicies.sumOf { 1 + it.relatedCpus.size }
                                else -> OverlaySizing.DEFAULT_DETAILED_ROW_COUNT
                            }
                        }
                        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val scaleOrientLabel = stringResource(
                            if (isLandscape) R.string.tuning_overlay_orient_landscape
                            else R.string.tuning_overlay_orient_portrait
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        val headerAlpha = if (config.overlayEnabled) 1f else 0.38f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.tuning_overlay_size_pos),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = headerAlpha)
                                )
                                Text(
                                    text = stringResource(
                                        if (fineOverlayControl) R.string.tuning_overlay_fine_on
                                        else R.string.tuning_overlay_fine_off
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = headerAlpha)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilterChip(
                                selected = fineOverlayControl,
                                onClick = { fineOverlayControl = !fineOverlayControl },
                                enabled = config.overlayEnabled,
                                label = { Text(stringResource(R.string.tuning_overlay_fine_chip)) },
                                modifier = Modifier.testTag("overlay_fine_chip")
                            )
                        }

                        val activeStyle = when {
                            config.overlayDetailed -> OverlayStyle.DETAILED
                            config.overlayExpanded -> OverlayStyle.EXPANDED
                            else -> OverlayStyle.COMPACT
                        }
                        for (style in OverlayStyle.entries) key(style) {
                            Spacer(modifier = Modifier.height(14.dp))
                            OverlayStyleControls(
                                style = style,
                                isActive = style == activeStyle,
                                config = config,
                                isLandscape = isLandscape,
                                orientLabel = scaleOrientLabel,
                                screenW = screenW,
                                screenH = screenH,
                                density = density,
                                strings = overlayStrings,
                                detailedRowCount = detailedRowCount,
                                fineMode = fineOverlayControl,
                                onUpdateOverlayStyle = onUpdateOverlayStyle,
                                onSetOverlayTextScale = onSetOverlayTextScale
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { onUpdateOverlayPosition(null, null, true, isLandscape) },
                            enabled = config.overlayEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay_reset_position_button")
                        ) {
                            Text(stringResource(R.string.tuning_overlay_reset_pos))
                        }
                    }
                }
            }

            // 2d. Nifty features (sensor pinning)
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.NIFTY) {
                item {
                    SensorPinSection(
                        state = sensorPin,
                        isRunning = tuningInfo.isRunning,
                        onUpdate = onUpdateSensorPin
                    )
                }
            }

            // 3. Temperature Throttling
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.THERMAL) {
                item {
                    ThermalThrottleSection(
                        config = config,
                        hardwareSpec = hardwareSpec,
                        telemetryFlow = telemetryFlow,
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
                        telemetryFlow = telemetryFlow,
                        onUpdatePowerThrottle = onUpdatePowerThrottle,
                        onSetSmoothPowerLimit = onSetSmoothPowerLimit,
                        onSetSmoothMinConfidence = onSetSmoothMinConfidence,
                        onSetSmoothSliceMs = onSetSmoothSliceMs,
                        onSetSmoothSliceAuto = onSetSmoothSliceAuto,
                        onSetSmoothFixDrift = onSetSmoothFixDrift
                    )
                }
                item {
                    AfterburnSection(
                        config = config,
                        telemetryFlow = telemetryFlow,
                        onUpdateAfterburn = onUpdateAfterburn,
                        installedApps = installedApps,
                        onSetBoostStrength = onSetBoostStrength
                    )
                }
                item {
                    StutterHunterSection(
                        config = config,
                        status = stutterHunterStatus,
                        installedApps = installedApps,
                        onUpdateStutterHunter = onUpdateStutterHunter,
                        onToggleWhitelist = onToggleHunterWhitelist
                    )
                }
            }

            // 4b. Game Thread Controller
            if (selectedCategory == TuningCategory.ALL) {
                item {
                    GameThreadSummaryCard(
                        config = config,
                        onOpenGameThreads = { selectedCategory = TuningCategory.GAME_THREADS }
                    )
                }
            }
            if (selectedCategory == TuningCategory.GAME_THREADS) {
                gameThreadsItems(
                    config = config,
                    hardwareSpec = hardwareSpec,
                    inspection = gameThreadInspection,
                    isRefreshing = isRefreshingGameThreads,
                    installedApps = installedApps,
                    onRefresh = onRefreshGameThreads,
                    onSetEnabled = onSetGameThreadEnabled,
                    onSetAutoClassify = onSetGameThreadAutoClassify,
                    onSetSelectedGame = onSetGameThreadSelectedGame,
                    onUpsertMappings = onUpsertThreadMappings,
                    onUpdateMapping = onUpdateThreadMapping,
                    onRemoveMapping = onRemoveThreadMapping,
                    onRemoveHistoricalThread = onRemoveHistoricalThread,
                    onClearGameHistory = onClearGameThreadHistory,
                    onClearAllHistory = onClearAllThreadHistory
                )
            }

            // 5. Presets, JSON Export/Import & Hardware Details
            if (selectedCategory == TuningCategory.ALL || selectedCategory == TuningCategory.PRESETS) {
                item {
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_presets_header),
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
                                placeholder = { Text(stringResource(R.string.tuning_preset_hint)) },
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
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.tuning_save_preset_cd))
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
                                        text = stringResource(
                                            if (preset.builtIn) R.string.tuning_preset_builtin
                                            else R.string.tuning_preset_user
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    OutlinedButton(
                                        onClick = { onLoadPreset(preset) },
                                        modifier = Modifier.testTag("load_preset_${preset.id}")
                                    ) {
                                        Text(stringResource(R.string.common_load))
                                    }
                                    if (!preset.builtIn) {
                                        IconButton(onClick = { onDeletePreset(preset) }) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tuning_delete_preset_cd))
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
                                Text(stringResource(R.string.tuning_export_json))
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_json_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.tuning_import_json))
                            }
                        }
                    }
                }

                item {
                    val noneText = stringResource(R.string.common_none)
                    EcuCard {
                        Text(
                            text = stringResource(R.string.tuning_hw_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.tuning_hw_counts,
                                hardwareSpec.cpuPolicies.size,
                                hardwareSpec.gpu.oppTable.size,
                                hardwareSpec.thermalSensors.size
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.tuning_hw_opp,
                                hardwareSpec.gpu.oppTableSourcePath ?: noneText,
                                hardwareSpec.gpu.detectedOrderNote
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.tuning_hw_ged,
                                hardwareSpec.gpu.gedLoadCandidatePaths.joinToString().ifBlank { noneText }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.tuning_hw_battery,
                                hardwareSpec.batteryPower.currentPath ?: noneText
                            ),
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
                            Text(stringResource(R.string.tuning_reprobe_hw))
                        }
                    }
                }
            }
        }
    }
}

/** Size + X/Y position for one overlay style (all three are always editable, not only the active one). */
@Composable
private fun OverlayStyleControls(
    style: OverlayStyle,
    isActive: Boolean,
    config: GovernorConfig,
    isLandscape: Boolean,
    orientLabel: String,
    screenW: Int,
    screenH: Int,
    density: Float,
    strings: OverlaySizing.OverlayStrings,
    detailedRowCount: Int,
    fineMode: Boolean,
    onUpdateOverlayStyle: (OverlayStyle, Boolean, Int?, Int?, Int?, Int?, Boolean) -> Unit,
    onSetOverlayTextScale: (OverlayStyle, Int) -> Unit
) {
    val tag = style.name.lowercase()
    val styleName = stringResource(
        when (style) {
            OverlayStyle.COMPACT -> R.string.tuning_overlay_style_compact
            OverlayStyle.EXPANDED -> R.string.tuning_overlay_style_expanded
            OverlayStyle.DETAILED -> R.string.tuning_overlay_style_detailed
        }
    )
    val textScalePct = config.overlayTextScaleFor(style)
    val maxScalePct = remember(screenW, screenH, density, style, detailedRowCount, strings, textScalePct) {
        val (natW, natH) = OverlaySizing.measureNaturalSizeAtScaleOne(
            density,
            style,
            detailedRowCount,
            strings = strings,
            textScalePct = textScalePct
        )
        OverlaySizing.maxScalePct(screenW, screenH, style, natW, natH)
    }
    val scalePct = config.overlayScaleFor(style, isLandscape)
        .coerceIn(OverlaySizing.MIN_SCALE_PCT, maxScalePct)
    val (posX, posY) = config.overlayPosPmFor(style, isLandscape)
    val x = posX.coerceIn(0, 1000)
    val y = posY.coerceIn(0, 1000)
    val enabled = config.overlayEnabled

    Text(
        text = stringResource(
            if (isActive) R.string.tuning_overlay_style_header_active else R.string.tuning_overlay_style_header,
            styleName.replaceFirstChar { it.uppercase() }
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    OverlayFineSliderRow(
        title = stringResource(R.string.tuning_overlay_style_size, orientLabel),
        valueText = "$scalePct%",
        explanation = stringResource(
            R.string.tuning_overlay_style_size_desc,
            OverlaySizing.MIN_SCALE_PCT,
            maxScalePct
        ),
        value = scalePct,
        fullMin = OverlaySizing.MIN_SCALE_PCT,
        fullMax = maxScalePct,
        stepAmount = 1,
        fineHalfWindow = 10,
        fineMode = fineMode,
        onIntValueChange = { onUpdateOverlayStyle(style, isLandscape, it, null, null, null, false) },
        enabled = enabled,
        testTag = "overlay_${tag}_scale_slider"
    )
    Spacer(modifier = Modifier.height(8.dp))
    OverlayFineSliderRow(
        title = stringResource(R.string.tuning_overlay_style_pos_x, orientLabel),
        valueText = "${String.format(Locale.US, "%.1f", x / 10f)}%",
        explanation = stringResource(R.string.tuning_overlay_pos_x_desc),
        value = x,
        fullMin = 0,
        fullMax = 1000,
        stepAmount = 1,
        fineHalfWindow = 100,
        fineMode = fineMode,
        onIntValueChange = { onUpdateOverlayStyle(style, isLandscape, null, it, null, null, false) },
        enabled = enabled,
        testTag = "overlay_${tag}_pos_x_slider"
    )
    Spacer(modifier = Modifier.height(8.dp))
    OverlayFineSliderRow(
        title = stringResource(R.string.tuning_overlay_style_pos_y, orientLabel),
        valueText = "${String.format(Locale.US, "%.1f", y / 10f)}%",
        explanation = stringResource(R.string.tuning_overlay_pos_y_desc),
        value = y,
        fullMin = 0,
        fullMax = 1000,
        stepAmount = 1,
        fineHalfWindow = 100,
        fineMode = fineMode,
        onIntValueChange = { onUpdateOverlayStyle(style, isLandscape, null, null, it, null, false) },
        enabled = enabled,
        testTag = "overlay_${tag}_pos_y_slider"
    )
    Spacer(modifier = Modifier.height(8.dp))
    val bgOpacity = config.overlayBgOpacityFor(style)
    OverlayFineSliderRow(
        title = stringResource(R.string.tuning_overlay_style_bg_opacity),
        valueText = "$bgOpacity%",
        explanation = stringResource(R.string.tuning_overlay_style_bg_opacity_desc),
        value = bgOpacity,
        fullMin = 0,
        fullMax = 100,
        stepAmount = 1,
        fineHalfWindow = 10,
        fineMode = fineMode,
        onIntValueChange = { onUpdateOverlayStyle(style, isLandscape, null, null, null, it, false) },
        enabled = enabled,
        testTag = "overlay_${tag}_bg_opacity_slider"
    )
    Spacer(modifier = Modifier.height(8.dp))
    OverlayFineSliderRow(
        title = stringResource(R.string.tuning_overlay_style_text_size),
        valueText = "$textScalePct%",
        explanation = stringResource(R.string.tuning_overlay_style_text_size_desc),
        value = textScalePct,
        fullMin = OVERLAY_TEXT_SCALE_MIN_PCT,
        fullMax = OVERLAY_TEXT_SCALE_MAX_PCT,
        stepAmount = 1,
        fineHalfWindow = 10,
        fineMode = fineMode,
        onIntValueChange = { onSetOverlayTextScale(style, it) },
        enabled = enabled,
        testTag = "overlay_${tag}_text_size_slider"
    )
    Spacer(modifier = Modifier.height(4.dp))
    TextButton(
        onClick = { onUpdateOverlayStyle(style, isLandscape, null, null, null, null, true) },
        enabled = enabled,
        modifier = Modifier.testTag("overlay_${tag}_reset_position_button")
    ) {
        Text(stringResource(R.string.tuning_overlay_style_reset_pos, styleName))
    }
}

@Composable
private fun OverlayFineSliderRow(
    title: String,
    valueText: String,
    explanation: String,
    value: Int,
    fullMin: Int,
    fullMax: Int,
    stepAmount: Int,
    fineHalfWindow: Int,
    fineMode: Boolean,
    onIntValueChange: (Int) -> Unit,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val safeMax = fullMax.coerceAtLeast(fullMin)
    val clampedValue = value.coerceIn(fullMin, safeMax)
    var fineAnchor by remember(fineMode, fullMin, safeMax) {
        mutableIntStateOf(clampedValue)
    }
    var isDragging by remember { mutableStateOf(false) }
    var lastCommittedInDrag by remember { mutableIntStateOf(clampedValue) }
    val isNudgingRef = remember { booleanArrayOf(false) }
    val liveValueRef = remember { intArrayOf(clampedValue) }

    SideEffect {
        if (!isNudgingRef[0] && !isDragging) {
            liveValueRef[0] = clampedValue
        }
    }

    LaunchedEffect(clampedValue, fineMode, fullMin, safeMax) {
        if (!isDragging && !isNudgingRef[0]) {
            fineAnchor = clampedValue
        }
    }

    val rangeStart = if (fineMode) (fineAnchor - fineHalfWindow).coerceAtLeast(fullMin) else fullMin
    val rangeEnd = if (fineMode) (fineAnchor + fineHalfWindow).coerceAtMost(safeMax) else safeMax
    val hasSpan = rangeEnd > rangeStart
    val sliderStart = rangeStart.toFloat()
    val sliderEnd = if (hasSpan) rangeEnd.toFloat() else (rangeStart + 1).toFloat()
    val sliderVal = clampedValue.coerceIn(rangeStart, rangeEnd).toFloat()
    val contentAlpha = if (enabled) 1f else 0.38f

    fun stepBy(delta: Int) {
        val prev = liveValueRef[0]
        val next = (prev + delta).coerceIn(fullMin, safeMax)
        if (next != prev) {
            liveValueRef[0] = next
            fineAnchor = next
            onIntValueChange(next)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
            )
        }
        Slider(
            value = sliderVal,
            onValueChange = { raw ->
                isDragging = true
                val snapped = raw.roundToInt().coerceIn(rangeStart, rangeEnd)
                lastCommittedInDrag = snapped
                liveValueRef[0] = snapped
                onIntValueChange(snapped)
            },
            onValueChangeFinished = {
                isDragging = false
                fineAnchor = lastCommittedInDrag.coerceIn(fullMin, safeMax)
            },
            valueRange = sliderStart..sliderEnd,
            steps = 0,
            enabled = enabled && hasSpan,
            modifier = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NudgeButton(
                    symbol = "\u2212",
                    contentDescription = stringResource(R.string.common_decrease, title),
                    enabled = enabled && clampedValue > fullMin,
                    onPressStart = {
                        isNudgingRef[0] = true
                        liveValueRef[0] = clampedValue
                    },
                    onStep = { stepBy(-stepAmount) },
                    onPressEnd = {
                        isNudgingRef[0] = false
                        fineAnchor = liveValueRef[0].coerceIn(fullMin, safeMax)
                    },
                    testTag = if (testTag.isNotEmpty()) "${testTag}_minus" else ""
                )
                NudgeButton(
                    symbol = "+",
                    contentDescription = stringResource(R.string.common_increase, title),
                    enabled = enabled && clampedValue < safeMax,
                    onPressStart = {
                        isNudgingRef[0] = true
                        liveValueRef[0] = clampedValue
                    },
                    onStep = { stepBy(stepAmount) },
                    onPressEnd = {
                        isNudgingRef[0] = false
                        fineAnchor = liveValueRef[0].coerceIn(fullMin, safeMax)
                    },
                    testTag = if (testTag.isNotEmpty()) "${testTag}_plus" else ""
                )
            }
        }
    }
}

@Composable
private fun NudgeButton(
    symbol: String,
    contentDescription: String,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onStep: () -> Unit,
    onPressEnd: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnPressStart by rememberUpdatedState(onPressStart)
    val latestOnStep by rememberUpdatedState(onStep)
    val latestOnPressEnd by rememberUpdatedState(onPressEnd)
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(10.dp)
    val contentAlpha = if (enabled) 1f else 0.38f

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.8f else 0.3f)
        ),
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .indication(interactionSource, ripple())
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                if (!latestEnabled) {
                    disabled()
                } else {
                    onClick {
                        latestOnPressStart()
                        latestOnStep()
                        latestOnPressEnd()
                        true
                    }
                }
            }
            .let { if (testTag.isNotEmpty()) it.testTag(testTag) else it }
            .pointerInput(interactionSource) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (!latestEnabled) return@awaitEachGesture
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    latestOnPressStart()

                    var cancelled = false
                    var heldRepeated = false
                    val holdStartMs = SystemClock.uptimeMillis()
                    val repeatJob = scope.launch {
                        delay(400L)
                        heldRepeated = true
                        while (isActive && latestEnabled) {
                            latestOnStep()
                            val elapsed = SystemClock.uptimeMillis() - holdStartMs
                            val interval = if (elapsed >= 1500L) 35L else 80L
                            delay(interval)
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                if (change != null && change.changedToUp()) {
                                    change.consume()
                                }
                                break
                            }
                            val distSq = (change.position - down.position).getDistanceSquared()
                            val slop = viewConfiguration.touchSlop
                            if (distSq > slop * slop || change.isConsumed) {
                                cancelled = true
                                break
                            }
                        }
                    } finally {
                        repeatJob.cancel()
                        if (cancelled) {
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                        } else {
                            interactionSource.tryEmit(PressInteraction.Release(press))
                            if (!heldRepeated && latestEnabled) {
                                latestOnStep()
                            }
                        }
                        latestOnPressEnd()
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}

