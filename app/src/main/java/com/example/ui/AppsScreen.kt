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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.InstalledAppInfo
import com.example.model.PerGameAutoTuneState
import com.example.model.PerGameProfile
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
    boostPackages: Set<String> = emptySet(),
    onToggleBoostPackage: (String) -> Unit = {},
    onRefreshUsageStats: () -> Unit,
    modifier: Modifier = Modifier,
    smartControls: Map<String, com.example.governor.AppControlInfo> = emptyMap(),
    onSmartSet: (String, com.example.governor.AppControl, com.example.governor.AppOverride) -> Unit = { _, _, _ -> },
    onSmartReset: (String) -> Unit = {},
    autoTuneByPkg: Map<String, PerGameAutoTuneState> = emptyMap(),
    perGameProfiles: Map<String, PerGameProfile> = emptyMap(),
    onOpenAutoTune: (InstalledAppInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var showOnlyGames by rememberSaveable { mutableStateOf(false) }
    // v3.0.0: false = Games list (Normal/Game mode), true = Afterburn list
    var boostMode by rememberSaveable { mutableStateOf(false) }
    val selectedPackages = if (boostMode) boostPackages else gamePackages
    val onToggleSelected: (String) -> Unit = if (boostMode) onToggleBoostPackage else onToggleGamePackage
    val selectColor = if (boostMode) ext.optimal else ext.warning
    val selectContainer = if (boostMode) ext.optimalContainer else ext.warningContainer

    val filteredApps = remember(installedApps, query, showOnlyGames, selectedPackages) {
        installedApps
            .filter { app ->
                val matchesQuery = query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                val matchesFilter = !showOnlyGames || app.packageName in selectedPackages
                matchesQuery && matchesFilter
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> { it.packageName in selectedPackages }
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
                        text = stringResource(
                            if (usageStatsGranted) R.string.apps_usage_active
                            else R.string.apps_usage_optional
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(
                            if (usageStatsGranted) R.string.apps_usage_active_desc
                            else R.string.apps_usage_optional_desc
                        ),
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
                        Text(stringResource(R.string.apps_grant_btn))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !boostMode,
                onClick = { boostMode = false },
                label = { Text(stringResource(R.string.apps_list_games, gamePackages.size)) },
                modifier = Modifier.testTag("mode_games_chip")
            )
            FilterChip(
                selected = boostMode,
                onClick = { boostMode = true },
                label = { Text(stringResource(R.string.apps_list_afterburn, boostPackages.size)) },
                modifier = Modifier.testTag("mode_afterburn_chip")
            )
        }
        Text(
            text = stringResource(
                if (boostMode) R.string.apps_hint_afterburn else R.string.apps_hint_games
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("apps_search_input"),
            placeholder = { Text(stringResource(R.string.apps_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.apps_search_cd)) },
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
                    label = { Text(stringResource(R.string.apps_filter_all, installedApps.size)) },
                    modifier = Modifier.testTag("filter_all_apps_chip")
                )
                FilterChip(
                    selected = showOnlyGames,
                    onClick = { showOnlyGames = true },
                    label = {
                        Text(
                            if (boostMode) stringResource(R.string.apps_filter_selected, boostPackages.size)
                            else stringResource(R.string.apps_filter_games, gamePackages.size)
                        )
                    },
                    modifier = Modifier.testTag("filter_games_only_chip")
                )
            }
            StatusPill(
                label = stringResource(R.string.apps_selected_pill, selectedPackages.size),
                color = selectColor,
                containerColor = selectContainer
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
                        text = stringResource(R.string.apps_empty),
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
                    val isGame = app.packageName in selectedPackages
                    val atState = autoTuneByPkg[app.packageName]
                    val hasOverride = perGameProfiles[app.packageName]?.isEmpty == false
                    EcuCard(
                        accentBorder = if (isGame) selectColor else null,
                        modifier = Modifier
                            .clickable { onToggleSelected(app.packageName) }
                            .testTag("app_item_${app.packageName}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!boostMode && isGame && (atState?.enabled == true || hasOverride)) {
                                        val pillText = when {
                                            atState?.enabled == true && atState.paused -> stringResource(R.string.autotune_badge_paused)
                                            atState?.enabled == true && atState.converged -> stringResource(R.string.autotune_badge_converged)
                                            atState?.enabled == true -> stringResource(atState.mode.labelRes)
                                            else -> stringResource(R.string.per_game_badge_custom)
                                        }
                                        StatusPill(
                                            label = pillText,
                                            color = MaterialTheme.colorScheme.primary,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    }
                                }
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
                                if (!boostMode && app.packageName in gamePackages) {
                                    androidx.compose.material3.TextButton(
                                        onClick = { onOpenAutoTune(app) },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.apps_open_autotune_btn),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                            Checkbox(
                                checked = isGame,
                                onCheckedChange = { onToggleSelected(app.packageName) },
                                modifier = Modifier.testTag("app_checkbox_${app.packageName}")
                            )
                        }
                    }
                }
            }
        }
    }
}
