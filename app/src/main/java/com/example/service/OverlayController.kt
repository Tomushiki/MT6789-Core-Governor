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
import android.content.Context
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
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.example.governor.GovernorEngine
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
        val ddrMhz: Long = 0L,
        val fps: Float = -1f,
        val refreshHz: Int = 60,
        val clusters: List<ClusterDisplay> = emptyList()
    )

    fun start() {
        if (visibilityJob?.isActive == true) return
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
            val view = OverlayPillView(appContext)
            view.setExpanded(cfg.overlayExpanded && !cfg.overlayDetailed)
            view.setDetailed(cfg.overlayDetailed)
            view.setScales(cfg.overlayCompactScalePct, cfg.overlayExpandedScalePct)
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
                gravity = Gravity.TOP or Gravity.START
            }
            applyPermillePosition(view, params, cfg)

            val initialData = toDisplayData(engine.telemetry.value)
            lastAppliedDisplay = initialData
            lastApplyTimeMs = System.currentTimeMillis()
            view.setDisplayData(initialData)

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
            if (existingView.setScales(cfg.overlayCompactScalePct, cfg.overlayExpandedScalePct)) {
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

    private fun currentPosPm(cfg: GovernorConfig): Pair<Int, Int> {
        val (xPm, yPm) = when {
            cfg.overlayDetailed -> cfg.overlayDetailedXPm to cfg.overlayDetailedYPm
            cfg.overlayExpanded -> cfg.overlayExpandedXPm to cfg.overlayExpandedYPm
            else -> cfg.overlayCompactXPm to cfg.overlayCompactYPm
        }
        return xPm.coerceIn(0, 1000) to yPm.coerceIn(0, 1000)
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

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
        params.x = (xPm / 1000f * max(0, screenW - viewW)).roundToInt()
        params.y = (yPm / 1000f * max(0, screenH - viewH)).roundToInt()
        lastAppliedXPm = xPm
        lastAppliedYPm = yPm
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
                    view.setDisplayData(next)
                }
            }
        }
    }

    private fun buildFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
            prev.ddrMhz != next.ddrMhz ||
            prevFpsTenth != nextFpsTenth ||
            prev.refreshHz != next.refreshHz ||
            prev.clusters != next.clusters
    }

    private inner class OverlayPillView(context: Context) : View(context) {

        private val baseDensity = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var data = DisplayData()
        private var expanded = false
        private var detailed = false
        private var clickThrough = false
        private var compactScalePct = 100
        private var expandedScalePct = 100
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

        private fun requestedScale(): Float {
            val pct = if (expanded || detailed) expandedScalePct else compactScalePct
            return pct.coerceIn(40, 400) / 100f
        }

        private fun effDensityFor(s: Float): Float = baseDensity * s

        private fun applyPaintSizes(s: Float) {
            val d = effDensityFor(s)
            borderPaint.strokeWidth = 1f * d
            trackPaint.strokeWidth = 4f * d
            arcPaint.strokeWidth = 4f * d
            compactLabelPaint.textSize = 9f * d
            compactTextPaint.textSize = 11.5f * d
            ringCenterPaint.textSize = 11.5f * d
            ringLabelPaint.textSize = 10f * d
            ringSubPaint.textSize = 9.5f * d
            detailedTextPaint.textSize = 12f * d
            detailedBoldPaint.textSize = 12f * d
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

        fun setScales(compactPct: Int, expandedPct: Int): Boolean {
            val clampedCompact = compactPct.coerceIn(40, 400)
            val clampedExpanded = expandedPct.coerceIn(40, 400)
            val prevEffective = effectiveScale
            compactScalePct = clampedCompact
            expandedScalePct = clampedExpanded
            val nextRequested = requestedScale()
            if (abs(nextRequested - prevEffective) > 0.0001f) {
                effectiveScale = nextRequested
                applyPaintSizes(effectiveScale)
                requestLayout()
                invalidate()
                return true
            }
            return false
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

        fun setDisplayData(next: DisplayData) {
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
        }

        private fun computeExpandedCellWidth(d: Float): Float {
            val ringSize = 48f * d
            val widestLabel = maxOf(
                ringLabelPaint.measureText("CPU"),
                ringLabelPaint.measureText("GPU"),
                ringLabelPaint.measureText("TEMP"),
                ringLabelPaint.measureText("FPS"),
                ringLabelPaint.measureText("POWER")
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
            regularRows.add("PWR  ${formatSignedWatts(dData.watts)}W")
            return regularRows to boldRows
        }

        private fun measureDetailedRightColWidth(): Float {
            val (regularRows, boldRows) = detailedRowStrings(data)
            var maxW = maxOf(
                detailedTextPaint.measureText("DDR  4266 MHz"),
                detailedBoldPaint.measureText("#0~5  2000MHz"),
                detailedTextPaint.measureText("100%  2000M"),
                detailedTextPaint.measureText("FPS  120.0"),
                detailedTextPaint.measureText("PWR  -10.00W"),
                detailedTextPaint.measureText("PWR  \u221210.00W")
            )
            for (r in regularRows) {
                maxW = maxOf(maxW, detailedTextPaint.measureText(r))
            }
            for (r in boldRows) {
                maxW = maxOf(maxW, detailedBoldPaint.measureText(r))
            }
            return maxW
        }

        private fun measureNaturalWidthAtScaleOne(): Float {
            applyPaintSizes(1f)
            val d = baseDensity
            return if (detailed) {
                val hPad = 12f * d
                val leftColW = 64f * d
                val colGap = 14f * d
                hPad * 2f + leftColW + colGap + measureDetailedRightColWidth()
            } else if (expanded) {
                val hPad = 12f * d
                val cellGap = 8f * d
                val cellWidth = computeExpandedCellWidth(d)
                hPad * 2f + cellWidth * 5f + cellGap * 4f
            } else {
                val hPad = 14f * d
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
                    compactTextPaint.measureText("\u221210.00W")
                )

                hPad * 2f + cpuItemW + itemGap + gpuItemW + itemGap + fpsItemW + itemGap + tempItemW + itemGap + powerItemW
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val maxAllowedWidth = (screenWidth - 16f * baseDensity).coerceAtLeast(48f * baseDensity)
            val naturalWidthAt1 = measureNaturalWidthAtScaleOne().coerceAtLeast(1f)
            val clampedS = minOf(requestedScale(), maxAllowedWidth / naturalWidthAt1).coerceAtLeast(0.1f)
            effectiveScale = clampedS
            applyPaintSizes(clampedS)
            val d = effDensityFor(clampedS)

            if (detailed) {
                val hPad = 12f * d
                val vPad = 12f * d
                val leftColW = 64f * d
                val colGap = 14f * d
                val ringSize = 44f * d
                val subGap = 2f * d
                val ringGap = 8f * d

                val subFm = ringSubPaint.fontMetrics
                val subH = subFm.descent - subFm.ascent
                val leftColH = 3f * (ringSize + subGap + subH) + 2f * ringGap

                val (regularRows, boldRows) = detailedRowStrings(data)
                val rowCount = regularRows.size + boldRows.size
                val lineHeight = (12f * d) * 1.45f
                val rightColH = rowCount * lineHeight

                val width = ceil((hPad * 2f + leftColW + colGap + measureDetailedRightColWidth()).toDouble()).toInt()
                val height = ceil((vPad * 2f + maxOf(leftColH, rightColH)).toDouble()).toInt()
                setMeasuredDimension(width, height)
            } else if (expanded) {
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
                    compactTextPaint.measureText("\u221210.00W")
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
            val powerVal = "${formatSignedWatts(data.watts)}W"

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

            compactTextPaint.color = blueColor
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
                subText: String
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

                ringCenterPaint.textSize = baseCenterSize
                val centerW = ringCenterPaint.measureText(centerText)
                if (centerW > maxCenterW && centerW > 0f) {
                    ringCenterPaint.textSize = baseCenterSize * (maxCenterW / centerW)
                }
                val centerFm = ringCenterPaint.fontMetrics
                val centerBaseline = ringCy - (centerFm.ascent + centerFm.descent) / 2f
                canvas.drawText(centerText, cx, centerBaseline, ringCenterPaint)

                ringLabelPaint.textSize = baseLabelSize
                val labelW = ringLabelPaint.measureText(label)
                if (labelW > cellWidth && labelW > 0f) {
                    ringLabelPaint.textSize = baseLabelSize * (cellWidth / labelW)
                }
                val labelFm = ringLabelPaint.fontMetrics
                val labelBaseline = ringTop + ringSize + labelGap - labelFm.ascent
                canvas.drawText(label, cx, labelBaseline, ringLabelPaint)

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
                label = "TEMP",
                subText = "\u00b0C"
            )
            drawRingCell(
                index = 3,
                fraction = if (data.fps < 0f) 0f else (data.fps / data.refreshHz.coerceAtLeast(1)).coerceIn(0f, 1f),
                ringColor = fpsColor(data.fps, data.refreshHz),
                centerText = if (data.fps < 0f) "--" else data.fps.roundToInt().toString(),
                label = "FPS",
                subText = "of ${data.refreshHz}Hz"
            )
            drawRingCell(
                index = 4,
                fraction = abs(data.watts) / 10f,
                ringColor = blueColor,
                centerText = formatSignedWatts(data.watts),
                label = "POWER",
                subText = "watts"
            )
        }

        private fun drawDetailed(canvas: Canvas, d: Float) {
            val hPad = 12f * d
            val vPad = 12f * d
            val leftColW = 64f * d
            val colGap = 14f * d
            val ringSize = 44f * d
            val stroke = 4f * d
            val subGap = 2f * d
            val ringGap = 8f * d

            val baseCenterSize = 11f * d
            val baseSubSize = 9.5f * d
            ringSubPaint.textSize = baseSubSize
            val subFm = ringSubPaint.fontMetrics
            val subH = subFm.descent - subFm.ascent

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
                val ringTop = vPad + slot * (ringSize + subGap + subH + ringGap)
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
                subText = "TEMP \u00b0C"
            )

            val rightLeft = hPad + leftColW + colGap
            val textSize = 12f * d
            val lineHeight = textSize * 1.45f
            detailedTextPaint.textSize = textSize
            detailedBoldPaint.textSize = textSize
            val fm = detailedTextPaint.fontMetrics
            val grayColor = 0xFFAAAAAA.toInt()
            val whiteColor = 0xFFFFFFFF.toInt()

            var rowIdx = 0
            fun rowBaseline(): Float {
                val top = vPad + rowIdx * lineHeight
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
            drawLabelValueRow("PWR  ", "${formatSignedWatts(data.watts)}W", whiteColor)
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
                            xPm <= 25 -> 0
                            xPm >= 975 -> 1000
                            abs(xPm - 500) <= 20 -> 500
                            else -> xPm
                        }
                        yPm = when {
                            yPm <= 25 -> 0
                            yPm >= 975 -> 1000
                            else -> yPm
                        }
                        params.x = (xPm / 1000f * freeW).roundToInt()
                        params.y = (yPm / 1000f * freeH).roundToInt()
                        lastAppliedXPm = xPm
                        lastAppliedYPm = yPm
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (_: Throwable) {
                        }
                        engine.updateConfig {
                            when {
                                detailed -> it.copy(overlayDetailedXPm = xPm, overlayDetailedYPm = yPm)
                                expanded -> it.copy(overlayExpandedXPm = xPm, overlayExpandedYPm = yPm)
                                else -> it.copy(overlayCompactXPm = xPm, overlayCompactYPm = yPm)
                            }
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
