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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.CoreGovernorApp
import com.example.governor.BatteryOptimization
import com.example.ui.components.EcuCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Background keep-alive": shows whether Android battery optimization is off for CoreGovernor and
 * lets the user turn it off. Root is tried first (no prompt), then Android's own prompt.
 */
@Composable
fun BatteryOptimizationCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ext = LocalExtendedColors.current
    val scope = rememberCoroutineScope()
    val engine = remember { (context.applicationContext as CoreGovernorApp).engine }

    var exempt by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // Re-check while this card is on screen, so the status updates after the user returns from
    // Android's prompt. It is one cheap system call every 1.5 s and stops when the card leaves the screen.
    LaunchedEffect(Unit) {
        while (isActive) {
            exempt = BatteryOptimization.isIgnoring(context)
            delay(1_500L)
        }
    }

    EcuCard(modifier = modifier, accentBorder = if (exempt) null else ext.warning) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Background keep-alive",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            StatusPill(
                label = if (exempt) "UNRESTRICTED" else "MAY BE STOPPED",
                color = if (exempt) ext.optimal else ext.warning,
                containerColor = if (exempt) ext.optimalContainer else ext.warningContainer
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (exempt) {
                "Android battery optimization is off for CoreGovernor, so the system should not stop the governor in the background."
            } else {
                "Android may stop the governor when the phone idles. Turn battery optimization off for CoreGovernor so it can stay alive in the background."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!exempt) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    note = null
                    scope.launch {
                        val ok = BatteryOptimization.exemptWithRoot(context, engine.rootShell)
                        busy = false
                        if (ok) {
                            exempt = true
                            note = "Done. Battery optimization is now off for CoreGovernor."
                        } else {
                            note = "The root method did not work, so Android's own prompt is opening. Choose Allow."
                            BatteryOptimization.openSystemPrompt(context)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("battery_optimization_button")
            ) {
                Text(if (busy) "Working…" else "Allow background activity")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
            onClick = { BatteryOptimization.openAppDetails(context) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_battery_settings_button")
        ) {
            Text("Open app settings")
        }
        note?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Some phone brands also have their own Autostart or Background settings. If the governor still stops, check them in Open app settings.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
