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

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.ProcRow
import com.example.governor.RatingResult
import com.example.ui.components.EcuCard
import com.example.ui.theme.LocalExtendedColors
import java.util.Locale
import kotlin.math.roundToInt

private fun localizedRatingLabel(ctx: Context, raw: String): String = when (raw) {
    "Performance" -> ctx.getString(R.string.rating_part_perf)
    "Efficiency" -> ctx.getString(R.string.rating_part_eff)
    "Power draw" -> ctx.getString(R.string.rating_part_power)
    "Thermals" -> ctx.getString(R.string.rating_part_thermals)
    "Idle load" -> ctx.getString(R.string.rating_part_idle)
    "Standby" -> ctx.getString(R.string.rating_part_standby)
    else -> raw
}

private fun localizedConfidence(ctx: Context, raw: String): String = when (raw) {
    "none" -> ctx.getString(R.string.rating_conf_none)
    "Low" -> ctx.getString(R.string.rating_conf_low)
    "Medium" -> ctx.getString(R.string.rating_conf_medium)
    "High" -> ctx.getString(R.string.rating_conf_high)
    else -> raw
}

private fun localizedRatingDetail(ctx: Context, raw: String): String = when {
    raw == "needs game play" -> ctx.getString(R.string.rating_detail_needs_game)
    raw == "collecting" -> ctx.getString(R.string.rating_detail_collecting)
    raw == "needs screen-off analysis" -> ctx.getString(R.string.rating_detail_needs_screen_off)
    raw.startsWith("5% low ") && raw.endsWith(" fps") -> {
        val v = raw.removePrefix("5% low ").removeSuffix(" fps").trim().toIntOrNull()
        if (v != null) ctx.getString(R.string.rating_detail_p5, v) else raw
    }
    raw.endsWith(" FPS/W") -> {
        val v = raw.removeSuffix(" FPS/W").trim()
        ctx.getString(R.string.rating_detail_eff, v)
    }
    raw.endsWith(" W average") -> {
        val v = raw.removeSuffix(" W average").trim()
        ctx.getString(R.string.rating_detail_watts, v)
    }
    raw.endsWith(" C average") -> {
        val v = raw.removeSuffix(" C average").trim()
        ctx.getString(R.string.rating_detail_temp, v)
    }
    raw.endsWith("% CPU outside games") -> {
        val v = raw.removeSuffix("% CPU outside games").trim().toIntOrNull()
        if (v != null) ctx.getString(R.string.rating_detail_idle, v) else raw
    }
    raw.endsWith(" %/h screen off") -> {
        val v = raw.removeSuffix(" %/h screen off").trim()
        ctx.getString(R.string.rating_detail_standby, v)
    }
    else -> raw
}

private fun localizedProtectedReason(ctx: Context, raw: String): String = when (raw) {
    "In use by the system" -> ctx.getString(R.string.proc_prot_system_use)
    "Core system" -> ctx.getString(R.string.proc_prot_core_system)
    "Root process" -> ctx.getString(R.string.proc_prot_root)
    "System daemon" -> ctx.getString(R.string.proc_prot_daemon)
    "Hardware service" -> ctx.getString(R.string.proc_prot_hardware)
    else -> raw
}

private fun buildShareRatingText(
    ctx: Context,
    r: RatingResult,
    device: String,
    appVersion: String
): String {
    val lines = mutableListOf<String>()
    val ver = if (appVersion.isNotBlank()) " v$appVersion" else ""
    lines += "$device • CoreGovernor$ver"
    lines += if (r.score != null) {
        ctx.getString(R.string.system_share_rating_header, r.score, localizedConfidence(ctx, r.confidence))
    } else {
        ctx.getString(R.string.system_rating_not_enough)
    }
    r.parts.forEach { p ->
        val label = localizedRatingLabel(ctx, p.label)
        val detail = localizedRatingDetail(ctx, p.detail)
        if (p.score != null) {
            lines += ctx.getString(R.string.system_share_rating_part, label, p.score, detail)
        }
    }
    lines += ctx.getString(R.string.system_share_rating_usage, String.format(Locale.US, "%.1f", r.seconds / 3600f))
    return lines.joinToString("\n")
}

/** System tab: overall device rating on top, then the process monitor with culprit detection. */
@Composable
fun SystemScreen(
    rating: RatingResult,
    rows: List<ProcRow>,
    error: String?,
    note: String?,
    thresholdPct: Float,
    appLabels: Map<String, String>,
    onThreshold: (Float) -> Unit,
    onKill: (ProcRow) -> Unit,
    onDismissNote: () -> Unit,
    onResetRating: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val ext = LocalExtendedColors.current
    var confirm by remember { mutableStateOf<ProcRow?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            EcuCard {
                Text(stringResource(R.string.system_rating_header), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                if (rating.score == null) {
                    Text(stringResource(R.string.system_rating_not_enough), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { rating.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    val mins = (rating.seconds / 60).toInt()
                    val minsText = pluralStringResource(R.plurals.plural_minutes, mins, mins)
                    Text(
                        stringResource(R.string.system_rating_progress, minsText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("${rating.score} / 100", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.system_rating_confidence, localizedConfidence(ctx, rating.confidence)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                rating.parts.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedRatingLabel(ctx, p.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (p.score != null) "${p.score}  " else "-  ",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            localizedRatingDetail(ctx, p.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.system_rating_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = rating.score != null,
                        onClick = {
                            val ver = try {
                                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                            } catch (_: Exception) { "" }
                            val text = buildShareRatingText(ctx, rating, android.os.Build.MODEL ?: "Android", ver)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.system_share_rating_chooser)))
                        }
                    ) { Text(stringResource(R.string.system_share_rating)) }
                    TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.common_reset)) }
                }
            }
        }

        item {
            EcuCard {
                Text(stringResource(R.string.system_proc_header), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.system_proc_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.system_culprit_sensitivity), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        stringResource(R.string.system_sens_high) to 8f,
                        stringResource(R.string.system_sens_medium) to 15f,
                        stringResource(R.string.system_sens_low) to 25f
                    ).forEach { (label, v) ->
                        FilterChip(selected = thresholdPct == v, onClick = { onThreshold(v) }, label = { Text(label) })
                    }
                }
                if (note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = ext.warning)
                    TextButton(onClick = onDismissNote) { Text(stringResource(R.string.common_ok)) }
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        items(rows.take(80), key = { it.key }) { r ->
            val label = appLabels[r.key] ?: r.key
            EcuCard(accentBorder = if (r.culprit) ext.warning else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.system_proc_stats,
                                r.cpuPct.roundToInt(),
                                r.ramMb.roundToInt(),
                                r.procCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val tag = when {
                            r.culprit -> stringResource(R.string.system_proc_culprit)
                            r.foreground -> stringResource(R.string.system_proc_fg)
                            r.protectedReason != null -> stringResource(
                                R.string.system_proc_protected,
                                localizedProtectedReason(ctx, r.protectedReason)
                            )
                            else -> null
                        }
                        if (tag != null) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r.culprit) ext.warning else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (r.killable) {
                        IconButton(onClick = { confirm = r }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.system_stop_proc_cd, r.key))
                        }
                    }
                }
            }
        }
        item { AppearanceCard() }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.system_stop_dialog_title, appLabels[r.key] ?: r.key)) },
            text = { Text(stringResource(R.string.system_stop_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = null; onKill(r) }) {
                    Text(stringResource(R.string.common_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.system_reset_rating_title)) },
            text = { Text(stringResource(R.string.system_reset_rating_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetRating() }) {
                    Text(stringResource(R.string.common_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
