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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.SysfsParsers
import com.example.model.GpuLiveState
import com.example.model.HardwareSpec
import com.example.model.TelemetryState
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSwitchRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
private data class OppRowModel(
    val key: String,
    val pos: Int,
    val oppIndex: Int,
    val freqKhz: Long,
    val posLabel: String,
    val freqLabel: String,
    val detailLabel: String
)

@Immutable
private data class OppHighlightSnapshot(
    val available: Boolean = false,
    val currentOppIndex: Int = -1,
    val currentFreqKhz: Long = 0L,
    val targetOppIndex: Int = -1,
    val allowedMinPos: Int = 0,
    val allowedMaxPos: Int = 0,
    val cappedMaxPos: Int = 0
)

@Immutable
private data class RawStatusSnapshot(
    val currentOppIndex: Int = -1,
    val currentFreqKhz: Long = 0L,
    val rawGpufreqStatus: String = ""
)

private fun GpuLiveState.toHighlightSnapshot(): OppHighlightSnapshot = OppHighlightSnapshot(
    available = available,
    currentOppIndex = currentOppIndex,
    currentFreqKhz = currentFreqKhz,
    targetOppIndex = targetOppIndex,
    allowedMinPos = allowedMinPos,
    allowedMaxPos = allowedMaxPos,
    cappedMaxPos = cappedMaxPos
)

private fun GpuLiveState.toRawStatusSnapshot(): RawStatusSnapshot = RawStatusSnapshot(
    currentOppIndex = currentOppIndex,
    currentFreqKhz = currentFreqKhz,
    rawGpufreqStatus = rawGpufreqStatus
)

@Composable
fun OppTableScreen(
    hardwareSpec: HardwareSpec,
    telemetryFlow: StateFlow<TelemetryState>,
    onRefreshTable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val gpuSpec = hardwareSpec.gpu

    // "Always pause" is remembered between app launches (own small prefs file, config is untouched).
    val uiPrefs = remember { context.getSharedPreferences("coregovernor_ui", Context.MODE_PRIVATE) }
    var alwaysPause by remember { mutableStateOf(uiPrefs.getBoolean("opp_always_pause", false)) }
    var paused by remember { mutableStateOf(alwaysPause) }
    var refreshGen by remember { mutableIntStateOf(0) }
    var lastHandledRefreshGen by remember { mutableIntStateOf(0) }

    val highlightState = remember(telemetryFlow) {
        mutableStateOf(telemetryFlow.value.gpu.toHighlightSnapshot())
    }
    val rawStatusState = remember(telemetryFlow) {
        mutableStateOf(telemetryFlow.value.gpu.toRawStatusSnapshot())
    }
    val loadSourceState = remember(telemetryFlow) {
        mutableStateOf(telemetryFlow.value.gpu.loadSourcePath)
    }

    LaunchedEffect(telemetryFlow, paused, refreshGen) {
        if (!paused) {
            lastHandledRefreshGen = refreshGen
            var lastRawPushRt = 0L
            telemetryFlow.collect { state ->
                val gpu = state.gpu
                val nextHighlight = gpu.toHighlightSnapshot()
                if (highlightState.value != nextHighlight) {
                    highlightState.value = nextHighlight
                }
                if (loadSourceState.value != gpu.loadSourcePath) {
                    loadSourceState.value = gpu.loadSourcePath
                }
                val nextRaw = gpu.toRawStatusSnapshot()
                val nowRt = SystemClock.elapsedRealtime()
                if (lastRawPushRt == 0L || (rawStatusState.value != nextRaw && nowRt - lastRawPushRt >= 1_000L)) {
                    rawStatusState.value = nextRaw
                    lastRawPushRt = nowRt
                }
            }
        } else if (refreshGen != lastHandledRefreshGen) {
            lastHandledRefreshGen = refreshGen
            withTimeoutOrNull(600L) {
                telemetryFlow.collect { state ->
                    val gpu = state.gpu
                    highlightState.value = gpu.toHighlightSnapshot()
                    rawStatusState.value = gpu.toRawStatusSnapshot()
                    loadSourceState.value = gpu.loadSourcePath
                }
            }
        }
    }

    val rows = remember(gpuSpec.oppTable, context) {
        gpuSpec.oppTable.mapIndexed { pos, opp ->
            OppRowModel(
                key = "${pos}_${opp.oppIndex}_${opp.freqKhz}",
                pos = pos,
                oppIndex = opp.oppIndex,
                freqKhz = opp.freqKhz,
                posLabel = "#${pos.toString().padStart(2, '0')}",
                freqLabel = "${opp.freqKhz / 1000} MHz  (${opp.freqKhz} kHz)",
                detailLabel = context.getString(
                    R.string.opp_real_detail,
                    opp.oppIndex,
                    SysfsParsers.formatVoltMv(opp.voltRaw)
                )
            )
        }
    }

    val handleRefresh: () -> Unit = remember(onRefreshTable) {
        {
            refreshGen++
            onRefreshTable()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live gpufreq_status Raw Debug Card
        item(key = "raw_status_card") {
            EcuCard(accentBorder = ext.gpuAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RawStatusHeader(
                        rawStatusState = rawStatusState,
                        accentColor = ext.gpuAccent,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = {
                            val raw = rawStatusState.value.rawGpufreqStatus.ifBlank {
                                context.getString(R.string.opp_empty_clip)
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("gpufreq_status", raw))
                            Toast.makeText(context, context.getString(R.string.monitor_copied_gpufreq), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_gpufreq_status_opp_screen")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.monitor_copy_raw_cd), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.common_copy))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                RawStatusTextBox(rawStatusState = rawStatusState)

                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.opp_pause_title),
                    explanation = stringResource(R.string.opp_pause_desc),
                    checked = paused,
                    onCheckedChange = { paused = it },
                    testTag = "pause_opp_live_updates_switch"
                )
                SettingSwitchRow(
                    title = stringResource(R.string.opp_always_pause_title),
                    explanation = stringResource(R.string.opp_always_pause_desc),
                    checked = alwaysPause,
                    onCheckedChange = { on ->
                        alwaysPause = on
                        uiPrefs.edit().putBoolean("opp_always_pause", on).apply()
                        if (on) paused = true
                    },
                    testTag = "always_pause_opp_live_updates_switch"
                )
            }
        }

        // 2. OPP Table Source & Order Info Card
        item(key = "opp_table_info_card") {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val entriesText = pluralStringResource(
                            R.plurals.plural_opp_entries,
                            gpuSpec.oppTable.size,
                            gpuSpec.oppTable.size
                        )
                        Text(
                            text = stringResource(R.string.opp_table_header, entriesText),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.opp_source,
                                gpuSpec.oppTableSourcePath ?: stringResource(R.string.opp_not_discovered)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.opp_order, gpuSpec.detectedOrderNote),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GedLoadSourceText(
                            loadSourceState = loadSourceState,
                            fallbackCandidates = gpuSpec.gedLoadCandidatePaths
                        )
                    }
                    IconButton(
                        onClick = handleRefresh,
                        modifier = Modifier.testTag("refresh_opp_table_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.opp_refresh_cd))
                    }
                }
            }
        }

        // 3. Full Sorted OPP List (Position 0 = lowest -> Last = highest)
        if (rows.isEmpty()) {
            item(key = "opp_empty_state") {
                EcuCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.opp_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(onClick = handleRefresh) {
                                Text(stringResource(R.string.opp_probe_btn))
                            }
                        }
                    }
                }
            }
        } else {
            items(
                items = rows,
                key = { it.key },
                contentType = { "opp_row" }
            ) { row ->
                OppRowItem(
                    row = row,
                    highlightState = highlightState
                )
            }
        }
    }
}

