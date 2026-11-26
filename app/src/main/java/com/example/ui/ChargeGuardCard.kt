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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.CoreGovernorApp
import com.example.R
import com.example.governor.ChargeGuardSettings
import com.example.governor.ChargeGuardStore
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** v3.6.6 Charge sleep guard card for the Screen-off section of Tuning. Keeps its own settings. */
@Composable
fun ChargeGuardCard() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(ChargeGuardStore.load(ctx)) }
    var picker by remember { mutableStateOf<Boolean?>(null) } // true = whitelist, false = always-stop list
    fun update(n: ChargeGuardSettings) { s = n; ChargeGuardStore.save(ctx, n) }

    EcuCard {
        Text(
            text = stringResource(R.string.cg_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingSwitchRow(
            title = stringResource(R.string.cg_enable),
            explanation = stringResource(R.string.cg_enable_desc),
            checked = s.enabled,
            onCheckedChange = { update(s.copy(enabled = it)) },
            testTag = "charge_guard_switch"
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingSwitchRow(
            title = stringResource(R.string.cg_others),
            explanation = stringResource(R.string.cg_others_desc),
            checked = s.killOthers,
            onCheckedChange = { update(s.copy(killOthers = it)) },
            enabled = s.enabled,
            testTag = "charge_guard_others_switch"
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(stringResource(R.string.cg_whitelist), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.cg_whitelist_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { picker = true }, enabled = s.enabled, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.cg_choose, s.whitelist.size))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(stringResource(R.string.cg_kill), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.cg_kill_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { picker = false }, enabled = s.enabled, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.cg_choose, s.killList.size))
        }
        Spacer(modifier = Modifier.height(10.dp))
        SettingSwitchRow(
            title = stringResource(R.string.cg_fast_wake),
            explanation = stringResource(R.string.cg_fast_wake_desc),
            checked = s.fastWake,
            onCheckedChange = { update(s.copy(fastWake = it)) },
            testTag = "charge_guard_fast_wake_switch"
        )
    }

    val which = picker
    if (which != null) {
        AppPickerDialog(
            title = stringResource(if (which) R.string.cg_whitelist else R.string.cg_kill),
            selected = if (which) s.whitelist else s.killList,
            includeSystem = !which,
            onDismiss = { picker = null },
            onChange = { set -> update(if (which) s.copy(whitelist = set) else s.copy(killList = set)) }
        )
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    selected: Set<String>,
    includeSystem: Boolean,
    onDismiss: () -> Unit,
    onChange: (Set<String>) -> Unit
) {
    val ctx = LocalContext.current
    val engine = remember { (ctx.applicationContext as CoreGovernorApp).engine }
    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selected) }

    LaunchedEffect(includeSystem) {
        all = withContext(Dispatchers.IO) {
            val out = sortedSetOf<String>()
            val flags = if (includeSystem) listOf("-3", "-s") else listOf("-3")
            for (f in flags) {
                val r = engine.rootShell.exec("pm list packages $f")
                if (r.isSuccess) {
                    r.stdout.lineSequence().map { it.trim().removePrefix("package:") }
                        .filter { it.isNotEmpty() && ChargeGuardStore.isValidPackage(it) }
                        .forEach { out.add(it) }
                }
            }
            out.addAll(selected)
            out.toList()
        }
    }

    val shown = remember(all, query, current) {
        all.filter { it.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<String> { it in current }.thenBy { it })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cg_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it }) { pkg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    current = if (pkg in current) current - pkg else current + pkg
                                    onChange(current)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = pkg in current,
                                onCheckedChange = {
                                    current = if (it) current + pkg else current - pkg
                                    onChange(current)
                                }
                            )
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cg_done)) } }
    )
}
