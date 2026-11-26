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

package com.example.governor

import com.example.model.BatteryState
import com.example.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

data class BatterySegmentRecord(
    val endTimeMs: Long,
    val direction: BatteryState,
    val startPct: Int,
    val endPct: Int,
    val deltaPct: Int,
    val durationMs: Long,
    val integratedMah: Float,
    val integratedWh: Float,
    val avgCurrentMa: Float,
    val estCapacityMah: Float,
    val estCapacityWh: Float,
    val avgWatts: Float = 0f,
    val peakVoltageMv: Float = 0f,
    val peakCurrentMa: Float = 0f,
    val bandSec: List<Float> = emptyList()
)

data class BatteryEstimatorUiSnapshot(
    val enabled: Boolean = true,
    val batteryPct: Int = -1,
    val batteryState: BatteryState = BatteryState.UNKNOWN,
    val livePowerInWatts: Float = 0f,
    val livePowerOutWatts: Float = 0f,
    val batteryTempC: Float = 0f,
    val batteryVoltageMv: Float = 0f,
    val batteryHealthLabel: String = "n/a",
    val cycleCount: Int = -1,
    val designCapacityMah: Int = 0,
    val sysfsDesignCapacityMah: Int = 0,
    val sysfsFullCapacityMah: Int = 0,
    val chargeCounterMah: Float = -1f,
    val chargeCounterEstimateMah: Float = -1f,
    val dischargeCapacityMah: Float = 0f,
    val dischargeCapacityWh: Float = 0f,
    val dischargeSegmentCount: Int = 0,
    val chargeCapacityMah: Float = 0f,
    val chargeCapacityWh: Float = 0f,
    val chargeSegmentCount: Int = 0,
    val combinedCapacityMah: Float = 0f,
    val combinedCapacityWh: Float = 0f,
    val totalValidSegments: Int = 0,
    val confidencePct: Int = 0,
    val directionDisagreementPct: Float = 0f,
    val hasDirectionDisagreement: Boolean = false,
    val sohPct: Float = -1f,
    val remainingWh: Float = -1f,
    val timeRemainingMin: Int = -1,
    val timeToFullMin: Int = -1,
    val lifetimeWhOut: Float = 0f,
    val lifetimeWhIn: Float = 0f,
    val lifetimeMahOut: Float = 0f,
    val lifetimeMahIn: Float = 0f,
    val sessionWhOut: Float = 0f,
    val sessionWhIn: Float = 0f,
    val sessionMahOut: Float = 0f,
    val sessionMahIn: Float = 0f,
    val neededPctToEstimate: Int = BatteryEstimatorMath.MIN_SEGMENT_DELTA_PCT,
    val activeSegmentDeltaPct: Int = 0,
    val activeSegmentState: BatteryState = BatteryState.UNKNOWN,
    val chargeBandSessionSec: List<Float> = emptyList(),
    val chargeBandLifetimeSec: List<Float> = emptyList(),
    val peakChargeVoltageMv: Float = 0f,
    val chargeTrustPct: Int = -1,
    val recentSegments: List<BatterySegmentRecord> = emptyList()
)

data class DirectionEstimate(
    val capacityMah: Float = 0f,
    val capacityWh: Float = 0f,
    val totalWeight: Float = 0f,
    val totalDeltaPct: Int = 0,
    val validCount: Int = 0,
    val trustedDeltaPct: Float = 0f
)

data class CombinedBatteryEstimate(
    val discharge: DirectionEstimate = DirectionEstimate(),
    val charge: DirectionEstimate = DirectionEstimate(),
    val combinedMah: Float = 0f,
    val combinedWh: Float = 0f,
    val confidencePct: Int = 0,
    val disagreementPct: Float = 0f,
    val hasDisagreement: Boolean = false,
    val chargeTrustPct: Int = -1
)

object BatteryEstimatorMath {
    const val MIN_SEGMENT_DELTA_PCT = 8
    const val MIN_SEGMENT_DURATION_MS = 180_000L // 3 minutes
    const val MAX_SEGMENTS_PER_DIRECTION = 20
    const val DISAGREEMENT_THRESHOLD_PCT = 12f
    const val CHARGE_TO_DISCHARGE_CAP = 1.5
    const val BAND_COUNT = 6
    private val BAND_EDGES_W = floatArrayOf(5f, 10f, 15f, 20f, 25f)

    /** Charge power band: 0 = under 5 W, 1 = 5-10, 2 = 10-15, 3 = 15-20, 4 = 20-25, 5 = 25 W and up. */
    fun bandIndex(watts: Float): Int {
        for (i in BAND_EDGES_W.indices) if (watts < BAND_EDGES_W[i]) return i
        return BAND_EDGES_W.size
    }

    /** The fuel gauge runs ahead of the real charge at high power, so fast-charge segments count less. */
    fun chargeTrust(avgWatts: Float): Float {
        if (avgWatts <= 0f) return 1f
        return (1f / (1f + (avgWatts - 10f).coerceAtLeast(0f) / 20f)).coerceIn(0.25f, 1f)
    }

    /** Trust of one segment: charge power, and the voltage lost inside the cell once the resistance is known. */
    fun trustFactor(seg: BatterySegmentRecord, resistanceMohm: Float): Float {
        if (seg.direction != BatteryState.CHARGING) return 1f
        var t = chargeTrust(seg.avgWatts)
        if (resistanceMohm > 0f) {
            val dropMv = seg.avgCurrentMa.coerceAtLeast(0f) * resistanceMohm / 1000f
            t /= (1f + dropMv / 400f)
        }
        return t
    }

    fun segmentWeight(deltaPct: Int, avgCurrentMa: Float): Float {
        if (deltaPct <= 0) return 0f
        val curPenalty = 1f / (1f + (avgCurrentMa.coerceAtLeast(0f) / 1000f))
        return deltaPct.toFloat() * curPenalty
    }

