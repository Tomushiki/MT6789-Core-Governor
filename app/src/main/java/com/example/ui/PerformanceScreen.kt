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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.governor.MetricSample
import com.example.governor.MetricSummary
import com.example.governor.MetricsMath
import com.example.governor.SessionInfo
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
    val zeroBased: Boolean = true
)

private fun fmt(v: Float, decimals: Int = 1): String =
    if (v.isNaN() || v.isInfinite()) "–" else String.format(Locale.ROOT, "%.${decimals}f", v)

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
    onSelectSession: (SessionInfo?) -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onDeleteAllSessions: () -> Unit,
    onClearLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    var windowMin by rememberSaveable { mutableStateOf(5) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

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
                    Text("Performance Metrics", style = MaterialTheme.typography.titleMedium)
                    if (recording) {
                        StatusPill(
                            label = "REC GAME",
                            color = ext.warning,
                            containerColor = ext.warningContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (viewingSaved && selectedSession != null) {
                    val name = appLabels[selectedSession.packageName] ?: selectedSession.packageName
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Saved session • ${formatDate(selectedSession.startMs)} • ${fmtDuration(selectedSession.summary.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { scrub = null; onSelectSession(null) }) {
                        Text("Back to live")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30).forEach { m ->
                            FilterChip(
                                selected = windowMin == m,
                                onClick = { windowMin = m; scrub = null },
                                label = { Text("Live ${m}m") }
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
                            text = "Game sessions are saved automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { scrub = null; onClearLive() }) { Text("Clear live") }
                    }
                }
            }
        }

        if (samples.size < 2) {
            item {
                EcuCard {
                    Text(
                        text = if (viewingSaved) {
                            "Loading session…"
                        } else {
                            "No data yet. Start the governor on the Monitor tab. Graphs fill in at one point per second."
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
                        title = "POWER • TEMP • FPS",
                        series = listOf(
                            ChartSeries("FPS", primary, fpsSeries, "", 0),
                            ChartSeries("Power", ext.optimal, wattSeries, " W", 1),
                            ChartSeries("Temp", ext.throttle, tempSeries, "°C", 1, zeroBased = false)
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
                        title = "FPS PER WATT",
                        series = listOf(ChartSeries("FPS/W", primary, fpwSeries, "", 1)),
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
                        title = "WORK PER WATT (busy GHz / W)",
                        series = listOf(ChartSeries("Work/W", secondary, wpwSeries, "", 2)),
                        timesMs = times,
                        saved = viewingSaved,
                        scrub = scrub,
                        onScrub = { scrub = it },
                        height = 130.dp
                    )
                }
            }
        }

        // 6. Saved sessions
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SAVED GAME SESSIONS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sessions.isNotEmpty()) {
                        TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                    }
                }
                if (sessions.isEmpty()) {
                    Text(
                        text = "Nothing saved yet. A session is recorded while a game from the Apps tab is in the foreground, and saved once it lasts at least 20 seconds. The newest 40 are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(sessions, key = { it.fileName }) { info ->
            SessionRow(
                info = info,
                name = appLabels[info.packageName] ?: info.packageName,
                selected = selectedSession?.fileName == info.fileName,
                onClick = { scrub = null; onSelectSession(info) },
                onDelete = { onDeleteSession(info) }
            )
        }

        // 7. How the numbers are made
        item {
            EcuCard {
                Text("HOW THESE ARE CALCULATED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "FPS per watt = FPS shown by the app divided by battery power draw. Work per watt = (CPU cluster frequency × cores × load + GPU frequency × load), in busy GHz, divided by power. " +
                        "Work is an estimate, not a benchmark, so compare it only on this phone. Higher is more efficient. Ratios use a 5-second average and are blank while power is below 0.3 W or there is no FPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all saved sessions?") },
            text = { Text("This removes ${sessions.size} saved session(s). It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; onDeleteAllSessions() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
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
            Text(
                text = "NOW  ${if (last.fps >= 0f) fmt(last.fps, 0) else "–"} FPS  •  ${fmt(last.watts)} W  •  ${fmt(last.tempC)}°C",
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("AVG FPS", fmt(summary.avgFps, 0), Modifier.weight(1f))
            StatTile("5% LOW", fmt(summary.p5Fps, 0), Modifier.weight(1f))
            StatTile("AVG POWER", fmt(summary.avgWatts) + " W", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("FPS / W", fmt(summary.avgFpsPerWatt), Modifier.weight(1f))
            StatTile("WORK / W", fmt(summary.avgWorkPerWatt, 2), Modifier.weight(1f))
            StatTile("MAX TEMP", fmt(summary.maxTempC) + "°C", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${fmtDuration(summary.durationMs)} shown • ${fmt(summary.energyWh, 2)} Wh used • peak ${fmt(summary.maxWatts)} W",
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
        Text(
            text = value,
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun SessionRow(
    info: SessionInfo,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val s = info.summary
    EcuCard(accentBorder = if (selected) MaterialTheme.colorScheme.primary else null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDate(info.startMs)} • ${fmtDuration(s.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${fmt(s.avgFps, 0)} FPS • ${fmt(s.avgWatts)} W • ${fmt(s.avgFpsPerWatt)} FPS/W • ${fmt(s.maxTempC)}°C",
                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete session")
            }
        }
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
                val v = if (scrubIndex != null) {
                    sr.values.getOrNull(scrubIndex) ?: Float.NaN
                } else {
                    sr.values.lastOrNull { !it.isNaN() } ?: Float.NaN
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(sr.color)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${sr.label} ${fmt(v, sr.decimals)}${sr.unit}",
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

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
            series.forEachIndexed { si, sr ->
                val (lo, hi) = ranges[si]
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
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
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
                    if (saved) "at ${fmtDuration(t)}" else "${fmtDuration(spanMs - t)} ago"
                } else {
                    "drag to inspect • double-tap to clear"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (saved) fmtDuration(spanMs) else "now",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showScaleNote) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Each line has its own scale: " + series.indices.joinToString("  •  ") { i ->
                    val (lo, hi) = ranges[i]
                    "${series[i].label} ${fmt(lo, 0)}–${fmt(hi, 0)}"
                },
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
