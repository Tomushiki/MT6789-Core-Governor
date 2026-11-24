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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.example.R
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Purely visual user choices: border color and a custom background image.
 * Nothing here touches the governor; it only feeds the theme.
 */
enum class ThemeMode(val label: String, @StringRes val labelRes: Int) {
    SYSTEM("System", R.string.theme_system),
    DARK("Dark", R.string.theme_dark),
    LIGHT("Light", R.string.theme_light),
    AMOLED("AMOLED", R.string.theme_amoled)
}

object Appearance {
    private const val PREFS = "coregovernor_ui"
    private const val KEY_BORDER = "ap_border"
    private const val KEY_DIM = "ap_dim"
    private const val KEY_THEME = "ap_theme"
    private const val KEY_ACCENT = "ap_accent"
    private const val KEY_RADIUS = "ap_radius"
    private const val KEY_TRANS = "ap_trans"
    private const val KEY_GLASS = "ap_glass"
    private const val FILE_NAME = "appearance_background.jpg"
    private const val MAX_SIDE = 1600

    /** null = default border color of the theme. */
    private var _borderColor by mutableStateOf<Color?>(null)
    val borderColor: Color? get() = _borderColor

    /** null = plain background. */
    private var _background by mutableStateOf<ImageBitmap?>(null)
    val background: ImageBitmap? get() = _background

    /** How strongly the picture is darkened (0.3 .. 0.95). */
    private var _dim by mutableStateOf<Float>(0.7f)
    val dim: Float get() = _dim

    private var _themeMode by mutableStateOf<ThemeMode>(ThemeMode.SYSTEM)
    val themeMode: ThemeMode get() = _themeMode

    /** null = the theme's own accent color. */
    private var _accent by mutableStateOf<Color?>(null)
    val accent: Color? get() = _accent

    /** Corner radius of cards and other surfaces, in dp (0 .. 28). */
    private var _cornerRadius by mutableStateOf<Float>(14f)
    val cornerRadius: Float get() = _cornerRadius

    /** How see-through cards and panels are (0 = solid, 0.7 = very transparent). */
    private var _transparency by mutableStateOf<Float>(0f)
    val transparency: Float get() = _transparency

    /** Frosted "liquid glass" look for cards. */
    private var _liquidGlass by mutableStateOf<Boolean>(false)
    val liquidGlass: Boolean get() = _liquidGlass

    private var appContext: Context? = null

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BORDER)) _borderColor = Color(prefs.getInt(KEY_BORDER, 0))
        _dim = prefs.getFloat(KEY_DIM, 0.7f).coerceIn(0.3f, 0.95f)
        _themeMode = ThemeMode.values().getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM }
        if (prefs.contains(KEY_ACCENT)) _accent = Color(prefs.getInt(KEY_ACCENT, 0))
        _cornerRadius = prefs.getFloat(KEY_RADIUS, 14f).coerceIn(0f, 28f)
        _transparency = prefs.getFloat(KEY_TRANS, 0f).coerceIn(0f, 0.7f)
        _liquidGlass = prefs.getBoolean(KEY_GLASS, false)
        val f = File(app.filesDir, FILE_NAME)
        if (f.exists()) {
            thread(name = "appearance-load") {
                try {
                    val bmp = decode { f.inputStream() }
                    if (bmp != null) _background = bmp.asImageBitmap()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun setBorder(color: Color?) {
        _borderColor = color
        val ctx = appContext ?: return
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (color == null) e.remove(KEY_BORDER) else e.putInt(KEY_BORDER, color.toArgb())
        e.apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode = mode
        prefs()?.edit()?.putInt(KEY_THEME, mode.ordinal)?.apply()
    }

    fun setAccent(color: Color?) {
        _accent = color
        val e = prefs()?.edit() ?: return
        if (color == null) e.remove(KEY_ACCENT) else e.putInt(KEY_ACCENT, color.toArgb())
        e.apply()
    }

    fun setCornerRadius(value: Float) {
        _cornerRadius = value.coerceIn(0f, 28f)
        prefs()?.edit()?.putFloat(KEY_RADIUS, cornerRadius)?.apply()
    }

    fun setTransparency(value: Float) {
        _transparency = value.coerceIn(0f, 0.7f)
        prefs()?.edit()?.putFloat(KEY_TRANS, transparency)?.apply()
    }

    fun setLiquidGlass(on: Boolean) {
        _liquidGlass = on
        prefs()?.edit()?.putBoolean(KEY_GLASS, on)?.apply()
    }

    /** Back to the plain minimalistic defaults (also removes the background picture). */
    fun resetAll() {
        setThemeMode(ThemeMode.SYSTEM)
        setAccent(null)
        setBorder(null)
        setCornerRadius(14f)
        setTransparency(0f)
        setLiquidGlass(false)
        setDim(0.7f)
        clearBackground()
    }

    fun setDim(value: Float) {
        _dim = value.coerceIn(0.3f, 0.95f)
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_DIM, dim).apply()
    }

    /** Copies the picked image (downscaled) into private storage so it survives deletion of the original. */
    fun setBackground(context: Context, uri: Uri) {
        val app = context.applicationContext
        thread(name = "appearance-pick") {
            try {
                var bmp = decode { app.contentResolver.openInputStream(uri) } ?: return@thread
                val rot = try {
                    app.contentResolver.openInputStream(uri)?.use { s ->
                        when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                } catch (_: Throwable) {
                    0f
                }
                if (rot != 0f) {
                    val m = Matrix().apply { postRotate(rot) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                File(app.filesDir, FILE_NAME).outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                _background = bmp.asImageBitmap()
                val pr = prefs()
                if (pr != null && !pr.contains(KEY_TRANS)) setTransparency(0.2f)
            } catch (_: Throwable) {
                // Silent fallback: keep whatever background was there before.
            }
        }
    }

    fun clearBackground() {
        _background = null
        val ctx = appContext ?: return
        try {
            File(ctx.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    private fun decode(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
