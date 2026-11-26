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

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.ChartDownsampler
import com.example.governor.MetricDirection
import com.example.governor.MetricSample
import com.example.governor.SessionComparisonLogic
import com.example.governor.SessionExport
import com.example.governor.SessionInfo
import com.example.model.CompositeScoreWeights
import com.example.ui.components.EcuCard
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtSigned(v: Float, decimals: Int, unit: String): String {
    if (!v.isFinite()) return "–"
    val sign = if (v >= 0f) "+" else ""
    return "$sign${String.format(Locale.US, "%.${decimals}f", v)}$unit"
}

private fun fmtDurShort(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

@Composable
fun SessionComparisonScreen(
    sessions: List<SessionInfo>,
    samplesByFile: Map<String, List<MetricSample>>,
    appLabels: Map<String, String>,
    scoreWeights: CompositeScoreWeights,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    val seriesColors = remember(primary, secondary, ext) {
        listOf(primary, ext.optimal, ext.warning, secondary)
    }

    val comparison = remember(sessions, scoreWeights) {
        SessionComparisonLogic.compare(sessions, scoreWeights)
    }

    val downsampledBySession = remember(sessions, samplesByFile) {
        sessions.map { info ->
            val raw = samplesByFile[info.fileName].orEmpty()
            ChartDownsampler.downsample(raw, maxPoints = 90)
        }
    }

    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header & session legend + Export
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
                            text = stringResource(R.string.cmp_title, sessions.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.cmp_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = {
                        val ver = try {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        val text = SessionExport.comparisonText(
                            result = comparison,
                            appLabels = appLabels,
                            device = android.os.Build.MODEL ?: "Android",
                            appVersion = ver
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.cmp_share_chooser)))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.cmp_share_btn))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                sessions.forEachIndexed { idx, info ->
                    val letter = ('A' + idx).toString()
                    val color = seriesColors.getOrElse(idx) { primary }
                    val name = appLabels[info.packageName] ?: info.packageName
                    val baseTag = if (idx == 0) " (${stringResource(R.string.cmp_baseline_tag)})" else ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$letter: $name • ${dateFmt.format(Date(info.startMs))} • ${fmtDurShort(info.summary.durationMs)}$baseTag",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2. Fairness warnings
        if (comparison.warnings.isNotEmpty()) {
            item {
                EcuCard(accentBorder = ext.warning) {
                    Text(
                        text = stringResource(R.string.cmp_warnings_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = ext.warning
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    comparison.warnings.forEach { w ->
                        Text(
                            text = "• ${stringResource(comparisonWarningLabelRes(w))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // 3. Plain-language verdicts
        if (comparison.verdicts.isNotEmpty()) {
            item {
                EcuCard {
                    Text(
                        text = stringResource(R.string.cmp_verdict_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    comparison.verdicts.forEachIndexed { i, v ->
                        val changesSummary = if (v.changedFields.isEmpty()) {
                            stringResource(R.string.cmp_verdict_no_settings_diff)
                        } else {
                            v.changedFields.take(4).joinToString(", ") { (k, fromVal, toVal) ->
                                "${ctx.getString(snapshotFieldLabelRes(k))}: $fromVal → $toVal"
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.cmp_verdict_line,
                                v.label,
                                fmtSigned(v.fpsDelta, 1, ""),
                                fmtSigned(v.wattsDelta, 2, " W"),
                                fmtSigned(v.tempDelta, 1, " °C"),
                                changesSummary
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                        if (i < comparison.verdicts.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }

        // 4. Side-by-side summary table
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.cmp_table_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    // Column header row
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cmp_col_metric),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(120.dp)
                        )
                        sessions.indices.forEach { idx ->
                            val letter = ('A' + idx).toString()
                            Text(
                                text = if (idx == 0) "$letter (Base)" else letter,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = seriesColors.getOrElse(idx) { primary },
                                modifier = Modifier.width(105.dp)
                            )
                        }
                    }
                    HorizontalDivider()
                    comparison.metricRows.forEachIndexed { rowIdx, row ->
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(comparisonMetricLabelRes(row.kind)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(120.dp).padding(end = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            row.cells.forEachIndexed { idx, cell ->
                                Column(modifier = Modifier.width(105.dp).padding(end = 6.dp)) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = cell.formatted,
                                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        if (idx > 0 && cell.deltaText.isNotEmpty() && cell.deltaText != "-") {
                                            val deltaColor = when (cell.direction) {
                                                MetricDirection.BETTER -> ext.optimal
                                                MetricDirection.WORSE -> ext.throttle
                                                MetricDirection.NEUTRAL, MetricDirection.NA -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                            Text(
                                                text = cell.deltaText,
                                                style = MonoMetricStyle.copy(
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = deltaColor,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (rowIdx < comparison.metricRows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }

        // 5. Overlay charts
        item {
            val allLoaded = downsampledBySession.any { it.size >= 2 }
            EcuCard {
                Text(
                    text = stringResource(R.string.cmp_charts_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!allLoaded) {
                    Text(
                        text = stringResource(R.string.metrics_loading_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OverlayComparisonChart(
                        title = stringResource(R.string.metrics_series_fps),
                        series = downsampledBySession.map { pts -> pts.map { it.elapsedSec to it.fps } },
                        colors = seriesColors,
                        gridColor = ext.chartGrid,
                        zeroBased = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OverlayComparisonChart(
                        title = stringResource(R.string.metrics_series_power),
                        series = downsampledBySession.map { pts -> pts.map { it.elapsedSec to it.watts } },
                        colors = seriesColors,
                        gridColor = ext.chartGrid,
                        zeroBased = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OverlayComparisonChart(
                        title = stringResource(R.string.metrics_series_temp),
                        series = downsampledBySession.map { pts -> pts.map { it.elapsedSec to it.tempC } },
                        colors = seriesColors,
                        gridColor = ext.chartGrid,
                        zeroBased = false
                    )
                }
            }
        }

        // 6. Settings differences table
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.cmp_settings_diffs_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (comparison.settingsDiffs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cmp_settings_identical),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    comparison.settingsDiffs.forEachIndexed { idx, row ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(
                                text = stringResource(snapshotFieldLabelRes(row.fieldKey)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            row.values.forEachIndexed { sIdx, v ->
                                val letter = ('A' + sIdx).toString()
                                val c = seriesColors.getOrElse(sIdx) { primary }
                                Text(
                                    text = "$letter: ${localizedSnapshotValue(v)}",
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                    color = c
                                )
                            }
                        }
                        if (idx < comparison.settingsDiffs.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayComparisonChart(
    title: String,
    series: List<List<Pair<Float, Float>>>,
    colors: List<Color>,
    gridColor: Color,
    zeroBased: Boolean
) {
    val validValues = series.flatten().map { it.second }.filter { it.isFinite() }
    val maxElapsed = (series.flatten().maxOfOrNull { it.first } ?: 1f).coerceAtLeast(1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (validValues.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "–",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val lo = if (zeroBased) 0f else (validValues.min() - 1f)
            val hi = if (zeroBased) validValues.max().coerceAtLeast(1f) * 1.1f else (validValues.max() + 1f)
            val span = (hi - lo).coerceAtLeast(1f)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(10.dp))
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
                    series.forEachIndexed { sIdx, pts ->
                        val c = colors.getOrElse(sIdx) { Color.Cyan }
                        val path = Path()
                        var penDown = false
                        pts.forEach { (sec, v) ->
                            if (!v.isFinite()) {
                                penDown = false
                            } else {
                                val x = (sec / maxElapsed).coerceIn(0f, 1f) * size.width
                                val y = size.height * (1f - ((v - lo) / span).coerceIn(0f, 1f))
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
                            color = c,
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${maxElapsed.toInt()}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