    fun weightedMedian(values: List<Float>, weights: List<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size == 1) return values[0]
        val pairs = values.indices
            .map { values[it] to weights.getOrElse(it) { 1f }.coerceAtLeast(1e-4f) }
            .sortedBy { it.first }
        val totalW = pairs.sumOf { it.second.toDouble() }
        if (totalW <= 0.0) return pairs[pairs.size / 2].first
        var acc = 0.0
        val half = totalW * 0.5
        for ((v, w) in pairs) {
            acc += w
            if (acc >= half) return v
        }
        return pairs.last().first
    }

    fun estimateDirection(
        segments: List<BatterySegmentRecord>,
        resistanceMohm: Float = 0f
    ): DirectionEstimate {
        if (segments.isEmpty()) return DirectionEstimate()
        val mahVals = segments.map { it.estCapacityMah }
        val whVals = segments.map { it.estCapacityWh }
        val weights = segments.map { segmentWeight(it.deltaPct, it.avgCurrentMa) * trustFactor(it, resistanceMohm) }

        val med = weightedMedian(mahVals, weights)
        val inliers = if (segments.size >= 3) {
            val devs = mahVals.map { abs(it - med) }
            val mad = weightedMedian(devs, weights)
            val scaledMad = 1.4826f * mad
            val tol = maxOf(0.08f * med, 2.5f * scaledMad)
            val kept = segments.indices.filter { abs(mahVals[it] - med) <= tol }
            if (kept.isNotEmpty()) kept else listOf(mahVals.indices.minBy { abs(mahVals[it] - med) })
        } else {
            segments.indices.toList()
        }

        var wSum = 0.0
        var mahSum = 0.0
        var whSum = 0.0
        for (idx in inliers) {
            val w = weights[idx].toDouble().coerceAtLeast(1e-4)
            wSum += w
            mahSum += mahVals[idx].toDouble() * w
            whSum += whVals[idx].toDouble() * w
        }
        if (wSum <= 0.0) return DirectionEstimate()

        return DirectionEstimate(
            capacityMah = (mahSum / wSum).toFloat(),
            capacityWh = (whSum / wSum).toFloat(),
            totalWeight = wSum.toFloat(),
            totalDeltaPct = inliers.sumOf { segments[it].deltaPct },
            validCount = segments.size,
            trustedDeltaPct = inliers.sumOf { (segments[it].deltaPct * trustFactor(segments[it], resistanceMohm)).toDouble() }.toFloat()
        )
    }

    fun combineDirections(
        dischargeSegments: List<BatterySegmentRecord>,
        chargeSegments: List<BatterySegmentRecord>,
        resistanceMohm: Float = 0f,
        anchorMah: Float = 0f,
        anchorSlow: Boolean = false,
        designMah: Float = 0f
    ): CombinedBatteryEstimate {
        val dis = estimateDirection(dischargeSegments)
        val chg = estimateDirection(chargeSegments, resistanceMohm)
        val hasD = dis.validCount > 0 && dis.capacityMah > 0f
        val hasC = chg.validCount > 0 && chg.capacityMah > 0f

        var combinedMah: Float
        var combinedWh: Float
        var disagreementPct = 0f
        var hasDisagreement = false

        when {
            hasD && hasC -> {
                // Slightly favor discharge segments (1.15x) over battery-side charge segments.
                val wd = dis.totalWeight.toDouble() * 1.15
                val wc = min(chg.totalWeight.toDouble(), dis.totalWeight.toDouble() * CHARGE_TO_DISCHARGE_CAP)
                val tot = (wd + wc).coerceAtLeast(1e-4)
                combinedMah = ((dis.capacityMah * wd + chg.capacityMah * wc) / tot).toFloat()
                combinedWh = ((dis.capacityWh * wd + chg.capacityWh * wc) / tot).toFloat()
                val denom = min(dis.capacityMah, chg.capacityMah).coerceAtLeast(1f)
                disagreementPct = abs(dis.capacityMah - chg.capacityMah) / denom * 100f
                hasDisagreement = disagreementPct > DISAGREEMENT_THRESHOLD_PCT
            }
            hasD -> {
                combinedMah = dis.capacityMah
                combinedWh = dis.capacityWh
            }
            hasC -> {
                combinedMah = chg.capacityMah
                combinedWh = chg.capacityWh
            }
            else -> {
                combinedMah = 0f
                combinedWh = 0f
            }
        }

        // A clean full charge (Battery truth calibration) joins the blend. A mostly slow charge counts
        // about 3x more than a fast one; a reading far from the design capacity is ignored.
        if (anchorMah > 0f) {
            val plausible = designMah <= 0f || (anchorMah in designMah * 0.5f..designMah * 1.3f)
            if (plausible) {
                val wa = if (anchorSlow) 180.0 else 60.0
                if (combinedMah > 0f) {
                    val wBase = (dis.totalWeight + chg.totalWeight).toDouble().coerceAtLeast(1e-4)
                    val tot = wBase + wa
                    val ratio = combinedWh / combinedMah
                    combinedMah = ((combinedMah * wBase + anchorMah * wa) / tot).toFloat()
                    if (ratio > 0f) combinedWh = combinedMah * ratio
                } else {
                    combinedMah = anchorMah
                }
            }
        }

        val totalSegments = dis.validCount + chg.validCount
        val totalDeltaPct = dis.totalDeltaPct + chg.trustedDeltaPct
        val confidence = if (totalSegments == 0) {
            0
        } else {
            val coverageScore = (totalDeltaPct / 120f).coerceIn(0f, 1f) * 55f
            val countScore = (totalSegments / 8f).coerceIn(0f, 1f) * 30f
            val agreeScore = when {
                hasD && hasC && disagreementPct <= 5f -> 15f
                hasD && hasC && disagreementPct <= DISAGREEMENT_THRESHOLD_PCT -> 10f
                hasD && hasC -> 2f
                else -> 7f
            }
            (coverageScore + countScore + agreeScore).roundToInt().coerceIn(5, 100)
        }

        return CombinedBatteryEstimate(
            discharge = dis,
            charge = chg,
            combinedMah = combinedMah,
            combinedWh = combinedWh,
            confidencePct = confidence,
            disagreementPct = disagreementPct,
            hasDisagreement = hasDisagreement,
            chargeTrustPct = if (chargeSegments.isEmpty()) -1 else {
                val td = chargeSegments.sumOf { it.deltaPct }.coerceAtLeast(1)
                ((chargeSegments.sumOf { (it.deltaPct * trustFactor(it, resistanceMohm)).toDouble() } / td) * 100.0)
                    .roundToInt().coerceIn(0, 100)
            }
        )
    }
}

