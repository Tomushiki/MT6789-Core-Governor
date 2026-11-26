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

package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.TelemetrySample
import com.example.ui.theme.Appearance
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import kotlin.math.roundToInt

@Composable
fun EcuCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(Appearance.cornerRadius.dp)
    if (Appearance.liquidGlass) {
        // Liquid glass: frosted translucent fill, soft sheen and a bright rim light on the edge.
        val fill = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))
        )
        val rim = accentBorder?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.30f))
        )
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.28f))
                .background(fill)
                .border(1.dp, rim, shape)
        ) {
            // Box (unlike Card) provides no content color, so Text would fall back to black.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Column(modifier = Modifier.padding(16.dp)) {
                    content()
                }
            }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = accentBorder ?: MaterialTheme.colorScheme.outline
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
fun FrequencyRangeBar(
    minAvailableKhz: Long,
    maxAvailableKhz: Long,
    allowedMinKhz: Long,
    allowedMaxKhz: Long,
    cappedMaxKhz: Long,
    targetKhz: Long,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            val span = (maxAvailableKhz - minAvailableKhz).coerceAtLeast(1L).toFloat()
            fun norm(khz: Long): Float = ((khz - minAvailableKhz).toFloat() / span).coerceIn(0f, 1f)

            drawRect(color = trackBg)

            val leftX = norm(allowedMinKhz) * size.width
            val allowedRightX = norm(allowedMaxKhz) * size.width
            val cappedRightX = norm(cappedMaxKhz) * size.width
            val targetX = norm(targetKhz) * size.width

            // Allowed range subtle band
            if (allowedRightX > leftX) {
                drawRect(
                    color = accentColor.copy(alpha = 0.22f),
                    topLeft = Offset(leftX, 0f),
                    size = androidx.compose.ui.geometry.Size(allowedRightX - leftX, size.height)
                )
            }
            // Throttled region in coral if cappedMax < allowedMax
            if (cappedMaxKhz < allowedMaxKhz && allowedRightX > cappedRightX) {
                drawRect(
                    color = ext.throttle.copy(alpha = 0.45f),
                    topLeft = Offset(cappedRightX, 0f),
                    size = androidx.compose.ui.geometry.Size(allowedRightX - cappedRightX, size.height)
                )
            }
            // Active target fill
            if (targetX > leftX) {
                drawRect(
                    color = accentColor,
                    topLeft = Offset(leftX, 0f),
                    size = androidx.compose.ui.geometry.Size(targetX - leftX, size.height)
                )
            }
        }
    }
}

@Composable
fun TelemetryHistoryGraph(
    history: List<TelemetrySample>,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val gridColor = ext.chartGrid

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.telemetry_history_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(stringResource(R.string.telemetry_legend_cpu), primary)
                LegendDot(stringResource(R.string.telemetry_legend_gpu), secondary)
                LegendDot(stringResource(R.string.telemetry_legend_temp), ext.throttle)
                LegendDot(stringResource(R.string.telemetry_legend_watts), ext.optimal)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (history.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.telemetry_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    // Horizontal reference grid lines (25%, 50%, 75%)
                    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                        val y = size.height * frac
                        drawLine(
                            color = gridColor.copy(alpha = 0.35f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }

                    fun drawSeries(values: List<Float>, color: Color, strokeWidth: Float = 3f) {
                        if (values.size < 2) return
                        val path = Path()
                        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
                        values.forEachIndexed { idx, norm ->
                            val x = idx * stepX
                            val y = size.height * (1f - norm.coerceIn(0f, 1f))
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    drawSeries(history.map { it.avgCpuLoadPct / 100f }, primary, 2f)
                    drawSeries(history.map { it.gpuLoadPct / 100f }, secondary, 2f)
                    // Temp scaled 20C..80C -> 0..1
                    drawSeries(history.map { (it.tempC - 20f) / 60f }, ext.throttle, 1.8f)
                    // Watts scaled 0..12W -> 0..1
                    drawSeries(history.map { it.watts / 12f }, ext.optimal, 1.8f)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingSliderRow(
    title: String,
    valueText: String,
    explanation: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = valueText,
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
        )
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
        )
    }
}

@Composable
fun DiscreteFreqSlider(
    title: String,
    freqsKhz: List<Long>,
    selectedKhz: Long,
    onSelectKhz: (Long) -> Unit,
    explanation: String,
    testTag: String = ""
) {
    if (freqsKhz.isEmpty()) return
    val currentIdx = freqsKhz.indexOfFirst { it == selectedKhz }.takeIf { it >= 0 }
        ?: freqsKhz.indices.minByOrNull { kotlin.math.abs(freqsKhz[it] - selectedKhz) } ?: 0
    val maxIdx = (freqsKhz.size - 1).coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${freqsKhz[currentIdx] / 1000} MHz",
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (freqsKhz.size > 1) {
            Slider(
                value = currentIdx.toFloat(),
                onValueChange = { raw ->
                    val idx = raw.roundToInt().coerceIn(0, freqsKhz.lastIndex)
                    onSelectKhz(freqsKhz[idx])
                },
                valueRange = 0f..maxIdx.toFloat(),
                steps = (freqsKhz.size - 2).coerceAtLeast(0),
                modifier = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
            )
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
