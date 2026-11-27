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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.governor.BatteryEstimatorUiSnapshot
import com.example.governor.BatteryHistoryMath
import com.example.governor.BatteryHistoryPoint
import com.example.governor.BatteryRange
import com.example.governor.BatterySession
import com.example.model.BatteryState
import com.example.ui.components.EcuCard
import com.example.ui.theme.LocalExtendedColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain-language battery section of the Energy screen: where you are now, how it changed, how healthy it is. */
@Composable
fun BatteryOverview(
    batt: BatteryEstimatorUiSnapshot,
    history: List<BatteryHistoryPoint>,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    var range by remember { mutableStateOf(BatteryRange.H24) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryHeroCard(batt)
        BatteryRangeChips(range) { range = it }
        val shown = remember(history, range, nowMs / 60_000L) { BatteryHistoryMath.window(history, nowMs, range) }
        BatteryLevelChartCard(shown, range)
        BatteryPowerChartCard(shown)
        BatteryComparisonCard(history, nowMs, range)
        BatteryHealthCard(batt)
        BatterySessionsCard(remember(history) { BatteryHistoryMath.sessions(history).asReversed() }, nowMs)
    }
}

// ---------- 1. Hero ----------

@Composable
private fun levelColor(pct: Int, state: BatteryState): Color {
    val ext = LocalExtendedColors.current
    return when {
        state == BatteryState.CHARGING || state == BatteryState.FULL_PLUGGED -> ext.optimal
        pct in 0..14 -> ext.throttle
        pct in 15..29 -> ext.warning
        else -> ext.optimal
    }
}

@Composable
private fun BatteryHeroCard(batt: BatteryEstimatorUiSnapshot) {
    val ext = LocalExtendedColors.current
    val pct = batt.batteryPct
    val accent = levelColor(pct, batt.batteryState)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val charging = batt.batteryState == BatteryState.CHARGING
    val plugged = batt.batteryState == BatteryState.FULL_PLUGGED

    EcuCard(accentBorder = accent) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                Canvas(modifier = Modifier.size(132.dp)) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (pct in 0..100) {
                        drawArc(accent, 135f, 270f * pct / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (pct in 0..100) "$pct%" else "--",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            charging -> stringResource(R.string.bo_state_charging)
                            plugged -> stringResource(R.string.bo_state_full)
                            batt.batteryState == BatteryState.DISCHARGING -> stringResource(R.string.bo_state_using)
                            else -> stringResource(R.string.bo_state_idle)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val timeLine: String = when {
                    charging && batt.timeToFullMin > 0 -> stringResource(R.string.bo_full_in, formatMinutes(batt.timeToFullMin))
                    charging -> stringResource(R.string.bo_charging_calc)
                    plugged -> stringResource(R.string.bo_plugged_full)
                    batt.timeRemainingMin > 0 -> stringResource(R.string.bo_left, formatMinutes(batt.timeRemainingMin))
                    else -> stringResource(R.string.bo_learning_time)
                }
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                val watts = if (charging) batt.livePowerInWatts else batt.livePowerOutWatts
                MiniFact(
                    label = if (charging) stringResource(R.string.bo_fact_charging_at) else stringResource(R.string.bo_fact_using),
                    value = if (batt.batteryState == BatteryState.DISCHARGING || charging) "%.1f W".format(Locale.US, watts) else "—",
                    note = if (batt.batteryState == BatteryState.DISCHARGING) powerWord(watts) else if (charging) chargeWord(watts) else ""
                )
                Spacer(Modifier.height(6.dp))
                MiniFact(
                    label = stringResource(R.string.bo_fact_temp),
                    value = if (batt.batteryTempC > 0f) "%.0f °C".format(Locale.US, batt.batteryTempC) else "—",
                    note = tempWord(batt.batteryTempC),
                    noteColor = if (batt.batteryTempC >= 42f) ext.throttle else if (batt.batteryTempC >= 38f) ext.warning else ext.optimal
                )
            }
        }
    }
}

@Composable
private fun MiniFact(label: String, value: String, note: String, noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (note.isNotEmpty()) {
            Text("  $note", style = MaterialTheme.typography.bodySmall, color = noteColor)
        }
    }
}

@Composable
private fun powerWord(watts: Float): String = when {
    watts < 1.0f -> stringResource(R.string.bo_power_light)
    watts < 2.5f -> stringResource(R.string.bo_power_normal)
    watts < 4.5f -> stringResource(R.string.bo_power_heavy)
    else -> stringResource(R.string.bo_power_very_heavy)
}

@Composable
private fun chargeWord(watts: Float): String = when {
    watts < 5f -> stringResource(R.string.bo_charge_slow)
    watts < 15f -> stringResource(R.string.bo_charge_normal)
    else -> stringResource(R.string.bo_charge_fast)
}

