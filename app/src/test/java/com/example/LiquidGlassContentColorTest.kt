package com.example

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.components.EcuCard
import com.example.ui.theme.Appearance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Content inside a liquid-glass EcuCard must resolve to the theme's onSurface, not default black. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiquidGlassContentColorTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun reset() = Appearance.setLiquidGlass(false)

    private fun resolve(scheme: ColorScheme): Color {
        Appearance.setLiquidGlass(true)
        var resolved = Color.Unspecified
        rule.setContent {
            MaterialTheme(colorScheme = scheme) {
                EcuCard { Probe { resolved = it } }
            }
        }
        rule.waitForIdle()
        return resolved
    }

    @Composable
    private fun Probe(report: (Color) -> Unit) {
        report(LocalContentColor.current)
    }

    @Test
    fun darkSchemeUsesOnSurface() {
        val scheme = darkColorScheme()
        assertEquals(scheme.onSurface, resolve(scheme))
    }

    @Test
    fun lightSchemeUsesOnSurface() {
        val scheme = lightColorScheme()
        assertEquals(scheme.onSurface, resolve(scheme))
    }
}
