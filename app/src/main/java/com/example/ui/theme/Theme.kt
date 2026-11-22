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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) {
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

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
