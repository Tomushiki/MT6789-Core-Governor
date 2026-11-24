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

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.example.R
import com.example.data.LocaleHelper
import com.example.governor.GovernorEngine
import com.example.model.BatteryState
import com.example.model.GovernorConfig
import com.example.model.LogLevel
import com.example.model.GovernorMode
import com.example.model.overlayCompactScaleFor
import com.example.model.overlayExpandedScaleFor
import com.example.model.overlayPosPm
import com.example.model.withOverlayPosPm
import com.example.model.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class OverlayStyle {
    COMPACT,
    EXPANDED,
    DETAILED
}

object OverlaySizing {
    const val MIN_SCALE_PCT = 25
    const val MAX_SCALE_PCT = 200
    const val DEFAULT_DETAILED_ROW_COUNT = 13

    data class OverlayStrings(
        val tempLabel: String = "TEMP",
        val powerLabel: String = "POWER",
        val pwrShortLabel: String = "PWR",
        val chgLabel: String = "CHG",
        val plugLabel: String = "PLUG 0.0",
        val wattsLabel: String = "watts",
        val fullLabel: String = "full",
        val ofHzFormat: String = "of %1\$dHz",
        val naLabel: String = "n/a"
    ) {
        fun formatOfHz(hz: Int): String = String.format(Locale.US, ofHzFormat, hz)

        companion object {
            fun fromContext(context: Context): OverlayStrings {
                return OverlayStrings(
                    tempLabel = context.getString(R.string.overlay_temp),
                    powerLabel = context.getString(R.string.overlay_power),
                    pwrShortLabel = context.getString(R.string.overlay_pwr_short),
                    chgLabel = context.getString(R.string.overlay_chg),
                    plugLabel = context.getString(R.string.overlay_plug),
                    wattsLabel = context.getString(R.string.overlay_watts),
                    fullLabel = context.getString(R.string.overlay_full),
                    ofHzFormat = context.getString(R.string.overlay_of_hz),
                    naLabel = context.getString(R.string.common_na)
                )
            }
        }
    }

    private val measureLock = Any()
    private val compactLabelPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    }
    private val compactTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    }
    private val ringLabelPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    }
    private val ringSubPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    }
    private val detailedTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    }
    private val detailedBoldPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    /** Pure: orders the physical sides as (width, height) for the given orientation. */
    fun orderScreenSides(sideA: Int, sideB: Int, landscape: Boolean): Pair<Int, Int> {
        val longSide = max(sideA, sideB)
        val shortSide = min(sideA, sideB)
        return if (landscape) longSide to shortSide else shortSide to longSide
    }

    /**
     * Single source of truth for the overlay's screen size and orientation. Display.rotation can lag
     * or be wrong on some builds (GSI), so the orientation comes from the system configuration, the
     * same value the Compose UI uses, and only the rotation-independent long/short sides come from
     * the display. Use the second value of this pair's ordering as "landscape = width > height".
     */
    fun getScreenSizePx(windowManager: WindowManager): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val landscape = when (android.content.res.Resources.getSystem().configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        }
        return orderScreenSides(dm.widthPixels, dm.heightPixels, landscape)
    }

    fun measureNaturalSizeAtScaleOne(
        density: Float,
        style: OverlayStyle,
        detailedRowCount: Int = DEFAULT_DETAILED_ROW_COUNT,
        extraRightColWidthAt1: Float = 0f,
        strings: OverlayStrings = OverlayStrings()
    ): Pair<Float, Float> {
        val d = density.coerceAtLeast(0.5f)
        synchronized(measureLock) {
            return when (style) {
                OverlayStyle.COMPACT -> {
                    val hPad = 14f * d
                    val vPad = 8f * d
                    val dotR = 3.5f * d
                    val dotGap = 4f * d
                    val labelGap = 3f * d
                    val itemGap = 10f * d
                    compactLabelPaint.textSize = 9f * d
                    compactTextPaint.textSize = 11.5f * d
                    val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText("100%")
                    val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText("100%")
                    val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText("100°")
                    val fpsItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("FPS") + labelGap + compactTextPaint.measureText("240")
                    val powerItemW = maxOf(
                        compactTextPaint.measureText("-10.00W"),
                        compactTextPaint.measureText("\u221210.00W"),
                        compactTextPaint.measureText("\u26A1+10.00W"),
                        compactTextPaint.measureText(strings.plugLabel)
                    )
                    val fm = compactTextPaint.fontMetrics
                    val textH = fm.descent - fm.ascent
                    val w = hPad * 2f + cpuItemW + itemGap + gpuItemW + itemGap + fpsItemW + itemGap + tempItemW + itemGap + powerItemW
                    val h = vPad * 2f + textH
                    w to h
                }
                OverlayStyle.EXPANDED -> {
                    val ringSize = 48f * d
                    val hPad = 12f * d
                    val vPad = 12f * d
                    val cellGap = 8f * d
                    val labelGap = 6f * d
                    val subGap = 2f * d
                    ringLabelPaint.textSize = 10f * d
                    ringSubPaint.textSize = 9.5f * d
                    val widestLabel = maxOf(
                        ringLabelPaint.measureText("CPU"),
                        ringLabelPaint.measureText("GPU"),
                        ringLabelPaint.measureText(strings.tempLabel),
                        ringLabelPaint.measureText("FPS"),
                        ringLabelPaint.measureText(strings.powerLabel),
                        ringLabelPaint.measureText(strings.chgLabel),
                        ringLabelPaint.measureText(strings.plugLabel)
                    )
                    val cellWidth = maxOf(ringSize, widestLabel)
                    val labelFm = ringLabelPaint.fontMetrics
                    val subFm = ringSubPaint.fontMetrics
                    val labelH = labelFm.descent - labelFm.ascent
                    val subH = subFm.descent - subFm.ascent
                    val w = hPad * 2f + cellWidth * 5f + cellGap * 4f
                    val h = vPad * 2f + ringSize + labelGap + labelH + subGap + subH
                    w to h
                }
                OverlayStyle.DETAILED -> {
                    val hPad = 14f * d
                    val vPad = 14f * d
                    val leftColW = 78f * d
                    val colGap = 16f * d
                    ringSubPaint.textSize = 12f * d
                    detailedTextPaint.textSize = 14.5f * d
                    detailedBoldPaint.textSize = 14.5f * d
                    val refRightW = maxOf(
                        detailedTextPaint.measureText("DDR  4266 MHz"),
                        detailedBoldPaint.measureText("#0~5  2000MHz"),
                        detailedTextPaint.measureText("100%  2000M"),
                        detailedTextPaint.measureText("FPS  120.0"),
                        detailedTextPaint.measureText("${strings.pwrShortLabel}  -10.00W"),
                        detailedTextPaint.measureText("${strings.pwrShortLabel}  \u221210.00W"),
                        detailedTextPaint.measureText("${strings.pwrShortLabel}  \u26A1+10.00W ${strings.chgLabel}"),
                        detailedTextPaint.measureText("${strings.pwrShortLabel}  ${strings.plugLabel}")
                    )
                    val rightW = maxOf(refRightW, extraRightColWidthAt1)
                    val subFm = ringSubPaint.fontMetrics
                    val subH = subFm.descent - subFm.ascent
                    val slotH = 66f * d + 4f * d + subH
                    val minLeftColH = 3f * slotH + 2f * (12f * d)
                    val rightColH = detailedRowCount.coerceAtLeast(1) * (14.5f * d * 1.5f)
                    val contentH = maxOf(minLeftColH, rightColH)
                    val w = hPad * 2f + leftColW + colGap + rightW
                    val h = vPad * 2f + contentH
                    w to h
                }
            }
        }
    }

    fun maxScalePct(
        screenW: Int,
        screenH: Int,
        style: OverlayStyle,
        naturalWAt1: Float,
        naturalHAt1: Float
    ): Int {
        if (screenW <= 0 || screenH <= 0) return MIN_SCALE_PCT
        val wFactor = 0.85f
        val hFactor = 0.50f
        val allowedW = screenW.toFloat() * wFactor
        val allowedH = screenH.toFloat() * hFactor
        val ratio = minOf(
            allowedW / naturalWAt1.coerceAtLeast(1f),
            allowedH / naturalHAt1.coerceAtLeast(1f)
        )
        return floor((ratio * 100f).toDouble()).toInt().coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT)
    }

    fun computeWindowOriginPx(
        screenW: Int,
        screenH: Int,
        viewW: Int,
        viewH: Int,
        xPm: Int,
        yPm: Int
    ): Pair<Int, Int> {
        val freeW = max(0, screenW - viewW)
        val freeH = max(0, screenH - viewH)
        val x = (xPm.coerceIn(0, 1000) / 1000f * freeW).roundToInt().coerceIn(0, freeW)
        val y = (yPm.coerceIn(0, 1000) / 1000f * freeH).roundToInt().coerceIn(0, freeH)
        return x to y
    }
}

