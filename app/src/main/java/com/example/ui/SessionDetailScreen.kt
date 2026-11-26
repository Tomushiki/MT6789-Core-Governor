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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.ChartDownsampler
import com.example.governor.MetricSample
import com.example.governor.MetricsMath
import com.example.governor.SessionInfo
import com.example.governor.SessionScoring
import com.example.model.CompositeScoreWeights
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun fmtNum(v: Float, decimals: Int = 1): String =
    if (v.isNaN() || v.isInfinite()) "–" else String.format(Locale.US, "%.${decimals}f", v)

private fun fmtDur(ms: Long): String {
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
fun SessionDetailScreen(
    session: SessionInfo,
    samples: List<MetricSample>,
    appName: String,
    scoreWeights: CompositeScoreWeights,
    onUpdateWeights: (CompositeScoreWeights) -> Unit,
    onToggleKeep: () -> Unit,
    onShareSummary: () -> Unit,
    onExportCsv: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val summary = session.summary
    val score = remember(summary, scoreWeights) {
        SessionScoring.compositeScore(summary, scoreWeights)
    }
    val timeAboveThresholdSec = remember(samples, scoreWeights.tempThresholdC) {
        MetricsMath.timeAboveTempSec(samples, scoreWeights.tempThresholdC)
    }
    val downsampled = remember(samples) {
        ChartDownsampler.downsample(samples, maxPoints = 90)
    }
    val dateStr = remember(session.startMs) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(session.startMs))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header card
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.session_detail_meta, dateStr, fmtDur(summary.durationMs), summary.sampleCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onToggleKeep) {
                        Icon(
                            imageVector = if (session.kept) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(
                                if (session.kept) R.string.metrics_unkeep_cd else R.string.metrics_keep_cd
                            ),
                            tint = if (session.kept) primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (session.kept) {
                        StatusPill(
                            label = stringResource(R.string.metrics_kept_pill),
                            color = primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                    if (summary.includesCharging) {
                        StatusPill(
                            label = stringResource(R.string.session_detail_charging_pill),
                            color = ext.warning,
                            containerColor = ext.warningContainer
                        )
                    }
                    if (session.midSessionChanges > 0) {
                        StatusPill(
                            label = stringResource(R.string.metrics_mid_changes_pill, session.midSessionChanges),
                            color = ext.throttle,
                            containerColor = ext.throttle.copy(alpha = 0.16f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShareSummary) {
                        Text(stringResource(R.string.metrics_share_summary))
                    }
                    OutlinedButton(onClick = onExportCsv) {
                        Text(stringResource(R.string.metrics_save_csv))
                    }
                }
            }
        }

        // Percentiles & stability & throttle breakdown
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.session_detail_perf_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.session_detail_composite_score, fmtNum(score, 1)),
                        style = MonoMetricStyle.copy(
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            fontWeight = FontWeight.Bold
                        ),
                        color = primary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTile(stringResource(R.string.metrics_stat_avg_fps), fmtNum(summary.avgFps, 1), Modifier.weight(1f))
                    DetailTile(stringResource(R.string.session_detail_p50_fps), fmtNum(summary.p50Fps, 1), Modifier.weight(1f))
                    DetailTile(stringResource(R.string.metrics_stat_5pct_low), fmtNum(summary.p5Fps, 1), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTile(stringResource(R.string.session_detail_p1_fps), fmtNum(summary.p1Fps, 1), Modifier.weight(1f))
                    DetailTile(stringResource(R.string.session_detail_stability), "${fmtNum(summary.frameStabilityPct, 0)}%", Modifier.weight(1f))
                    DetailTile(stringResource(R.string.metrics_stat_fpw), fmtNum(summary.avgFpsPerWatt, 1), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTile(stringResource(R.string.metrics_stat_avg_power), "${fmtNum(summary.avgWatts, 2)} W", Modifier.weight(1f))
                    DetailTile(stringResource(R.string.metrics_stat_max_temp), "${fmtNum(summary.maxTempC, 1)} °C", Modifier.weight(1f))
                    DetailTile(stringResource(R.string.metrics_stat_energy), "${fmtNum(summary.energyWh, 2)} Wh", Modifier.weight(1f))
                }
            }
        }

        // Thermal threshold & throttling time card
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.session_detail_thermal_power_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.session_detail_temp_threshold_label, scoreWeights.tempThresholdC.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(42f, 45f, 48f, 50f).forEach { thr ->
                        FilterChip(
                            selected = scoreWeights.tempThresholdC.roundToInt() == thr.roundToInt(),
                            onClick = { onUpdateWeights(scoreWeights.copy(tempThresholdC = thr)) },
                            label = { Text("${thr.roundToInt()}°C") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTile(
                        label = stringResource(R.string.session_detail_time_above_temp, scoreWeights.tempThresholdC.roundToInt()),
                        value = if (samples.isEmpty()) "–" else "${timeAboveThresholdSec}s",
                        modifier = Modifier.weight(1f)
                    )
                    DetailTile(
                        label = stringResource(R.string.session_detail_thermal_throttle_sec),
                        value = "${summary.thermalThrottleSec}s",
                        modifier = Modifier.weight(1f)
                    )
                    DetailTile(
                        label = stringResource(R.string.session_detail_power_throttle_sec),
                        value = "${summary.powerThrottleSec}s",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Charts (when samples are loaded)
        if (downsampled.size >= 2) {
            item {
                EcuCard {
                    Text(
                        text = stringResource(R.string.metrics_chart_power_temp_fps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SimpleMultiSeriesSparkChart(
                        fps = downsampled.map { it.fps },
                        watts = downsampled.map { it.watts },
                        temp = downsampled.map { it.tempC },
                        fpsColor = primary,
                        wattsColor = ext.optimal,
                        tempColor = ext.throttle,
                        gridColor = ext.chartGrid
                    )
                }
            }
        }

        // Settings Snapshot card
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.session_detail_snapshot_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (session.midSessionChanges > 0) {
                    Text(
                        text = stringResource(R.string.session_detail_mid_changes_note, session.midSessionChanges),
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.throttle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val snap = session.snapshot
                if (snap == null) {
                    Text(
                        text = stringResource(R.string.snap_not_recorded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val entries = snap.toEntries()
                    entries.forEachIndexed { idx, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(snapshotFieldLabelRes(entry.key)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = localizedSnapshotValue(entry.displayValue),
                                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (idx < entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTile(label: String, value: String, modifier: Modifier = Modifier) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
private fun SimpleMultiSeriesSparkChart(
    fps: List<Float>,
    watts: List<Float>,
    temp: List<Float>,
    fpsColor: Color,
    wattsColor: Color,
    tempColor: Color,
    gridColor: Color
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(vertical = 8.dp, horizontal = 6.dp)
        ) {
            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                val y = size.height * frac
                drawLine(
                    color = gridColor.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
            fun plot(vals: List<Float>, color: Color, zeroBased: Boolean) {
                val valid = vals.filter { it.isFinite() }
                if (valid.isEmpty()) return
                val hi = if (zeroBased) valid.max().coerceAtLeast(1f) * 1.1f else valid.max() + 1f
                val lo = if (zeroBased) 0f else (valid.min() - 1f)
                val span = (hi - lo).coerceAtLeast(1f)
                val stepX = size.width / (vals.size - 1).coerceAtLeast(1)
                val path = Path()
                var penDown = false
                vals.forEachIndexed { i, v ->
                    if (!v.isFinite()) {
                        penDown = false
                    } else {
                        val x = i * stepX
                        val y = size.height * (1f - ((v - lo) / span).coerceIn(0f, 1f))
                        if (!penDown) {
                            path.moveTo(x, y)
                            penDown = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                }
                drawPath(path = path, color = color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            plot(fps, fpsColor, true)
            plot(watts, wattsColor, true)
            plot(temp, tempColor, false)
        }
    }
}
