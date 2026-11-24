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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.OffPeriod
import com.example.governor.ScreenOffSnapshot
import com.example.governor.WakeStat
import com.example.model.GovernorConfig
import com.example.ui.components.EcuCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dur(ms: Long): String {
    val m = ms / 60_000L
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private fun pph(v: Float): String = if (v < 0f) "-" else "%.2f %%/h".format(Locale.US, v)

private fun periodLine(p: OffPeriod, fmt: SimpleDateFormat, sleepSuffix: String): String =
    "${fmt.format(Date(p.startMs))}  ${dur(p.durMs)}  -${"%.1f".format(Locale.US, p.pctDrop)}% (${pph(p.pctPerHour)})" +
        (if (p.avgW > 0f) "  ${"%.2f".format(Locale.US, p.avgW)} W" else "") + sleepSuffix

@Composable
fun ScreenOffCard(
    config: GovernorConfig,
    snap: ScreenOffSnapshot,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.screen_off_reset_title)) },
            text = { Text(stringResource(R.string.screen_off_reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReset() }) {
                    Text(stringResource(R.string.common_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    EcuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.screen_off_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.screen_off_desc),
                        style = MaterialTheme.typography.labelSmall, color = sub
                    )
                }
                Switch(checked = config.screenOffAnalysisEnabled, onCheckedChange = onToggle)
            }
            if (!snap.hasData) {
                Text(
                    if (config.screenOffAnalysisEnabled) {
                        stringResource(R.string.screen_off_no_data)
                    } else {
                        stringResource(R.string.screen_off_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall, color = sub
                )
            } else {
                val rate = snap.offPctPerHWeek
                val verdict = when {
                    rate < 0f -> stringResource(R.string.screen_off_verdict_none)
                    rate < 0.5f -> stringResource(R.string.screen_off_verdict_excellent)
                    rate < 1.0f -> stringResource(R.string.screen_off_verdict_good)
                    rate < 2.0f -> stringResource(R.string.screen_off_verdict_high)
                    else -> stringResource(R.string.screen_off_verdict_very_high)
                }
                Text(verdict, style = MaterialTheme.typography.titleSmall)
                val summaryText = buildString {
                    append(stringResource(R.string.screen_off_summary_line1, pph(rate), "%.1f".format(Locale.US, snap.offHoursWeek)))
                    if (snap.offPctPerHToday >= 0f) {
                        append(stringResource(R.string.screen_off_summary_today, pph(snap.offPctPerHToday)))
                    }
                    append('\n')
                    append(stringResource(R.string.screen_off_summary_on, pph(snap.onPctPerHWeek)))
                    if (snap.offMahPerHWeek >= 0f) {
                        append('\n')
                        append(stringResource(R.string.screen_off_summary_off_drain, "%.1f".format(Locale.US, snap.offMahPerHWeek)))
                    }
                    if (snap.offAvgWWeek > 0f) {
                        append(stringResource(R.string.screen_off_summary_avg_w, "%.3f".format(Locale.US, snap.offAvgWWeek)))
                    }
                    if (snap.deepSleepPctWeek >= 0) {
                        append('\n')
                        append(stringResource(R.string.screen_off_summary_deep_sleep, snap.deepSleepPctWeek))
                    }
                    if (snap.standbyHoursToEmpty > 0f) {
                        append('\n')
                        append(stringResource(R.string.screen_off_summary_standby, "%.0f".format(Locale.US, snap.standbyHoursToEmpty)))
                    }
                }
                Text(summaryText, style = MaterialTheme.typography.bodySmall)
                if (snap.deepSleepPctWeek in 0..69) {
                    Text(
                        stringResource(R.string.screen_off_low_sleep_warn),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                    )
                }
                snap.worst?.let {
                    val line = periodLine(it, fmt, stringResource(R.string.screen_off_sleep_pct, it.deepSleepPct))
                    Text(
                        stringResource(R.string.screen_off_worst_recent, line),
                        style = MaterialTheme.typography.labelSmall,
                        color = sub
                    )
                }
                if (snap.topKernel.isNotEmpty()) {
                    Text(stringResource(R.string.screen_off_kernel_wakes), style = MaterialTheme.typography.labelMedium)
                    snap.topKernel.forEach { k ->
                        val wakeups = pluralStringResource(R.plurals.plural_wakeups, k.count, k.count)
                        WakeLine(k.name, stringResource(R.string.screen_off_kernel_wake_detail, (k.ms / 1000).toInt(), wakeups))
                    }
                }
                if (snap.topApps.isNotEmpty()) {
                    val checks = pluralStringResource(R.plurals.plural_checks, snap.appChecks, snap.appChecks)
                    Text(stringResource(R.string.screen_off_app_wakes, checks), style = MaterialTheme.typography.labelMedium)
                    snap.topApps.forEach { a ->
                        WakeLine(a.name, stringResource(R.string.screen_off_app_wake_held, a.count))
                    }
                } else if (snap.appChecks > 0) {
                    Text(stringResource(R.string.screen_off_no_app_wakes), style = MaterialTheme.typography.labelSmall, color = sub)
                }
                Text(stringResource(R.string.screen_off_recent_periods), style = MaterialTheme.typography.labelMedium)
                snap.recent.forEach { p ->
                    val line = periodLine(p, fmt, stringResource(R.string.screen_off_sleep_pct, p.deepSleepPct))
                    Text(line, style = MaterialTheme.typography.labelSmall, color = sub)
                }
                OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.screen_off_reset_data)) }
            }
        }
    }
}

@Composable
private fun WakeLine(name: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
