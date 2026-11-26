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

package com.example.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.AfterburnState
import com.example.model.BoostStrength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AfterburnTransitionEvent(
    val packageName: String,
    val prevState: AfterburnState,
    val newState: AfterburnState,
    val strength: BoostStrength,
    val toastEnabled: Boolean,
    val timestampMs: Long,
    /** True only when [packageName] is a configured game AND is the foreground app right now. */
    val inSelectedGame: Boolean
)

/**
 * Pure policy for deciding when to display the Afterburn Active pill toast.
 * Shows once per boost session for a selected game, with >= 20s debounce, on transitions
 * into [AfterburnState.BOOSTING], plus one ending toast when BOOSTING drops to REDUCED or
 * BLOCKED while still in that game. Never shows outside a selected, foreground game.
 */
object AfterburnToastPolicy {
    const val DEFAULT_DEBOUNCE_MS = 20_000L

    data class State(
        val lastToastMs: Long = Long.MIN_VALUE / 2,
        val activeSessionPkg: String? = null,
        val endedToastShown: Boolean = false
    )

    /**
     * v3.7.1: the pill is shown for any app Afterburn is allowed to boost: the foreground package must be in the
     * Games list or in the Boost apps list. Afterburn itself only ever looks at the Boost list, so a Boost-only
     * app used to be boosted without any pill.
     */
    fun isBoostedAppForeground(
        eventPkg: String,
        gamePackages: Set<String>,
        boostPackages: Set<String>,
        foregroundPkg: String
    ): Boolean = eventPkg.isNotBlank() && eventPkg == foregroundPkg &&
        (eventPkg in gamePackages || eventPkg in boostPackages)

    /** Strict game-only gate: a configured game that is also the current foreground package (Games list only). */
    fun isSelectedGameForeground(
        eventPkg: String,
        gamePackages: Set<String>,
        foregroundPkg: String
    ): Boolean = eventPkg.isNotBlank() && eventPkg in gamePackages && eventPkg == foregroundPkg

    /** BOOSTING dropping to a state where the boost is reduced or blocked (not IDLE/OFF, which mean "left the game"). */
    fun isEndingTransition(event: AfterburnTransitionEvent): Boolean =
        event.prevState == AfterburnState.BOOSTING && (
            event.newState == AfterburnState.REDUCED ||
                event.newState == AfterburnState.BLOCKED_THERMAL ||
                event.newState == AfterburnState.BLOCKED_BATTERY
            )

    fun evaluate(
        current: State,
        event: AfterburnTransitionEvent,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS
    ): Pair<Boolean, State> {
        val nextSessionPkg = when {
            event.newState == AfterburnState.OFF || event.newState == AfterburnState.IDLE -> null
            current.activeSessionPkg != null && current.activeSessionPkg != event.packageName -> null
            else -> current.activeSessionPkg
        }

        val endedShown = if (nextSessionPkg == null) false else current.endedToastShown
        val unchanged = current.copy(activeSessionPkg = nextSessionPkg, endedToastShown = endedShown)

        if (!event.toastEnabled || event.packageName.isBlank() || !event.inSelectedGame) {
            return false to unchanged
        }
        if (isEndingTransition(event)) {
            // Only for a session whose start toast was shown, and only once per session.
            val show = nextSessionPkg == event.packageName && !endedShown
            return if (show) {
                true to current.copy(
                    lastToastMs = event.timestampMs,
                    activeSessionPkg = nextSessionPkg,
                    endedToastShown = true
                )
            } else {
                false to unchanged
            }
        }
        if (event.newState != AfterburnState.BOOSTING || event.prevState == AfterburnState.BOOSTING) {
            return false to unchanged
        }
        if (nextSessionPkg == event.packageName) {
            return false to unchanged
        }
        if (event.timestampMs - current.lastToastMs < debounceMs) {
            return false to unchanged
        }

        return true to State(
            lastToastMs = event.timestampMs,
            activeSessionPkg = event.packageName
        )
    }
}

/**
 * Renders the ~2.5s floating neon glass pill when Afterburn engages for a selected app.
 */
