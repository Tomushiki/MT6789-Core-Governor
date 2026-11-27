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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.LocaleHelper
import com.example.governor.SessionExport
import com.example.governor.SessionInfo
import com.example.governor.UpdateState
import com.example.model.GovernorMode
import com.example.ui.AdaptiveRefreshCard
import com.example.ui.AppDemandCard
import com.example.ui.AppsScreen
import com.example.ui.AutoTuneScreen
import com.example.ui.ScreenOffCard
import com.example.ui.CoreGovernorViewModel
import com.example.ui.EnergyScreen
import com.example.ui.LogScreen
import com.example.ui.MonitorScreen
import com.example.ui.OppTableScreen
import com.example.ui.SystemScreen
import com.example.ui.PerformanceScreen
import com.example.ui.SessionComparisonScreen
import com.example.ui.SessionDetailScreen
import com.example.ui.TuningScreen
import com.example.ui.components.StatusPill
import com.example.ui.theme.CoreGovernorTheme
import com.example.ui.theme.LocalExtendedColors

enum class NavTab(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MONITOR(R.string.nav_monitor, Icons.Filled.Speed, Icons.Outlined.Speed),
    METRICS(R.string.nav_metrics, Icons.Filled.Insights, Icons.Outlined.Insights),
    ENERGY(R.string.nav_energy, Icons.Filled.Bolt, Icons.Outlined.Bolt),
    TUNING(R.string.nav_tuning, Icons.Filled.Tune, Icons.Outlined.Tune),
    OPPS(R.string.nav_opps, Icons.Filled.TableChart, Icons.Outlined.TableChart),
    SYSTEM(R.string.nav_system, Icons.Filled.Memory, Icons.Outlined.Memory),
    APPS(R.string.nav_apps, Icons.Filled.Apps, Icons.Outlined.Apps),
    LOG(R.string.nav_log, Icons.Filled.ListAlt, Icons.Outlined.ListAlt)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.example.ui.theme.Appearance.init(applicationContext)
        setContent {
            CoreGovernorTheme {
                com.example.ui.AppearanceBackground {
                    CoreGovernorRootScreen()
                }
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
    val comparisonSamples by vm.comparisonSamples.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val includePrereleases by vm.includePrereleases.collectAsStateWithLifecycle()
    val dismissedUpdateVersion by vm.dismissedUpdateVersion.collectAsStateWithLifecycle()
    val protectionStatus by vm.protectionStatus.collectAsStateWithLifecycle()
    val stutterHunterStatus by vm.stutterHunterStatus.collectAsStateWithLifecycle()
    val gameThreadInspection by vm.gameThreadInspection.collectAsStateWithLifecycle()
    val isRefreshingGameThreads by vm.isRefreshingGameThreads.collectAsStateWithLifecycle()
    var showUpdatePanel by rememberSaveable { mutableStateOf(false) }

    val smartControls by vm.appControls.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(NavTab.MONITOR) }
    var autoTunePkg by rememberSaveable { mutableStateOf<String?>(null) }
    var detailSessionFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var compareSessionFiles by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var detailCsvTarget by remember { mutableStateOf<SessionInfo?>(null) }
    val detailCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val target = detailCsvTarget
        if (uri != null && target != null) {
            vm.exportSessionCsv(target, uri)
            Toast.makeText(context, context.getString(R.string.metrics_saving_csv), Toast.LENGTH_SHORT).show()
        }
    }
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

    val hasSubScreen = detailSessionFileName != null || compareSessionFiles.isNotEmpty() || autoTunePkg != null
    BackHandler(enabled = hasSubScreen || currentTab != NavTab.MONITOR) {
        when {
            detailSessionFileName != null -> detailSessionFileName = null
            compareSessionFiles.isNotEmpty() -> compareSessionFiles = emptyList()
            autoTunePkg != null -> autoTunePkg = null
            else -> currentTab = NavTab.MONITOR
        }
    }

    val hasBackground = com.example.ui.theme.Appearance.background != null
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (hasBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (hasBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            val versionSummary = when (val u = updateState) {
                                is UpdateState.Checking -> stringResource(R.string.update_status_checking, vm.currentVersion)
                                is UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date, vm.currentVersion)
                                is UpdateState.Available -> stringResource(R.string.update_status_available, vm.currentVersion, u.latest)
                                is UpdateState.Failed.RateLimited -> stringResource(
                                    R.string.update_status_rate_limited,
                                    vm.currentVersion,
                                    com.example.governor.UpdateChecker.formatRetryTime(u.retryAtMs)
                                )
                                is UpdateState.Failed.Offline -> stringResource(R.string.update_status_offline, vm.currentVersion)
                                is UpdateState.Failed.NoRelease -> stringResource(R.string.update_status_no_release, vm.currentVersion)
                                is UpdateState.Failed.Http -> stringResource(R.string.update_status_http, vm.currentVersion, u.code)
                                is UpdateState.Failed -> stringResource(R.string.update_status_failed, vm.currentVersion)
                                else -> stringResource(R.string.update_status_idle, vm.currentVersion)
                            }
                            Text(
                                text = versionSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        showUpdatePanel = true
                                        vm.checkForUpdate()
                                    }
                                    .testTag("version_line")
                            )
                        }
                        val isGame = activeMode == GovernorMode.GAME
                        StatusPill(
                            label = stringResource(if (isGame) R.string.mode_game else R.string.mode_normal),
                            color = if (isGame) ext.warning else MaterialTheme.colorScheme.primary,
                            containerColor = if (isGame) ext.warningContainer else MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = if (hasBackground) MaterialTheme.colorScheme.background.copy(alpha = 0.85f) else MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    val tabLabel = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentTab != tab) {
                                autoTunePkg = null
                                detailSessionFileName = null
                                compareSessionFiles = emptyList()
                            }
                            currentTab = tab
                            if (tab == NavTab.APPS) vm.refreshUsageStatsPermission()
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tabLabel
                            )
                        },
                        label = { Text(tabLabel, maxLines = 1, softWrap = false) },
                        alwaysShowLabel = false,
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
        val availUpdate = updateState as? UpdateState.Available
        val showAvailCard = availUpdate != null && (showUpdatePanel || dismissedUpdateVersion != availUpdate.latest)
        if (showAvailCard || showUpdatePanel) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("update_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    when (val u = updateState) {
                        is UpdateState.Available -> {
                            val titleText = if (u.isPrerelease) {
                                stringResource(R.string.update_card_prerelease, u.latest)
                            } else {
                                stringResource(R.string.update_card_available, u.latest)
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.update_card_installed, u.current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (u.notes.isNotBlank()) {
                                Text(
                                    text = u.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                        is UpdateState.Checking -> {
                            Text(
                                text = stringResource(R.string.update_card_checking),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.UpToDate -> {
                            Text(
                                text = stringResource(R.string.update_card_up_to_date, u.current),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.Failed.RateLimited -> {
                            Text(
                                text = stringResource(
                                    R.string.update_card_rate_limited,
                                    com.example.governor.UpdateChecker.formatRetryTime(u.retryAtMs)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.Failed.Offline -> {
                            Text(
                                text = stringResource(R.string.update_card_offline),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.Failed.NoRelease -> {
                            Text(
                                text = stringResource(R.string.update_card_no_release),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.Failed.Http -> {
                            Text(
                                text = stringResource(R.string.update_card_http, u.code),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        is UpdateState.Failed -> {
                            Text(
                                text = u.reason,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        UpdateState.Idle -> {
                            Text(
                                text = stringResource(R.string.update_card_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.update_include_prereleases),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        androidx.compose.material3.Switch(
                            checked = includePrereleases,
                            onCheckedChange = vm::setIncludePrereleases,
                            modifier = Modifier.testTag("include_prereleases_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val avail = updateState as? UpdateState.Available
                        if (avail != null) {
                            if (avail.pageUrl.isNotBlank()) {
                                TextButton(onClick = { vm.openUrl(avail.pageUrl) }) {
                                    Text(stringResource(R.string.update_btn_notes))
                                }
                            }
                            TextButton(
                                onClick = {
                                    vm.dismissUpdate(avail.latest)
                                    showUpdatePanel = false
                                },
                                modifier = Modifier.testTag("update_dismiss_button")
                            ) { Text(stringResource(R.string.common_dismiss)) }
                            Button(
                                onClick = { vm.openUrl(avail.downloadUrl) },
                                modifier = Modifier.testTag("update_download_button")
                            ) { Text(stringResource(R.string.update_btn_download)) }
                        } else {
                            TextButton(
                                onClick = { showUpdatePanel = false },
                                modifier = Modifier.testTag("update_dismiss_button")
                            ) { Text(stringResource(R.string.common_dismiss)) }
                            if (updateState !is UpdateState.Checking) {
                                TextButton(onClick = { vm.checkForUpdate() }) {
                                    Text(stringResource(R.string.common_retry))
                                }
                            }
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
                val detailInfo = remember(detailSessionFileName, metricsSessions, selectedSession) {
                    val fn = detailSessionFileName ?: return@remember null
                    metricsSessions.firstOrNull { it.fileName == fn } ?: selectedSession?.takeIf { it.fileName == fn }
                }
                val compareList = remember(compareSessionFiles, metricsSessions) {
                    compareSessionFiles.mapNotNull { fn -> metricsSessions.firstOrNull { it.fileName == fn } }
                }
                LaunchedEffect(compareList) {
                    if (compareList.size >= 2) vm.loadComparisonSamples(compareList)
                }
                LaunchedEffect(detailInfo?.fileName) {
                    if (detailInfo != null && selectedSession?.fileName != detailInfo.fileName) {
                        vm.selectSession(detailInfo)
                    }
                }
                when {
                    detailInfo != null -> {
                        val samplesForDetail = if (selectedSession?.fileName == detailInfo.fileName) selectedSamples else emptyList()
                        val detailAppName = appLabels[detailInfo.packageName] ?: detailInfo.packageName
                        SessionDetailScreen(
                            session = detailInfo,
                            samples = samplesForDetail,
                            appName = detailAppName,
                            scoreWeights = config.scoreWeights,
                            onUpdateWeights = vm::updateScoreWeights,
                            onToggleKeep = { vm.toggleSessionKept(detailInfo) },
                            onShareSummary = {
                                val text = SessionExport.summaryText(
                                    title = detailAppName,
                                    startMs = detailInfo.startMs,
                                    s = detailInfo.summary,
                                    device = Build.MODEL ?: "Android",
                                    appVersion = vm.currentVersion
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, context.getString(R.string.metrics_share_chooser))
                                )
                            },
                            onExportCsv = {
                                detailCsvTarget = detailInfo
                                detailCsvLauncher.launch(detailInfo.fileName)
                            },
                            onBack = { detailSessionFileName = null },
                            modifier = Modifier
                        )
                    }
                    compareList.size >= 2 -> {
                        SessionComparisonScreen(
                            sessions = compareList,
                            samplesByFile = comparisonSamples,
                            appLabels = appLabels,
                            scoreWeights = config.scoreWeights,
                            onBack = { compareSessionFiles = emptyList() },
                            modifier = Modifier
                        )
                    }
                    else -> {
                        PerformanceScreen(
                            live = metricsLive,
                            sessions = metricsSessions,
                            selectedSession = selectedSession,
                            selectedSamples = selectedSamples,
                            recording = metricsRecording,
                            appLabels = appLabels,
                            scoreWeights = config.scoreWeights,
                            onSelectSession = vm::selectSession,
                            onDeleteSession = vm::deleteSession,
                            onDeleteAllSessions = vm::deleteAllSessions,
                            onToggleSessionKeep = vm::toggleSessionKept,
                            onOpenSessionDetail = { s ->
                                vm.selectSession(s)
                                detailSessionFileName = s.fileName
                            },
                            onOpenCompare = { list ->
                                compareSessionFiles = list.map { it.fileName }
                            },
                            onUpdateScoreWeights = vm::updateScoreWeights,
                            onClearLive = vm::clearLiveMetrics,
                            onExportCsv = vm::exportSessionCsv,
                            modifier = Modifier
                        )
                    }
                }
            }
            NavTab.ENERGY -> {
                val energySnapshot by vm.powerModelSnapshot.collectAsStateWithLifecycle()
                val batterySnapshot by vm.batterySnapshot.collectAsStateWithLifecycle()
                val batteryHistory by vm.batteryHistory.collectAsStateWithLifecycle()
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
                    batteryHistory = batteryHistory,
                    onClearBatteryHistory = vm::clearBatteryHistory,
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
                onUpdateOverlayStyle = vm::updateOverlayStyle,
                onSetOverlayTextScale = vm::setOverlayTextScale,
                onUpdateRamPin = vm::updateRamPin,
                onUpdateClusterRange = vm::updateClusterRange,
                onUpdateGpuRange = vm::updateGpuRange,
                onUpdateModeScalingParams = vm::updateModeScalingParams,
                onSelectTempSensor = vm::selectTempSensor,
                onUpdateTempThrottle = vm::updateTempThrottle,
                onUpdatePowerThrottle = vm::updatePowerThrottle,
                onSetSmoothPowerLimit = vm::setSmoothPowerLimit,
                onSetSmoothMinConfidence = vm::setSmoothMinConfidence,
                onSetSmoothSliceMs = vm::setSmoothSliceMs,
                onSetSmoothSliceAuto = vm::setSmoothSliceAuto,
                onSetSmoothFixDrift = vm::setSmoothFixDrift,
                onUpdateAfterburn = vm::updateAfterburn,
                onSetBoostStrength = vm::setBoostStrength,
                onSavePreset = vm::saveNamedPreset,
                onLoadPreset = vm::loadPreset,
                onDeletePreset = vm::deletePreset,
                onExportJson = vm::exportJson,
                onImportJson = vm::importJson,
                onRediscoverHardware = vm::rediscoverHardware,
                sensorPin = sensorPin,
                onUpdateSensorPin = vm::updateSensorPin,
                protectionStatus = protectionStatus,
                onSetProtectFromKill = vm::setProtectFromKill,
                onReverifyProtection = vm::reverifyProtection,
                stutterHunterStatus = stutterHunterStatus,
                installedApps = installedApps,
                onUpdateStutterHunter = vm::updateStutterHunter,
                onToggleHunterWhitelist = vm::toggleHunterWhitelist,
                gameThreadInspection = gameThreadInspection,
                isRefreshingGameThreads = isRefreshingGameThreads,
                onRefreshGameThreads = vm::refreshGameThreads,
                onSetGameThreadEnabled = vm::setGameThreadEnabled,
                onSetGameThreadAutoClassify = vm::setGameThreadAutoClassify,
                onSetGameThreadSelectedGame = vm::setGameThreadSelectedGame,
                onUpsertThreadMappings = vm::upsertThreadMappings,
                onUpdateThreadMapping = vm::updateThreadMapping,
                onRemoveThreadMapping = vm::removeThreadMapping,
                onRemoveHistoricalThread = vm::removeHistoricalThread,
                onClearGameThreadHistory = vm::clearGameThreadHistory,
                onClearAllThreadHistory = vm::clearAllThreadHistory,
                modifier = Modifier
            )
            NavTab.OPPS -> OppTableScreen(
                hardwareSpec = hardwareSpec,
                telemetryFlow = vm.telemetry,
                onRefreshTable = vm::refreshGpuOppTable,
                modifier = Modifier
            )
            NavTab.APPS -> {
                val atPkg = autoTunePkg
                if (atPkg != null) {
                    val label = installedApps.firstOrNull { it.packageName == atPkg }?.label ?: atPkg
                    val pkgSessions = remember(metricsSessions, atPkg) {
                        metricsSessions.filter { it.packageName == atPkg }
                    }
                    AutoTuneScreen(
                        packageName = atPkg,
                        appName = label,
                        config = config,
                        sessions = pkgSessions,
                        onSetEnabled = { vm.setAutoTuneEnabled(atPkg, it) },
                        onSetMode = { vm.setAutoTuneMode(atPkg, it) },
                        onSetMinMinutes = { vm.setAutoTuneMinMinutes(atPkg, it) },
                        onSetPaused = { vm.setAutoTunePaused(atPkg, it) },
                        onToggleLockParam = { vm.toggleAutoTuneLock(atPkg, it) },
                        onApplySuggestion = { vm.applyAutoTuneSuggestion(atPkg) },
                        onResetToBaseline = { vm.resetAutoTuneToBaseline(atPkg) },
                        onRevertToUserSettings = { vm.revertAutoTuneToUserSettings(atPkg) },
                        onRetune = { vm.retuneAutoTune(atPkg) },
                        onUpdateProfile = { vm.setPerGameProfile(atPkg, it) },
                        onOpenSessionDetail = { s ->
                            vm.selectSession(s)
                            detailSessionFileName = s.fileName
                            currentTab = NavTab.METRICS
                            autoTunePkg = null
                        },
                        onCompareTrialWithBaseline = { baseFn, trialFn ->
                            compareSessionFiles = listOf(baseFn, trialFn)
                            detailSessionFileName = null
                            currentTab = NavTab.METRICS
                            autoTunePkg = null
                        },
                        onBack = { autoTunePkg = null },
                        modifier = Modifier
                    )
                } else {
                    AppsScreen(
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
                        onSmartReset = vm::smartResetApp,
                        autoTuneByPkg = config.autoTuneByPkg,
                        perGameProfiles = config.perGameProfiles,
                        onOpenAutoTune = { autoTunePkg = it.packageName }
                    )
                }
            }
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
