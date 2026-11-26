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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.example.governor.GameThreadHistoryLogic
import com.example.governor.GameThreadInspectionResult
import com.example.governor.GameThreadLogic
import com.example.governor.GameThreadPreset
import com.example.governor.GameThreadStatusReason
import com.example.governor.ObservedThreadRow
import com.example.model.GovernorConfig
import com.example.model.HardwareSpec
import com.example.model.InstalledAppInfo
import com.example.model.ThreadAffinityMapping
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSwitchRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GameThreadSummaryCard(
    config: GovernorConfig,
    onOpenGameThreads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val gtc = config.gameThreadController
    val enabled = gtc.enabled
    EcuCard(
        modifier = modifier,
        accentBorder = if (enabled) ext.optimal else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.gtc_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            StatusPill(
                label = stringResource(if (enabled) R.string.gtc_status_enabled else R.string.gtc_status_disabled),
                color = if (enabled) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = if (enabled) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.gtc_summary_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onOpenGameThreads,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_game_threads_button")
        ) {
            Text(stringResource(R.string.gtc_open_button, gtc.mappings.size))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.gameThreadsItems(
    config: GovernorConfig,
    hardwareSpec: HardwareSpec,
    inspection: GameThreadInspectionResult,
    isRefreshing: Boolean,
    installedApps: List<InstalledAppInfo>,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetAutoClassify: (Boolean) -> Unit,
    onSetSelectedGame: (String) -> Unit,
    onUpsertMappings: (List<ThreadAffinityMapping>) -> Unit,
    onUpdateMapping: (Int, ThreadAffinityMapping) -> Unit,
    onRemoveMapping: (Int) -> Unit,
    onRemoveHistoricalThread: (String, String) -> Unit = { _, _ -> },
    onClearGameHistory: (String) -> Unit = {},
    onClearAllHistory: () -> Unit = {}
) {
    item(key = "gtc_screen_root") {
        GameThreadsPanel(
            config = config,
            hardwareSpec = hardwareSpec,
            inspection = inspection,
            isRefreshing = isRefreshing,
            installedApps = installedApps,
            onRefresh = onRefresh,
            onSetEnabled = onSetEnabled,
            onSetAutoClassify = onSetAutoClassify,
            onSetSelectedGame = onSetSelectedGame,
            onUpsertMappings = onUpsertMappings,
            onUpdateMapping = onUpdateMapping,
            onRemoveMapping = onRemoveMapping,
            onRemoveHistoricalThread = onRemoveHistoricalThread,
            onClearGameHistory = onClearGameHistory,
            onClearAllHistory = onClearAllHistory
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameThreadsPanel(
    config: GovernorConfig,
    hardwareSpec: HardwareSpec,
    inspection: GameThreadInspectionResult,
    isRefreshing: Boolean,
    installedApps: List<InstalledAppInfo>,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetAutoClassify: (Boolean) -> Unit,
    onSetSelectedGame: (String) -> Unit,
    onUpsertMappings: (List<ThreadAffinityMapping>) -> Unit,
    onUpdateMapping: (Int, ThreadAffinityMapping) -> Unit,
    onRemoveMapping: (Int) -> Unit,
    onRemoveHistoricalThread: (String, String) -> Unit = { _, _ -> },
    onClearGameHistory: (String) -> Unit = {},
    onClearAllHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val gtc = config.gameThreadController
    val appLabels = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
    val cpuCount = remember(hardwareSpec, inspection.availableCpuCount) {
        maxOf(
            GameThreadLogic.availableCpuCount(hardwareSpec),
            inspection.availableCpuCount.coerceIn(1, GameThreadLogic.MAX_SUPPORTED_CORES)
        )
    }

    var selectedTids by remember { mutableStateOf(emptySet<Int>()) }
    var customPatternInput by rememberSaveable { mutableStateOf("") }
    var selectedCores by remember {
        mutableStateOf(
            GameThreadPreset.BIG.cores.filterTo(linkedSetOf()) { it < cpuCount }
                .ifEmpty { setOf(0) }
        )
    }
    var exactMatchMode by rememberSaveable { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var pendingAssignMapping by remember { mutableStateOf<ThreadAffinityMapping?>(null) }
    var manualHistoryPkg by rememberSaveable { mutableStateOf("") }
    var confirmClearGamePkg by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val availableThreadsByTid = remember(inspection.threads) {
        inspection.threads.associateBy { it.tid }
    }
    val validSelectedTids = remember(selectedTids, availableThreadsByTid) {
        selectedTids.filterTo(linkedSetOf()) { it in availableThreadsByTid }
    }

    val scopeOptions = remember(
        config.gamePackages,
        gtc.selectedGamePackage,
        gtc.lastObservedGamePackage,
        gtc.threadHistory
    ) {
        (config.gamePackages + gtc.selectedGamePackage + gtc.lastObservedGamePackage + gtc.threadHistory.keys)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    val defaultHistoryPkg = remember(
        inspection.targetPackage,
        inspection.foregroundPackage,
        config.gamePackages,
        gtc.selectedGamePackage,
        gtc.lastObservedGamePackage,
        gtc.threadHistory
    ) {
        inspection.targetPackage.ifBlank {
            GameThreadHistoryLogic.resolveInspectionTargetPackage(
                fgPkg = inspection.foregroundPackage,
                gamePackages = config.gamePackages,
                config = gtc
            )
        }
    }

    val activeHistoryPkg = when {
        manualHistoryPkg.isNotBlank() && manualHistoryPkg in scopeOptions -> manualHistoryPkg
        defaultHistoryPkg.isNotBlank() -> defaultHistoryPkg
        else -> scopeOptions.firstOrNull().orEmpty()
    }

    val activeHistoryRecord = gtc.threadHistory[activeHistoryPkg]
    val isHistoryTargetLive = inspection.threads.isNotEmpty() && inspection.targetPackage == activeHistoryPkg
    val historyRows = remember(
        activeHistoryRecord,
        inspection.threads,
        isHistoryTargetLive,
        gtc.mappings,
        gtc.autoClassify,
        activeHistoryPkg,
        cpuCount
    ) {
        GameThreadHistoryLogic.buildHistoryRows(
            historyRecord = activeHistoryRecord,
            liveThreads = inspection.threads,
            isTargetLive = isHistoryTargetLive,
            config = gtc,
            targetPackage = activeHistoryPkg,
            availableCpuCount = cpuCount
        )
    }

    val liveObservedMap = remember(inspection.targetPackage, gtc.threadHistory) {
        gtc.threadHistory[inspection.targetPackage]?.threads
            ?.associate { it.threadName to it.lastObservedAtMs }
            ?: emptyMap()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Master Controls & Options
        EcuCard(accentBorder = if (gtc.enabled) ext.optimal else null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.gtc_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                StatusPill(
                    label = stringResource(if (gtc.enabled) R.string.gtc_status_enabled else R.string.gtc_status_disabled),
                    color = if (gtc.enabled) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = if (gtc.enabled) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("gtc_status_pill")
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            SettingSwitchRow(
                title = stringResource(R.string.gtc_enable_title),
                explanation = stringResource(R.string.gtc_enable_desc),
                checked = gtc.enabled,
                onCheckedChange = onSetEnabled,
                testTag = "gtc_enable_switch"
            )

            Spacer(modifier = Modifier.height(10.dp))

            SettingSwitchRow(
                title = stringResource(R.string.gtc_auto_classify_title),
                explanation = stringResource(R.string.gtc_auto_classify_desc),
                checked = gtc.autoClassify,
                onCheckedChange = onSetAutoClassify,
                testTag = "gtc_auto_classify_switch"
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.gtc_scope_label),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = gtc.selectedGamePackage.isBlank(),
                    onClick = { onSetSelectedGame("") },
                    label = { Text(stringResource(R.string.gtc_scope_all)) },
                    modifier = Modifier.testTag("gtc_scope_all")
                )
                scopeOptions.forEach { pkg ->
                    val label = gtc.threadHistory[pkg]?.displayName?.ifBlank { null }
                        ?: appLabels[pkg]
                        ?: pkg
                    FilterChip(
                        selected = gtc.selectedGamePackage == pkg,
                        onClick = {
                            manualHistoryPkg = pkg
                            onSetSelectedGame(pkg)
                        },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("gtc_scope_$pkg")
                    )
                }
            }
        }

        // 2. Foreground Game & Live Thread Enumeration
        EcuCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.gtc_session_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.testTag("gtc_refresh_button")
                ) {
                    Text(
                        stringResource(
                            if (isRefreshing) R.string.gtc_refreshing_btn else R.string.gtc_refresh_btn
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val fgText = formatPackageDisplay(
                pkg = inspection.foregroundPackage,
                appLabels = appLabels,
                historyDisplay = gtc.threadHistory[inspection.foregroundPackage]?.displayName,
                fallback = stringResource(R.string.gtc_fg_none)
            )

            InfoKeyValueRow(
                label = stringResource(R.string.gtc_fg_game_label),
                value = fgText,
                testTag = "gtc_fg_game_value"
            )
            if (inspection.targetPackage.isNotBlank() && inspection.targetPackage != inspection.foregroundPackage) {
                Spacer(modifier = Modifier.height(4.dp))
                val targetText = formatPackageDisplay(
                    pkg = inspection.targetPackage,
                    appLabels = appLabels,
                    historyDisplay = gtc.threadHistory[inspection.targetPackage]?.displayName,
                    fallback = inspection.targetPackage
                )
                InfoKeyValueRow(
                    label = stringResource(R.string.gtc_target_game_label),
                    value = targetText,
                    testTag = "gtc_target_game_value"
                )
            }
            if (inspection.processName.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                InfoKeyValueRow(
                    label = stringResource(R.string.gtc_process_label),
                    value = inspection.processName,
                    testTag = "gtc_process_value"
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            InfoKeyValueRow(
                label = stringResource(R.string.gtc_pid_label),
                value = inspection.pid?.toString() ?: stringResource(R.string.gtc_pid_none),
                testTag = "gtc_pid_value"
            )
            Spacer(modifier = Modifier.height(4.dp))
            InfoKeyValueRow(
                label = stringResource(R.string.gtc_cpu_count_label),
                value = stringResource(R.string.gtc_cpu_count_val, cpuCount),
                testTag = "gtc_cpu_count_value"
            )
            if (inspection.refreshedAtMs > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                InfoKeyValueRow(
                    label = stringResource(R.string.gtc_last_refreshed_label),
                    value = formatObservedTimestamp(inspection.refreshedAtMs),
                    testTag = "gtc_last_refreshed_value"
                )
            }

            val targetDisplayLabel = formatPackageDisplay(
                pkg = inspection.targetPackage,
                appLabels = appLabels,
                historyDisplay = gtc.threadHistory[inspection.targetPackage]?.displayName,
                fallback = inspection.targetPackage
            )

            val statusMessage = when (inspection.statusReason) {
                GameThreadStatusReason.ROOT_UNAVAILABLE -> stringResource(R.string.gtc_status_root_unavailable)
                GameThreadStatusReason.NO_GAME_IN_FOREGROUND -> stringResource(R.string.gtc_status_no_game)
                GameThreadStatusReason.NO_PID -> stringResource(R.string.gtc_status_no_pid)
                GameThreadStatusReason.NO_THREADS -> stringResource(
                    R.string.gtc_status_no_threads,
                    inspection.pid ?: 0
                )
                GameThreadStatusReason.BACKGROUND_LIVE -> stringResource(
                    R.string.gtc_status_background_live,
                    targetDisplayLabel,
                    inspection.pid ?: 0
                )
                GameThreadStatusReason.SHOWING_HISTORY -> stringResource(R.string.gtc_status_showing_history)
                GameThreadStatusReason.OK -> null
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (inspection.statusReason == GameThreadStatusReason.ROOT_UNAVAILABLE) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gtc_status_message")
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inspection.statusReason == GameThreadStatusReason.ROOT_UNAVAILABLE) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (inspection.threads.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.gtc_live_threads_title, inspection.threads.size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row {
                        TextButton(
                            onClick = {
                                selectedTids = inspection.threads.mapTo(linkedSetOf()) { it.tid }
                            },
                            modifier = Modifier.testTag("gtc_select_all_threads")
                        ) {
                            Text(stringResource(R.string.gtc_select_all))
                        }
                        if (validSelectedTids.isNotEmpty()) {
                            TextButton(
                                onClick = { selectedTids = emptySet() },
                                modifier = Modifier.testTag("gtc_clear_threads")
                            ) {
                                Text(stringResource(R.string.gtc_clear_selection))
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .testTag("gtc_live_thread_list")
                ) {
                    items(inspection.threads, key = { it.tid }) { thread ->
                        val checked = thread.tid in validSelectedTids
                        val observedTs = liveObservedMap[thread.name] ?: inspection.refreshedAtMs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTids = if (checked) {
                                        validSelectedTids - thread.tid
                                    } else {
                                        validSelectedTids + thread.tid
                                    }
                                }
                                .padding(vertical = 3.dp)
                                .testTag("gtc_thread_${thread.tid}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedTids = if (isChecked) {
                                        validSelectedTids + thread.tid
                                    } else {
                                        validSelectedTids - thread.tid
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text(
                                    text = stringResource(R.string.gtc_thread_row, thread.tid, thread.name),
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (observedTs > 0L) {
                                    Text(
                                        text = stringResource(
                                            R.string.gtc_live_observed_now,
                                            formatObservedTimestamp(observedTs)
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            StatusPill(
                                label = stringResource(R.string.gtc_history_badge_live),
                                color = ext.optimal,
                                containerColor = ext.optimalContainer
                            )
                        }
                    }
                }
            }
        }

        // 3. Thread History (per-game persisted history for research & offline mapping)
        EcuCard(modifier = Modifier.testTag("gtc_thread_history_card")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.gtc_history_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${historyRows.size}",
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("gtc_history_count")
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.gtc_history_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (scopeOptions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.gtc_history_game_picker),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scopeOptions.forEach { pkg ->
                        val label = gtc.threadHistory[pkg]?.displayName?.ifBlank { null }
                            ?: appLabels[pkg]
                            ?: pkg
                        FilterChip(
                            selected = activeHistoryPkg == pkg,
                            onClick = { manualHistoryPkg = pkg },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("gtc_history_game_$pkg")
                        )
                    }
                }
            }

            if (activeHistoryRecord != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val procLabel = activeHistoryRecord.lastProcessName.ifBlank { activeHistoryRecord.packageName }
                Text(
                    text = stringResource(
                        R.string.gtc_history_last_process,
                        procLabel,
                        formatObservedTimestamp(activeHistoryRecord.lastObservedAtMs)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("gtc_history_meta")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (activeHistoryPkg.isBlank() && gtc.threadHistory.isEmpty()) {
                Text(
                    text = stringResource(R.string.gtc_history_empty_games),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("gtc_history_empty")
                )
            } else if (historyRows.isEmpty()) {
                val gameLabel = formatPackageDisplay(
                    pkg = activeHistoryPkg,
                    appLabels = appLabels,
                    historyDisplay = activeHistoryRecord?.displayName,
                    fallback = activeHistoryPkg
                )
                Text(
                    text = stringResource(R.string.gtc_history_empty_for_game, gameLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("gtc_history_empty")
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .testTag("gtc_history_list")
                ) {
                    itemsIndexed(historyRows, key = { _, row -> row.threadName }) { idx, row ->
                        val existingInHistory = activeHistoryRecord?.threads?.any { it.threadName == row.threadName } == true
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTids = emptySet()
                                    customPatternInput = row.threadName
                                }
                                .padding(vertical = 6.dp)
                                .testTag("gtc_history_row_$idx")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = row.threadName,
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                StatusPill(
                                    label = stringResource(
                                        if (row.isLive) R.string.gtc_history_badge_live
                                        else R.string.gtc_history_badge_historical
                                    ),
                                    color = if (row.isLive) ext.optimal else MaterialTheme.colorScheme.onSurfaceVariant,
                                    containerColor = if (row.isLive) ext.optimalContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.testTag("gtc_history_badge_$idx")
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            val timeText = formatObservedTimestamp(row.lastObservedAtMs)
                            Text(
                                text = if (row.isLive) {
                                    stringResource(
                                        R.string.gtc_history_live_meta,
                                        row.liveTids.joinToString(", "),
                                        timeText,
                                        row.observationCount
                                    )
                                } else {
                                    stringResource(
                                        R.string.gtc_history_past_meta,
                                        row.lastKnownTid,
                                        timeText,
                                        row.observationCount
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val matched = row.matchedMapping
                            Text(
                                text = if (matched != null) {
                                    val modeText = stringResource(
                                        if (matched.exactMatch) R.string.gtc_match_exact
                                        else R.string.gtc_match_pattern
                                    )
                                    val coresText = GameThreadLogic.formatMaskLabel(matched.cpuMask, cpuCount)
                                    stringResource(
                                        R.string.gtc_history_mapping_assigned,
                                        modeText,
                                        coresText,
                                        matched.cpuMask
                                    )
                                } else {
                                    stringResource(R.string.gtc_history_mapping_none)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (matched != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedTids = emptySet()
                                        customPatternInput = row.threadName
                                    },
                                    modifier = Modifier.testTag("gtc_history_use_$idx")
                                ) {
                                    Text(stringResource(R.string.gtc_history_use_btn))
                                }
                                TextButton(
                                    onClick = {
                                        val existingIdx = if (matched != null) {
                                            gtc.mappings.indexOf(matched)
                                        } else {
                                            -1
                                        }
                                        if (existingIdx >= 0) {
                                            editingIndex = existingIdx
                                        } else {
                                            val defaultMask = GameThreadLogic.coresToHexMask(selectedCores, cpuCount)
                                                ?: GameThreadLogic.presetMask(GameThreadPreset.BIG, cpuCount)
                                                ?: "1"
                                            val defaultScope = gtc.selectedGamePackage.ifBlank { activeHistoryPkg }
                                            pendingAssignMapping = ThreadAffinityMapping(
                                                pattern = row.threadName,
                                                cpuMask = defaultMask,
                                                exactMatch = exactMatchMode,
                                                packageName = defaultScope
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("gtc_history_assign_$idx")
                                ) {
                                    Text(
                                        stringResource(
                                            if (matched != null) R.string.gtc_history_edit_btn
                                            else R.string.gtc_history_assign_btn
                                        )
                                    )
                                }
                                if (existingInHistory) {
                                    TextButton(
                                        onClick = { onRemoveHistoricalThread(activeHistoryPkg, row.threadName) },
                                        modifier = Modifier.testTag("gtc_history_remove_$idx")
                                    ) {
                                        Text(
                                            stringResource(R.string.common_remove),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        if (idx < historyRows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }

            if (activeHistoryRecord?.threads?.isNotEmpty() == true || gtc.threadHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeHistoryRecord?.threads?.isNotEmpty() == true) {
                        OutlinedButton(
                            onClick = { confirmClearGamePkg = activeHistoryPkg },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gtc_clear_game_history_btn")
                        ) {
                            Text(
                                stringResource(R.string.gtc_history_clear_game_btn),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (gtc.threadHistory.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { confirmClearAll = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gtc_clear_all_history_btn")
                        ) {
                            Text(
                                stringResource(R.string.gtc_history_clear_all_btn),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 4. Thread Selection & CPU Assignment
        EcuCard {
            Text(
                text = stringResource(R.string.gtc_assign_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (validSelectedTids.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.gtc_selected_count, validSelectedTids.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                val previewNames = validSelectedTids.mapNotNull { availableThreadsByTid[it]?.name }.distinct()
                Text(
                    text = previewNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                OutlinedTextField(
                    value = customPatternInput,
                    onValueChange = { customPatternInput = it },
                    label = { Text(stringResource(R.string.gtc_pattern_label)) },
                    placeholder = { Text(stringResource(R.string.gtc_pattern_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gtc_pattern_input")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            CoreMaskSelector(
                selectedCores = selectedCores,
                availableCpuCount = cpuCount,
                exactMatch = exactMatchMode,
                onCoresChange = { selectedCores = it },
                onExactMatchChange = { exactMatchMode = it }
            )

            Spacer(modifier = Modifier.height(10.dp))
            val computedMask = GameThreadLogic.coresToHexMask(selectedCores, cpuCount)
            val canSave = computedMask != null &&
                (validSelectedTids.isNotEmpty() || customPatternInput.isNotBlank())

            Button(
                onClick = {
                    val mask = computedMask ?: return@Button
                    if (validSelectedTids.isNotEmpty()) {
                        val names = validSelectedTids
                            .mapNotNull { availableThreadsByTid[it]?.name?.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val newMappings = names.map { name ->
                            ThreadAffinityMapping(
                                pattern = name,
                                cpuMask = mask,
                                exactMatch = exactMatchMode,
                                packageName = gtc.selectedGamePackage
                            )
                        }
                        onUpsertMappings(newMappings)
                        selectedTids = emptySet()
                    } else if (customPatternInput.isNotBlank()) {
                        onUpsertMappings(
                            listOf(
                                ThreadAffinityMapping(
                                    pattern = customPatternInput.trim(),
                                    cpuMask = mask,
                                    exactMatch = exactMatchMode,
                                    packageName = gtc.selectedGamePackage
                                )
                            )
                        )
                        customPatternInput = ""
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gtc_save_mapping_button")
            ) {
                Text(
                    if (validSelectedTids.isNotEmpty()) {
                        stringResource(R.string.gtc_save_selected_btn, validSelectedTids.size)
                    } else {
                        stringResource(R.string.gtc_save_mapping_btn)
                    }
                )
            }
        }

        // 5. Saved Thread Mappings
        EcuCard {
            Text(
                text = stringResource(R.string.gtc_mappings_header, gtc.mappings.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (gtc.mappings.isEmpty()) {
                Text(
                    text = stringResource(
                        if (gtc.autoClassify) R.string.gtc_mappings_empty_auto else R.string.gtc_mappings_empty
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("gtc_mappings_empty_text")
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gtc.mappings.forEachIndexed { idx, mapping ->
                        val matchLabel = stringResource(
                            if (mapping.exactMatch) R.string.gtc_match_exact else R.string.gtc_match_pattern
                        )
                        val coresLabel = GameThreadLogic.formatMaskLabel(mapping.cpuMask, cpuCount)
                        val scopeLabel = if (mapping.packageName.isBlank()) {
                            stringResource(R.string.gtc_scope_all)
                        } else {
                            gtc.threadHistory[mapping.packageName]?.displayName?.ifBlank { null }
                                ?: appLabels[mapping.packageName]
                                ?: mapping.packageName
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gtc_mapping_row_$idx"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = mapping.pattern,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.gtc_mapping_meta,
                                        matchLabel,
                                        coresLabel,
                                        mapping.cpuMask,
                                        scopeLabel
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                TextButton(
                                    onClick = { editingIndex = idx },
                                    modifier = Modifier.testTag("gtc_edit_mapping_$idx")
                                ) {
                                    Text(stringResource(R.string.gtc_edit_btn))
                                }
                                TextButton(
                                    onClick = { onRemoveMapping(idx) },
                                    modifier = Modifier.testTag("gtc_remove_mapping_$idx")
                                ) {
                                    Text(
                                        stringResource(R.string.common_remove),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        if (idx < gtc.mappings.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }

    val activeEditIdx = editingIndex
    val activeMapping = if (activeEditIdx != null && activeEditIdx in gtc.mappings.indices) {
        gtc.mappings[activeEditIdx]
    } else null

    if (activeEditIdx != null && activeMapping != null) {
        EditThreadMappingDialog(
            mapping = activeMapping,
            availableCpuCount = cpuCount,
            scopeOptions = scopeOptions,
            appLabels = appLabels,
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                onUpdateMapping(activeEditIdx, updated)
                editingIndex = null
            }
        )
    }

    val assignDraft = pendingAssignMapping
    if (assignDraft != null) {
        EditThreadMappingDialog(
            mapping = assignDraft,
            availableCpuCount = cpuCount,
            scopeOptions = scopeOptions,
            appLabels = appLabels,
            titleRes = R.string.gtc_add_dialog_title,
            onDismiss = { pendingAssignMapping = null },
            onSave = { created ->
                onUpsertMappings(listOf(created))
                pendingAssignMapping = null
            }
        )
    }

    val clearTarget = confirmClearGamePkg
    if (clearTarget != null) {
        val clearLabel = formatPackageDisplay(
            pkg = clearTarget,
            appLabels = appLabels,
            historyDisplay = gtc.threadHistory[clearTarget]?.displayName,
            fallback = clearTarget
        )
        AlertDialog(
            onDismissRequest = { confirmClearGamePkg = null },
            title = { Text(stringResource(R.string.gtc_history_confirm_clear_game_title)) },
            text = { Text(stringResource(R.string.gtc_history_confirm_clear_game_body, clearLabel)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearGameHistory(clearTarget)
                        confirmClearGamePkg = null
                    },
                    modifier = Modifier.testTag("gtc_confirm_clear_game_btn")
                ) {
                    Text(
                        stringResource(R.string.common_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearGamePkg = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.gtc_history_confirm_clear_all_title)) },
            text = { Text(stringResource(R.string.gtc_history_confirm_clear_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAllHistory()
                        confirmClearAll = false
                    },
                    modifier = Modifier.testTag("gtc_confirm_clear_all_btn")
                ) {
                    Text(
                        stringResource(R.string.common_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private fun formatPackageDisplay(
    pkg: String,
    appLabels: Map<String, String>,
    historyDisplay: String?,
    fallback: String
): String {
    if (pkg.isBlank()) return fallback
    val label = historyDisplay?.takeIf { it.isNotBlank() } ?: appLabels[pkg]
    return if (!label.isNullOrBlank() && label != pkg) {
        "$label ($pkg)"
    } else {
        pkg
    }
}

private fun formatObservedTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "--"
    return SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoreMaskSelector(
    selectedCores: Set<Int>,
    availableCpuCount: Int,
    exactMatch: Boolean,
    onCoresChange: (Set<Int>) -> Unit,
    onExactMatchChange: (Boolean) -> Unit
) {
    Text(
        text = stringResource(R.string.gtc_match_mode_label),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !exactMatch,
            onClick = { onExactMatchChange(false) },
            label = { Text(stringResource(R.string.gtc_match_pattern)) },
            modifier = Modifier.testTag("gtc_mode_pattern")
        )
        FilterChip(
            selected = exactMatch,
            onClick = { onExactMatchChange(true) },
            label = { Text(stringResource(R.string.gtc_match_exact)) },
            modifier = Modifier.testTag("gtc_mode_exact")
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.gtc_presets_label),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val presets = listOf(
            GameThreadPreset.BIG to R.string.gtc_preset_big,
            GameThreadPreset.MID to R.string.gtc_preset_mid,
            GameThreadPreset.LITTLE to R.string.gtc_preset_little
        )
        presets.forEach { (preset, labelRes) ->
            val presetValidCores = preset.cores.filterTo(linkedSetOf()) { it < availableCpuCount }
            val isSelected = presetValidCores.isNotEmpty() && selectedCores == presetValidCores
            FilterChip(
                selected = isSelected,
                enabled = presetValidCores.isNotEmpty(),
                onClick = { onCoresChange(presetValidCores) },
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.testTag("gtc_preset_${preset.name.lowercase()}")
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (core in 0 until GameThreadLogic.UI_CORE_COUNT) {
            val supported = core < availableCpuCount
            val checked = supported && core in selectedCores
            FilterChip(
                selected = checked,
                enabled = supported,
                onClick = {
                    val next = if (core in selectedCores) selectedCores - core else selectedCores + core
                    onCoresChange(next)
                },
                label = { Text(stringResource(R.string.gtc_core_chip, core)) },
                modifier = Modifier.testTag("gtc_core_chip_$core")
            )
        }
    }

    if (availableCpuCount < GameThreadLogic.UI_CORE_COUNT) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gtc_cpu_limit_note, availableCpuCount, availableCpuCount - 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(6.dp))
    val validSelected = selectedCores.filterTo(linkedSetOf()) { it in 0 until availableCpuCount }
    val hexMask = GameThreadLogic.coresToHexMask(validSelected, availableCpuCount)
    val readable = GameThreadLogic.formatCoresLabel(validSelected)
    Text(
        text = if (hexMask != null) {
            stringResource(R.string.gtc_mask_preview, readable, hexMask)
        } else {
            stringResource(R.string.gtc_mask_empty)
        },
        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
        color = if (hexMask != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("gtc_mask_preview_text")
    )
}

@Composable
private fun EditThreadMappingDialog(
    mapping: ThreadAffinityMapping,
    availableCpuCount: Int,
    scopeOptions: List<String>,
    appLabels: Map<String, String>,
    titleRes: Int = R.string.gtc_edit_dialog_title,
    onDismiss: () -> Unit,
    onSave: (ThreadAffinityMapping) -> Unit
) {
    var pattern by remember { mutableStateOf(mapping.pattern) }
    var exactMatch by remember { mutableStateOf(mapping.exactMatch) }
    var packageName by remember { mutableStateOf(mapping.packageName) }
    var cores by remember {
        mutableStateOf(
            GameThreadLogic.hexMaskToCores(mapping.cpuMask, availableCpuCount)
                .ifEmpty { setOf(0) }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.gtc_pattern_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gtc_edit_pattern_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.gtc_scope_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = packageName.isBlank(),
                        onClick = { packageName = "" },
                        label = { Text(stringResource(R.string.gtc_scope_all)) }
                    )
                    scopeOptions.forEach { pkg ->
                        FilterChip(
                            selected = packageName == pkg,
                            onClick = { packageName = pkg },
                            label = { Text(appLabels[pkg] ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                CoreMaskSelector(
                    selectedCores = cores,
                    availableCpuCount = availableCpuCount,
                    exactMatch = exactMatch,
                    onCoresChange = { cores = it },
                    onExactMatchChange = { exactMatch = it }
                )
            }
        },
        confirmButton = {
            val mask = GameThreadLogic.coresToHexMask(cores, availableCpuCount)
            TextButton(
                onClick = {
                    if (mask != null && pattern.isNotBlank()) {
                        onSave(
                            ThreadAffinityMapping(
                                pattern = pattern.trim(),
                                cpuMask = mask,
                                exactMatch = exactMatch,
                                packageName = packageName.trim()
                            )
                        )
                    }
                },
                enabled = mask != null && pattern.isNotBlank(),
                modifier = Modifier.testTag("gtc_edit_save_btn")
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun InfoKeyValueRow(
    label: String,
    value: String,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(testTag)
        )
    }
}
