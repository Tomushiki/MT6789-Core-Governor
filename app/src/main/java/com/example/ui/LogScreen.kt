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
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.ui.components.EcuCard
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoMetricStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    var errorsOnly by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    val visibleLogs = remember(logs, errorsOnly) {
        if (errorsOnly) logs.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN } else logs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !errorsOnly,
                    onClick = { errorsOnly = false },
                    label = { Text(stringResource(R.string.log_filter_all, logs.size)) },
                    modifier = Modifier.testTag("log_filter_all")
                )
                FilterChip(
                    selected = errorsOnly,
                    onClick = { errorsOnly = true },
                    label = { Text(stringResource(R.string.log_filter_errors)) },
                    modifier = Modifier.testTag("log_filter_errors")
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearLogs,
                    modifier = Modifier.testTag("log_clear_button")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.log_clear_cd))
                }
                Button(
                    onClick = {
                        val text = visibleLogs.asReversed().joinToString("\n") { entry ->
                            "${dateFmt.format(Date(entry.timestampMs))} [${entry.level}] [${entry.tag}] ${entry.message}"
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CoreGovernor Log", text))
                        Toast.makeText(
                            context,
                            context.resources.getQuantityString(
                                R.plurals.plural_log_copied,
                                visibleLogs.size,
                                visibleLogs.size
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.testTag("log_copy_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_copy))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (visibleLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleLogs, key = { it.id }) { entry ->
                    val levelColor = when (entry.level) {
                        LogLevel.ERROR -> ext.throttle
                        LogLevel.WARN -> ext.warning
                        LogLevel.INFO -> MaterialTheme.colorScheme.primary
                    }
                    EcuCard(accentBorder = if (entry.level == LogLevel.ERROR) ext.throttle else null) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "[${entry.level}] ${entry.tag}",
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = levelColor
                                )
                                Text(
                                    text = dateFmt.format(Date(entry.timestampMs)),
                                    style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.message,
                                style = MonoMetricStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
