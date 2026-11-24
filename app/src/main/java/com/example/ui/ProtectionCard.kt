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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.governor.ProtectionStatus
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSwitchRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalExtendedColors

@Composable
fun ProtectionCard(
    enabled: Boolean,
    status: ProtectionStatus,
    onToggle: (Boolean) -> Unit,
    onReverify: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val activeOk = enabled && status.rootAvailable && status.oomApplied && status.dozeWhitelisted && status.standbyActive

    val pillLabel: String
    val pillColor: androidx.compose.ui.graphics.Color
    val pillContainer: androidx.compose.ui.graphics.Color
    when {
        !enabled -> {
            pillLabel = stringResource(R.string.protection_pill_off)
            pillColor = MaterialTheme.colorScheme.onSurfaceVariant
            pillContainer = MaterialTheme.colorScheme.surfaceVariant
        }
        status.needsRoot -> {
            pillLabel = stringResource(R.string.protection_pill_needs_root)
            pillColor = ext.throttle
            pillContainer = ext.throttleContainer
        }
        status.isVerifying -> {
            pillLabel = stringResource(R.string.protection_pill_verifying)
            pillColor = MaterialTheme.colorScheme.primary
            pillContainer = MaterialTheme.colorScheme.primaryContainer
        }
        activeOk -> {
            pillLabel = stringResource(R.string.protection_pill_active)
            pillColor = ext.optimal
            pillContainer = ext.optimalContainer
        }
        else -> {
            pillLabel = stringResource(R.string.protection_pill_partial)
            pillColor = ext.warning
            pillContainer = ext.warningContainer
        }
    }

    val parts = buildList {
        if (status.needsRoot) {
            add(stringResource(R.string.protection_status_needs_root))
        } else {
            if (status.oomApplied) add(stringResource(R.string.protection_item_oom))
            if (status.dozeWhitelisted) add(stringResource(R.string.protection_item_whitelisted))
            if (status.standbyActive) add(stringResource(R.string.protection_item_standby))
            if (status.phantomKillerSupported && status.phantomKillerOff) {
                add(stringResource(R.string.protection_item_phantom_off))
            }
            if (status.watchdogRunning) add(stringResource(R.string.protection_item_watchdog))
        }
    }
    val statusLine = when {
        !enabled -> stringResource(R.string.protection_status_disabled)
        parts.isNotEmpty() -> parts.joinToString(" · ")
        status.isVerifying -> stringResource(R.string.protection_pill_verifying)
        else -> stringResource(R.string.protection_status_pending)
    }

    EcuCard(
        modifier = modifier,
        accentBorder = if (enabled && status.needsRoot) ext.throttle else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.protection_card_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            StatusPill(
                label = pillLabel,
                color = pillColor,
                containerColor = pillContainer
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        SettingSwitchRow(
            title = stringResource(R.string.protection_switch_title),
            explanation = stringResource(R.string.protection_switch_desc),
            checked = enabled,
            onCheckedChange = onToggle,
            testTag = "protect_from_kill_switch"
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.needsRoot) ext.throttle else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("protection_status_text")
        )

        if (enabled) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onReverify,
                enabled = !status.isVerifying,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("protection_reverify_button")
            ) {
                Text(stringResource(R.string.protection_reverify_button))
            }
        }
    }
}
