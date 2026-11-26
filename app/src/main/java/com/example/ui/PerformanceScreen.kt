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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.ImpactConfidence
import com.example.governor.MetricSample
import com.example.governor.MetricSummary
import com.example.governor.MetricsMath
import com.example.governor.SessionInfo
import com.example.governor.TweakImpactAnalyzer
import com.example.governor.TweakImpactEntry
import com.example.model.BatteryState
import com.example.model.CompositeScoreWeights
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val unit: String,
    val decimals: Int = 1,
    /** Zero-based lines (FPS, watts) start at 0; temperature fits its own min..max. */
    val zeroBased: Boolean = true,
    val states: List<BatteryState>? = null,
    val chargingColor: Color? = null
)

private fun fmt(v: Float, decimals: Int = 1): String =
    if (v.isNaN() || v.isInfinite()) "–" else String.format(Locale.US, "%.${decimals}f", v)

private fun fmtDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
fun PerformanceScreen(
    live: List<MetricSample>,
    sessions: List<SessionInfo>,
    selectedSession: SessionInfo?,
    selectedSamples: List<MetricSample>,
    recording: Boolean,
    appLabels: Map<String, String>,
    scoreWeights: CompositeScoreWeights = CompositeScoreWeights(),
    onSelectSession: (SessionInfo?) -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onDeleteAllSessions: (Boolean) -> Unit,
    onToggleSessionKeep: (SessionInfo) -> Unit = {},
    onOpenSessionDetail: (SessionInfo) -> Unit = {},
    onOpenCompare: (List<SessionInfo>) -> Unit = {},
    onUpdateScoreWeights: (CompositeScoreWeights) -> Unit = {},
    onClearLive: () -> Unit,
    onExportCsv: (SessionInfo, android.net.Uri) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val exportContext = androidx.compose.ui.platform.LocalContext.current
    var csvTarget by remember { mutableStateOf<SessionInfo?>(null) }
    val csvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val target = csvTarget
        if (uri != null && target != null) {
            onExportCsv(target, uri)
            android.widget.Toast.makeText(
                exportContext,
                exportContext.getString(R.string.metrics_saving_csv),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    var windowMin by rememberSaveable { mutableStateOf(5) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var includeKeptInDeleteAll by remember { mutableStateOf(false) }
    var compareSelectMode by rememberSaveable { mutableStateOf(false) }
    var compareFileNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showWeightsDialog by remember { mutableStateOf(false) }

    val distinctRankGames = remember(sessions) {
        sessions.map { it.packageName }.filter { it.isNotBlank() }.distinct()
    }
    var selectedRankPkg by rememberSaveable { mutableStateOf("") }
    val effectiveRankPkg = if (selectedRankPkg in distinctRankGames) selectedRankPkg else (distinctRankGames.firstOrNull() ?: "")

    val samples = remember(live, selectedSession, selectedSamples, windowMin) {
        if (selectedSession != null) {
            selectedSamples
        } else {
            val cutoff = (live.lastOrNull()?.tMs ?: 0L) - windowMin * 60_000L
            live.filter { it.tMs >= cutoff }
        }
    }
    val summary = remember(samples) { MetricsMath.summarize(samples) }
    val tempSeries = remember(samples) { samples.map { it.tempC } }
    val wattSeries = remember(samples) { samples.map { it.watts } }
    val battStates = remember(samples) { samples.map { it.batteryState } }
    val fpsSeries = remember(samples) { samples.map { if (it.fps >= 0f) it.fps else Float.NaN } }
    val fpwSeries = remember(samples) { MetricsMath.movingAverage(samples.map { it.fpsPerWatt }, 5) }
    val wpwSeries = remember(samples) { MetricsMath.movingAverage(samples.map { it.workPerWatt }, 5) }
    val times = remember(samples) { samples.map { it.tMs } }
    val viewingSaved = selectedSession != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header and source selector
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.metrics_title), style = MaterialTheme.typography.titleMedium)
                    if (recording) {
                        StatusPill(
                            label = stringResource(R.string.metrics_rec_game),
                            color = ext.warning,
                            containerColor = ext.warningContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedSession != null) {
                    val name = appLabels[selectedSession.packageName] ?: selectedSession.packageName
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.metrics_saved_session_meta,
                            formatDate(selectedSession.startMs),
                            fmtDuration(selectedSession.summary.durationMs)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scrub = null; onSelectSession(null) }) {
                            Text(stringResource(R.string.metrics_back_to_live))
                        }
                        Button(onClick = { onOpenSessionDetail(selectedSession) }) {
                            Text(stringResource(R.string.metrics_full_details_btn))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val ver = try {
                                exportContext.packageManager.getPackageInfo(exportContext.packageName, 0).versionName ?: ""
                            } catch (_: Exception) { "" }
                            val text = com.example.governor.SessionExport.summaryText(
                                title = name,
                                startMs = selectedSession.startMs,
                                s = selectedSession.summary,
                                device = android.os.Build.MODEL ?: "Android",
                                appVersion = ver
                            )
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            exportContext.startActivity(
                                android.content.Intent.createChooser(
                                    send,
                                    exportContext.getString(R.string.metrics_share_chooser)
                                )
                            )
                        }) { Text(stringResource(R.string.metrics_share_summary)) }
                        OutlinedButton(onClick = {
                            csvTarget = selectedSession
                            csvLauncher.launch(selectedSession.fileName)
                        }) { Text(stringResource(R.string.metrics_save_csv)) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30).forEach { m ->
                            FilterChip(
                                selected = windowMin == m,
                                onClick = { windowMin = m; scrub = null },
                                label = { Text(stringResource(R.string.metrics_live_window, m)) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.metrics_auto_saved_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { scrub = null; onClearLive() }) {
                            Text(stringResource(R.string.metrics_clear_live))
                        }
                    }
                }
            }
        }

        if (samples.size < 2) {
            item {
                EcuCard {
                    Text(
                        text = if (viewingSaved) {
                            stringResource(R.string.metrics_loading_session)
                        } else {
                            stringResource(R.string.metrics_no_data)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 2. Summary tiles
            item { SummaryCard(summary = summary, live = !viewingSaved, last = samples.last()) }

            // 3. Overlay: power, temperature, FPS together
            item {
                EcuCard {
                    MetricChart(
                        title = stringResource(R.string.metrics_chart_power_temp_fps),
                        series = listOf(
                            ChartSeries(stringResource(R.string.metrics_series_fps), primary, fpsSeries, "", 0),
                            ChartSeries(stringResource(R.string.metrics_series_power), ext.optimal, wattSeries, " W", 1, states = battStates, chargingColor = ext.warning),
                            ChartSeries(stringResource(R.string.metrics_series_temp), ext.throttle, tempSeries, "°C", 1, zeroBased = false)
                        ),
                        timesMs = times,
                        saved = viewingSaved,
                        scrub = scrub,
                        onScrub = { scrub = it },
                        height = 170.dp,
                        showScaleNote = true
                    )
                }
            }

            // 4. FPS per watt
            item {
                EcuCard {
                    MetricChart(
                        title = stringResource(R.string.metrics_chart_fpw),
                        series = listOf(ChartSeries(stringResource(R.string.metrics_series_fpw), primary, fpwSeries, "", 1)),
                        timesMs = times,
                        saved = viewingSaved,
                        scrub = scrub,
                        onScrub = { scrub = it },
                        height = 130.dp
                    )
                }
            }

            // 5. Work per watt
            item {
                EcuCard {
                    MetricChart(
                        title = stringResource(R.string.metrics_chart_wpw),
                        series = listOf(ChartSeries(stringResource(R.string.metrics_series_wpw), secondary, wpwSeries, "", 2)),
                        timesMs = times,
                        saved = viewingSaved,
                        scrub = scrub,
                        onScrub = { scrub = it },
                        height = 130.dp
                    )
                }
            }
        }

        // 6. Tweak impact ranking per game
        if (effectiveRankPkg.isNotBlank() && sessions.count { it.packageName == effectiveRankPkg } >= 2) {
            item {
                val report = remember(effectiveRankPkg, sessions, scoreWeights) {
                    TweakImpactAnalyzer.analyze(effectiveRankPkg, sessions, scoreWeights)
                }
                TweakImpactRankingCard(
                    games = distinctRankGames,
                    selectedPkg = effectiveRankPkg,
                    appLabels = appLabels,
                    report = report,
                    onSelectPkg = { selectedRankPkg = it },
                    onOpenWeights = { showWeightsDialog = true }
                )
            }
        }

        // 7. Saved sessions + compare selector
        item {
            val selectedCompareSessions = remember(sessions, compareFileNames) {
                compareFileNames.mapNotNull { fn -> sessions.firstOrNull { it.fileName == fn } }
            }
            val diffGamesInCompare = selectedCompareSessions.map { it.packageName }.distinct().size > 1
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.metrics_saved_sessions_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sessions.size >= 2) {
                            TextButton(onClick = {
                                compareSelectMode = !compareSelectMode
                                if (!compareSelectMode) compareFileNames = emptyList()
                            }) {
                                Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(
                                        if (compareSelectMode) R.string.common_cancel else R.string.metrics_compare_btn
                                    )
                                )
                            }
                        }
                        if (sessions.isNotEmpty()) {
                            TextButton(onClick = {
                                includeKeptInDeleteAll = false
                                confirmDeleteAll = true
                            }) {
                                Text(stringResource(R.string.metrics_delete_all))
                            }
                        }
                    }
                }
                if (compareSelectMode) {
                    Text(
                        text = stringResource(R.string.metrics_compare_pick_hint, selectedCompareSessions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (diffGamesInCompare) {
                        Text(
                            text = stringResource(R.string.cmp_warn_diff_games),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.warning
                        )
                    }
                    if (selectedCompareSessions.size in 2..4) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(onClick = { onOpenCompare(selectedCompareSessions) }) {
                            Text(stringResource(R.string.metrics_compare_launch_btn, selectedCompareSessions.size))
                        }
                    }
                }
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.metrics_sessions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(sessions, key = { it.fileName }) { info ->
            val inCompare = info.fileName in compareFileNames
            SessionRow(
                info = info,
                name = appLabels[info.packageName] ?: info.packageName,
                selected = if (compareSelectMode) inCompare else selectedSession?.fileName == info.fileName,
                compareMode = compareSelectMode,
                checkedForCompare = inCompare,
                onClick = {
                    if (compareSelectMode) {
                        compareFileNames = if (inCompare) {
                            compareFileNames - info.fileName
                        } else if (compareFileNames.size < 4) {
                            compareFileNames + info.fileName
                        } else {
                            compareFileNames
                        }
                    } else {
                        scrub = null
                        onSelectSession(info)
                    }
                },
                onToggleKeep = { onToggleSessionKeep(info) },
                onOpenDetails = { onOpenSessionDetail(info) },
                onDelete = { onDeleteSession(info) }
            )
        }

        // 8. How the numbers are made
        item {
            EcuCard {
                Text(
                    stringResource(R.string.metrics_how_calc_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.metrics_how_calc_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmDeleteAll) {
        val keptCount = sessions.count { it.kept }
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.metrics_delete_all_title)) },
            text = {
                Column {
                    val removable = if (includeKeptInDeleteAll) sessions.size else (sessions.size - keptCount)
                    Text(pluralStringResource(R.plurals.plural_sessions_remove, removable, removable))
                    if (keptCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { includeKeptInDeleteAll = !includeKeptInDeleteAll }
                        ) {
                            Checkbox(
                                checked = includeKeptInDeleteAll,
                                onCheckedChange = { includeKeptInDeleteAll = it }
                            )
                            Text(
                                text = stringResource(R.string.metrics_delete_include_kept, keptCount),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; onDeleteAllSessions(includeKeptInDeleteAll) }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showWeightsDialog) {
        CompositeScoreWeightsDialog(
            initial = scoreWeights,
            onDismiss = { showWeightsDialog = false },
            onSave = {
                showWeightsDialog = false
                onUpdateScoreWeights(it)
            }
        )
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun SummaryCard(summary: MetricSummary, live: Boolean, last: MetricSample) {
    EcuCard {
        if (live) {
            val nowPwr = when (last.batteryState) {
                BatteryState.CHARGING -> stringResource(R.string.metrics_chg_val, fmt(last.watts))
                BatteryState.FULL_PLUGGED -> stringResource(R.string.metrics_plug_val)
                BatteryState.DISCHARGING -> "${fmt(last.watts)} W"
                BatteryState.UNKNOWN -> "– W"
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = stringResource(
                        R.string.metrics_now_line,
                        if (last.fps >= 0f) fmt(last.fps, 0) else "–",
                        nowPwr,
                        fmt(last.tempC)
                    ),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.metrics_stat_avg_fps), fmt(summary.avgFps, 0), Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_5pct_low), fmt(summary.p5Fps, 0), Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_avg_power), fmt(summary.avgWatts) + " W", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.metrics_stat_fpw), fmt(summary.avgFpsPerWatt), Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_wpw), fmt(summary.avgWorkPerWatt, 2), Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_max_temp), fmt(summary.maxTempC) + "°C", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.metrics_stat_peak_power), fmt(summary.maxWatts) + " W", Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_min_power), fmt(summary.minWatts) + " W", Modifier.weight(1f))
            StatTile(stringResource(R.string.metrics_stat_energy), fmt(summary.energyWh, 2) + " Wh", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        val chgNote = if (summary.includesCharging) stringResource(R.string.metrics_chg_excluded) else ""
        Text(
            text = stringResource(R.string.metrics_duration_shown, fmtDuration(summary.durationMs), chgNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SessionRow(
    info: SessionInfo,
    name: String,
    selected: Boolean,
    compareMode: Boolean,
    checkedForCompare: Boolean,
    onClick: () -> Unit,
    onToggleKeep: () -> Unit,
    onOpenDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val s = info.summary
    EcuCard(accentBorder = if (selected) primary else null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (compareMode) {
                Checkbox(
                    checked = checkedForCompare,
                    onCheckedChange = { onClick() }
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (info.kept) {
                        StatusPill(
                            label = stringResource(R.string.metrics_kept_pill),
                            color = primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                    if (info.midSessionChanges > 0) {
                        StatusPill(
                            label = stringResource(R.string.metrics_mid_changes_pill, info.midSessionChanges),
                            color = ext.throttle,
                            containerColor = ext.throttle.copy(alpha = 0.16f)
                        )
                    }
                }
                val chgBadge = if (s.includesCharging) stringResource(R.string.metrics_incl_charging) else ""
                val snapNote = if (info.snapshot == null) " • ${stringResource(R.string.snap_not_recorded)}" else ""
                Text(
                    text = "${formatDate(info.startMs)} • ${fmtDuration(s.durationMs)}$chgBadge$snapNote",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.metrics_session_stats,
                        fmt(s.avgFps, 0),
                        fmt(s.avgWatts),
                        fmt(s.avgFpsPerWatt),
                        fmt(s.maxTempC)
                    ),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.metrics_session_power_minmax, fmt(s.minWatts), fmt(s.maxWatts)),
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!compareMode) {
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        IconButton(onClick = onToggleKeep) {
                            Icon(
                                imageVector = if (info.kept) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = stringResource(
                                    if (info.kept) R.string.metrics_unkeep_cd else R.string.metrics_keep_cd
                                ),
                                tint = if (info.kept) primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.metrics_delete_session_cd))
                        }
                    }
                    TextButton(onClick = onOpenDetails) {
                        Text(stringResource(R.string.metrics_details_short_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun TweakImpactRankingCard(
    games: List<String>,
    selectedPkg: String,
    appLabels: Map<String, String>,
    report: com.example.governor.TweakImpactReport,
    onSelectPkg: (String) -> Unit,
    onOpenWeights: () -> Unit
) {
    val ext = LocalExtendedColors.current
    EcuCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.rank_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenWeights) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.rank_weights_btn))
            }
        }
        if (games.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                games.take(4).forEach { pkg ->
                    FilterChip(
                        selected = pkg == selectedPkg,
                        onClick = { onSelectPkg(pkg) },
                        label = { Text(appLabels[pkg] ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (report.analyzedSessionCount < 2 || (report.bestTweaks.isEmpty() && report.worstTweaks.isEmpty())) {
            Text(
                text = stringResource(R.string.rank_not_enough_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.rank_analyzed_count, report.analyzedSessionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.bestTweaks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rank_best_tweaks),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ext.optimal
                )
                report.bestTweaks.take(4).forEach { e ->
                    TweakImpactRow(entry = e, positive = true)
                }
            }
            if (report.worstTweaks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rank_worst_tweaks),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ext.throttle
                )
                report.worstTweaks.take(4).forEach { e ->
                    TweakImpactRow(entry = e, positive = false)
                }
            }
        }
    }
}

@Composable
private fun TweakImpactRow(entry: TweakImpactEntry, positive: Boolean) {
    val ext = LocalExtendedColors.current
    val deltaColor = if (positive) ext.optimal else ext.throttle
    val arrow = if (entry.deltaScore >= 0f) "↑ +" else "↓ "
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${stringResource(snapshotFieldLabelRes(entry.fieldKey))}: ${localizedSnapshotValue(entry.fromValue)} → ${localizedSnapshotValue(entry.toValue)}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            Text(
                text = "$arrow${String.format(Locale.US, "%.1f", entry.deltaScore)}",
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize, fontWeight = FontWeight.Bold),
                color = deltaColor
            )
        }
        val confStr = stringResource(impactConfidenceLabelRes(entry.confidence))
        val fpsStr = if (entry.deltaFps.isFinite()) "${if (entry.deltaFps >= 0f) "+" else ""}${String.format(Locale.US, "%.1f", entry.deltaFps)} FPS" else "–"
        val p5Str = if (entry.deltaP5Fps.isFinite()) "${if (entry.deltaP5Fps >= 0f) "+" else ""}${String.format(Locale.US, "%.1f", entry.deltaP5Fps)} p5" else "–"
        val wStr = if (entry.deltaWatts.isFinite()) "${if (entry.deltaWatts >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", entry.deltaWatts)} W" else "–"
        val tStr = if (entry.deltaTempC.isFinite()) "${if (entry.deltaTempC >= 0f) "+" else ""}${String.format(Locale.US, "%.1f", entry.deltaTempC)} °C" else "–"
        Text(
            text = stringResource(R.string.rank_entry_meta, fpsStr, p5Str, wStr, tStr, entry.fromCount, entry.toCount, confStr),
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.confidence == ImpactConfidence.LOW) ext.warning else MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    }
}