class AfterburnToastController(
    context: Context,
    private val scope: CoroutineScope,
    private val contextWrapper: (Context) -> Context = { it }
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val labelCache = ConcurrentHashMap<String, String>()

    private var policyState = AfterburnToastPolicy.State()
    private var currentView: View? = null
    private var dismissJob: Job? = null

    fun onEvent(event: AfterburnTransitionEvent) {
        val (show, nextState) = AfterburnToastPolicy.evaluate(policyState, event)
        policyState = nextState
        if (!show) return
        if (!Settings.canDrawOverlays(appContext)) return

        scope.launch {
            val appLabel = resolveAppLabel(event.packageName)
            showOverlayPill(appLabel, event.strength, if (AfterburnToastPolicy.isEndingTransition(event)) event.newState else null)
        }
    }

    fun dismissNow() {
        dismissJob?.cancel()
        dismissJob = null
        removeViewSafe(currentView)
        currentView = null
    }

    private suspend fun resolveAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val resolved = withContext(Dispatchers.IO) {
            try {
                val pm = appContext.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            } catch (_: Throwable) {
                packageName
            }
        }
        if (labelCache.size > 128) labelCache.clear()
        labelCache[packageName] = resolved
        return resolved
    }

    /** [endState] is null for the "boost started" pill, or the REDUCED/BLOCKED state for the ending pill. */
    private fun showOverlayPill(appLabel: String, strength: BoostStrength, endState: AfterburnState? = null) {
        if (!Settings.canDrawOverlays(appContext)) return
        dismissJob?.cancel()
        removeViewSafe(currentView)

        val localizedCtx = contextWrapper(appContext)
        val density = localizedCtx.resources.displayMetrics.density
        val monoTypeface = try {
            ResourcesCompat.getFont(localizedCtx, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        } catch (_: Throwable) {
            Typeface.MONOSPACE
        }

        val strengthText = when (strength) {
            BoostStrength.LIGHT -> localizedCtx.getString(R.string.boost_strength_light)
            BoostStrength.MEDIUM -> localizedCtx.getString(R.string.boost_strength_medium)
            BoostStrength.MAX -> localizedCtx.getString(R.string.boost_strength_max)
        }
        val headlineText: String
        val subText: String
        when (endState) {
            null -> {
                headlineText = localizedCtx.getString(R.string.afterburn_toast_title)
                subText = localizedCtx.getString(R.string.afterburn_toast_subtitle, appLabel, strengthText)
            }
            AfterburnState.REDUCED -> {
                headlineText = localizedCtx.getString(R.string.afterburn_toast_reduced_title)
                subText = localizedCtx.getString(R.string.afterburn_toast_reduced_subtitle, appLabel)
            }
            AfterburnState.BLOCKED_BATTERY -> {
                headlineText = localizedCtx.getString(R.string.afterburn_toast_stopped_title)
                subText = localizedCtx.getString(R.string.afterburn_toast_stopped_battery_subtitle, appLabel)
            }
            else -> {
                headlineText = localizedCtx.getString(R.string.afterburn_toast_stopped_title)
                subText = localizedCtx.getString(R.string.afterburn_toast_stopped_heat_subtitle, appLabel)
            }
        }

        val container = NeonGlassPillLayout(localizedCtx, density).apply {
            val hPad = (16f * density).toInt()
            val vPad = (10f * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = localizedCtx.resources.configuration.layoutDirection

            val icon = ImageView(localizedCtx).apply {
                val d: Drawable? = ContextCompat.getDrawable(localizedCtx, R.drawable.ic_afterburn_flame)
                setImageDrawable(d)
                val sz = (20f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (10f * density).toInt()
                }
                contentDescription = null
            }

            val textCol = LinearLayout(localizedCtx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(localizedCtx).apply {
                text = headlineText
                typeface = Typeface.create(monoTypeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(0xFF7DD3FC.toInt())
                letterSpacing = 0.08f
                maxLines = 1
            }

            val subView = TextView(localizedCtx).apply {
                text = subText
                typeface = monoTypeface
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(0xFFE5E7EB.toInt())
                maxLines = 1
            }

            textCol.addView(titleView)
            textCol.addView(subView)
            addView(icon)
            addView(textCol)
        }

        val topMarginPx = computeTopOffsetPx(density)
        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topMarginPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        container.alpha = 0f
        container.translationY = -16f * density

        try {
            windowManager.addView(container, params)
            currentView = container
        } catch (_: Throwable) {
            return
        }

        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        dismissJob = scope.launch {
            delay(2350L)
            val v = currentView ?: return@launch
            v.animate()
                .alpha(0f)
                .translationY(-10f * density)
                .setDuration(220L)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeViewSafe(v)
                        if (currentView === v) currentView = null
                    }
                })
                .start()
        }
    }

    private fun computeTopOffsetPx(density: Float): Int {
        var insetTop = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                )
                insetTop = insets.top
            } catch (_: Throwable) {
            }
        } else {
            val resId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) insetTop = appContext.resources.getDimensionPixelSize(resId)
        }
        return insetTop + (14f * density).toInt()
    }

    private fun removeViewSafe(view: View?) {
        if (view == null) return
        try {
            view.animate().cancel()
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        } catch (_: Throwable) {
        }
    }

    private class NeonGlassPillLayout(
        context: Context,
        private val density: Float
    ) : LinearLayout(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE80F141C.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        private val rect = RectF()
        private val borderRect = RectF()

        init {
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                borderPaint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0xCC38BDF8.toInt(), 0xAA818CF8.toInt(), 0xCCFB923C.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            val half = borderPaint.strokeWidth / 2f
            borderRect.set(half, half, w - half, h - half)
            canvas.drawRoundRect(borderRect, (radius - half).coerceAtLeast(0f), (radius - half).coerceAtLeast(0f), borderPaint)
            super.onDraw(canvas)
        }
    }
}
