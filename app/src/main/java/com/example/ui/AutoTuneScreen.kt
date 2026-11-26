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

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.AutoTuneLogic
import com.example.governor.SessionInfo
import com.example.model.AutoTuneMode
import com.example.model.AutoTuneParam
import com.example.model.AutoTuneTrialStatus
import com.example.model.BoostStrength
import com.example.model.GovernorConfig
import com.example.model.PerGameAutoTuneState
import com.example.model.PerGameProfile
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun fmtScore(v: Float): String =
    if (!v.isFinite()) "–" else String.format(Locale.US, "%.1f", v)

@Composable
fun AutoTuneScreen(
    packageName: String,
    appName: String,
    config: GovernorConfig,
    sessions: List<SessionInfo>,
    onSetEnabled: (Boolean) -> Unit,
    onSetMode: (AutoTuneMode) -> Unit,
    onSetMinMinutes: (Int) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onToggleLockParam: (AutoTuneParam) -> Unit,
    onApplySuggestion: () -> Unit,
    onResetToBaseline: () -> Unit,
    onRevertToUserSettings: () -> Unit,
    onRetune: () -> Unit,
    onUpdateProfile: (PerGameProfile?) -> Unit,
    onOpenSessionDetail: (SessionInfo) -> Unit,
    onCompareTrialWithBaseline: (String, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val state = config.autoTuneByPkg[packageName] ?: PerGameAutoTuneState()
    val profile = config.perGameProfiles[packageName] ?: PerGameProfile()
    val sessionsByName = remember(sessions) { sessions.associateBy { it.fileName } }
    val baselineTrial = remember(state.trials) { state.trials.firstOrNull { it.status == AutoTuneTrialStatus.BASELINE } }
    var showOverridesEditor by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val statusLabel: String
    val statusColor: androidx.compose.ui.graphics.Color
    val statusContainer: androidx.compose.ui.graphics.Color
    when {
        !state.enabled -> {
            statusLabel = stringResource(R.string.common_off)
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            statusContainer = MaterialTheme.colorScheme.surfaceVariant
        }
        state.paused -> {
            statusLabel = stringResource(R.string.autotune_badge_paused)
            statusColor = ext.warning
            statusContainer = ext.warningContainer
        }
        state.converged -> {
            statusLabel = stringResource(R.string.autotune_badge_converged)
            statusColor = ext.optimal
            statusContainer = ext.optimalContainer
        }
        state.pendingCandidate != null -> {
            statusLabel = stringResource(R.string.autotune_badge_trial, state.pendingTrialId)
            statusColor = primary
            statusContainer = MaterialTheme.colorScheme.primaryContainer
        }
        !state.baselineCaptured -> {
            statusLabel = stringResource(R.string.autotune_badge_needs_baseline)
            statusColor = ext.warning
            statusContainer = ext.warningContainer
        }
        else -> {
            statusLabel = stringResource(state.mode.labelRes)
            statusColor = primary
            statusContainer = MaterialTheme.colorScheme.primaryContainer
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header & Master Auto-Tune controls
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
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(label = statusLabel, color = statusColor, containerColor = statusContainer)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.autotune_enable_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.autotune_enable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.autotune_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoTuneMode.entries.forEach { m ->
                        FilterChip(
                            selected = state.mode == m,
                            onClick = { onSetMode(m) },
                            label = { Text(stringResource(m.labelRes)) }
                        )
                    }
                    if (state.enabled) {
                        FilterChip(
                            selected = state.paused,
                            onClick = { onSetPaused(!state.paused) },
                            label = {
                                Text(
                                    stringResource(
                                        if (state.paused) R.string.autotune_resume_btn
                                        else R.string.autotune_pause_btn
                                    )
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.autotune_min_duration_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.autotune_min_duration_val, state.minSessionMinutes),
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        color = primary
                    )
                }
                Slider(
                    value = state.minSessionMinutes.toFloat().coerceIn(3f, 20f),
                    onValueChange = { onSetMinMinutes(it.roundToInt()) },
                    valueRange = 3f..20f,
                    steps = 16
                )
            }
        }

        // 2. Pending suggestion banner (Suggest-only)
        val sug = state.pendingSuggestion
        if (state.enabled && !state.paused && state.mode == AutoTuneMode.SUGGEST_ONLY && sug != null && sug.param != null) {
            item {
                EcuCard(accentBorder = primary) {
                    Text(
                        text = stringResource(R.string.autotune_suggestion_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.autotune_suggestion_body,
                            stringResource(sug.param.labelRes),
                            sug.oldValue,
                            sug.newValue
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onApplySuggestion) {
                        Text(stringResource(R.string.autotune_apply_suggestion_btn))
                    }
                }
            }
        }

        // 3. Summary: Baseline vs Best & Quick Recovery Actions
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.autotune_progress_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!state.baselineCaptured) {
                    Text(
                        text = stringResource(R.string.autotune_waiting_baseline, state.minSessionMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val gainPct = if (state.baselineScore.isFinite() && state.baselineScore > 0f && state.bestScore.isFinite()) {
                        ((state.bestScore - state.baselineScore) / state.baselineScore) * 100f
                    } else 0f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.autotune_baseline_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = fmtScore(state.baselineScore),
                                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.autotune_best_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${fmtScore(state.bestScore)} (${if (gainPct >= 0f) "+" else ""}${String.format(Locale.US, "%.1f", gainPct)}%)",
                                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                                color = if (gainPct > 0f) ext.optimal else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.baselineCaptured) {
                        OutlinedButton(onClick = onResetToBaseline) {
                            Text(stringResource(R.string.autotune_reset_baseline_btn))
                        }
                    }
                    OutlinedButton(onClick = onRevertToUserSettings) {
                        Text(stringResource(R.string.autotune_revert_mine_btn))
                    }
                }
                if (state.converged || state.triedCandidateKeys.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = onRetune) {
                        Text(stringResource(R.string.autotune_retune_btn))
                    }
                }
            }
        }

        // 4. Per-game Override Profile card
        item {
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.per_game_profile_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (profile.isEmpty) {
                                stringResource(R.string.per_game_using_global)
                            } else {
                                stringResource(R.string.per_game_overrides_active)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(onClick = { showOverridesEditor = !showOverridesEditor }) {
                        Text(
                            stringResource(
                                if (showOverridesEditor) R.string.common_hide else R.string.per_game_edit_btn
                            )
                        )
                    }
                }
                if (showOverridesEditor) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val pLim = profile.powerLimitWatts ?: config.powerThrottle.limitWatts
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.autotune_param_power_limit), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (profile.powerLimitWatts != null) "${String.format(Locale.US, "%.1f", pLim)} W" else stringResource(R.string.snap_val_global),
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                            color = primary
                        )
                    }
                    Slider(
                        value = pLim.coerceIn(3f, config.powerThrottle.limitWatts.coerceAtLeast(3f)),
                        onValueChange = { v ->
                            val snapped = ((v * 2f).roundToInt() / 2f).coerceIn(3f, config.powerThrottle.limitWatts.coerceAtLeast(3f))
                            onUpdateProfile(profile.copy(powerLimitWatts = snapped))
                        },
                        valueRange = 3f..config.powerThrottle.limitWatts.coerceAtLeast(3.5f)
                    )

                    val tStart = profile.tempStartC ?: config.tempThrottle.startTempC
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.autotune_param_temp_start), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (profile.tempStartC != null) "${String.format(Locale.US, "%.1f", tStart)} °C" else stringResource(R.string.snap_val_global),
                            style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                            color = primary
                        )
                    }
                    Slider(
                        value = tStart.coerceIn(36f, config.tempThrottle.startTempC.coerceAtLeast(36f)),
                        onValueChange = { v ->
                            val snapped = ((v * 2f).roundToInt() / 2f).coerceIn(36f, config.tempThrottle.startTempC.coerceAtLeast(36f))
                            onUpdateProfile(profile.copy(tempStartC = snapped))
                        },
                        valueRange = 36f..config.tempThrottle.startTempC.coerceAtLeast(37f)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.snap_afterburn), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = profile.afterburnStrength == null,
                            onClick = { onUpdateProfile(profile.copy(afterburnStrength = null)) },
                            label = { Text(stringResource(R.string.snap_val_global)) }
                        )
                        BoostStrength.entries.forEach { bs ->
                            FilterChip(
                                selected = profile.afterburnStrength == bs,
                                onClick = { onUpdateProfile(profile.copy(afterburnStrength = bs)) },
                                label = { Text(stringResource(bs.labelRes)) }
                            )
                        }
                    }

                    if (!profile.isEmpty) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { onUpdateProfile(null) }) {
                            Text(stringResource(R.string.per_game_clear_overrides_btn))
                        }
                    }
                }
            }
        }

        // 5. Parameter locks card
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.autotune_locks_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.autotune_locks_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val chunks = remember { AutoTuneLogic.PARAM_TABLE.chunked(2) }
                chunks.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { bound ->
                            val locked = bound.param in state.lockedParams
                            FilterChip(
                                selected = locked,
                                onClick = { onToggleLockParam(bound.param) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(bound.param.labelRes),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 6. Trial journal header + entries
        item {
            EcuCard {
                Text(
                    text = stringResource(R.string.autotune_journal_header, state.trials.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.trials.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.autotune_journal_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(state.trials.reversed(), key = { "${it.trialId}_${it.timestampMs}" }) { trial ->
            val stColor = when (trial.status) {
                AutoTuneTrialStatus.KEPT, AutoTuneTrialStatus.BASELINE -> ext.optimal
                AutoTuneTrialStatus.REJECTED_SAFETY -> ext.throttle
                AutoTuneTrialStatus.REVERTED -> ext.warning
                AutoTuneTrialStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
                AutoTuneTrialStatus.PENDING -> primary
            }
            val stContainer = when (trial.status) {
                AutoTuneTrialStatus.KEPT, AutoTuneTrialStatus.BASELINE -> ext.optimalContainer
                AutoTuneTrialStatus.REJECTED_SAFETY -> ext.throttle.copy(alpha = 0.16f)
                AutoTuneTrialStatus.REVERTED -> ext.warningContainer
                AutoTuneTrialStatus.IGNORED -> MaterialTheme.colorScheme.surfaceVariant
                AutoTuneTrialStatus.PENDING -> MaterialTheme.colorScheme.primaryContainer
            }
            EcuCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${trial.trialId} • ${dateFmt.format(Date(trial.timestampMs))}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    StatusPill(
                        label = stringResource(trial.status.labelRes),
                        color = stColor,
                        containerColor = stContainer
                    )
                }
                if (trial.param != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${stringResource(trial.param.labelRes)}: ${trial.oldValue} → ${trial.newValue}",
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                        color = primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = autoTuneReasonText(trial.reasonCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (trial.avgFps.isFinite() || trial.score.isFinite()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.autotune_trial_metrics,
                            fmtScore(trial.score),
                            fmtScore(trial.avgFps),
                            fmtScore(trial.p5Fps),
                            fmtScore(trial.avgWatts),
                            fmtScore(trial.maxTempC)
                        ),
                        style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val linked = if (trial.sessionFileName.isNotEmpty()) sessionsByName[trial.sessionFileName] else null
                val baseFile = baselineTrial?.sessionFileName.orEmpty()
                val canCompareBaseline = linked != null && baseFile.isNotEmpty() && baseFile != linked.fileName && sessionsByName.containsKey(baseFile)
                if (linked != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpenSessionDetail(linked) }) {
                            Text(stringResource(R.string.autotune_view_session_btn))
                        }
                        if (canCompareBaseline) {
                            TextButton(onClick = { onCompareTrialWithBaseline(baseFile, linked.fileName) }) {
                                Text(stringResource(R.string.autotune_compare_baseline_btn))
                            }
                        }
                    }
                }
            }
        }
    }
}