/**
 * Estimates real battery capacity (mAh and Wh) & SoH by integrating tick current/voltage
 * across uninterrupted integer percent transitions, with robust MAD outlier filtering and atomic JSON persistence.
 */
class BatteryEstimator(
    private val storageFile: File,
    private val scope: CoroutineScope? = null,
    private val onLog: (LogLevel, String, String) -> Unit = { _, _, _ -> }
) {

    companion object {
        const val SCHEMA_VERSION = 1
        const val AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000L
        private const val RATE_EMA_TAU_SEC = 120.0
        private const val CURVE_ALPHA = 0.05f
    }

    private enum class SegPhase {
        NONE,
        WAITING_FIRST_TRANSITION,
        COLLECTING
    }

    private val lock = Any()

    private val dischargeSegments = ArrayList<BatterySegmentRecord>()
    private val chargeSegments = ArrayList<BatterySegmentRecord>()
    private var combined = CombinedBatteryEstimate()

    // Lifetime & session counters
    private var lifetimeWhOut = 0.0
    private var lifetimeWhIn = 0.0
    private var lifetimeMahOut = 0.0
    private var lifetimeMahIn = 0.0
    private var sessionWhOut = 0.0
    private var sessionWhIn = 0.0
    private var sessionMahOut = 0.0
    private var sessionMahIn = 0.0

    // Live diagnostics & OS/sysfs properties
    private var enabled = true
    private val sessionChgBandSec = DoubleArray(BatteryEstimatorMath.BAND_COUNT)
    private val lifetimeChgBandSec = DoubleArray(BatteryEstimatorMath.BAND_COUNT)
    private var peakChargeMv = 0f
    /** Learned charge power (W) for each 10% slice of battery (0-10 ... 90-100); 0 = not learned yet. */
    private val chgCurveW = FloatArray(10)
    private val segBandSec = DoubleArray(BatteryEstimatorMath.BAND_COUNT)
    private var segPeakMv = 0f
    private var segPeakMa = 0f
    @Volatile private var calAnchorMah = 0f
    @Volatile private var calAnchorSlow = false
    @Volatile private var internalResistanceMohm = 0f
    private var designOverrideMah = 0
    private var sysfsDesignCapacityMah = 0
    private var sysfsFullCapacityMah = 0
    private var sysfsCycleCount = -1
    private var osCycleCount = -1
    private var lastBatteryPct = -1
    private var lastBatteryState = BatteryState.UNKNOWN
    private var lastTempC = 0f
    private var lastVoltageMv = 0f
    private var lastHealthLabel = "n/a"
    private var lastChargeCounterMah = -1f
    private var livePowerOutWatts = 0f
    private var livePowerInWatts = 0f

    // Smoothed ~2 min discharge & charge rates (watts)
    private var emaDischargeWatts = 0f
    private var emaChargeWatts = 0f

    // Hot-path tick tracking & in-flight segment
    private var lastTickMs = 0L
    private var segPhase = SegPhase.NONE
    private var segState = BatteryState.UNKNOWN
    private var waitingInitialPct = -1
    private var segStartPct = -1
    private var segLastPct = -1
    private var segStartTimeMs = 0L
    private var segAccumMah = 0.0
    private var segAccumWh = 0.0
    private var segIntegrationMs = 0L

    private fun resetSegExtrasLocked() {
        segBandSec.fill(0.0)
        segPeakMv = 0f
        segPeakMa = 0f
    }

    private var lastLoggedCombinedMah = 0f
    private var dirty = false
    private var lastSavedMs = 0L
    private var lastUiPushMs = 0L
    @Volatile
    private var uiVisible = false

    private val _snapshot = MutableStateFlow(BatteryEstimatorUiSnapshot())
    val snapshot: StateFlow<BatteryEstimatorUiSnapshot> = _snapshot.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            pushSnapshotNow()
        }
    }

    fun updateConfig(estimationEnabled: Boolean, designCapacityOverrideMah: Int) {
        val cleanOverride = designCapacityOverrideMah.coerceAtLeast(0)
        var changed = false
        synchronized(lock) {
            if (enabled != estimationEnabled || designOverrideMah != cleanOverride) {
                enabled = estimationEnabled
                designOverrideMah = cleanOverride
                if (!enabled) {
                    discardInFlightLocked()
                }
                changed = true
            }
        }
        if (changed) pushSnapshotNow()
    }

    fun resetSession() {
        synchronized(lock) {
            sessionWhOut = 0.0
            sessionWhIn = 0.0
            sessionMahOut = 0.0
            sessionMahIn = 0.0
            sessionChgBandSec.fill(0.0)
            lastTickMs = 0L
            discardInFlightLocked()
        }
        if (uiVisible) pushSnapshotNow()
    }

    fun updateBatteryProperties(
        batteryPct: Int,
        tempC: Float,
        voltageMv: Float,
        healthLabel: String,
        chargeCounterMah: Float,
        cycleCount: Int
    ) {
        synchronized(lock) {
            if (batteryPct in 0..100) lastBatteryPct = batteryPct
            if (tempC in -30f..120f) lastTempC = tempC
            if (voltageMv > 0f) lastVoltageMv = voltageMv
            if (healthLabel.isNotBlank()) lastHealthLabel = healthLabel
            if (chargeCounterMah >= 0f) lastChargeCounterMah = chargeCounterMah
            if (cycleCount >= 0) osCycleCount = cycleCount
        }
    }

    fun updateSlowSysfs(
        fullMah: Int?,
        designMah: Int?,
        cycleCount: Int?
    ) {
        var changed = false
        synchronized(lock) {
            if (fullMah != null && fullMah > 0 && sysfsFullCapacityMah != fullMah) {
                sysfsFullCapacityMah = fullMah
                changed = true
            }
            if (designMah != null && designMah > 0 && sysfsDesignCapacityMah != designMah) {
                sysfsDesignCapacityMah = designMah
                changed = true
            }
            if (cycleCount != null && cycleCount >= 0 && sysfsCycleCount != cycleCount) {
                sysfsCycleCount = cycleCount
                changed = true
            }
        }
        if (changed && uiVisible) pushSnapshotNow()
    }

    /** Capacity read from a clean full charge (0 = none) and whether that charge was mostly slow. */
    fun setCalibrationAnchor(mah: Float, slow: Boolean) {
        calAnchorMah = mah
        calAnchorSlow = slow
    }

    /**
     * Called once per governor control tick. Allocation-free on the hot path; persists only in background.
     */
    fun recordTick(
        nowMs: Long,
        batteryState: BatteryState,
        absCurrentMa: Float,
        voltageMv: Float,
        dischargeWatts: Float,
        chargeWatts: Float,
        batteryPct: Int,
        pollIntervalMs: Long,
        estimationEnabled: Boolean,
        internalResistanceMohm: Float = 0f
    ) {
        this.internalResistanceMohm = internalResistanceMohm
        var completedLog: String? = null
        var deltaLog: String? = null
        var jsonToSave: String? = null
        var shouldPushUi = false

        synchronized(lock) {
            enabled = estimationEnabled
            lastBatteryState = batteryState
            if (batteryPct in 0..100) lastBatteryPct = batteryPct
            if (voltageMv > 0f) lastVoltageMv = voltageMv
            livePowerOutWatts = if (batteryState == BatteryState.DISCHARGING) dischargeWatts else 0f
            livePowerInWatts = if (batteryState == BatteryState.CHARGING) chargeWatts else 0f

            val maxDtMs = maxOf(600L, pollIntervalMs.coerceIn(50L, 2000L) * 3L)
            val prevTick = lastTickMs
            lastTickMs = nowMs
            if (prevTick <= 0L || nowMs <= prevTick) {
                if (enabled && batteryPct in 1..100 &&
                    (batteryState == BatteryState.DISCHARGING || batteryState == BatteryState.CHARGING)
                ) {
                    stepSegmentLocked(nowMs, batteryState, batteryPct, 0L, 0.0, 0.0)
                }
                if (uiVisible && nowMs - lastUiPushMs >= 1000L) {
                    lastUiPushMs = nowMs
                    shouldPushUi = true
                }
                return@synchronized
            }

            val dtMs = nowMs - prevTick
            if (dtMs > maxDtMs) {
                // Gap detected (sleep / suspend / jump): discard this interval and invalidate partial segment.
                discardInFlightLocked()
                if (uiVisible && nowMs - lastUiPushMs >= 1000L) {
                    lastUiPushMs = nowMs
                    shouldPushUi = true
                }
                return@synchronized
            }

            if (batteryState == BatteryState.DISCHARGING || batteryState == BatteryState.CHARGING) {
                val curValid = absCurrentMa.isFinite() && absCurrentMa in 1f..20_000f
                val voltValid = voltageMv.isFinite() && voltageMv in 2000f..6000f
                if (curValid && voltValid) {
                    val dtHours = dtMs / 3_600_000.0
                    val dtSec = dtMs / 1000.0
                    val dMah = absCurrentMa.toDouble() * dtHours
                    val watts = if (batteryState == BatteryState.DISCHARGING) dischargeWatts else chargeWatts
                    val effWatts = if (watts > 0.01f) watts.toDouble() else ((absCurrentMa / 1000.0) * (voltageMv / 1000.0))
                    val dWh = effWatts * dtHours

                    if (batteryState == BatteryState.DISCHARGING) {
                        lifetimeMahOut += dMah
                        lifetimeWhOut += dWh
                        sessionMahOut += dMah
                        sessionWhOut += dWh
                        if (effWatts > 0.1) {
                            val alpha = (1.0 - exp(-dtSec / RATE_EMA_TAU_SEC)).toFloat()
                            emaDischargeWatts = if (emaDischargeWatts <= 0f) effWatts.toFloat()
                            else emaDischargeWatts + (effWatts.toFloat() - emaDischargeWatts) * alpha
                        }
                    } else {
                        lifetimeMahIn += dMah
                        lifetimeWhIn += dWh
                        sessionMahIn += dMah
                        sessionWhIn += dWh
                        val bi = BatteryEstimatorMath.bandIndex(effWatts.toFloat())
                        sessionChgBandSec[bi] += dtSec
                        lifetimeChgBandSec[bi] += dtSec
                        segBandSec[bi] += dtSec
                        if (voltageMv > peakChargeMv) peakChargeMv = voltageMv
                        if (voltageMv > segPeakMv) segPeakMv = voltageMv
                        if (absCurrentMa > segPeakMa) segPeakMa = absCurrentMa
                        if (effWatts > 0.5 && batteryPct in 0..99) {
                            val bin = (batteryPct / 10).coerceIn(0, 9)
                            val prevW = chgCurveW[bin]
                            chgCurveW[bin] = if (prevW <= 0f) effWatts.toFloat()
                            else prevW + (effWatts.toFloat() - prevW) * CURVE_ALPHA
                        }
                        if (effWatts > 0.1) {
                            val alpha = (1.0 - exp(-dtSec / RATE_EMA_TAU_SEC)).toFloat()
                            emaChargeWatts = if (emaChargeWatts <= 0f) effWatts.toFloat()
                            else emaChargeWatts + (effWatts.toFloat() - emaChargeWatts) * alpha
                        }
                    }
                    dirty = true

                    if (enabled && batteryPct in 1..100) {
                        val segmentResult = stepSegmentLocked(
                            nowMs = nowMs,
                            batteryState = batteryState,
                            batteryPct = batteryPct,
                            dtMs = dtMs,
                            dMah = dMah,
                            dWh = dWh
                        )
                        if (segmentResult != null) {
                            completedLog = segmentResult.first
                            deltaLog = segmentResult.second
                            // Save immediately after completing a valid segment
                            lastSavedMs = nowMs
                            dirty = false
                            jsonToSave = serializeLocked()
                        }
                    } else {
                        discardInFlightLocked()
                    }
                }
            } else {
                // FULL_PLUGGED or UNKNOWN
                discardInFlightLocked()
            }

            if (jsonToSave == null && dirty) {
                if (lastSavedMs == 0L) lastSavedMs = nowMs
                if (nowMs - lastSavedMs >= AUTO_SAVE_INTERVAL_MS) {
                    lastSavedMs = nowMs
                    dirty = false
                    jsonToSave = serializeLocked()
                }
            }

            if (uiVisible && (nowMs - lastUiPushMs >= 1000L || completedLog != null)) {
                lastUiPushMs = nowMs
                shouldPushUi = true
            }
        }

        completedLog?.let { onLog(LogLevel.INFO, "BatteryEst", it) }
        deltaLog?.let { onLog(LogLevel.INFO, "BatteryEst", it) }
        jsonToSave?.let { persistAsyncOrBlocking(it) }
        if (shouldPushUi) {
            pushSnapshotNow()
        }
    }

    private fun discardInFlightLocked() {
        segPhase = SegPhase.NONE
        segState = BatteryState.UNKNOWN
        waitingInitialPct = -1
        segStartPct = -1
        segLastPct = -1
        segStartTimeMs = 0L
        segAccumMah = 0.0
        resetSegExtrasLocked()
        segAccumWh = 0.0
        segIntegrationMs = 0L
    }

    /**
     * Returns (segmentLog, combinedDeltaLog) if a valid segment completed on this tick.
     */
    private fun stepSegmentLocked(
        nowMs: Long,
        batteryState: BatteryState,
        batteryPct: Int,
        dtMs: Long,
        dMah: Double,
        dWh: Double
    ): Pair<String, String?>? {
        if (segPhase == SegPhase.NONE || segState != batteryState) {
            // Wait strictly for the first integer percent boundary in this state before integrating.
            segPhase = SegPhase.WAITING_FIRST_TRANSITION
            segState = batteryState
            waitingInitialPct = batteryPct
            segAccumMah = 0.0
            resetSegExtrasLocked()
            segAccumWh = 0.0
            segIntegrationMs = 0L
            return null
        }

        if (segPhase == SegPhase.WAITING_FIRST_TRANSITION) {
            val expectedTransition = if (batteryState == BatteryState.DISCHARGING) {
                batteryPct < waitingInitialPct
            } else {
                batteryPct > waitingInitialPct
            }
            if (expectedTransition) {
                segPhase = SegPhase.COLLECTING
                segStartPct = batteryPct
                segLastPct = batteryPct
                segStartTimeMs = nowMs
                segAccumMah = 0.0
                resetSegExtrasLocked()
                segAccumWh = 0.0
                segIntegrationMs = 0L
            } else if (batteryPct != waitingInitialPct) {
                // Fuel-gauge bounce in unexpected direction; re-anchor boundary.
                waitingInitialPct = batteryPct
            }
            return null
        }

        // SegPhase.COLLECTING
        segAccumMah += dMah
        segAccumWh += dWh
        segIntegrationMs += dtMs

        if (batteryPct != segLastPct) {
            val movedForward = if (batteryState == BatteryState.DISCHARGING) {
                batteryPct < segLastPct
            } else {
                batteryPct > segLastPct
            }
            segLastPct = batteryPct

            if (movedForward) {
                val deltaPct = if (batteryState == BatteryState.DISCHARGING) {
                    segStartPct - batteryPct
                } else {
                    batteryPct - segStartPct
                }
                val durMs = nowMs - segStartTimeMs
                if (deltaPct >= BatteryEstimatorMath.MIN_SEGMENT_DELTA_PCT &&
                    durMs >= BatteryEstimatorMath.MIN_SEGMENT_DURATION_MS &&
                    segIntegrationMs > 0L
                ) {
                    val estMah = (segAccumMah / (deltaPct / 100.0)).toFloat()
                    val estWh = (segAccumWh / (deltaPct / 100.0)).toFloat()
                    val avgCurMa = (segAccumMah / (segIntegrationMs / 3_600_000.0)).toFloat()
                    val segAvgW = (segAccumWh / (segIntegrationMs / 3_600_000.0)).toFloat()
                    val segPkMv = segPeakMv
                    val segPkMa = segPeakMa
                    val segBands = segBandSec.map { it.toFloat() }

                    val startPctSaved = segStartPct
                    // Next segment starts cleanly from this exact percent boundary.
                    segStartPct = batteryPct
                    segLastPct = batteryPct
                    segStartTimeMs = nowMs
                    segAccumMah = 0.0
                    resetSegExtrasLocked()
                    segAccumWh = 0.0
                    segIntegrationMs = 0L

                    if (estMah in 500f..25_000f && estWh > 1f) {
                        val rec = BatterySegmentRecord(
                            endTimeMs = nowMs,
                            direction = batteryState,
                            startPct = startPctSaved,
                            endPct = batteryPct,
                            deltaPct = deltaPct,
                            durationMs = durMs,
                            integratedMah = segAccumMahSnapshot(estMah, deltaPct),
                            integratedWh = segAccumWhSnapshot(estWh, deltaPct),
                            avgCurrentMa = avgCurMa,
                            estCapacityMah = estMah,
                            estCapacityWh = estWh,
                            avgWatts = segAvgW,
                            peakVoltageMv = segPkMv,
                            peakCurrentMa = segPkMa,
                            bandSec = if (batteryState == BatteryState.CHARGING) segBands else emptyList()
                        )
                        val targetList = if (batteryState == BatteryState.DISCHARGING) dischargeSegments else chargeSegments
                        targetList.add(rec)
                        while (targetList.size > BatteryEstimatorMath.MAX_SEGMENTS_PER_DIRECTION) {
                            targetList.removeAt(0)
                        }
                        combined = BatteryEstimatorMath.combineDirections(
                            dischargeSegments, chargeSegments,
                            internalResistanceMohm, calAnchorMah, calAnchorSlow, estimateDesignMahLocked()
                        )
                        dirty = true

                        val dirLabel = if (batteryState == BatteryState.DISCHARGING) "disch" else "chg"
                        val durMin = (durMs / 60_000L).coerceAtLeast(1L)
                        val segLog = String.format(
                            Locale.US,
                            "Segment #%d (%s): %d%%->%d%% in %dm -> %.0f mAh (combined %.0f mAh, conf %d%%)",
                            combined.discharge.validCount + combined.charge.validCount,
                            dirLabel,
                            startPctSaved,
                            batteryPct,
                            durMin,
                            estMah,
                            combined.combinedMah,
                            combined.confidencePct
                        )

                        var changeLog: String? = null
                        val newComb = combined.combinedMah
                        if (newComb > 0f) {
                            if (lastLoggedCombinedMah > 0f) {
                                val diffPct = abs(newComb - lastLoggedCombinedMah) / lastLoggedCombinedMah * 100f
                                if (diffPct > 3f) {
                                    val signed = ((newComb - lastLoggedCombinedMah) / lastLoggedCombinedMah * 100f)
                                    changeLog = String.format(
                                        Locale.US,
                                        "Battery estimate shifted: %.0f -> %.0f mAh (%+.1f%%)",
                                        lastLoggedCombinedMah,
                                        newComb,
                                        signed
                                    )
                                    lastLoggedCombinedMah = newComb
                                }
                            } else {
                                lastLoggedCombinedMah = newComb
                            }
                        }
                        return segLog to changeLog
                    }
                }
            }
        }
        return null
    }

    private fun segAccumMahSnapshot(estMah: Float, deltaPct: Int): Float = estMah * (deltaPct / 100f)
    private fun segAccumWhSnapshot(estWh: Float, deltaPct: Int): Float = estWh * (deltaPct / 100f)

    fun load() {
        if (!storageFile.exists()) {
            pushSnapshotNow()
            return
        }
        try {
            val raw = storageFile.readText()
            if (raw.isNotBlank()) {
                deserializeFromString(raw)
            }
        } catch (_: Throwable) {
        }
        pushSnapshotNow()
    }

    fun flush() {
        val json = synchronized(lock) {
            if (!dirty) return
            lastSavedMs = System.currentTimeMillis()
            dirty = false
            serializeLocked()
        }
        writeJsonAtomically(json)
    }

    fun resetEstimate() {
        synchronized(lock) {
            dischargeSegments.clear()
            chargeSegments.clear()
            combined = CombinedBatteryEstimate()
            lastLoggedCombinedMah = 0f
            discardInFlightLocked()
            dirty = true
        }
        flush()
        pushSnapshotNow()
        onLog(LogLevel.INFO, "BatteryEst", "Battery capacity estimate reset.")
    }

    /** Some kernels report the design capacity 10x too small (516 instead of 5160). Fix it when the full capacity proves it. */
    /**
     * Time to full from the learned charge-power curve, one slice per 10% of battery, so the slow
     * taper near 100% is counted. The slice being charged now uses the live power; slices not learned
     * yet use the live power, reduced near the top where charging always slows.
     */
    private fun chargeHoursToFullLocked(totalWh: Float): Double {
        var hours = 0.0
        val startBin = (lastBatteryPct / 10).coerceIn(0, 9)
        for (bin in startBin..9) {
            val from = kotlin.math.max(lastBatteryPct, bin * 10)
            val frac = ((bin + 1) * 10 - from) / 100f
            if (frac <= 0f) continue
            val learned = chgCurveW[bin]
            val w = when {
                bin == startBin -> emaChargeWatts
                learned > 0.5f -> learned
                bin >= 9 -> emaChargeWatts * 0.35f
                bin >= 8 -> emaChargeWatts * 0.6f
                else -> emaChargeWatts
            }.coerceAtLeast(0.5f)
            hours += (totalWh * frac).toDouble() / w
        }
        return hours
    }

    private fun estimateDesignMahLocked(): Float =
        (if (designOverrideMah > 0) designOverrideMah else plausibleDesignMah(sysfsDesignCapacityMah, sysfsFullCapacityMah)).toFloat()

    private fun plausibleDesignMah(design: Int, fullMah: Int): Int {
        if (design <= 0 || fullMah <= 0) return design
        val ratio = fullMah.toFloat() / design.toFloat()
        if (ratio in 0.5f..1.6f) return design
        val x10 = design * 10
        val ratio10 = fullMah.toFloat() / x10.toFloat()
        return if (ratio10 in 0.5f..1.6f) x10 else 0
    }

    fun pushSnapshotNow() {
        val snap = synchronized(lock) {
            val activeDelta = if (segPhase == SegPhase.COLLECTING && segStartPct >= 0 && lastBatteryPct >= 0) {
                if (segState == BatteryState.DISCHARGING) (segStartPct - lastBatteryPct).coerceAtLeast(0)
                else (lastBatteryPct - segStartPct).coerceAtLeast(0)
            } else 0

            val resolvedDesign = if (designOverrideMah > 0) designOverrideMah else plausibleDesignMah(sysfsDesignCapacityMah, sysfsFullCapacityMah)
            val combMah = combined.combinedMah
            val combWh = combined.combinedWh
            val soh = if (combMah > 0f && resolvedDesign > 0) {
                (combMah / resolvedDesign.toFloat()) * 100f
            } else -1f

            val remWh = if (combWh > 0f && lastBatteryPct in 0..100) {
                combWh * (lastBatteryPct / 100f)
            } else -1f

            val timeRemMin = if (lastBatteryState == BatteryState.DISCHARGING &&
                remWh > 0f && emaDischargeWatts > 0.15f && combined.confidencePct >= 15
            ) {
                ((remWh / emaDischargeWatts) * 60f).roundToInt().coerceIn(0, 99_999)
            } else -1

            val timeFullMin = if (lastBatteryState == BatteryState.CHARGING &&
                combWh > 0f && lastBatteryPct in 0..99 && emaChargeWatts > 0.15f && combined.confidencePct >= 15
            ) {
                (chargeHoursToFullLocked(combWh) * 60.0).roundToInt().coerceIn(0, 99_999)
            } else -1

            val counterEst = if (lastChargeCounterMah > 0f && lastBatteryPct >= 5) {
                lastChargeCounterMah / (lastBatteryPct / 100f)
            } else -1f

            val resolvedCycles = when {
                osCycleCount >= 0 -> osCycleCount
                sysfsCycleCount >= 0 -> sysfsCycleCount
                else -> -1
            }

            val recent = (dischargeSegments + chargeSegments)
                .sortedByDescending { it.endTimeMs }
                .take(8)

            BatteryEstimatorUiSnapshot(
                enabled = enabled,
                batteryPct = lastBatteryPct,
                batteryState = lastBatteryState,
                livePowerInWatts = livePowerInWatts,
                livePowerOutWatts = livePowerOutWatts,
                batteryTempC = lastTempC,
                batteryVoltageMv = lastVoltageMv,
                batteryHealthLabel = lastHealthLabel,
                cycleCount = resolvedCycles,
                designCapacityMah = resolvedDesign,
                sysfsDesignCapacityMah = sysfsDesignCapacityMah,
                sysfsFullCapacityMah = sysfsFullCapacityMah,
                chargeCounterMah = lastChargeCounterMah,
                chargeCounterEstimateMah = counterEst,
                dischargeCapacityMah = combined.discharge.capacityMah,
                dischargeCapacityWh = combined.discharge.capacityWh,
                dischargeSegmentCount = combined.discharge.validCount,
                chargeCapacityMah = combined.charge.capacityMah,
                chargeCapacityWh = combined.charge.capacityWh,
                chargeSegmentCount = combined.charge.validCount,
                combinedCapacityMah = combMah,
                combinedCapacityWh = combWh,
                totalValidSegments = combined.discharge.validCount + combined.charge.validCount,
                confidencePct = combined.confidencePct,
                directionDisagreementPct = combined.disagreementPct,
                hasDirectionDisagreement = combined.hasDisagreement,
                sohPct = soh,
                remainingWh = remWh,
                timeRemainingMin = timeRemMin,
                timeToFullMin = timeFullMin,
                lifetimeWhOut = lifetimeWhOut.toFloat(),
                lifetimeWhIn = lifetimeWhIn.toFloat(),
                lifetimeMahOut = lifetimeMahOut.toFloat(),
                lifetimeMahIn = lifetimeMahIn.toFloat(),
                sessionWhOut = sessionWhOut.toFloat(),
                sessionWhIn = sessionWhIn.toFloat(),
                sessionMahOut = sessionMahOut.toFloat(),
                sessionMahIn = sessionMahIn.toFloat(),
                neededPctToEstimate = BatteryEstimatorMath.MIN_SEGMENT_DELTA_PCT,
                activeSegmentDeltaPct = activeDelta,
                activeSegmentState = segState,
                chargeBandSessionSec = sessionChgBandSec.map { it.toFloat() },
                chargeBandLifetimeSec = lifetimeChgBandSec.map { it.toFloat() },
                peakChargeVoltageMv = peakChargeMv,
                chargeTrustPct = combined.chargeTrustPct,
                recentSegments = recent
            )
        }
        _snapshot.value = snap
    }

    private fun persistAsyncOrBlocking(jsonText: String) {
        if (scope != null) {
            scope.launch(Dispatchers.IO) {
                writeJsonAtomically(jsonText)
            }
        } else {
            writeJsonAtomically(jsonText)
        }
    }

    private fun writeJsonAtomically(jsonText: String) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${storageFile.name}.tmp")
        try {
            tmp.writeText(jsonText)
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Throwable) {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun serializeLocked(): String {
        fun safeNum(v: Double): String =
            if (v.isFinite()) String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
                .let { if (it == "-0") "0" else it }
            else "0"

        fun StringBuilder.appendSeg(s: BatterySegmentRecord) {
            append("{\"endTimeMs\":").append(s.endTimeMs)
            append(",\"dir\":\"").append(s.direction.name).append('"')
            append(",\"startPct\":").append(s.startPct)
            append(",\"endPct\":").append(s.endPct)
            append(",\"deltaPct\":").append(s.deltaPct)
            append(",\"durationMs\":").append(s.durationMs)
            append(",\"mah\":").append(safeNum(s.integratedMah.toDouble()))
            append(",\"wh\":").append(safeNum(s.integratedWh.toDouble()))
            append(",\"avgCurMa\":").append(safeNum(s.avgCurrentMa.toDouble()))
            append(",\"estMah\":").append(safeNum(s.estCapacityMah.toDouble()))
            append(",\"estWh\":").append(safeNum(s.estCapacityWh.toDouble()))
            append(",\"avgW\":").append(safeNum(s.avgWatts.toDouble()))
            append(",\"pkMv\":").append(safeNum(s.peakVoltageMv.toDouble()))
            append(",\"pkMa\":").append(safeNum(s.peakCurrentMa.toDouble()))
            append(",\"bands\":[")
            s.bandSec.forEachIndexed { i, v -> if (i > 0) append(','); append(safeNum(v.toDouble())) }
            append(']')
            append('}')
        }

        val sb = StringBuilder(1024)
        sb.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
        sb.append(",\"savedAtMs\":").append(System.currentTimeMillis())
        sb.append(",\"lifetimeWhOut\":").append(safeNum(lifetimeWhOut))
        sb.append(",\"lifetimeWhIn\":").append(safeNum(lifetimeWhIn))
        sb.append(",\"lifetimeMahOut\":").append(safeNum(lifetimeMahOut))
        sb.append(",\"lifetimeMahIn\":").append(safeNum(lifetimeMahIn))
        sb.append(",\"sysfsDesignCapacityMah\":").append(sysfsDesignCapacityMah)
        sb.append(",\"sysfsFullCapacityMah\":").append(sysfsFullCapacityMah)
        sb.append(",\"sysfsCycleCount\":").append(sysfsCycleCount)
        sb.append(",\"peakChargeMv\":").append(safeNum(peakChargeMv.toDouble()))
        sb.append(",\"chgBandLife\":[")
        lifetimeChgBandSec.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(safeNum(v)) }
        sb.append("],\"chgCurveW\":[")
        chgCurveW.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(safeNum(v.toDouble())) }
        sb.append(']')
        sb.append(",\"dischargeSegments\":[")
        dischargeSegments.forEachIndexed { idx, s ->
            if (idx > 0) sb.append(',')
            sb.appendSeg(s)
        }
        sb.append("],\"chargeSegments\":[")
        chargeSegments.forEachIndexed { idx, s ->
            if (idx > 0) sb.append(',')
            sb.appendSeg(s)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun deserializeFromString(raw: String) {
        val root = MiniJson.parseObject(raw) ?: throw IllegalArgumentException("Invalid JSON root")
        fun Any?.asLong(default: Long = 0L): Long = (this as? Number)?.toLong() ?: default
        fun Any?.asInt(default: Int = 0): Int = (this as? Number)?.toInt() ?: default
        fun Any?.asDouble(default: Double = 0.0): Double = (this as? Number)?.toDouble() ?: default

        fun parseList(arrAny: Any?, fallbackDir: BatteryState): List<BatterySegmentRecord> {
            val arr = arrAny as? List<*> ?: return emptyList()
            val out = ArrayList<BatterySegmentRecord>()
            for (item in arr) {
                val o = item as? Map<*, *> ?: continue
                val dirName = o["dir"] as? String ?: fallbackDir.name
                val dir = try {
                    BatteryState.valueOf(dirName)
                } catch (_: Throwable) {
                    fallbackDir
                }
                val startPct = o["startPct"].asInt(-1)
                val endPct = o["endPct"].asInt(-1)
                val deltaPct = o["deltaPct"].asInt(0)
                val estMah = o["estMah"].asDouble(0.0).toFloat()
                val estWh = o["estWh"].asDouble(0.0).toFloat()
                if (deltaPct >= BatteryEstimatorMath.MIN_SEGMENT_DELTA_PCT && estMah in 500f..25_000f) {
                    out.add(
                        BatterySegmentRecord(
                            endTimeMs = o["endTimeMs"].asLong(0L),
                            direction = dir,
                            startPct = startPct,
                            endPct = endPct,
                            deltaPct = deltaPct,
                            durationMs = o["durationMs"].asLong(0L),
                            integratedMah = o["mah"].asDouble(0.0).toFloat(),
                            integratedWh = o["wh"].asDouble(0.0).toFloat(),
                            avgCurrentMa = o["avgCurMa"].asDouble(0.0).toFloat(),
                            estCapacityMah = estMah,
                            estCapacityWh = estWh,
                            avgWatts = o["avgW"].asDouble(0.0).toFloat(),
                            peakVoltageMv = o["pkMv"].asDouble(0.0).toFloat(),
                            peakCurrentMa = o["pkMa"].asDouble(0.0).toFloat(),
                            bandSec = (o["bands"] as? List<*>)?.map { it.asDouble(0.0).toFloat() } ?: emptyList()
                        )
                    )
                }
            }
            return out.takeLast(BatteryEstimatorMath.MAX_SEGMENTS_PER_DIRECTION)
        }

        synchronized(lock) {
            lifetimeWhOut = root["lifetimeWhOut"].asDouble(0.0).coerceAtLeast(0.0)
            lifetimeWhIn = root["lifetimeWhIn"].asDouble(0.0).coerceAtLeast(0.0)
            lifetimeMahOut = root["lifetimeMahOut"].asDouble(0.0).coerceAtLeast(0.0)
            lifetimeMahIn = root["lifetimeMahIn"].asDouble(0.0).coerceAtLeast(0.0)
            peakChargeMv = root["peakChargeMv"].asDouble(0.0).toFloat().coerceAtLeast(0f)
            (root["chgBandLife"] as? List<*>)?.forEachIndexed { i, v ->
                if (i < lifetimeChgBandSec.size) lifetimeChgBandSec[i] = v.asDouble(0.0).coerceAtLeast(0.0)
            }
            (root["chgCurveW"] as? List<*>)?.forEachIndexed { i, v ->
                if (i < chgCurveW.size) chgCurveW[i] = v.asDouble(0.0).toFloat().coerceAtLeast(0f)
            }
            sysfsDesignCapacityMah = root["sysfsDesignCapacityMah"].asInt(0).coerceAtLeast(0)
            sysfsFullCapacityMah = root["sysfsFullCapacityMah"].asInt(0).coerceAtLeast(0)
            sysfsCycleCount = root["sysfsCycleCount"].asInt(-1)

            dischargeSegments.clear()
            dischargeSegments.addAll(parseList(root["dischargeSegments"], BatteryState.DISCHARGING))
            chargeSegments.clear()
            chargeSegments.addAll(parseList(root["chargeSegments"], BatteryState.CHARGING))

            combined = BatteryEstimatorMath.combineDirections(
                            dischargeSegments, chargeSegments,
                            internalResistanceMohm, calAnchorMah, calAnchorSlow, estimateDesignMahLocked()
                        )
            lastLoggedCombinedMah = combined.combinedMah
            // Never resume an in-flight segment after restart
            discardInFlightLocked()
            dirty = false
        }
    }
}

private object MiniJson {
    @Suppress("UNCHECKED_CAST")
    fun parseObject(src: String): Map<String, Any?>? = Parser(src).parseRoot() as? Map<String, Any?>

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWs()
            if (pos >= s.length) throw IllegalArgumentException("Empty JSON")
            val v = readValue()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun readValue(): Any? {
            skipWs()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected EOF")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected char '$c' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw IllegalArgumentException("Expected string key")
                val k = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("Expected ':'")
                pos++
                val v = readValue()
                map[k] = v
                skipWs()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= s.length) throw IllegalArgumentException("Bad escape")
                    when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw IllegalArgumentException("Bad unicode")
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape '\\$e'")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            return if (isFloat) token.toDouble() else (token.toLongOrNull() ?: token.toDouble())
        }

        private fun expect(lit: String) {
            if (!s.regionMatches(pos, lit, 0, lit.length)) throw IllegalArgumentException("Expected $lit")
            pos += lit.length
        }
    }
}