@Composable
private fun RawStatusHeader(
    rawStatusState: State<RawStatusSnapshot>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val rawStatus = rawStatusState.value
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.opp_raw_header),
                style = MaterialTheme.typography.labelMedium,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            val oppLabel = if (rawStatus.currentOppIndex >= 0) "OPP ${rawStatus.currentOppIndex}" else "OPP --"
            val freqLabel = if (rawStatus.currentFreqKhz > 0L) {
                "${rawStatus.currentFreqKhz / 1000} MHz (${rawStatus.currentFreqKhz} kHz)"
            } else {
                "-- MHz"
            }
            Text(
                text = stringResource(R.string.opp_parsed, oppLabel, freqLabel),
                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RawStatusTextBox(
    rawStatusState: State<RawStatusSnapshot>
) {
    val rawText = rawStatusState.value.rawGpufreqStatus
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = rawText.ifBlank { stringResource(R.string.opp_empty_raw) },
            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(10.dp)
                .testTag("raw_gpufreq_status_text")
        )
    }
}

@Composable
private fun GedLoadSourceText(
    loadSourceState: State<String>,
    fallbackCandidates: List<String>
) {
    val source = loadSourceState.value
    val noneText = stringResource(R.string.common_none)
    Text(
        text = stringResource(
            R.string.opp_ged_load,
            source.ifBlank { fallbackCandidates.joinToString().ifBlank { noneText } }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun OppRowItem(
    row: OppRowModel,
    highlightState: State<OppHighlightSnapshot>
) {
    val ext = LocalExtendedColors.current
    val isCurrent by remember(row.oppIndex, row.freqKhz) {
        derivedStateOf {
            val s = highlightState.value
            if (s.currentOppIndex >= 0) {
                s.currentOppIndex == row.oppIndex
            } else {
                s.currentFreqKhz > 0L && s.currentFreqKhz == row.freqKhz
            }
        }
    }
    val isTarget by remember(row.oppIndex) {
        derivedStateOf { highlightState.value.targetOppIndex == row.oppIndex }
    }
    val isCappedOut by remember(row.pos) {
        derivedStateOf {
            val s = highlightState.value
            s.available && row.pos > s.cappedMaxPos && row.pos in s.allowedMinPos..s.allowedMaxPos
        }
    }

    val border = when {
        isTarget -> MaterialTheme.colorScheme.primary
        isCurrent -> ext.gpuAccent
        else -> null
    }

    EcuCard(accentBorder = border) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = row.posLabel,
                        style = MonoMetricStyle.copy(
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        Text(
                            text = row.freqLabel,
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = row.detailLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isCurrent) {
                        StatusPill(
                            label = stringResource(R.string.opp_pill_live),
                            color = ext.gpuAccent,
                            containerColor = ext.warningContainer
                        )
                    }
                    if (isTarget) {
                        StatusPill(
                            label = stringResource(R.string.opp_pill_pinned),
                            color = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else if (isCappedOut) {
                        StatusPill(
                            label = stringResource(R.string.opp_pill_capped),
                            color = ext.throttle,
                            containerColor = ext.throttleContainer
                        )
                    }
                }
            }
        }
    }
}
