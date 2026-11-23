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
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.InstalledAppInfo
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors

@Composable
fun AppsScreen(
    installedApps: List<InstalledAppInfo>,
    isLoadingApps: Boolean,
    gamePackages: Set<String>,
    usageStatsGranted: Boolean,
    onToggleGamePackage: (String) -> Unit,
    onRefreshUsageStats: () -> Unit,
    modifier: Modifier = Modifier,
    smartControls: Map<String, com.example.governor.AppControlInfo> = emptyMap(),
    onSmartSet: (String, com.example.governor.AppControl, com.example.governor.AppOverride) -> Unit = { _, _, _ -> },
    onSmartReset: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    var query by remember { mutableStateOf("") }
    var showOnlyGames by remember { mutableStateOf(false) }

    val filteredApps = remember(installedApps, query, showOnlyGames, gamePackages) {
        installedApps
            .filter { app ->
                val matchesQuery = query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                val matchesFilter = !showOnlyGames || app.packageName in gamePackages
                matchesQuery && matchesFilter
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> { it.packageName in gamePackages }
                    .thenBy { it.label.lowercase() }
            )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Foreground Detection Permission Card
        EcuCard(accentBorder = if (usageStatsGranted) ext.optimal else ext.warning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (usageStatsGranted) "UsageStats Detection Active" else "UsageStats Optional (Root Fallback Active)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (usageStatsGranted) {
                            "Fast foreground app events switch between Normal and Game modes."
                        } else {
                            "Grant Usage Access for instant app-switch detection, or rely on root dumpsys window fallback."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!usageStatsGranted) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            onRefreshUsageStats()
                        },
                        modifier = Modifier.testTag("grant_usage_stats_button")
                    ) {
                        Text("Grant")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("apps_search_input"),
            placeholder = { Text("Search installed apps or package name...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showOnlyGames,
                    onClick = { showOnlyGames = false },
                    label = { Text("All (${installedApps.size})") },
                    modifier = Modifier.testTag("filter_all_apps_chip")
                )
                FilterChip(
                    selected = showOnlyGames,
                    onClick = { showOnlyGames = true },
                    label = { Text("Games (${gamePackages.size})") },
                    modifier = Modifier.testTag("filter_games_only_chip")
                )
            }
            StatusPill(
                label = "${gamePackages.size} SELECTED",
                color = ext.warning,
                containerColor = ext.warningContainer
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No matching apps found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isGame = app.packageName in gamePackages
                    EcuCard(
                        accentBorder = if (isGame) ext.warning else null,
                        modifier = Modifier
                            .clickable { onToggleGamePackage(app.packageName) }
                            .testTag("app_item_${app.packageName}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                SmartProfileControl(
                                    pkg = app.packageName,
                                    info = smartControls[app.packageName],
                                    onSet = { c, o -> onSmartSet(app.packageName, c, o) },
                                    onReset = { onSmartReset(app.packageName) }
                                )
                            }
                            Checkbox(
                                checked = isGame,
                                onCheckedChange = { onToggleGamePackage(app.packageName) },
                                modifier = Modifier.testTag("app_checkbox_${app.packageName}")
                            )
                        }
                    }
                }
            }
        }
    }
}