@Composable
private fun tempWord(tempC: Float): String = when {
    tempC <= 0f -> ""
    tempC < 33f -> stringResource(R.string.bo_temp_cool)
    tempC < 38f -> stringResource(R.string.bo_temp_normal)
    tempC < 42f -> stringResource(R.string.bo_temp_warm)
    else -> stringResource(R.string.bo_temp_hot)
}

// ---------- 2. Range chips ----------

@Composable
private fun BatteryRangeChips(selected: BatteryRange, onSelect: (BatteryRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        BatteryRange.values().forEach { r ->
            FilterChip(
                selected = r == selected,
                onClick = { onSelect(r) },
                label = {
                    Text(
                        when (r) {
                            BatteryRange.H6 -> stringResource(R.string.bo_range_6h)
                            BatteryRange.H24 -> stringResource(R.string.bo_range_24h)
                            BatteryRange.D7 -> stringResource(R.string.bo_range_7d)
                        }
                    )
                }
            )
        }
    }
}

// ---------- 3. Level chart ----------

@Composable
private fun BatteryLevelChartCard(points: List<BatteryHistoryPoint>, range: BatteryRange) {
    val ext = LocalExtendedColors.current
    EcuCard {
        Text(stringResource(R.string.bo_level_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.bo_level_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            EmptyChart(stringResource(R.string.bo_chart_empty))
            return@EcuCard
        }
        val thin = remember(points) { BatteryHistoryMath.thin(points, 220) }
        var picked by remember(points) { mutableStateOf<Int?>(null) }
        val fmt = remember(range) { SimpleDateFormat(if (range == BatteryRange.D7) "EEE HH:mm" else "HH:mm", Locale.getDefault()) }
        val p = picked?.let { thin.getOrNull(it) }
        Text(
            text = if (p != null) {
                val what = if (p.state == BatteryState.CHARGING || p.state == BatteryState.FULL_PLUGGED) stringResource(R.string.bo_tip_charging) else stringResource(R.string.bo_tip_using)
                "${fmt.format(Date(p.timeMs))} · ${p.pct}% · $what"
            } else stringResource(R.string.bo_chart_hint),
            style = MaterialTheme.typography.labelMedium,
            color = if (p != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        val line = MaterialTheme.colorScheme.primary
        val grid = ext.chartGrid
        val chargeShade = ext.optimal.copy(alpha = 0.16f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInput(thin) {
                    detectDragGestures(
                        onDragStart = { o -> picked = nearestIndex(o.x, size.width.toFloat(), thin.size) },
                        onDrag = { change, _ -> picked = nearestIndex(change.position.x, size.width.toFloat(), thin.size) },
                        onDragEnd = { picked = null },
                        onDragCancel = { picked = null }
                    )
                }
                .pointerInput(thin) {
                    detectTapGestures(onPress = { o ->
                        picked = nearestIndex(o.x, size.width.toFloat(), thin.size)
                        tryAwaitRelease()
                        picked = null
                    })
                }
        ) {
            val padV = 8.dp.toPx()
            val h = size.height - padV * 2
            for (g in listOf(0f, 0.5f, 1f)) {
                val y = padV + h * (1f - g)
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val t0 = thin.first().timeMs
            val span = (thin.last().timeMs - t0).coerceAtLeast(1L).toFloat()
            fun x(i: Int) = size.width * (thin[i].timeMs - t0) / span
            fun y(i: Int) = padV + h * (1f - thin[i].pct.coerceIn(0, 100) / 100f)

            // Green bands while plugged in.
            var bandStart = -1
            for (i in thin.indices) {
                val plugged = thin[i].state == BatteryState.CHARGING || thin[i].state == BatteryState.FULL_PLUGGED
                if (plugged && bandStart < 0) bandStart = i
                if ((!plugged || i == thin.lastIndex) && bandStart >= 0) {
                    val end = if (plugged) i else i - 1
                    drawRect(chargeShade, Offset(x(bandStart), 0f), Size((x(end) - x(bandStart)).coerceAtLeast(2f), size.height))
                    bandStart = -1
                }
            }
            val path = Path()
            thin.indices.forEach { i -> if (i == 0) path.moveTo(x(i), y(i)) else path.lineTo(x(i), y(i)) }
            val fill = Path().apply {
                addPath(path)
                lineTo(x(thin.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 3f, cap = StrokeCap.Round))
            picked?.let { i ->
                if (i in thin.indices) {
                    drawLine(line.copy(alpha = 0.6f), Offset(x(i), 0f), Offset(x(i), size.height), 1.5f)
                    drawCircle(line, 7f, Offset(x(i), y(i)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val axisFmt = remember(range) { SimpleDateFormat(if (range == BatteryRange.D7) "EEE" else "HH:mm", Locale.getDefault()) }
            Text(axisFmt.format(Date(thin.first().timeMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.bo_legend_charging), style = MaterialTheme.typography.labelSmall, color = ext.optimal)
            Text(axisFmt.format(Date(thin.last().timeMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun nearestIndex(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    return ((x / width).coerceIn(0f, 1f) * (count - 1)).roundToInt()
}

@Composable
private fun EmptyChart(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}

// ---------- 4. Power chart ----------

@Composable
private fun BatteryPowerChartCard(points: List<BatteryHistoryPoint>) {
    val ext = LocalExtendedColors.current
    EcuCard {
        Text(stringResource(R.string.bo_power_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.bo_power_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            EmptyChart(stringResource(R.string.bo_chart_empty))
            return@EcuCard
        }
        val thin = remember(points) { BatteryHistoryMath.thin(points, 120) }
        val maxW = remember(thin) { (thin.maxOf { abs(it.watts) }).coerceAtLeast(2f) }
        val useColor = ext.warning
        val chargeColor = ext.optimal
        val grid = ext.chartGrid
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            val mid = size.height / 2f
            drawLine(grid.copy(alpha = 0.7f), Offset(0f, mid), Offset(size.width, mid), 1.5f)
            val barW = (size.width / thin.size).coerceAtLeast(2f)
            thin.forEachIndexed { i, p ->
                val x = size.width * i / thin.size
                val hgt = (abs(p.watts) / maxW) * (mid - 6f)
                if (p.watts >= 0f) drawRect(chargeColor.copy(alpha = 0.85f), Offset(x, mid - hgt), Size(barW * 0.8f, hgt))
                else drawRect(useColor.copy(alpha = 0.85f), Offset(x, mid), Size(barW * 0.8f, hgt))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.bo_legend_charge_up), style = MaterialTheme.typography.labelSmall, color = chargeColor)
            Text(stringResource(R.string.bo_legend_use_down), style = MaterialTheme.typography.labelSmall, color = useColor)
            Text("max %.1f W".format(Locale.US, maxW), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- 5. Comparison ----------

@Composable
private fun BatteryComparisonCard(history: List<BatteryHistoryPoint>, nowMs: Long, range: BatteryRange) {
    val ext = LocalExtendedColors.current
    val windowMs = range.hours * 3_600_000L
    val cur = remember(history, range, nowMs / 60_000L) {
        BatteryHistoryMath.summary(history.filter { it.timeMs >= nowMs - windowMs })
    }
    val prev = remember(history, range, nowMs / 60_000L) {
        BatteryHistoryMath.summary(history.filter { it.timeMs >= nowMs - 2 * windowMs && it.timeMs < nowMs - windowMs })
    }
    val rangeLabel = when (range) {
        BatteryRange.H6 -> stringResource(R.string.bo_range_6h)
        BatteryRange.H24 -> stringResource(R.string.bo_range_24h)
        BatteryRange.D7 -> stringResource(R.string.bo_range_7d)
    }
    EcuCard {
        Text(stringResource(R.string.bo_compare_title, rangeLabel), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (cur.drainPctPerHour < 0f && cur.totalChargingMs <= 0L) {
            Text(stringResource(R.string.bo_compare_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EcuCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_drain),
                value = if (cur.drainPctPerHour >= 0f) "%.1f%%/h".format(Locale.US, cur.drainPctPerHour) else "—",
                modifier = Modifier.weight(1f)
            )
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_screen_on),
                value = if (cur.screenOnDrainPctPerHour >= 0f) "%.1f%%/h".format(Locale.US, cur.screenOnDrainPctPerHour) else "—",
                modifier = Modifier.weight(1f)
            )
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_screen_off),
                value = if (cur.screenOffDrainPctPerHour >= 0f) "%.1f%%/h".format(Locale.US, cur.screenOffDrainPctPerHour) else "—",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_on_battery),
                value = formatMs(cur.totalOnBatteryMs),
                modifier = Modifier.weight(1f)
            )
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_charge_speed),
                value = if (cur.totalChargingMs > 0L) "%.1f W".format(Locale.US, cur.avgChargeWatts) else "—",
                modifier = Modifier.weight(1f)
            )
            BatteryStatTile(
                label = stringResource(R.string.bo_stat_peak_temp),
                value = if (cur.peakTempC > 0f) "%.0f °C".format(Locale.US, cur.peakTempC) else "—",
                modifier = Modifier.weight(1f)
            )
        }
        if (cur.drainPctPerHour >= 0f && prev.drainPctPerHour >= 0f && prev.drainPctPerHour > 0.05f) {
            val change = (cur.drainPctPerHour - prev.drainPctPerHour) / prev.drainPctPerHour * 100f
            Spacer(Modifier.height(10.dp))
            val (msg, color) = when {
                change <= -8f -> stringResource(R.string.bo_vs_better, abs(change).roundToInt()) to ext.optimal
                change >= 8f -> stringResource(R.string.bo_vs_worse, change.roundToInt()) to ext.warning
                else -> stringResource(R.string.bo_vs_same) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BatteryStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 6. Health ----------

@Composable
private fun BatteryHealthCard(batt: BatteryEstimatorUiSnapshot) {
    val ext = LocalExtendedColors.current
    val soh = batt.sohPct
    val color = when {
        soh <= 0f -> MaterialTheme.colorScheme.outline
        soh >= 85f -> ext.optimal
        soh >= 75f -> ext.warning
        else -> ext.throttle
    }
    val word = when {
        soh <= 0f -> stringResource(R.string.bo_health_unknown)
        soh >= 90f -> stringResource(R.string.bo_health_excellent)
        soh >= 80f -> stringResource(R.string.bo_health_good)
        soh >= 70f -> stringResource(R.string.bo_health_aging)
        else -> stringResource(R.string.bo_health_worn)
    }
    EcuCard(accentBorder = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bo_health_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(word, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
            }
            Text(
                if (soh > 0f) "${soh.roundToInt()}%" else "--",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar((if (soh > 0f) soh else 0f) / 100f, color)
        Spacer(Modifier.height(10.dp))
        val cap = if (batt.combinedCapacityMah > 0f) "${batt.combinedCapacityMah.roundToInt()} mAh" else "—"
        val design = if (batt.designCapacityMah > 0) "${batt.designCapacityMah} mAh" else "—"
        Text(
            stringResource(R.string.bo_health_capacity, cap, design),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (batt.cycleCount >= 0) {
            Text(
                stringResource(R.string.bo_health_cycles, batt.cycleCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (batt.confidencePct >= 50) stringResource(R.string.bo_health_sure) else stringResource(R.string.bo_health_learning),
            style = MaterialTheme.typography.bodySmall,
            color = if (batt.confidencePct >= 50) MaterialTheme.colorScheme.onSurfaceVariant else ext.warning
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
    }
}

// ---------- 7. Sessions ----------

@Composable
private fun BatterySessionsCard(sessions: List<BatterySession>, nowMs: Long) {
    var showAll by remember { mutableStateOf(false) }
    EcuCard {
        Text(stringResource(R.string.bo_sessions_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.bo_sessions_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.bo_sessions_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EcuCard
        }
        val list = if (showAll) sessions.take(40) else sessions.take(5)
        list.forEachIndexed { i, s ->
            SessionRow(s, nowMs)
            if (i != list.lastIndex) Spacer(Modifier.height(8.dp))
        }
        if (sessions.size > 5) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) stringResource(R.string.bo_sessions_less) else stringResource(R.string.bo_sessions_more))
            }
        }
    }
}

@Composable
private fun SessionRow(s: BatterySession, nowMs: Long) {
    val ext = LocalExtendedColors.current
    val charging = s.state == BatteryState.CHARGING
    val color = if (charging) ext.optimal else ext.warning
    val delta = s.deltaPct
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (charging) stringResource(R.string.bo_session_charged, formatMs(s.durationMs))
                    else stringResource(R.string.bo_session_used, formatMs(s.durationMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    dayLabel(s.startMs, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${if (delta > 0) "+" else ""}$delta%",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val a = (minOf(s.startPct, s.endPct) / 100f).coerceIn(0f, 1f)
            val b = (maxOf(s.startPct, s.endPct) / 100f).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                drawRect(color, Offset(size.width * a, 0f), Size((size.width * (b - a)).coerceAtLeast(6f), size.height))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("${s.startPct}% → ${s.endPct}%")
                append(" · ")
                append("%.1f W".format(Locale.US, abs(s.avgWatts)))
                if (!charging && s.screenOnShare > 0f) {
                    append(" · ")
                    append("screen ${(s.screenOnShare * 100).roundToInt()}%")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- helpers ----------

internal fun formatMinutes(totalMin: Int): String {
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

internal fun formatMs(ms: Long): String {
    val min = (ms / 60_000L).toInt()
    return if (min < 1) "<1m" else formatMinutes(min)
}

@Composable
private fun dayLabel(timeMs: Long, nowMs: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
    val a = Calendar.getInstance().apply { timeInMillis = timeMs }
    val b = Calendar.getInstance().apply { timeInMillis = nowMs }
    val dayDiff = (b.get(Calendar.YEAR) * 366 + b.get(Calendar.DAY_OF_YEAR)) - (a.get(Calendar.YEAR) * 366 + a.get(Calendar.DAY_OF_YEAR))
    val day = when (dayDiff) {
        0 -> stringResource(R.string.bo_today)
        1 -> stringResource(R.string.bo_yesterday)
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(timeMs))
    }
    return "$day, $time"
}
