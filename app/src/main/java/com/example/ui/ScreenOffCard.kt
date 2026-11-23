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
import androidx.compose.ui.unit.dp
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

private fun periodLine(p: OffPeriod, fmt: SimpleDateFormat): String =
    "${fmt.format(Date(p.startMs))}  ${dur(p.durMs)}  -${"%.1f".format(Locale.US, p.pctDrop)}% (${pph(p.pctPerHour)})" +
        (if (p.avgW > 0f) "  ${"%.2f".format(Locale.US, p.avgW)} W" else "") + "  sleep ${p.deepSleepPct}%"

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
            title = { Text("Reset screen-off data?") },
            text = { Text("Deletes all recorded screen-off periods and daily totals.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
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
                    Text("Screen-off power analysis", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Measures battery drain, deep sleep and what keeps the phone awake while the screen is off (3 min or longer, not charging). Needs the service running.",
                        style = MaterialTheme.typography.labelSmall, color = sub
                    )
                }
                Switch(checked = config.screenOffAnalysisEnabled, onCheckedChange = onToggle)
            }
            if (!snap.hasData) {
                Text(
                    if (config.screenOffAnalysisEnabled) "No data yet. Leave the screen off for a few minutes (unplugged) and check back."
                    else "Off.",
                    style = MaterialTheme.typography.bodySmall, color = sub
                )
            } else {
                val rate = snap.offPctPerHWeek
                val verdict = when {
                    rate < 0f -> "Not enough data"
                    rate < 0.5f -> "Excellent standby"
                    rate < 1.0f -> "Good standby"
                    rate < 2.0f -> "Higher than normal"
                    else -> "Very high drain - something is keeping the phone awake"
                }
                Text(verdict, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Last 7 days: ${pph(rate)} screen off (${"%.1f".format(Locale.US, snap.offHoursWeek)} h)" +
                        (if (snap.offPctPerHToday >= 0f) "   Today: ${pph(snap.offPctPerHToday)}" else "") +
                        "\nScreen on: ${pph(snap.onPctPerHWeek)}" +
                        (if (snap.offMahPerHWeek >= 0f) "\nOff drain: ${"%.1f".format(Locale.US, snap.offMahPerHWeek)} mAh/h" else "") +
                        (if (snap.offAvgWWeek > 0f) "   avg ${"%.3f".format(Locale.US, snap.offAvgWWeek)} W" else "") +
                        (if (snap.deepSleepPctWeek >= 0) "\nDeep sleep: ${snap.deepSleepPctWeek}% of off time" else "") +
                        (if (snap.standbyHoursToEmpty > 0f) "\nStandby estimate: ~${"%.0f".format(Locale.US, snap.standbyHoursToEmpty)} h from full to empty" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
                if (snap.deepSleepPctWeek in 0..69) {
                    Text(
                        "Deep sleep is low: the CPU is being woken or held awake for a large part of the off time. Check the wake sources below.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                    )
                }
                snap.worst?.let {
                    Text("Worst recent: ${periodLine(it, fmt)}", style = MaterialTheme.typography.labelSmall, color = sub)
                }
                if (snap.topKernel.isNotEmpty()) {
                    Text("Kernel wake sources (time active while off)", style = MaterialTheme.typography.labelMedium)
                    snap.topKernel.forEach { k -> WakeLine("${k.name}", "${k.ms / 1000}s, ${k.count} wakeups") }
                }
                if (snap.topApps.isNotEmpty()) {
                    Text("Apps holding wakelocks while off (${snap.appChecks} checks)", style = MaterialTheme.typography.labelMedium)
                    snap.topApps.forEach { a -> WakeLine(a.name, "held in ${a.count}") }
                } else if (snap.appChecks > 0) {
                    Text("No app wakelocks seen while the screen was off.", style = MaterialTheme.typography.labelSmall, color = sub)
                }
                Text("Recent screen-off periods", style = MaterialTheme.typography.labelMedium)
                snap.recent.forEach { p -> Text(periodLine(p, fmt), style = MaterialTheme.typography.labelSmall, color = sub) }
                OutlinedButton(onClick = { confirm = true }) { Text("Reset data") }
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
