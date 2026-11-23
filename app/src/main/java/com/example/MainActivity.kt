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

package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppsScreen
import com.example.ui.CoreGovernorViewModel
import com.example.ui.LogScreen
import com.example.ui.MonitorScreen
import com.example.ui.OppTableScreen
import com.example.ui.PerformanceScreen
import com.example.ui.TuningScreen
import com.example.ui.components.StatusPill
import com.example.ui.theme.CoreGovernorTheme
import com.example.ui.theme.LocalExtendedColors

enum class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MONITOR("Monitor", Icons.Filled.Speed, Icons.Outlined.Speed),
    METRICS("Metrics", Icons.Filled.Insights, Icons.Outlined.Insights),
    TUNING("Tuning", Icons.Filled.Tune, Icons.Outlined.Tune),
    OPPS("OPPs", Icons.Filled.TableChart, Icons.Outlined.TableChart),
    APPS("Apps", Icons.Filled.Apps, Icons.Outlined.Apps),
    LOG("Log", Icons.Filled.ListAlt, Icons.Outlined.ListAlt)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoreGovernorTheme {
                CoreGovernorRootScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreGovernorRootScreen(
    vm: CoreGovernorViewModel = viewModel()
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current

    val hardwareSpec by vm.hardwareSpec.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val sensorPin by vm.sensorPin.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by vm.isLoadingApps.collectAsStateWithLifecycle()
    val usageStatsGranted by vm.usageStatsGranted.collectAsStateWithLifecycle()
    val metricsLive by vm.metricsLive.collectAsStateWithLifecycle()
    val metricsSessions by vm.metricsSessions.collectAsStateWithLifecycle()
    val metricsRecording by vm.metricsRecording.collectAsStateWithLifecycle()
    val selectedSession by vm.selectedSession.collectAsStateWithLifecycle()
    val selectedSamples by vm.selectedSamples.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(NavTab.MONITOR) }

    // Android 13+ notification permission for Foreground Service status notification
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BackHandler(enabled = currentTab != NavTab.MONITOR) {
        currentTab = NavTab.MONITOR
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CoreGovernor",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "CoreGovernor • MediaTek Controller",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            label = telemetry.activeMode.name,
                            color = if (telemetry.activeMode.name == "GAME") ext.warning else MaterialTheme.colorScheme.primary,
                            containerColor = if (telemetry.activeMode.name == "GAME") ext.warningContainer else MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            currentTab = tab
                            if (tab == NavTab.APPS) vm.refreshUsageStatsPermission()
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            NavTab.MONITOR -> MonitorScreen(
                telemetry = telemetry,
                hardwareSpec = hardwareSpec,
                onToggleGovernor = vm::toggleGovernorRunning,
                onSelectModeOverride = vm::setModeOverride,
                onRediscover = vm::rediscoverHardware,
                modifier = Modifier.padding(innerPadding)
            )
            NavTab.METRICS -> {
                val appLabels = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
                PerformanceScreen(
                    live = metricsLive,
                    sessions = metricsSessions,
                    selectedSession = selectedSession,
                    selectedSamples = selectedSamples,
                    recording = metricsRecording,
                    appLabels = appLabels,
                    onSelectSession = vm::selectSession,
                    onDeleteSession = vm::deleteSession,
                    onDeleteAllSessions = vm::deleteAllSessions,
                    onClearLive = vm::clearLiveMetrics,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            NavTab.TUNING -> TuningScreen(
                config = config,
                hardwareSpec = hardwareSpec,
                telemetry = telemetry,
                presets = presets,
                onUpdatePollInterval = vm::updatePollInterval,
                onUpdateSafetyFloor = vm::updateSafetyFloor,
                onUpdateStartOnBoot = vm::updateStartOnBoot,
                onUpdateCpuLoadUsesBusiestCore = vm::updateCpuLoadUsesBusiestCore,
                onUpdateScreenOffSaving = vm::updateScreenOffSaving,
                onUpdateOverlay = vm::updateOverlay,
                onUpdateOverlayPosition = vm::updateOverlayPosition,
                onUpdateRamPin = vm::updateRamPin,
                onUpdateClusterRange = vm::updateClusterRange,
                onUpdateGpuRange = vm::updateGpuRange,
                onUpdateModeScalingParams = vm::updateModeScalingParams,
                onSelectTempSensor = vm::selectTempSensor,
                onUpdateTempThrottle = vm::updateTempThrottle,
                onUpdatePowerThrottle = vm::updatePowerThrottle,
                onSavePreset = vm::saveNamedPreset,
                onLoadPreset = vm::loadPreset,
                onDeletePreset = vm::deletePreset,
                onExportJson = vm::exportJson,
                onImportJson = vm::importJson,
                onRediscoverHardware = vm::rediscoverHardware,
                sensorPin = sensorPin,
                onUpdateSensorPin = vm::updateSensorPin,
                modifier = Modifier.padding(innerPadding)
            )
            NavTab.OPPS -> OppTableScreen(
                hardwareSpec = hardwareSpec,
                telemetry = telemetry,
                onRefreshTable = vm::refreshGpuOppTable,
                modifier = Modifier.padding(innerPadding)
            )
            NavTab.APPS -> AppsScreen(
                installedApps = installedApps,
                isLoadingApps = isLoadingApps,
                gamePackages = config.gamePackages,
                usageStatsGranted = usageStatsGranted,
                onToggleGamePackage = vm::toggleGamePackage,
                onRefreshUsageStats = vm::refreshUsageStatsPermission,
                modifier = Modifier.padding(innerPadding)
            )
            NavTab.LOG -> LogScreen(
                logs = logs,
                onClearLogs = vm::clearLogs,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
