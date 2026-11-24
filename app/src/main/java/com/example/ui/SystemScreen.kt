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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.governor.ProcRow
import com.example.governor.RatingModel
import com.example.governor.RatingResult
import com.example.ui.components.EcuCard
import com.example.ui.theme.LocalExtendedColors

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
                Text("DEVICE RATING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                if (rating.score == null) {
                    Text("Not enough data yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = rating.progress, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Use the phone with the governor running. About 30 minutes of unplugged use are needed (${rating.seconds / 60} min so far).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("${rating.score} / 100", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Confidence: ${rating.confidence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                rating.parts.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (p.score != null) "${p.score}  " else "-  ",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            p.detail,
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
                    "Charging time is not counted. Compare phones only on similar usage. On one phone, a higher score after a settings change means that setting suits your usage better.",
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
                            val text = RatingModel.shareText(rating, android.os.Build.MODEL ?: "Android", ver)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share rating"))
                        }
                    ) { Text("Share rating") }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                }
            }
        }

        item {
            EcuCard {
                Text("PROCESS MONITOR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sorted by CPU use (% of one core). Culprits are apps that kept using a lot of CPU in the background for about 30 seconds. Stopping an app is done with root, and it may start again by itself. Core system processes are protected and cannot be stopped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("Culprit sensitivity", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("High" to 8f, "Medium" to 15f, "Low" to 25f).forEach { (label, v) ->
                        FilterChip(selected = thresholdPct == v, onClick = { onThreshold(v) }, label = { Text(label) })
                    }
                }
                if (note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = ext.warning)
                    TextButton(onClick = onDismissNote) { Text("OK") }
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
                            String.format(java.util.Locale.US, "%.0f%% CPU  -  %.0f MB  -  %d proc", r.cpuPct, r.ramMb, r.procCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val tag = when {
                            r.culprit -> "CULPRIT: busy in the background"
                            r.foreground -> "In the foreground"
                            r.protectedReason != null -> "Protected: ${r.protectedReason}"
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
                            Icon(Icons.Filled.Close, contentDescription = "Stop ${r.key}")
                        }
                    }
                }
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Stop ${appLabels[r.key] ?: r.key}?") },
            text = { Text("It will be force-stopped with root. It may start again by itself, and unsaved data in it is lost.") },
            confirmButton = { TextButton(onClick = { confirm = null; onKill(r) }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset rating data?") },
            text = { Text("The collected totals are cleared and the rating starts again from zero.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onResetRating() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}
