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

    private data class DisplayData(
        val cpuLoadPct: Int = 0,
        val cpuFreqMhz: Long = 0L,
        val gpuLoadPct: Int = 0,
        val gpuFreqMhz: Long = 0L,
        val tempDeg: Int = 0,
        val tempC: Float = 0f,
        val wattsCenti: Int = 0,
        val watts: Float = 0f
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
            view.setExpanded(cfg.overlayExpanded)
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
            if (existingView.isExpandedMode() != cfg.overlayExpanded) {
                existingView.setExpanded(cfg.overlayExpanded)
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
        val xPm = if (cfg.overlayExpanded) cfg.overlayExpandedXPm else cfg.overlayCompactXPm
        val yPm = if (cfg.overlayExpanded) cfg.overlayExpandedYPm else cfg.overlayCompactYPm
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
        val cpuLoad = if (state.clusters.isNotEmpty()) {
            state.clusters.map { it.smoothedLoadPct }.average().toFloat().roundToInt().coerceIn(0, 100)
        } else 0
        val cpuFreqMhz = (state.clusters.maxOfOrNull { it.actualFreqKhz } ?: 0L) / 1000L
        val gpuLoad = state.gpu.smoothedLoadPct.roundToInt().coerceIn(0, 100)
        val gpuFreqMhz = state.gpu.currentFreqKhz / 1000L
        val tempDeg = state.temperatureC.roundToInt()
        val wattsCenti = (state.watts * 100f).roundToInt()
        return DisplayData(
            cpuLoadPct = cpuLoad,
            cpuFreqMhz = cpuFreqMhz,
            gpuLoadPct = gpuLoad,
            gpuFreqMhz = gpuFreqMhz,
            tempDeg = tempDeg,
            tempC = state.temperatureC,
            wattsCenti = wattsCenti,
            watts = state.watts
        )
    }

    private fun hasDisplayedValuesChanged(prev: DisplayData?, next: DisplayData): Boolean {
        if (prev == null) return true
        return prev.cpuLoadPct != next.cpuLoadPct ||
            prev.cpuFreqMhz != next.cpuFreqMhz ||
            prev.gpuLoadPct != next.gpuLoadPct ||
            prev.gpuFreqMhz != next.gpuFreqMhz ||
            prev.tempDeg != next.tempDeg ||
            prev.wattsCenti != next.wattsCenti
    }

    private inner class OverlayPillView(context: Context) : View(context) {

        private val baseDensity = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var data = DisplayData()
        private var expanded = false
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

        private val pillRect = RectF()
        private val borderRect = RectF()
        private val arcRect = RectF()

        init {
            applyPaintSizes(1f)
        }

        private fun requestedScale(): Float {
            val pct = if (expanded) expandedScalePct else compactScalePct
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
        }

        fun isExpandedMode(): Boolean = expanded

        fun setExpanded(value: Boolean) {
            if (expanded != value) {
                expanded = value
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
            data = next
            invalidate()
        }

        private fun computeExpandedCellWidth(d: Float): Float {
            val ringSize = 48f * d
            val widestLabel = maxOf(
                ringLabelPaint.measureText("CPU"),
                ringLabelPaint.measureText("GPU"),
                ringLabelPaint.measureText("TEMP"),
                ringLabelPaint.measureText("POWER")
            )
            return maxOf(ringSize, widestLabel)
        }

        private fun measureNaturalWidthAtScaleOne(): Float {
            applyPaintSizes(1f)
            val d = baseDensity
            return if (!expanded) {
                val hPad = 14f * d
                val dotR = 3.5f * d
                val dotGap = 4f * d
                val labelGap = 3f * d
                val itemGap = 10f * d

                val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText("100%")
                val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText("100%")
                val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText("100°")
                val powerItemW = maxOf(
                    compactTextPaint.measureText("-10.00W"),
                    compactTextPaint.measureText("\u221210.00W")
                )

                hPad * 2f + cpuItemW + itemGap + gpuItemW + itemGap + tempItemW + itemGap + powerItemW
            } else {
                val hPad = 12f * d
                val cellGap = 8f * d
                val cellWidth = computeExpandedCellWidth(d)
                hPad * 2f + cellWidth * 4f + cellGap * 3f
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

            if (!expanded) {
                val hPad = 14f * d
                val vPad = 8f * d
                val dotR = 3.5f * d
                val dotGap = 4f * d
                val labelGap = 3f * d
                val itemGap = 10f * d

                val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText("100%")
                val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText("100%")
                val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText("100°")
                val powerItemW = maxOf(
                    compactTextPaint.measureText("-10.00W"),
                    compactTextPaint.measureText("\u221210.00W")
                )

                val contentW = cpuItemW + itemGap + gpuItemW + itemGap + tempItemW + itemGap + powerItemW
                val fm = compactTextPaint.fontMetrics
                val textH = fm.descent - fm.ascent

                val width = ceil((hPad * 2f + contentW).toDouble()).toInt()
                val height = ceil((vPad * 2f + textH).toDouble()).toInt()
                setMeasuredDimension(width, height)
            } else {
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

                val width = ceil((hPad * 2f + cellWidth * 4f + cellGap * 3f).toDouble()).toInt()
                val height = ceil((vPad * 2f + ringSize + labelGap + labelH + subGap + subH).toDouble()).toInt()
                setMeasuredDimension(width, height)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = effDensityFor(effectiveScale)
            pillRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val corner = if (expanded) 20f * d else height / 2f
            canvas.drawRoundRect(pillRect, corner, corner, bgPaint)

            val halfBorder = borderPaint.strokeWidth / 2f
            borderRect.set(halfBorder, halfBorder, width - halfBorder, height - halfBorder)
            val borderCorner = (corner - halfBorder).coerceAtLeast(0f)
            canvas.drawRoundRect(borderRect, borderCorner, borderCorner, borderPaint)

            if (!expanded) {
                drawCompact(canvas, d)
            } else {
                drawExpanded(canvas, d)
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
            val tempVal = "${data.tempDeg}°"
            val powerVal = "${formatSignedWatts(data.watts)}W"

            val cpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("CPU") + labelGap + compactTextPaint.measureText(cpuVal)
            val gpuItemW = dotR * 2f + dotGap + compactLabelPaint.measureText("GPU") + labelGap + compactTextPaint.measureText(gpuVal)
            val tempItemW = dotR * 2f + dotGap + compactTextPaint.measureText(tempVal)
            val powerItemW = compactTextPaint.measureText(powerVal)
            val totalContentW = cpuItemW + itemGap + gpuItemW + itemGap + tempItemW + itemGap + powerItemW

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
                fraction = abs(data.watts) / 10f,
                ringColor = blueColor,
                centerText = formatSignedWatts(data.watts),
                label = "POWER",
                subText = "watts"
            )
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
                            if (expanded) {
                                it.copy(overlayExpandedXPm = xPm, overlayExpandedYPm = yPm)
                            } else {
                                it.copy(overlayCompactXPm = xPm, overlayCompactYPm = yPm)
                            }
                        }
                    } else {
                        engine.updateConfig { it.copy(overlayExpanded = !it.overlayExpanded) }
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
