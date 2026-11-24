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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.governor.UpdateState
import com.example.ui.AdaptiveRefreshCard
import com.example.ui.AppDemandCard
import com.example.ui.AppsScreen
import com.example.ui.ScreenOffCard
import com.example.ui.CoreGovernorViewModel
import com.example.ui.EnergyScreen
import com.example.ui.LogScreen
import com.example.ui.MonitorScreen
import com.example.ui.OppTableScreen
import com.example.ui.SystemScreen
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
    ENERGY("Energy", Icons.Filled.Bolt, Icons.Outlined.Bolt),
    TUNING("Tuning", Icons.Filled.Tune, Icons.Outlined.Tune),
    OPPS("OPPs", Icons.Filled.TableChart, Icons.Outlined.TableChart),
    SYSTEM("System", Icons.Filled.Memory, Icons.Outlined.Memory),
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

    override fun onStop() {
        super.onStop()
        val engine = (application as CoreGovernorApp).engine
        engine.storage.flush()
        engine.powerModel.flush()
        engine.appDemand.flush()
        engine.batteryEstimator.flush()
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
    // Only the active mode is read at the root, so the top bar does not recompose on every telemetry tick.
    val activeMode by vm.activeMode.collectAsStateWithLifecycle()
    val sensorPin by vm.sensorPin.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by vm.isLoadingApps.collectAsStateWithLifecycle()
    val usageStatsGranted by vm.usageStatsGranted.collectAsStateWithLifecycle()
    val metricsSessions by vm.metricsSessions.collectAsStateWithLifecycle()
    val metricsRecording by vm.metricsRecording.collectAsStateWithLifecycle()
    val selectedSession by vm.selectedSession.collectAsStateWithLifecycle()
    val selectedSamples by vm.selectedSamples.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    var updateDismissed by rememberSaveable { mutableStateOf(false) }

    val smartControls by vm.appControls.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(NavTab.MONITOR) }
    // Tracks whether the activity is started (in front), so hidden tabs never do background UI work.
    var appStarted by remember { mutableStateOf(true) }
    val activityLifecycle = (context as? ComponentActivity)?.lifecycle
    DisposableEffect(activityLifecycle) {
        val lc = activityLifecycle
        if (lc == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        appStarted = true
                        vm.setAppInForeground(true)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        appStarted = false
                        vm.setAppInForeground(false)
                    }
                    else -> Unit
                }
            }
            lc.addObserver(observer)
            onDispose { lc.removeObserver(observer) }
        }
    }
    LaunchedEffect(currentTab) {
        vm.setEnergyTabVisible(currentTab == NavTab.ENERGY)
        vm.setSystemTabVisible(currentTab == NavTab.SYSTEM)
    }
    LaunchedEffect(currentTab, appStarted) {
        vm.setMetricsTabVisible(appStarted && currentTab == NavTab.METRICS)
    }

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
                                text = when (val u = updateState) {
                                    is UpdateState.Checking -> "v${vm.currentVersion} • checking for updates…"
                                    is UpdateState.UpToDate -> "v${vm.currentVersion} • up to date"
                                    is UpdateState.Available -> "v${vm.currentVersion} • update ${u.latest} available"
                                    is UpdateState.Failed -> "v${vm.currentVersion} • update check failed (tap to retry)"
                                    else -> "v${vm.currentVersion} • tap to check for updates"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { vm.checkForUpdate(); updateDismissed = false }
                                    .testTag("version_line")
                            )
                        }
                        StatusPill(
                            label = activeMode.name,
                            color = if (activeMode.name == "GAME") ext.warning else MaterialTheme.colorScheme.primary,
                            containerColor = if (activeMode.name == "GAME") ext.warningContainer else MaterialTheme.colorScheme.primaryContainer
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
                        label = { Text(tab.label, maxLines = 1, softWrap = false) },
                        alwaysShowLabel = false,
                        modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
        (updateState as? UpdateState.Available)?.let { u ->
            if (!updateDismissed) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Update available: v${u.latest}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "You have v${u.current}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { vm.openUrl(u.pageUrl) }) { Text("Release notes") }
                            TextButton(onClick = { updateDismissed = true }) { Text("Later") }
                            Button(onClick = { vm.openUrl(u.downloadUrl) }) { Text("Download") }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
        when (currentTab) {
            NavTab.MONITOR -> {
                // Live telemetry is collected only here, so only the Monitor tab recomposes on each tick.
                val telemetry by vm.telemetry.collectAsStateWithLifecycle()
                MonitorScreen(
                    telemetry = telemetry,
                    hardwareSpec = hardwareSpec,
                    onToggleGovernor = vm::toggleGovernorRunning,
                    onSelectModeOverride = vm::setModeOverride,
                    onRediscover = vm::rediscoverHardware,
                    modifier = Modifier
                )
            }
            NavTab.METRICS -> {
                val metricsLive by vm.metricsLive.collectAsStateWithLifecycle()
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
                    onExportCsv = vm::exportSessionCsv,
                    modifier = Modifier
                )
            }
            NavTab.ENERGY -> {
                val energySnapshot by vm.powerModelSnapshot.collectAsStateWithLifecycle()
                val batterySnapshot by vm.batterySnapshot.collectAsStateWithLifecycle()
                val demandRows by vm.appDemandRows.collectAsStateWithLifecycle()
                val refreshUi by vm.refreshState.collectAsStateWithLifecycle()
                val screenOffSnap by vm.screenOffSnapshot.collectAsStateWithLifecycle()
                val isRunning by vm.isRunning.collectAsStateWithLifecycle()
                EnergyScreen(
                    snapshot = energySnapshot,
                    batterySnapshot = batterySnapshot,
                    config = config,
                    isRunning = isRunning,
                    onUpdateSettings = vm::updateEfficiencySettings,
                    onResetModel = vm::resetPowerModel,
                    onExportJson = vm::exportPowerModelJson,
                    onUpdateBatterySettings = vm::updateBatterySettings,
                    onResetBatteryEstimate = vm::resetBatteryEstimate,
                    modifier = Modifier,
                    telemetryFlow = vm.telemetry,
                    extraContent = {
                        item(key = "smart_app_demand") {
                            AppDemandCard(
                                config = config,
                                rows = demandRows,
                                labels = installedApps.associate { it.packageName to it.label },
                                onToggle = vm::setSmartEnabled,
                                onMode = vm::setSmartMode
                            )
                        }
                        item(key = "screen_off") {
                            ScreenOffCard(
                                config = config,
                                snap = screenOffSnap,
                                onToggle = vm::setScreenOffEnabled,
                                onReset = vm::resetScreenOff
                            )
                        }
                        item(key = "adaptive_refresh") {
                            AdaptiveRefreshCard(
                                config = config,
                                state = refreshUi,
                                onToggle = vm::setAdaptiveRefresh,
                                onHz = { i, a, d -> vm.setAdaptiveHz(i, a, d) },
                                onTest = vm::testRefreshSwitch
                            )
                        }
                    }
                )
            }
            NavTab.TUNING -> TuningScreen(
                config = config,
                hardwareSpec = hardwareSpec,
                telemetryFlow = vm.telemetry,
                presets = presets,
                onUpdatePollInterval = vm::updatePollInterval,
                onUpdateAdaptivePolling = vm::updateAdaptivePolling,
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
                onUpdateAfterburn = vm::updateAfterburn,
                onSavePreset = vm::saveNamedPreset,
                onLoadPreset = vm::loadPreset,
                onDeletePreset = vm::deletePreset,
                onExportJson = vm::exportJson,
                onImportJson = vm::importJson,
                onRediscoverHardware = vm::rediscoverHardware,
                sensorPin = sensorPin,
                onUpdateSensorPin = vm::updateSensorPin,
                modifier = Modifier
            )
            NavTab.OPPS -> OppTableScreen(
                hardwareSpec = hardwareSpec,
                telemetryFlow = vm.telemetry,
                onRefreshTable = vm::refreshGpuOppTable,
                modifier = Modifier
            )
            NavTab.APPS -> AppsScreen(
                installedApps = installedApps,
                isLoadingApps = isLoadingApps,
                gamePackages = config.gamePackages,
                usageStatsGranted = usageStatsGranted,
                onToggleGamePackage = vm::toggleGamePackage,
                boostPackages = config.boostPackages,
                onToggleBoostPackage = vm::toggleBoostPackage,
                onRefreshUsageStats = vm::refreshUsageStatsPermission,
                modifier = Modifier,
                smartControls = smartControls,
                onSmartSet = vm::smartSetControl,
                onSmartReset = vm::smartResetApp
            )
            NavTab.SYSTEM -> {
                val rating by vm.deviceRating.collectAsStateWithLifecycle()
                val rows by vm.systemRows.collectAsStateWithLifecycle()
                val sysError by vm.systemError.collectAsStateWithLifecycle()
                val sysNote by vm.systemNote.collectAsStateWithLifecycle()
                val threshold by vm.culpritThreshold.collectAsStateWithLifecycle()
                val labels = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
                SystemScreen(
                    rating = rating,
                    rows = rows,
                    error = sysError,
                    note = sysNote,
                    thresholdPct = threshold,
                    appLabels = labels,
                    onThreshold = vm::setCulpritThreshold,
                    onKill = vm::killApp,
                    onDismissNote = vm::dismissSystemNote,
                    onResetRating = vm::resetRating,
                    modifier = Modifier
                )
            }
            NavTab.LOG -> LogScreen(
                logs = logs,
                onClearLogs = vm::clearLogs,
                modifier = Modifier
            )
        }
        }
        }
    }
}
