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

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.R
import com.example.data.LocaleHelper
import com.example.service.GovernorService
import com.example.ui.components.SettingSwitchRow
import com.example.ui.theme.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ui.components.EcuCard
import com.example.ui.components.SettingSliderRow
import com.example.ui.theme.Appearance

private val BorderSwatches = listOf(
    Color(0xFF5A6270),
    Color(0xFF4FC3D0),
    Color(0xFF5FCF8F),
    Color(0xFFE8B04B),
    Color(0xFFE86A6A),
    Color(0xFFA98BE8),
    Color(0xFFE6E6E6)
)

private val AccentSwatches = listOf(
    Color(0xFF7FD6E2),
    Color(0xFF7AA8F0),
    Color(0xFF6FD39A),
    Color(0xFFE9B459),
    Color(0xFFEC7272),
    Color(0xFFE98AB4),
    Color(0xFFA98BE8)
)

/** Draws the optional custom background behind the whole app. Plain background when none is set. */
@Composable
fun AppearanceBackground(content: @Composable () -> Unit) {
    val image = Appearance.background
    val dim = Appearance.dim
    val glass = Appearance.liquidGlass
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(
                    // The picture is static, so the blur is drawn once; it only applies on Android 12+.
                    if (glass) Modifier.blur(18.dp, BlurredEdgeTreatment.Unbounded) else Modifier
                )
            )
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = dim)))
        }
        content()
    }
}

@Composable
private fun SwatchRow(
    colors: List<Color>,
    selected: Color?,
    onPick: (Color) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { c ->
            val isSel = selected == c
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (isSel) 3.dp else 1.dp,
                        color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun SectionRow(title: String, resetEnabled: Boolean, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onReset, enabled = resetEnabled) { Text(stringResource(R.string.common_default)) }
    }
}

/** Visual options only: language, theme, accent, border color, corner radius, transparency, liquid glass, background image. */
@Composable
fun AppearanceCard() {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Appearance.setBackground(ctx, uri)
    }
    val border = Appearance.borderColor
    val accent = Appearance.accent
    var showHue by remember { mutableStateOf(false) }
    var hue by remember { mutableStateOf(190f) }
    var langExpanded by remember { mutableStateOf(false) }
    val currentTag = remember(ctx) { LocaleHelper.getSelectedTag(ctx) }
    val sysDefaultLabel = stringResource(R.string.language_system_default)
    val currentLangOption = LocaleHelper.SUPPORTED.firstOrNull { it.tag == currentTag } ?: LocaleHelper.SUPPORTED.first()
    val currentLangLabel = if (currentLangOption.tag.isEmpty()) sysDefaultLabel else currentLangOption.nativeName

    EcuCard {
        Text(
            text = stringResource(R.string.appearance_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))

        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(
                onClick = { langExpanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("language_selector_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = currentLangLabel, maxLines = 1)
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                LocaleHelper.SUPPORTED.forEach { opt ->
                    val optLabel = if (opt.tag.isEmpty()) sysDefaultLabel else opt.nativeName
                    DropdownMenuItem(
                        text = { Text(optLabel) },
                        trailingIcon = {
                            if (opt.tag == currentTag) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            langExpanded = false
                            if (opt.tag != currentTag) {
                                LocaleHelper.setSelectedTag(ctx, opt.tag)
                                GovernorService.refreshLocale(ctx)
                                (ctx as? Activity)?.recreate()
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.language_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.values().forEach { m ->
                ModeChip(stringResource(m.labelRes), Appearance.themeMode == m, Modifier.weight(1f)) {
                    Appearance.setThemeMode(m)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionRow(stringResource(R.string.appearance_accent_color), accent != null) { Appearance.setAccent(null) }
        SwatchRow(AccentSwatches, accent) { Appearance.setAccent(it) }

        Spacer(Modifier.height(10.dp))
        SectionRow(stringResource(R.string.appearance_border_color), border != null) {
            showHue = false
            Appearance.setBorder(null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SwatchRow(BorderSwatches, border) { showHue = false; Appearance.setBorder(it) }
            TextButton(onClick = { showHue = !showHue }) { Text(stringResource(R.string.common_custom)) }
        }
        if (showHue) {
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    Appearance.setBorder(Color.hsv(it, 0.55f, 0.9f))
                },
                valueRange = 0f..360f
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingSliderRow(
            title = stringResource(R.string.appearance_corner_radius),
            valueText = stringResource(R.string.appearance_corner_radius_val, Appearance.cornerRadius.toInt()),
            explanation = stringResource(R.string.appearance_corner_radius_desc),
            value = Appearance.cornerRadius,
            valueRange = 0f..28f,
            onValueChange = { Appearance.setCornerRadius(it) }
        )
        Spacer(Modifier.height(6.dp))
        SettingSliderRow(
            title = stringResource(R.string.appearance_transparency),
            valueText = "${(Appearance.transparency * 100).toInt()}%",
            explanation = stringResource(R.string.appearance_transparency_desc),
            value = Appearance.transparency,
            valueRange = 0f..0.7f,
            onValueChange = { Appearance.setTransparency(it) }
        )
        Spacer(Modifier.height(6.dp))
        SettingSwitchRow(
            title = stringResource(R.string.appearance_liquid_glass),
            explanation = stringResource(R.string.appearance_liquid_glass_desc),
            checked = Appearance.liquidGlass,
            onCheckedChange = { Appearance.setLiquidGlass(it) }
        )

        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.appearance_bg_image), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.appearance_choose_image)) }
            TextButton(onClick = { Appearance.clearBackground() }, enabled = Appearance.background != null) {
                Text(stringResource(R.string.common_remove))
            }
        }
        if (Appearance.background != null) {
            Spacer(Modifier.height(6.dp))
            SettingSliderRow(
                title = stringResource(R.string.appearance_bg_dimming),
                valueText = "${(Appearance.dim * 100).toInt()}%",
                explanation = stringResource(R.string.appearance_bg_dimming_desc),
                value = Appearance.dim,
                valueRange = 0.3f..0.95f,
                onValueChange = { Appearance.setDim(it) }
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showHue = false; Appearance.resetAll() }) {
            Text(stringResource(R.string.appearance_reset_all))
        }
    }
}