class OverlayController(
    context: Context,
    private val engine: GovernorEngine,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenInteractive = MutableStateFlow(true)
    private var visibilityJob: Job? = null
    private var telemetryJob: Job? = null

    private var overlayView: OverlayPillView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isDragging = false
    private var lastAppliedXPm: Int = -1
    private var lastAppliedYPm: Int = -1
    private var overlayStrings: OverlaySizing.OverlayStrings =
        OverlaySizing.OverlayStrings.fromContext(LocaleHelper.wrap(appContext))

    fun refreshLocale() {
        overlayStrings = OverlaySizing.OverlayStrings.fromContext(LocaleHelper.wrap(appContext))
        val view = overlayView ?: return
        view.setStrings(overlayStrings)
        repositionForCurrentScreen()
    }

    // Re-applies the saved position for the new orientation after a rotation.
    private val rotationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Window metrics settle a moment after the callback, so apply twice.
            mainHandler.removeCallbacks(repositionRunnable)
            mainHandler.postDelayed(repositionRunnable, 150L)
            mainHandler.postDelayed(repositionRunnable, 600L)
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {}
    }
    private var rotationRegistered = false
    private val repositionRunnable = Runnable { repositionForCurrentScreen() }

    private fun repositionForCurrentScreen() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (isDragging) return
        view.refreshScaleForCurrentOrientation()
        view.requestLayout()
        applyPermillePosition(view, params, engine.config.value)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private var lastAppliedDisplay: DisplayData? = null
    private var lastApplyTimeMs: Long = 0L

    private data class CoreDisplay(
        val loadPct: Int,
        val effMhz: Long
    )

    private data class ClusterDisplay(
        val label: String,
        val freqMhz: Long,
        val cores: List<CoreDisplay>
    )

    private data class DisplayData(
        val cpuLoadPct: Int = 0,
        val cpuFreqMhz: Long = 0L,
        val gpuLoadPct: Int = 0,
        val gpuFreqMhz: Long = 0L,
        val tempDeg: Int = 0,
        val tempC: Float = 0f,
        val wattsCenti: Int = 0,
        val watts: Float = 0f,
        val chargeWattsCenti: Int = 0,
        val chargeWatts: Float = 0f,
        val batteryState: BatteryState = BatteryState.UNKNOWN,
        val ddrMhz: Long = 0L,
        val fps: Float = -1f,
        val refreshHz: Int = 60,
        val clusters: List<ClusterDisplay> = emptyList()
    )

    fun start() {
        if (visibilityJob?.isActive == true) return
        if (!rotationRegistered) {
            appContext.registerComponentCallbacks(rotationCallbacks)
            rotationRegistered = true
        }
        visibilityJob = scope.launch(Dispatchers.Main.immediate) {
            combine(
                engine.config,
                engine.telemetry.map { it.activeMode }.distinctUntilChanged(),
                screenInteractive
            ) { cfg, activeMode, interactive ->
                Triple(cfg, activeMode, interactive)
            }.collect { (cfg, activeMode, interactive) ->
                val canDraw = Settings.canDrawOverlays(appContext)
                val modeAllowed = !cfg.overlayGameOnly || activeMode == GovernorMode.GAME
                val shouldShow = cfg.overlayEnabled && canDraw && interactive && modeAllowed
                if (shouldShow) {
                    showOrUpdate(cfg)
                } else {
                    hide()
                }
            }
        }
    }

    fun setScreenInteractive(interactive: Boolean) {
        screenInteractive.value = interactive
        if (!interactive) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                hide()
            } else {
                mainHandler.post { hide() }
            }
        }
    }

    fun stop() {
        visibilityJob?.cancel()
        visibilityJob = null
        if (rotationRegistered) {
            try { appContext.unregisterComponentCallbacks(rotationCallbacks) } catch (_: Throwable) {}
            rotationRegistered = false
        }
        mainHandler.removeCallbacks(repositionRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hide()
        } else {
            mainHandler.post { hide() }
        }
    }

    private fun showOrUpdate(cfg: GovernorConfig) {
        val existingView = overlayView
        val existingParams = layoutParams
        val targetFlags = buildFlags(cfg.overlayClickThrough)
        val targetAlpha = cfg.overlayOpacityPct.coerceIn(30, 100) / 100f

        if (existingView == null || existingParams == null) {
            val view = OverlayPillView(appContext, overlayStrings)
            view.setExpanded(cfg.overlayExpanded && !cfg.overlayDetailed)
            view.setDetailed(cfg.overlayDetailed)
            view.setScales(
                compactPortraitPct = cfg.overlayCompactScaleFor(false),
                expandedPortraitPct = cfg.overlayExpandedScaleFor(false),
                compactLandscapePct = cfg.overlayCompactScaleFor(true),
                expandedLandscapePct = cfg.overlayExpandedScaleFor(true)
            )
            view.setClickThrough(cfg.overlayClickThrough)
            view.alpha = targetAlpha
            view.setBgOpacity(cfg.overlayBgOpacityPct)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                targetFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                @Suppress("RtlHardcoded")
                gravity = Gravity.TOP or Gravity.LEFT
                // Let the overlay reach the status bar / notch area in both orientations.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val initialData = toDisplayData(engine.telemetry.value)
            lastAppliedDisplay = initialData
            lastApplyTimeMs = System.currentTimeMillis()
            view.setDisplayData(initialData)
            applyPermillePosition(view, params, cfg)

            try {
                windowManager.addView(view, params)
                overlayView = view
                layoutParams = params
                startTelemetryCollection()
            } catch (_: Throwable) {
                overlayView = null
                layoutParams = null
            }
        } else {
            var layoutChanged = false
            var needsReposition = false
            existingView.alpha = targetAlpha
            existingView.setBgOpacity(cfg.overlayBgOpacityPct)
            existingView.setClickThrough(cfg.overlayClickThrough)
            val effectiveExpanded = cfg.overlayExpanded && !cfg.overlayDetailed
            if (existingView.isExpandedMode() != effectiveExpanded || existingView.isDetailedMode() != cfg.overlayDetailed) {
                existingView.setExpanded(effectiveExpanded)
                existingView.setDetailed(cfg.overlayDetailed)
                layoutChanged = true
                needsReposition = true
            }
            if (existingView.setScales(
                    compactPortraitPct = cfg.overlayCompactScaleFor(false),
                    expandedPortraitPct = cfg.overlayExpandedScaleFor(false),
                    compactLandscapePct = cfg.overlayCompactScaleFor(true),
                    expandedLandscapePct = cfg.overlayExpandedScaleFor(true)
                )
            ) {
                layoutChanged = true
                needsReposition = true
            }
            if (existingParams.flags != targetFlags) {
                existingParams.flags = targetFlags
                layoutChanged = true
            }
            if (!isDragging) {
                val (xPm, yPm) = currentPosPm(cfg)
                if (needsReposition || xPm != lastAppliedXPm || yPm != lastAppliedYPm) {
                    applyPermillePosition(existingView, existingParams, cfg)
                    layoutChanged = true
                }
            }
            if (layoutChanged) {
                try {
                    windowManager.updateViewLayout(existingView, existingParams)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun isLandscapeNow(): Boolean {
        val (w, h) = getScreenSize()
        return w > h
    }

    private fun currentPosPm(cfg: GovernorConfig): Pair<Int, Int> =
        cfg.overlayPosPm(isLandscapeNow())

    private fun getScreenSize(): Pair<Int, Int> =
        OverlaySizing.getScreenSizePx(windowManager)

    private fun getMeasuredViewSize(view: View, screenW: Int, screenH: Int): Pair<Int, Int> {
        if (view.measuredWidth <= 0 || view.measuredHeight <= 0 || view.isLayoutRequested) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
            )
        }
        return view.measuredWidth to view.measuredHeight
    }

    private fun applyPermillePosition(
        view: View,
        params: WindowManager.LayoutParams,
        cfg: GovernorConfig
    ) {
        val (xPm, yPm) = currentPosPm(cfg)
        val (screenW, screenH) = getScreenSize()
        val (viewW, viewH) = getMeasuredViewSize(view, screenW, screenH)
        val (x, y) = OverlaySizing.computeWindowOriginPx(screenW, screenH, viewW, viewH, xPm, yPm)
        params.x = x
        params.y = y
        lastAppliedXPm = xPm
        lastAppliedYPm = yPm
        engine.log(
            LogLevel.INFO,
            "Overlay",
            "Place: screen ${screenW}x$screenH, overlay ${viewW}x$viewH, pos $xPm/$yPm pm -> $x,$y px"
        )
    }

    private fun hide() {
        telemetryJob?.cancel()
        telemetryJob = null
        val view = overlayView
        overlayView = null
        layoutParams = null
        isDragging = false
        lastAppliedXPm = -1
        lastAppliedYPm = -1
        lastAppliedDisplay = null
        lastApplyTimeMs = 0L
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
    }

    private fun startTelemetryCollection() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch(Dispatchers.Main.immediate) {
            engine.telemetry.collect { state ->
                val view = overlayView ?: return@collect
                val now = System.currentTimeMillis()
                if (lastAppliedDisplay != null && now - lastApplyTimeMs < 1000L) {
                    return@collect
                }
                val next = toDisplayData(state)
                lastApplyTimeMs = now
                if (hasDisplayedValuesChanged(lastAppliedDisplay, next)) {
                    lastAppliedDisplay = next
                    if (view.setDisplayData(next)) {
                        repositionForCurrentScreen()
                    }
                }
            }
        }
    }

    private fun buildFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (clickThrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun toDisplayData(state: TelemetryState): DisplayData {
        val cpuLoad = state.totalCpuLoadPct.roundToInt().coerceIn(0, 100)
        val cpuFreqMhz = (state.clusters.maxOfOrNull { it.actualFreqKhz } ?: 0L) / 1000L
        val gpuLoad = state.gpu.smoothedLoadPct.roundToInt().coerceIn(0, 100)
        val gpuFreqMhz = state.gpu.currentFreqKhz / 1000L
        val tempDeg = state.temperatureC.roundToInt()
        val wattsCenti = (state.watts * 100f).roundToInt()
        @Suppress("DEPRECATION")
        val refreshHz = try {
            windowManager.defaultDisplay?.refreshRate?.roundToInt()?.takeIf { it > 0 } ?: 60
        } catch (_: Throwable) {
            60
        }
        val clusters = if (engine.config.value.overlayDetailed) {
            state.clusters.map { c ->
                val sortedCpus = c.relatedCpus.sorted()
                val label = when {
                    sortedCpus.isEmpty() -> "#?"
                    sortedCpus.size == 1 -> "#${sortedCpus.first()}"
                    else -> "#${sortedCpus.first()}~${sortedCpus.last()}"
                }
                val freqMhz = c.actualFreqKhz / 1000L
                val cores = sortedCpus.map { cpuId ->
                    val coreLoad = (state.coreLoadsPct[cpuId] ?: 0f).roundToInt().coerceIn(0, 100)
                    val effMhz = ((coreLoad / 100f) * (c.actualFreqKhz / 1000f)).roundToLong()
                    CoreDisplay(loadPct = coreLoad, effMhz = effMhz)
                }
                ClusterDisplay(label = label, freqMhz = freqMhz, cores = cores)
            }
        } else {
            emptyList()
        }
        return DisplayData(
            cpuLoadPct = cpuLoad,
            cpuFreqMhz = cpuFreqMhz,
            gpuLoadPct = gpuLoad,
            gpuFreqMhz = gpuFreqMhz,
            tempDeg = tempDeg,
            tempC = state.temperatureC,
            wattsCenti = wattsCenti,
            watts = state.watts,
            chargeWattsCenti = (state.chargeWatts * 100f).roundToInt(),
            chargeWatts = state.chargeWatts,
            batteryState = state.batteryState,
            ddrMhz = state.ddrFreqMhz,
            fps = state.fps,
            refreshHz = refreshHz,
            clusters = clusters
        )
    }

    private fun hasDisplayedValuesChanged(prev: DisplayData?, next: DisplayData): Boolean {
        if (prev == null) return true
        val prevFpsTenth = if (prev.fps < 0f) -1 else (prev.fps * 10f).roundToInt()
        val nextFpsTenth = if (next.fps < 0f) -1 else (next.fps * 10f).roundToInt()
        return prev.cpuLoadPct != next.cpuLoadPct ||
            prev.cpuFreqMhz != next.cpuFreqMhz ||
            prev.gpuLoadPct != next.gpuLoadPct ||
            prev.gpuFreqMhz != next.gpuFreqMhz ||
            prev.tempDeg != next.tempDeg ||
            prev.wattsCenti != next.wattsCenti ||
            prev.chargeWattsCenti != next.chargeWattsCenti ||
            prev.batteryState != next.batteryState ||
            prev.ddrMhz != next.ddrMhz ||
            prevFpsTenth != nextFpsTenth ||
            prev.refreshHz != next.refreshHz ||
            prev.clusters != next.clusters
    }

    private inner class OverlayPillView(
        context: Context,
        private var strings: OverlaySizing.OverlayStrings
    ) : View(context) {

        private val baseDensity = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var data = DisplayData()

        fun setStrings(next: OverlaySizing.OverlayStrings) {
            if (strings != next) {
                strings = next
                requestLayout()
                invalidate()
            }
        }
        private var expanded = false
        private var detailed = false
        private var clickThrough = false
        private var compactScalePctP = 100
        private var expandedScalePctP = 100
        private var compactScalePctL = 100
        private var expandedScalePctL = 100
        private var effectiveScale = 1f

        private var downRawX = 0f
        private var downRawY = 0f
        private var downWinX = 0
        private var downWinY = 0

        private val bgColor = 0xFF16171A.toInt()
        private val borderColor = 0x1AFFFFFF
        private val trackColor = 0xFF34353A.toInt()
        private val greenColor = 0xFF4ADE80.toInt()
        private val orangeColor = 0xFFFB923C.toInt()
        private val redColor = 0xFFEF4444.toInt()
        private val blueColor = 0xFF60A5FA.toInt()
        private val textPrimaryColor = 0xFFF3F4F6.toInt()
        private val textSecondaryColor = 0xFF9CA3AF.toInt()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = trackColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val compactLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondaryColor
            typeface = Typeface.MONOSPACE
        }
        private val compactTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimaryColor
            typeface = Typeface.MONOSPACE
        }
        private val ringCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimaryColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimaryColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        private val ringSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondaryColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        private val monoRegularTypeface = Typeface.MONOSPACE
        private val monoBoldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        private val detailedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoRegularTypeface
        }
        private val detailedBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoBoldTypeface
        }

        private val pillRect = RectF()
        private val borderRect = RectF()
        private val arcRect = RectF()

        init {
            applyPaintSizes(1f)
        }

        private fun requestedScalePct(): Int {
            val land = isLandscapeNow()
            val pct = if (expanded || detailed) {
                if (land) expandedScalePctL else expandedScalePctP
            } else {
                if (land) compactScalePctL else compactScalePctP
            }
            return pct.coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
        }

        private fun requestedScale(): Float = requestedScalePct() / 100f

        private fun effDensityFor(s: Float): Float = baseDensity * s

        private fun applyPaintSizes(s: Float) {
            val d = effDensityFor(s)
            borderPaint.strokeWidth = 1f * d
            val ringStroke = if (detailed) 6f * d else 4f * d
            trackPaint.strokeWidth = ringStroke
            arcPaint.strokeWidth = ringStroke
            compactLabelPaint.textSize = 9f * d
            compactTextPaint.textSize = 11.5f * d
            ringCenterPaint.textSize = if (detailed) 16.5f * d else 11.5f * d
            ringLabelPaint.textSize = 10f * d
            ringSubPaint.textSize = if (detailed) 12f * d else 9.5f * d
            detailedTextPaint.textSize = 14.5f * d
            detailedBoldPaint.textSize = 14.5f * d
        }

        fun isExpandedMode(): Boolean = expanded

        fun isDetailedMode(): Boolean = detailed

        fun setExpanded(value: Boolean) {
            if (expanded != value) {
                expanded = value
                effectiveScale = requestedScale()
                applyPaintSizes(effectiveScale)
                requestLayout()
                invalidate()
            }
        }

        fun setDetailed(value: Boolean) {
            if (detailed != value) {
                detailed = value
                effectiveScale = requestedScale()
                applyPaintSizes(effectiveScale)
                requestLayout()
                invalidate()
            }
        }

        fun refreshScaleForCurrentOrientation(): Boolean {
            val nextRequested = requestedScale()
            if (abs(nextRequested - effectiveScale) > 0.0001f) {
                effectiveScale = nextRequested
                applyPaintSizes(effectiveScale)
                requestLayout()
                invalidate()
                return true
            }
            return false
        }

        fun setScales(
            compactPortraitPct: Int,
            expandedPortraitPct: Int,
            compactLandscapePct: Int,
            expandedLandscapePct: Int
        ): Boolean {
            compactScalePctP = compactPortraitPct.coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
            expandedScalePctP = expandedPortraitPct.coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
            compactScalePctL = compactLandscapePct.coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
            expandedScalePctL = expandedLandscapePct.coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
            return refreshScaleForCurrentOrientation()
        }

        fun setClickThrough(value: Boolean) {
            clickThrough = value
        }

        fun setBgOpacity(pct: Int) {
            val target = (255 * pct.coerceIn(0, 100)) / 100
            if (bgPaint.alpha != target) {
                bgPaint.alpha = target
                invalidate()
            }
        }

        fun setDisplayData(next: DisplayData): Boolean {
            val structureChanged = detailed && (
                data.clusters.size != next.clusters.size ||
                    data.clusters.sumOf { it.cores.size } != next.clusters.sumOf { it.cores.size } ||
                    (data.ddrMhz == 0L) != (next.ddrMhz == 0L)
                )
            data = next
            if (structureChanged) {
                requestLayout()
            }
            invalidate()
            return structureChanged
        }

        private fun computeExpandedCellWidth(d: Float): Float {
            val ringSize = 48f * d
            val widestLabel = maxOf(
                ringLabelPaint.measureText("CPU"),
                ringLabelPaint.measureText("GPU"),
                ringLabelPaint.measureText(strings.tempLabel),
                ringLabelPaint.measureText("FPS"),
                ringLabelPaint.measureText(strings.powerLabel),
                ringLabelPaint.measureText(strings.chgLabel),
                ringLabelPaint.measureText(strings.plugLabel)
            )
            return maxOf(ringSize, widestLabel)
        }

        private fun detailedRowStrings(dData: DisplayData): Pair<List<String>, List<String>> {
            val regularRows = mutableListOf<String>()
            val boldRows = mutableListOf<String>()
            val ddrVal = if (dData.ddrMhz == 0L) "--" else "${dData.ddrMhz} MHz"
            regularRows.add("DDR  $ddrVal")
            for (c in dData.clusters) {
                boldRows.add("${c.label}  ${c.freqMhz}MHz")
                for (core in c.cores) {
                    val pctCol = String.format(Locale.US, "%3d%%", core.loadPct)
                    regularRows.add("$pctCol  ${core.effMhz}M")
                }
            }
            val fpsVal = if (dData.fps < 0f) "--" else String.format(Locale.US, "%.1f", dData.fps)
            regularRows.add("FPS  $fpsVal")
            val pwrVal = when (dData.batteryState) {
                BatteryState.CHARGING -> "\u26A1+${String.format(Locale.US, "%.2f", dData.chargeWatts)}W ${strings.chgLabel}"
                BatteryState.FULL_PLUGGED -> strings.plugLabel
                BatteryState.DISCHARGING -> "${formatSignedWatts(if (dData.watts > 0.005f) -dData.watts else 0f)}W"
                BatteryState.UNKNOWN -> "--W"
            }
            regularRows.add("${strings.pwrShortLabel}  $pwrVal")
            return regularRows to boldRows
        }

        private fun measureDetailedRightColWidth(): Float {
            val (regularRows, boldRows) = detailedRowStrings(data)
            var maxW = maxOf(
                detailedTextPaint.measureText("DDR  4266 MHz"),
                detailedBoldPaint.measureText("#0~5  2000MHz"),
                detailedTextPaint.measureText("100%  2000M"),
                detailedTextPaint.measureText("FPS  120.0"),
                detailedTextPaint.measureText("${strings.pwrShortLabel}  -10.00W"),
                detailedTextPaint.measureText("${strings.pwrShortLabel}  \u221210.00W"),
                detailedTextPaint.measureText("${strings.pwrShortLabel}  \u26A1+10.00W ${strings.chgLabel}"),
                detailedTextPaint.measureText("${strings.pwrShortLabel}  ${strings.plugLabel}")
            )
            for (r in regularRows) {
                maxW = maxOf(maxW, detailedTextPaint.measureText(r))
            }
            for (r in boldRows) {
                maxW = maxOf(maxW, detailedBoldPaint.measureText(r))
            }
            return maxW
        }

        private fun detailedRowCount(dData: DisplayData): Int =
            3 + dData.clusters.sumOf { 1 + it.cores.size }

        private fun measureDetailedContentHeight(d: Float): Float {
            val subFm = ringSubPaint.fontMetrics
            val subH = subFm.descent - subFm.ascent
            val slotH = 66f * d + 4f * d + subH
            val minLeftColH = 3f * slotH + 2f * (12f * d)
            val rightColH = detailedRowCount(data) * (14.5f * d * 1.5f)
            return maxOf(minLeftColH, rightColH)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val (screenW, screenH) = getScreenSize()
            val style = when {
                detailed -> OverlayStyle.DETAILED
                expanded -> OverlayStyle.EXPANDED
                else -> OverlayStyle.COMPACT
            }
            val extraRightWAt1 = if (detailed) {
                applyPaintSizes(1f)
                measureDetailedRightColWidth()
            } else {
                0f
            }
            val (natWAt1, natHAt1) = OverlaySizing.measureNaturalSizeAtScaleOne(
                density = baseDensity,
                style = style,
                detailedRowCount = detailedRowCount(data),
                extraRightColWidthAt1 = extraRightWAt1,
                strings = strings
            )
            val maxPct = OverlaySizing.maxScalePct(screenW, screenH, style, natWAt1, natHAt1)
            val effectivePct = minOf(requestedScalePct(), maxPct)
                .coerceIn(OverlaySizing.MIN_SCALE_PCT, OverlaySizing.MAX_SCALE_PCT)
            val clampedS = effectivePct / 100f
            effectiveScale = clampedS
            applyPaintSizes(clampedS)
            val d = effDensityFor(clampedS)

            if (detailed) {
                val hPad = 14f * d
                val vPad = 14f * d
                val leftColW = 78f * d
                val colGap = 16f * d
                val contentH = measureDetailedContentHeight(d)

                val width = ceil((hPad * 2f + leftColW + colGap + measureDetailedRightColWidth()).toDouble()).toInt()
                val height = ceil((vPad * 2f + contentH).toDouble()).toInt()
                setMeasuredDimension(width, height)
                return
            }

            if (expanded) {
                val ringSize = 48f * d
                val hPad = 12f * d
                val vPad = 12f * d
                val cellGap = 8f * d
                val cellWidth = computeExpandedCellWidth(d)
                val labelGap = 6f * d
                val subGap = 2f * d

                val labelFm = ringLabelPaint.fontMetrics
                val subFm = ringSubPaint.fontMetrics
                val labelH = labelFm.descent - labelFm.ascent
                val subH = subFm.descent - subFm.ascent

                val width = ceil((hPad * 2f + cellWidth * 5f + cellGap * 4f).toDouble()).toInt()
                val height = ceil((vPad * 2f + ringSize + labelGap + labelH + subGap + subH).toDouble()).toInt()
                setMeasuredDimension(width, height)
            } else {
                val hPad = 14f * d
                val vPad = 8f * d
                val dotR = 3.5f * d
                val dotGap = 4f * d
                val labelGap = 3f * d
                val itemGap = 10f * d

                val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText("100%")
                val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText("100%")
                val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText("100°")
                val fpsItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("FPS") + labelGap + compactTextPaint.measureText("240")
                val powerItemW = maxOf(
                    compactTextPaint.measureText("-10.00W"),
                    compactTextPaint.measureText("\u221210.00W"),
                    compactTextPaint.measureText("\u26A1+10.00W"),
                    compactTextPaint.measureText(strings.plugLabel)
                )

                val contentW = cpuItemW + itemGap + gpuItemW + itemGap + fpsItemW + itemGap + tempItemW + itemGap + powerItemW
                val fm = compactTextPaint.fontMetrics
                val textH = fm.descent - fm.ascent

                val width = ceil((hPad * 2f + contentW).toDouble()).toInt()
                val height = ceil((vPad * 2f + textH).toDouble()).toInt()
                setMeasuredDimension(width, height)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = effDensityFor(effectiveScale)
            pillRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val corner = when {
                detailed -> 16f * d
                expanded -> 20f * d
                else -> height / 2f
            }
            canvas.drawRoundRect(pillRect, corner, corner, bgPaint)

            val halfBorder = borderPaint.strokeWidth / 2f
            borderRect.set(halfBorder, halfBorder, width - halfBorder, height - halfBorder)
            val borderCorner = (corner - halfBorder).coerceAtLeast(0f)
            canvas.drawRoundRect(borderRect, borderCorner, borderCorner, borderPaint)

            when {
                detailed -> drawDetailed(canvas, d)
                expanded -> drawExpanded(canvas, d)
                else -> drawCompact(canvas, d)
            }
        }

        private fun drawCompact(canvas: Canvas, d: Float) {
            val dotR = 3.5f * d
            val dotGap = 4f * d
            val labelGap = 3f * d
            val itemGap = 10f * d
            val centerY = height / 2f
            val valFm = compactTextPaint.fontMetrics
            val valBaselineY = centerY - (valFm.ascent + valFm.descent) / 2f
            val lblFm = compactLabelPaint.fontMetrics
            val lblBaselineY = centerY - (lblFm.ascent + lblFm.descent) / 2f

            val cpuVal = "${data.cpuLoadPct}%"
            val gpuVal = "${data.gpuLoadPct}%"
            val fpsVal = if (data.fps < 0f) "--" else data.fps.roundToInt().toString()
            val tempVal = "${data.tempDeg}°"
            val (powerVal, powerColor) = when (data.batteryState) {
                BatteryState.CHARGING -> "\u26A1+${String.format(Locale.US, "%.2f", data.chargeWatts)}W" to greenColor
                BatteryState.FULL_PLUGGED -> strings.plugLabel to greenColor
                BatteryState.DISCHARGING -> "${formatSignedWatts(if (data.watts > 0.005f) -data.watts else 0f)}W" to blueColor
                BatteryState.UNKNOWN -> "--W" to textSecondaryColor
            }

            val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText(cpuVal)
            val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText(gpuVal)
            val fpsItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("FPS") + labelGap + compactTextPaint.measureText(fpsVal)
            val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText(tempVal)
            val powerItemW = compactTextPaint.measureText(powerVal)
            val totalContentW = cpuItemW + itemGap + gpuItemW + itemGap + fpsItemW + itemGap + tempItemW + itemGap + powerItemW

            var x = (width - totalContentW) / 2f

            fun drawLabeledDotItem(label: String, value: String, dotColor: Int) {
                dotPaint.color = dotColor
                canvas.drawCircle(x + dotR, centerY, dotR, dotPaint)
                x += dotR * 2f + dotGap
                canvas.drawText(label, x, lblBaselineY, compactLabelPaint)
                x += compactLabelPaint.measureText(label) + labelGap
                compactTextPaint.color = textPrimaryColor
                canvas.drawText(value, x, valBaselineY, compactTextPaint)
                x += compactTextPaint.measureText(value) + itemGap
            }

            drawLabeledDotItem("CPU", cpuVal, loadColor(data.cpuLoadPct))
            drawLabeledDotItem("GPU", gpuVal, loadColor(data.gpuLoadPct))
            drawLabeledDotItem("FPS", fpsVal, fpsColor(data.fps, data.refreshHz))

            dotPaint.color = tempColor(data.tempC)
            canvas.drawCircle(x + dotR, centerY, dotR, dotPaint)
            x += dotR * 2f + dotGap
            compactTextPaint.color = textPrimaryColor
            canvas.drawText(tempVal, x, valBaselineY, compactTextPaint)
            x += compactTextPaint.measureText(tempVal) + itemGap

            compactTextPaint.color = powerColor
            canvas.drawText(powerVal, x, valBaselineY, compactTextPaint)
        }

        private fun drawExpanded(canvas: Canvas, d: Float) {
            val ringSize = 48f * d
            val stroke = 4f * d
            val hPad = 12f * d
            val vPad = 12f * d
            val cellGap = 8f * d
            val cellWidth = computeExpandedCellWidth(d)
            val labelGap = 6f * d
            val subGap = 2f * d

            val baseCenterSize = 11.5f * d
            val baseLabelSize = 10f * d
            val baseSubSize = 9.5f * d

            ringLabelPaint.textSize = baseLabelSize
            val baseLabelFm = ringLabelPaint.fontMetrics
            val baseLabelH = baseLabelFm.descent - baseLabelFm.ascent

            val innerDiameter = (ringSize - 2f * stroke).coerceAtLeast(1f)
            val maxCenterW = innerDiameter * 0.8f

            fun drawRingCell(
                index: Int,
                fraction: Float,
                ringColor: Int,
                centerText: String,
                label: String,
                subText: String,
                centerColor: Int = textPrimaryColor,
                labelColor: Int = textPrimaryColor
            ) {
                val cellLeft = hPad + index * (cellWidth + cellGap)
                val cx = cellLeft + cellWidth / 2f
                val ringTop = vPad
                val ringCy = ringTop + ringSize / 2f
                val inset = stroke / 2f

                arcRect.set(
                    cx - ringSize / 2f + inset,
                    ringTop + inset,
                    cx + ringSize / 2f - inset,
                    ringTop + ringSize - inset
                )
                canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
                val sweep = fraction.coerceIn(0f, 1f) * 360f
                if (sweep > 0f) {
                    arcPaint.color = ringColor
                    canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
                }

                ringCenterPaint.color = centerColor
                ringCenterPaint.textSize = baseCenterSize
                val centerW = ringCenterPaint.measureText(centerText)
                if (centerW > maxCenterW && centerW > 0f) {
                    ringCenterPaint.textSize = baseCenterSize * (maxCenterW / centerW)
                }
                val centerFm = ringCenterPaint.fontMetrics
                val centerBaseline = ringCy - (centerFm.ascent + centerFm.descent) / 2f
                canvas.drawText(centerText, cx, centerBaseline, ringCenterPaint)
                ringCenterPaint.color = textPrimaryColor

                ringLabelPaint.color = labelColor
                ringLabelPaint.textSize = baseLabelSize
                val labelW = ringLabelPaint.measureText(label)
                if (labelW > cellWidth && labelW > 0f) {
                    ringLabelPaint.textSize = baseLabelSize * (cellWidth / labelW)
                }
                val labelFm = ringLabelPaint.fontMetrics
                val labelBaseline = ringTop + ringSize + labelGap - labelFm.ascent
                canvas.drawText(label, cx, labelBaseline, ringLabelPaint)
                ringLabelPaint.color = textPrimaryColor

                if (subText.isNotEmpty()) {
                    ringSubPaint.textSize = baseSubSize
                    val subW = ringSubPaint.measureText(subText)
                    if (subW > cellWidth && subW > 0f) {
                        ringSubPaint.textSize = baseSubSize * (cellWidth / subW)
                    }
                    val subFm = ringSubPaint.fontMetrics
                    val subBaseline = ringTop + ringSize + labelGap + baseLabelH + subGap - subFm.ascent
                    canvas.drawText(subText, cx, subBaseline, ringSubPaint)
                }
            }

            drawRingCell(
                index = 0,
                fraction = data.cpuLoadPct / 100f,
                ringColor = loadColor(data.cpuLoadPct),
                centerText = "${data.cpuLoadPct}%",
                label = "CPU",
                subText = "${data.cpuFreqMhz}MHz"
            )
            drawRingCell(
                index = 1,
                fraction = data.gpuLoadPct / 100f,
                ringColor = loadColor(data.gpuLoadPct),
                centerText = "${data.gpuLoadPct}%",
                label = "GPU",
                subText = "${data.gpuFreqMhz}MHz"
            )
            drawRingCell(
                index = 2,
                fraction = (data.tempC - 30f) / 50f,
                ringColor = tempColor(data.tempC),
                centerText = "${data.tempDeg}°",
                label = strings.tempLabel,
                subText = "\u00b0C"
            )
            drawRingCell(
                index = 3,
                fraction = if (data.fps < 0f) 0f else (data.fps / data.refreshHz.coerceAtLeast(1)).coerceIn(0f, 1f),
                ringColor = fpsColor(data.fps, data.refreshHz),
                centerText = if (data.fps < 0f) "--" else data.fps.roundToInt().toString(),
                label = "FPS",
                subText = strings.formatOfHz(data.refreshHz)
            )
            when (data.batteryState) {
                BatteryState.CHARGING -> drawRingCell(
                    index = 4,
                    fraction = data.chargeWatts / 15f,
                    ringColor = greenColor,
                    centerText = "\u26A1+${String.format(Locale.US, "%.1f", data.chargeWatts)}",
                    label = strings.chgLabel,
                    subText = strings.wattsLabel,
                    centerColor = greenColor,
                    labelColor = greenColor
                )
                BatteryState.FULL_PLUGGED -> drawRingCell(
                    index = 4,
                    fraction = 1f,
                    ringColor = greenColor,
                    centerText = "0.0",
                    label = strings.plugLabel,
                    subText = strings.fullLabel,
                    centerColor = greenColor,
                    labelColor = greenColor
                )
                BatteryState.DISCHARGING -> drawRingCell(
                    index = 4,
                    fraction = abs(data.watts) / 10f,
                    ringColor = blueColor,
                    centerText = formatSignedWatts(if (data.watts > 0.005f) -data.watts else 0f),
                    label = strings.powerLabel,
                    subText = strings.wattsLabel
                )
                BatteryState.UNKNOWN -> drawRingCell(
                    index = 4,
                    fraction = 0f,
                    ringColor = textSecondaryColor,
                    centerText = "--",
                    label = strings.powerLabel,
                    subText = strings.naLabel,
                    centerColor = textSecondaryColor
                )
            }
        }

        private fun drawDetailed(canvas: Canvas, d: Float) {
            val hPad = 14f * d
            val vPad = 14f * d
            val leftColW = 78f * d
            val colGap = 16f * d
            val ringSize = 66f * d
            val stroke = 6f * d
            val subGap = 4f * d
            val minRingGap = 12f * d

            val baseCenterSize = 16.5f * d
            val baseSubSize = 12f * d
            ringSubPaint.textSize = baseSubSize
            val subFm = ringSubPaint.fontMetrics
            val subH = subFm.descent - subFm.ascent

            val textSize = 14.5f * d
            val lineHeight = textSize * 1.5f
            val rowCount = detailedRowCount(data)
            val rightColH = rowCount * lineHeight

            val slotHeight = ringSize + subGap + subH
            val minLeftColH = 3f * slotHeight + 2f * minRingGap
            val contentH = maxOf(minLeftColH, rightColH)
            val ringGap = ((contentH - 3f * slotHeight) / 2f).coerceAtLeast(minRingGap)
            val rightTop = vPad + ((contentH - rightColH) / 2f).coerceAtLeast(0f)

            val innerDiameter = (ringSize - 2f * stroke).coerceAtLeast(1f)
            val maxCenterW = innerDiameter * 0.8f
            val cx = hPad + leftColW / 2f

            fun drawStackedRing(
                slot: Int,
                fraction: Float,
                ringColor: Int,
                centerText: String,
                subText: String
            ) {
                val ringTop = vPad + slot * (slotHeight + ringGap)
                val ringCy = ringTop + ringSize / 2f
                val inset = stroke / 2f

                arcRect.set(
                    cx - ringSize / 2f + inset,
                    ringTop + inset,
                    cx + ringSize / 2f - inset,
                    ringTop + ringSize - inset
                )
                canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
                val sweep = fraction.coerceIn(0f, 1f) * 360f
                if (sweep > 0f) {
                    arcPaint.color = ringColor
                    canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
                }

                ringCenterPaint.textSize = baseCenterSize
                val centerW = ringCenterPaint.measureText(centerText)
                if (centerW > maxCenterW && centerW > 0f) {
                    ringCenterPaint.textSize = baseCenterSize * (maxCenterW / centerW)
                }
                val centerFm = ringCenterPaint.fontMetrics
                val centerBaseline = ringCy - (centerFm.ascent + centerFm.descent) / 2f
                canvas.drawText(centerText, cx, centerBaseline, ringCenterPaint)

                ringSubPaint.textSize = baseSubSize
                val subW = ringSubPaint.measureText(subText)
                if (subW > leftColW && subW > 0f) {
                    ringSubPaint.textSize = baseSubSize * (leftColW / subW)
                }
                val sFm = ringSubPaint.fontMetrics
                val subBaseline = ringTop + ringSize + subGap - sFm.ascent
                canvas.drawText(subText, cx, subBaseline, ringSubPaint)
            }

            drawStackedRing(
                slot = 0,
                fraction = data.cpuLoadPct / 100f,
                ringColor = loadColor(data.cpuLoadPct),
                centerText = "${data.cpuLoadPct}%",
                subText = "${data.cpuFreqMhz}M"
            )
            drawStackedRing(
                slot = 1,
                fraction = data.gpuLoadPct / 100f,
                ringColor = loadColor(data.gpuLoadPct),
                centerText = "${data.gpuLoadPct}%",
                subText = "${data.gpuFreqMhz}M"
            )
            drawStackedRing(
                slot = 2,
                fraction = (data.tempC - 30f) / 50f,
                ringColor = tempColor(data.tempC),
                centerText = "${data.tempDeg}°",
                subText = "${strings.tempLabel} \u00b0C"
            )

            val rightLeft = hPad + leftColW + colGap
            detailedTextPaint.textSize = textSize
            detailedBoldPaint.textSize = textSize
            val fm = detailedTextPaint.fontMetrics
            val grayColor = 0xFFAAAAAA.toInt()
            val whiteColor = 0xFFFFFFFF.toInt()

            var rowIdx = 0
            fun rowBaseline(): Float {
                val top = rightTop + rowIdx * lineHeight
                rowIdx++
                return top + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
            }

            fun drawLabelValueRow(label: String, value: String, valueColor: Int) {
                val y = rowBaseline()
                detailedTextPaint.color = grayColor
                canvas.drawText(label, rightLeft, y, detailedTextPaint)
                val labelW = detailedTextPaint.measureText(label)
                detailedTextPaint.color = valueColor
                canvas.drawText(value, rightLeft + labelW, y, detailedTextPaint)
            }

            val ddrValue = if (data.ddrMhz == 0L) "--" else "${data.ddrMhz} MHz"
            drawLabelValueRow("DDR  ", ddrValue, whiteColor)

            for (cluster in data.clusters) {
                val headerY = rowBaseline()
                detailedBoldPaint.color = whiteColor
                canvas.drawText("${cluster.label}  ${cluster.freqMhz}MHz", rightLeft, headerY, detailedBoldPaint)

                for (core in cluster.cores) {
                    val coreY = rowBaseline()
                    val pctCol = String.format(Locale.US, "%3d%%", core.loadPct)
                    detailedTextPaint.color = grayColor
                    canvas.drawText("$pctCol  ${core.effMhz}M", rightLeft, coreY, detailedTextPaint)
                }
            }

            val fpsStr = if (data.fps < 0f) "--" else String.format(Locale.US, "%.1f", data.fps)
            drawLabelValueRow("FPS  ", fpsStr, fpsColor(data.fps, data.refreshHz))
            val (pwrStr, pwrColor) = when (data.batteryState) {
                BatteryState.CHARGING -> "\u26A1+${String.format(Locale.US, "%.2f", data.chargeWatts)}W ${strings.chgLabel}" to greenColor
                BatteryState.FULL_PLUGGED -> strings.plugLabel to greenColor
                BatteryState.DISCHARGING -> "${formatSignedWatts(if (data.watts > 0.005f) -data.watts else 0f)}W" to whiteColor
                BatteryState.UNKNOWN -> "--W" to grayColor
            }
            drawLabelValueRow("${strings.pwrShortLabel}  ", pwrStr, pwrColor)
        }

        private fun fpsColor(fps: Float, refreshHz: Int): Int = when {
            fps < 0f -> 0xFF888888.toInt()
            fps >= 0.85f * refreshHz -> greenColor
            fps >= 0.5f * refreshHz -> orangeColor
            else -> redColor
        }

        private fun loadColor(pct: Int): Int = when {
            pct < 60 -> greenColor
            pct <= 85 -> orangeColor
            else -> redColor
        }

        private fun tempColor(tempC: Float): Int = when {
            tempC < 50f -> greenColor
            tempC <= 70f -> orangeColor
            else -> redColor
        }

        private fun formatSignedWatts(watts: Float): String {
            val rounded = (watts * 100f).roundToInt() / 100f
            val normalized = if (rounded == -0.0f) 0.0f else rounded
            val formatted = String.format(Locale.US, "%+.2f", normalized)
            return formatted.replace('-', '\u2212')
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (clickThrough) return false
            val params = this@OverlayController.layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWinX = params.x
                    downWinY = params.y
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val (screenW, screenH) = getScreenSize()
                        val (viewW, viewH) = getMeasuredViewSize(this, screenW, screenH)
                        val maxX = max(0, screenW - viewW)
                        val maxY = max(0, screenH - viewH)
                        params.x = (downWinX + dx).roundToInt().coerceIn(0, maxX)
                        params.y = (downWinY + dy).roundToInt().coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                        val (screenW, screenH) = getScreenSize()
                        val (viewW, viewH) = getMeasuredViewSize(this, screenW, screenH)
                        val freeW = max(0, screenW - viewW)
                        val freeH = max(0, screenH - viewH)
                        var xPm = if (freeW <= 0) 0 else (params.x * 1000f / max(1, freeW)).roundToInt().coerceIn(0, 1000)
                        var yPm = if (freeH <= 0) 0 else (params.y * 1000f / max(1, freeH)).roundToInt().coerceIn(0, 1000)
                        xPm = when {
                            xPm <= 8 -> 0
                            xPm >= 992 -> 1000
                            abs(xPm - 500) <= 8 -> 500
                            else -> xPm
                        }
                        yPm = when {
                            yPm <= 8 -> 0
                            yPm >= 992 -> 1000
                            else -> yPm
                        }
                        val (finalX, finalY) = OverlaySizing.computeWindowOriginPx(
                            screenW, screenH, viewW, viewH, xPm, yPm
                        )
                        params.x = finalX
                        params.y = finalY
                        lastAppliedXPm = xPm
                        lastAppliedYPm = yPm
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (_: Throwable) {
                        }
                        val land = screenW > screenH
                        engine.log(
                            LogLevel.INFO,
                            "Overlay",
                            "Drag end: screen ${screenW}x$screenH, overlay ${viewW}x$viewH, raw ${params.x},${params.y} -> $xPm/$yPm pm (${if (land) "landscape" else "portrait"})"
                        )
                        engine.updateConfig {
                            // Save into the slot for the orientation the user dragged in.
                            it.withOverlayPosPm(land, xPm, yPm)
                        }
                    } else {
                        engine.updateConfig {
                            when {
                                it.overlayDetailed -> it.copy(overlayExpanded = false, overlayDetailed = false)
                                it.overlayExpanded -> it.copy(overlayExpanded = false, overlayDetailed = true)
                                else -> it.copy(overlayExpanded = true, overlayDetailed = false)
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
