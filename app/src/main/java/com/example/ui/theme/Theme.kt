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

package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class GovernorExtendedColors(
    val optimal: Color,
    val optimalContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val throttle: Color,
    val throttleContainer: Color,
    val gpuAccent: Color,
    val chartGrid: Color
)

val LocalExtendedColors = staticCompositionLocalOf {
    GovernorExtendedColors(
        optimal = EmeraldAccent,
        optimalContainer = EmeraldMuted,
        warning = AmberAccent,
        warningContainer = AmberMuted,
        throttle = CoralThrottle,
        throttleContainer = CoralMuted,
        gpuAccent = AmberAccent,
        chartGrid = HairlineBorder
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = ObsCanvas,
    primaryContainer = CyanMuted,
    onPrimaryContainer = TextPrimaryDark,
    secondary = AmberAccent,
    onSecondary = ObsCanvas,
    secondaryContainer = AmberMuted,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = EmeraldAccent,
    onTertiary = ObsCanvas,
    tertiaryContainer = EmeraldMuted,
    onTertiaryContainer = TextPrimaryDark,
    error = CoralThrottle,
    onError = ObsCanvas,
    errorContainer = CoralMuted,
    onErrorContainer = TextPrimaryDark,
    background = ObsCanvas,
    onBackground = TextPrimaryDark,
    surface = CarbonSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CarbonSurfaceHigh,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = CarbonSurface,
    surfaceContainer = CarbonSurfaceHigh,
    surfaceContainerHigh = CarbonSurfaceHighest,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder
)

private val LightColorScheme = lightColorScheme(
    primary = CyanDark,
    onPrimary = Color.White,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = TextPrimaryLight,
    secondary = AmberDark,
    onSecondary = Color.White,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = EmeraldDark,
    onTertiary = Color.White,
    error = CoralDark,
    onError = Color.White,
    background = LabCanvas,
    onBackground = TextPrimaryLight,
    surface = LabSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LabSurfaceHigh,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLow = LabSurface,
    surfaceContainer = LabSurfaceHigh,
    surfaceContainerHigh = LabSurfaceHigh,
    outline = LabBorder,
    outlineVariant = LabBorder
)

@Composable
fun CoreGovernorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val border = Appearance.borderColor
    val accent = Appearance.accent
    val mode = Appearance.themeMode
    val dark = when (mode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        else -> true
    }
    val surfaceAlpha = 1f - Appearance.transparency
    var colorScheme = if (dark) DarkColorScheme else LightColorScheme
    if (mode == ThemeMode.AMOLED) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color(0xFF0A0A0A),
            surfaceVariant = Color(0xFF121212),
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1A1A1A)
        )
    }
    if (accent != null) {
        val a = if (dark) accent else Color(
            red = accent.red * 0.55f, green = accent.green * 0.55f, blue = accent.blue * 0.55f
        )
        colorScheme = colorScheme.copy(
            primary = a,
            onPrimary = if (dark) Color(0xFF0E0F11) else Color.White,
            primaryContainer = androidx.compose.ui.graphics.lerp(colorScheme.surface, a, 0.28f)
        )
    }
    if (border != null) {
        colorScheme = colorScheme.copy(outline = border, outlineVariant = border)
    }
    if (surfaceAlpha < 1f) {
        // See-through surfaces (transparency control); lets a background picture show through.
        colorScheme = colorScheme.copy(
            surface = colorScheme.surface.copy(alpha = surfaceAlpha),
            surfaceVariant = colorScheme.surfaceVariant.copy(alpha = surfaceAlpha),
            surfaceContainerLow = colorScheme.surfaceContainerLow.copy(alpha = surfaceAlpha),
            surfaceContainer = colorScheme.surfaceContainer.copy(alpha = surfaceAlpha),
            surfaceContainerHigh = colorScheme.surfaceContainerHigh.copy(alpha = surfaceAlpha)
        )
    }
    val r = Appearance.cornerRadius.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r * 0.3f),
        small = RoundedCornerShape(r * 0.6f),
        medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r * 1.2f),
        extraLarge = RoundedCornerShape(r * 1.6f)
    )
    val baseExtended = if (dark) {
        GovernorExtendedColors(
            optimal = EmeraldAccent,
            optimalContainer = EmeraldMuted,
            warning = AmberAccent,
            warningContainer = AmberMuted,
            throttle = CoralThrottle,
            throttleContainer = CoralMuted,
            gpuAccent = AmberAccent,
            chartGrid = HairlineBorder
        )
    } else {
        GovernorExtendedColors(
            optimal = EmeraldDark,
            optimalContainer = Color(0xFFD6F5E3),
            warning = AmberDark,
            warningContainer = AmberContainerLight,
            throttle = CoralDark,
            throttleContainer = Color(0xFFFFDAD6),
            gpuAccent = AmberDark,
            chartGrid = LabBorder
        )
    }

    val extended = if (border != null) baseExtended.copy(chartGrid = border) else baseExtended

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = shapes,
            content = content
        )
    }
}