@Composable
private fun CompositeScoreWeightsDialog(
    initial: CompositeScoreWeights,
    onDismiss: () -> Unit,
    onSave: (CompositeScoreWeights) -> Unit
) {
    var p5 by remember { mutableStateOf(initial.p5FpsPct) }
    var avg by remember { mutableStateOf(initial.avgFpsPct) }
    var fpw by remember { mutableStateOf(initial.fpsPerWattPct) }
    var temp by remember { mutableStateOf(initial.tempPct) }
    var stab by remember { mutableStateOf(initial.stabilityPct) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rank_weights_dialog_title)) },
        text = {
            Column {
                WeightSliderRow(stringResource(R.string.cmp_metric_p5_fps), p5) { p5 = it }
                WeightSliderRow(stringResource(R.string.cmp_metric_avg_fps), avg) { avg = it }
                WeightSliderRow(stringResource(R.string.cmp_metric_fpw), fpw) { fpw = it }
                WeightSliderRow(stringResource(R.string.cmp_metric_avg_temp), temp) { temp = it }
                WeightSliderRow(stringResource(R.string.cmp_metric_stability), stab) { stab = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        p5FpsPct = p5,
                        avgFpsPct = avg,
                        fpsPerWattPct = fpw,
                        tempPct = temp,
                        stabilityPct = stab
                    )
                )
            }) {
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
private fun WeightSliderRow(label: String, pct: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$pct%", style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
        }
        Slider(
            value = pct.toFloat().coerceIn(0f, 100f),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f
        )
    }
}

/**
 * Line chart with one or more series sharing the same time axis. Each series is scaled to its own
 * range so FPS, watts and degrees can sit on one plot. Dragging shows the values at that moment,
 * and the same position is shown on every chart on the screen.
 */
@Composable
private fun MetricChart(
    title: String,
    series: List<ChartSeries>,
    timesMs: List<Long>,
    saved: Boolean,
    scrub: Float?,
    onScrub: (Float?) -> Unit,
    height: Dp,
    showScaleNote: Boolean = false
) {
    val ext = LocalExtendedColors.current
    val gridColor = ext.chartGrid
    val onScrubState by rememberUpdatedState(onScrub)
    val n = timesMs.size
    val scrubIndex = scrub?.let { (it * (n - 1)).roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0)) }

    val ranges = remember(series) { series.map { rangeOf(it) } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.forEach { sr ->
                val activeIdx = scrubIndex ?: sr.values.indexOfLast { !it.isNaN() }
                val v = if (activeIdx >= 0) sr.values.getOrNull(activeIdx) ?: Float.NaN else Float.NaN
                val ptState = if (activeIdx >= 0) sr.states?.getOrNull(activeIdx) else null
                val dotColor = if (ptState != null && ptState != BatteryState.DISCHARGING && sr.chargingColor != null) {
                    sr.chargingColor
                } else {
                    sr.color
                }
                val itemText = when (ptState) {
                    BatteryState.CHARGING -> stringResource(R.string.metrics_chg_val_unit, fmt(v, sr.decimals), sr.unit)
                    BatteryState.FULL_PLUGGED -> stringResource(R.string.metrics_plug_val_unit, sr.unit)
                    else -> "${sr.label} ${fmt(v, sr.decimals)}${sr.unit}"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = itemText,
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { off -> onScrubState((off.x / size.width).coerceIn(0f, 1f)) },
                            onDoubleTap = { onScrubState(null) }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { off -> onScrubState((off.x / size.width).coerceIn(0f, 1f)) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                onScrubState((change.position.x / size.width).coerceIn(0f, 1f))
                            }
                        )
                    }
            ) {
                for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                    val y = size.height * frac
                    drawLine(
                        color = gridColor.copy(alpha = 0.6f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                }
                val stepX = size.width / (n - 1).coerceAtLeast(1)
                val strokePx = 2.5.dp.toPx()
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                series.forEachIndexed { si, sr ->
                    val (lo, hi) = ranges[si]
                    val states = sr.states
                    val chgColor = sr.chargingColor
                    if (states != null && chgColor != null && states.size == sr.values.size) {
                        val disPath = Path()
                        val chgPath = Path()
                        var prevMode = -1 // 0 = dis, 1 = chg
                        var prevX = 0f
                        var prevY = 0f
                        sr.values.forEachIndexed { i, v ->
                            if (v.isNaN() || v.isInfinite()) {
                                prevMode = -1
                            } else {
                                val x = i * stepX
                                val y = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                                val mode = if (states[i] == BatteryState.DISCHARGING) 0 else 1
                                val targetPath = if (mode == 0) disPath else chgPath
                                if (prevMode != mode) {
                                    if (prevMode >= 0) {
                                        targetPath.moveTo(prevX, prevY)
                                        targetPath.lineTo(x, y)
                                    } else {
                                        targetPath.moveTo(x, y)
                                    }
                                } else {
                                    targetPath.lineTo(x, y)
                                }
                                prevMode = mode
                                prevX = x
                                prevY = y
                            }
                        }
                        drawPath(
                            path = disPath,
                            color = sr.color,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        drawPath(
                            path = chgPath,
                            color = chgColor,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round, pathEffect = dashEffect)
                        )
                    } else {
                        val path = Path()
                        var penDown = false
                        sr.values.forEachIndexed { i, v ->
                            if (v.isNaN() || v.isInfinite()) {
                                penDown = false
                            } else {
                                val x = i * stepX
                                val y = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                                if (!penDown) {
                                    path.moveTo(x, y)
                                    penDown = true
                                } else {
                                    path.lineTo(x, y)
                                }
                            }
                        }
                        drawPath(
                            path = path,
                            color = sr.color,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                }
                if (scrubIndex != null) {
                    val x = scrubIndex * stepX
                    drawLine(
                        color = Color.White.copy(alpha = 0.7f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            val spanMs = if (n >= 2) timesMs.last() - timesMs.first() else 0L
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (saved) "0:00" else "-${fmtDuration(spanMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (scrubIndex != null) {
                        val t = timesMs[scrubIndex] - timesMs.first()
                        if (saved) stringResource(R.string.metrics_scrub_at, fmtDuration(t))
                        else stringResource(R.string.metrics_scrub_ago, fmtDuration(spanMs - t))
                    } else {
                        stringResource(R.string.metrics_scrub_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (saved) fmtDuration(spanMs) else stringResource(R.string.metrics_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showScaleNote) {
            Spacer(modifier = Modifier.height(2.dp))
            val rangesSummary = series.indices.joinToString("  •  ") { i ->
                val (lo, hi) = ranges[i]
                "${series[i].label} ${fmt(lo, 0)}–${fmt(hi, 0)}"
            }
            Text(
                text = stringResource(R.string.metrics_scale_note, rangesSummary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Display range for one series, ignoring missing values. Never returns an empty range. */
private fun rangeOf(sr: ChartSeries): Pair<Float, Float> {
    val valid = sr.values.filter { !it.isNaN() && !it.isInfinite() }
    if (valid.isEmpty()) return 0f to 1f
    val max = valid.max()
    return if (sr.zeroBased) {
        0f to (max * 1.1f).coerceAtLeast(1f)
    } else {
        val min = valid.min()
        val pad = ((max - min) * 0.1f).coerceAtLeast(1f)
        (min - pad) to (max + pad)
    }
}
