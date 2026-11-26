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

import kotlin.math.abs
import kotlin.math.exp

enum class BatteryChemistry(val label: String) {
    UNKNOWN("Detecting"),
    STANDARD("Standard Li-ion (4.2 V)"),
    HIGH_VOLTAGE("High-voltage Li-ion (4.4 V)")
}

/** Live result of the voltage/current based battery grading. */
data class BatteryTruthState(
    val valid: Boolean = false,
    /** Voltage-corrected state of charge estimate, 0..100. Only meaningful when valid. */
    val truePct: Float = -1f,
    /** truePct minus the gauge percentage. Positive = the gauge under-reports (real headroom). */
    val headroomPct: Float = 0f,
    val chemistry: BatteryChemistry = BatteryChemistry.UNKNOWN,
    val internalResistanceMohm: Float = 100f,
    /** Voltage lost to load right now (current x internal resistance), in mV. */
    val sagMv: Float = 0f,
    /** Voltage with the sag added back (the open-circuit voltage estimate), in mV. */
    val restingMv: Float = 0f,
    val confidencePct: Int = 0,
    val calChargeDone: Boolean = false,
    val calDischargeDone: Boolean = false,
    /** "" when idle, otherwise which calibration run is currently being recorded. */
    val calRunning: String = "",
    val fullChargeMv: Float = 0f,
    val fullChargeMah: Float = 0f,
    /** How many load steps the resistance was learned from (0 = still the default guess). */
    val resistanceSamples: Int = 0,
    /** Full capacity read from the last clean charge to 100% (normalised for the start level), 0 = none. */
    val calCapacityMah: Float = 0f,
    /** True when that charge was mostly slow (under 15 W), which the fuel gauge tracks most faithfully. */
    val calCapacitySlow: Boolean = false,
    /** True when truePct was graded while charging (voltage minus current x resistance). Less trustworthy: decisions such as Afterburn ignore it. */
    val charging: Boolean = false
)

/** v3.1.0 pure maths: open-circuit voltage curves and interpolation. */
object BatteryTruthMath {
    // Open-circuit voltage (V) to state of charge (%), highest first.
    private val STD_V = floatArrayOf(4.20f, 4.15f, 4.11f, 4.08f, 4.02f, 3.98f, 3.95f, 3.91f, 3.87f, 3.85f,
        3.84f, 3.82f, 3.80f, 3.79f, 3.77f, 3.75f, 3.73f, 3.71f, 3.69f, 3.61f, 3.27f)
    private val STD_P = floatArrayOf(100f, 95f, 90f, 85f, 80f, 75f, 70f, 65f, 60f, 55f,
        50f, 45f, 40f, 35f, 30f, 25f, 20f, 15f, 10f, 5f, 0f)
    private val HV_V = floatArrayOf(4.40f, 4.33f, 4.27f, 4.21f, 4.15f, 4.09f, 4.03f, 3.98f, 3.94f, 3.90f,
        3.87f, 3.84f, 3.82f, 3.80f, 3.78f, 3.76f, 3.74f, 3.71f, 3.68f, 3.60f, 3.30f)
    private val HV_P = STD_P

    /** Anything above this resting voltage cannot be a standard 4.2 V cell. */
    const val HV_CERTAIN_V = 4.26f

    /** Interpolates a learned curve: mv[i] is the resting voltage (mV) at i*5 %, 0 = not learned. */
    fun socFromLearned(ocvMv: Float, mv: FloatArray): Float {
        var loP = -1; var hiP = -1
        for (i in mv.indices) if (mv[i] > 0f) { if (loP < 0) loP = i; hiP = i }
        if (loP < 0) return -1f
        if (ocvMv <= mv[loP]) return loP * 5f
        if (ocvMv >= mv[hiP]) return hiP * 5f
        var prev = loP
        for (i in loP + 1..hiP) {
            if (mv[i] <= 0f) continue
            if (ocvMv <= mv[i]) {
                val span = mv[i] - mv[prev]
                val t = if (span > 0f) (ocvMv - mv[prev]) / span else 0f
                return prev * 5f + ((i - prev) * 5f) * t
            }
            prev = i
        }
        return hiP * 5f
    }

    fun socFromOcv(ocvV: Float, hv: Boolean): Float {
        val v = if (hv) HV_V else STD_V
        val p = if (hv) HV_P else STD_P
        if (ocvV >= v[0]) return 100f
        if (ocvV <= v[v.size - 1]) return 0f
        for (i in 0 until v.size - 1) {
            if (ocvV <= v[i] && ocvV >= v[i + 1]) {
                val span = v[i] - v[i + 1]
                val t = if (span > 0f) (ocvV - v[i + 1]) / span else 0f
                return p[i + 1] + (p[i] - p[i + 1]) * t
            }
        }
        return 0f
    }
}

/**
 * Grades the real battery level from voltage and current behaviour:
 *  - learns the internal resistance from voltage drops when the load current jumps,
 *  - adds the sag back (open-circuit voltage = V + I x R) and reads the level from a curve,
 *  - tells a high-voltage (4.4 V) cell from a standard (4.2 V) cell by what the cell actually does.
 * Used from the governor loop thread only. Not persisted: it re-learns within a few minutes.
 */
class BatteryTruthModel {
    private var rOhm = 0.10f
    private var rSamples = 0
    private var prevV = 0f
    private var prevIdisA = 0f
    private var prevMs = 0L
    private var smoothedOcvV = 0f
    private var lastMs = 0L
    private var settledTicks = 0
    private var hvLatched = false
    private var evidence = 0f // positive favours high-voltage
    // v3.6.6 charge-side grading: smoothed (V - charge current x R) while charging
    private var chgOcvV = 0f
    private var chgSettledTicks = 0

    // v3.1.0 calibration profile (saved between runs; survives reset())
    private var fullChargeMv = 0f
    private var fullChargeMah = 0f
    private var chargeDone = false
    private var dischargeDone = false
    private var calCapMah = 0f
    private var calSlow = false
    private var chargeTotalMs = 0L
    private var chargeHighMs = 0L
    private val learnedMv = FloatArray(21)
    private var dirty = false

    // calibration run trackers
    private var lastCalMs = 0L
    private var chargeRun = false
    private var chargeStartPct = 0
    private var chargeMah = 0f
    private var taperMs = 0L
    private var taperVSum = 0f
    private var taperN = 0
    private var dischargeRun = false
    private val bucketSum = FloatArray(21)
    private val bucketN = IntArray(21)

    fun reset() {
        rOhm = 0.10f; rSamples = 0; prevV = 0f; prevIdisA = 0f; prevMs = 0L
        smoothedOcvV = 0f; lastMs = 0L; settledTicks = 0; hvLatched = false; evidence = 0f
        chgOcvV = 0f; chgSettledTicks = 0
        abortRuns(); lastCalMs = 0L
    }

    private fun abortRuns() {
        chargeRun = false; chargeMah = 0f; taperMs = 0L; taperVSum = 0f; taperN = 0
        chargeTotalMs = 0L; chargeHighMs = 0L
        dischargeRun = false; bucketSum.fill(0f); bucketN.fill(0)
    }

    /** Text form of the calibration profile for saving (plain text, no Android classes). */
    fun serialize(): String =
        "v1;$fullChargeMv;$fullChargeMah;$chargeDone;$dischargeDone;" + learnedMv.joinToString(",") + ";$calCapMah;$calSlow"

    fun load(text: String?) {
        if (text.isNullOrBlank()) return
        try {
            val p = text.split(";")
            if (p.size < 6 || p[0] != "v1") return
            fullChargeMv = p[1].toFloat(); fullChargeMah = p[2].toFloat()
            chargeDone = p[3].toBoolean(); dischargeDone = p[4].toBoolean()
            val c = p[5].split(",")
            for (i in 0 until minOf(21, c.size)) learnedMv[i] = c[i].toFloat()
            if (p.size >= 8) { calCapMah = p[6].toFloat(); calSlow = p[7].toBoolean() }
        } catch (_: Exception) {
            fullChargeMv = 0f; fullChargeMah = 0f; chargeDone = false; dischargeDone = false; learnedMv.fill(0f)
            calCapMah = 0f; calSlow = false
        }
    }

    /** Returns the text to save when the profile changed since the last call, else null. */
    fun takeDirtySerialized(): String? {
        if (!dirty) return null
        dirty = false
        return serialize()
    }

    fun clearCalibration() {
        fullChargeMv = 0f; fullChargeMah = 0f; chargeDone = false; dischargeDone = false
        calCapMah = 0f; calSlow = false
        learnedMv.fill(0f); abortRuns(); dirty = true
    }

    /**
     * Calibration: one undisturbed charge until the battery stops taking power (pct 100, current tapers)
     * records the full-charge voltage (this alone settles 4.2 V vs 4.4 V) and the charged capacity; one
     * undisturbed discharge from 95%+ down to the bottom records the real resting-voltage curve.
     * Any gap, plug change or restart discards the run in progress.
     */
    private fun calibrate(nowMs: Long, vMv: Float, absMa: Float, pct: Int, discharging: Boolean, plugged: Boolean, ocvV: Float) {
        val dt = if (lastCalMs > 0L) nowMs - lastCalMs else 0L
        lastCalMs = nowMs
        if (dt > 60_000L) abortRuns()
        if (pct !in 0..100) { abortRuns(); return }
        val step = if (dt in 1..5_000L) dt else 0L

        if (plugged) {
            if (dischargeRun) { dischargeRun = false; bucketSum.fill(0f); bucketN.fill(0) }
            if (!chargeRun) { chargeRun = true; chargeStartPct = pct; chargeMah = 0f; taperMs = 0L; taperVSum = 0f; taperN = 0; chargeTotalMs = 0L; chargeHighMs = 0L }
            chargeMah += absMa * step / 3_600_000f
            chargeTotalMs += step
            if ((absMa / 1000f) * (vMv / 1000f) >= 15f) chargeHighMs += step
            if (pct >= 100 && absMa <= 150f && vMv >= 3900f) {
                taperMs += step; taperVSum += vMv; taperN++
                if (taperMs >= 60_000L && taperN > 0) {
                    fullChargeMv = taperVSum / taperN
                    if (chargeStartPct <= 40) {
                        fullChargeMah = chargeMah
                        // Charged mAh covers only (100 - start)% of the battery: scale it to the full capacity.
                        calCapMah = chargeMah / ((100 - chargeStartPct) / 100f)
                        calSlow = chargeTotalMs > 0L && chargeHighMs.toFloat() / chargeTotalMs <= 0.2f
                    }
                    chargeDone = true; dirty = true
                    chargeRun = false
                }
            } else if (pct >= 100) {
                taperMs = 0L; taperVSum = 0f; taperN = 0
            }
        } else {
            if (chargeRun) { chargeRun = false; chargeMah = 0f; taperMs = 0L; taperVSum = 0f; taperN = 0 }
            if (discharging) {
                if (!dischargeRun && pct >= 95) { dischargeRun = true; bucketSum.fill(0f); bucketN.fill(0) }
                if (dischargeRun) {
                    val b = ((pct + 2) / 5).coerceIn(0, 20)
                    if (kotlin.math.abs(pct - b * 5) <= 1) { bucketSum[b] += ocvV * 1000f; bucketN[b]++ }
                    if (pct <= 3) {
                        var filled = 0
                        val tmp = FloatArray(21)
                        for (i in 0..20) if (bucketN[i] >= 20) { tmp[i] = bucketSum[i] / bucketN[i]; filled++ }
                        if (filled >= 16) {
                            var last = Float.MAX_VALUE // voltage must fall as the level falls
                            for (i in 20 downTo 0) {
                                if (tmp[i] <= 0f) continue
                                if (tmp[i] > last) tmp[i] = last
                                last = tmp[i]
                            }
                            for (i in 0..20) learnedMv[i] = tmp[i]
                            dischargeDone = true; dirty = true
                        }
                        dischargeRun = false; bucketSum.fill(0f); bucketN.fill(0)
                    }
                }
            }
        }
    }

    /**
     * @param dischargeMa current out of the battery in mA (positive = discharging, negative = charging)
     */
    fun update(
        nowMs: Long,
        voltageMv: Float,
        dischargeMa: Float,
        gaugePct: Int,
        discharging: Boolean,
        plugged: Boolean = !discharging
    ): BatteryTruthState {
        if (voltageMv < 2500f || voltageMv > 5000f) return current(false, gaugePct, 0f)
        val vV = voltageMv / 1000f
        val iA = dischargeMa / 1000f

        // Learn internal resistance from a clear load step seen within 2 s.
        if (prevMs > 0L && nowMs - prevMs in 1..2000L) {
            val dI = iA - prevIdisA
            if (abs(dI) >= 0.4f) {
                val r = (prevV - vV) / dI
                if (r in 0.02f..0.30f) {
                    rOhm += (r - rOhm) * (if (rSamples < 8) 0.25f else 0.08f)
                    rSamples++
                }
            }
        }
        prevV = vV; prevIdisA = iA; prevMs = nowMs

        val sagV = (iA.coerceAtLeast(0f)) * rOhm
        val ocv = vV + sagV
        calibrate(nowMs, voltageMv, abs(dischargeMa), gaugePct, discharging, plugged, ocv)
        val dt = if (lastMs > 0L) (nowMs - lastMs).coerceIn(1L, 5000L) else 0L
        lastMs = nowMs

        if (!discharging) {
            // Charging voltage is pushed up by the charger: the discharge grade is off, the charge grade is separate (below).
            smoothedOcvV = 0f
            settledTicks = 0
            if (ocv > BatteryTruthMath.HV_CERTAIN_V + 0.04f) hvLatched = true
            // v3.6.6: while charging, take the charge current's voltage rise (I x R) back off instead.
            val chargeA = -iA
            if (chargeA >= CHG_MIN_A) {
                val cocv = vV - chargeA * rOhm
                val a = if (chgOcvV <= 0f) 1f else (1f - exp(-dt / 20_000f)).coerceIn(0.002f, 1f)
                chgOcvV = if (chgOcvV <= 0f) cocv else chgOcvV + (cocv - chgOcvV) * a
                if (chgSettledTicks < 100_000) chgSettledTicks++
            } else {
                chgOcvV = 0f; chgSettledTicks = 0
            }
            return currentCharging(gaugePct, sagV * 1000f)
        }

        val alpha = if (smoothedOcvV <= 0f) 1f else (1f - exp(-dt / 20_000f)).coerceIn(0.002f, 1f)
        smoothedOcvV = if (smoothedOcvV <= 0f) ocv else smoothedOcvV + (ocv - smoothedOcvV) * alpha
        if (settledTicks < 100_000) settledTicks++

        if (smoothedOcvV > BatteryTruthMath.HV_CERTAIN_V) hvLatched = true
        if (gaugePct in 5..95) {
            val std = BatteryTruthMath.socFromOcv(smoothedOcvV, false)
            val hv = BatteryTruthMath.socFromOcv(smoothedOcvV, true)
            val diff = (abs(std - gaugePct) - abs(hv - gaugePct)).coerceIn(-30f, 30f)
            evidence += (diff - evidence) * 0.01f
        }
        return current(true, gaugePct, sagV * 1000f)
    }

    /**
     * Real level while charging. The charger pushes the voltage up, so this is only trusted between 5% and 90%
     * (before the constant-voltage taper), after the voltage has settled, once the chemistry is known, and it may
     * never stray more than 10 points from the gauge. Flagged charging = true so the Afterburn decision ignores it.
     */
    private fun currentCharging(gaugePct: Int, sagMv: Float): BatteryTruthState {
        val chem = chemistry()
        if (chgOcvV <= 0f || chgSettledTicks < CHG_SETTLE_TICKS || chem == BatteryChemistry.UNKNOWN || gaugePct !in 5..90) {
            return current(false, gaugePct, sagMv)
        }
        val learned = if (dischargeDone) BatteryTruthMath.socFromLearned(chgOcvV * 1000f, learnedMv) else -1f
        val raw = if (learned >= 0f) learned else BatteryTruthMath.socFromOcv(chgOcvV, chem == BatteryChemistry.HIGH_VOLTAGE)
        val pct = raw.coerceIn((gaugePct - 10).coerceAtLeast(0).toFloat(), (gaugePct + 10).coerceAtMost(100).toFloat())
        val conf = (25 + rSamples.coerceAtMost(10) * 3 + (chgSettledTicks / 40).coerceAtMost(20) +
            (if (dischargeDone) 10 else 0)).coerceIn(0, 70)
        return BatteryTruthState(true, pct, pct - gaugePct, chem, rOhm * 1000f, sagMv, chgOcvV * 1000f, conf,
            chargeDone, dischargeDone, runLabel(), fullChargeMv, fullChargeMah, rSamples, calCapMah, calSlow, true)
    }

    private companion object {
        const val CHG_MIN_A = 0.15f
        const val CHG_SETTLE_TICKS = 60
    }

    private fun chemistry(): BatteryChemistry = when {
        fullChargeMv >= 4270f -> BatteryChemistry.HIGH_VOLTAGE
        fullChargeMv in 4000f..4269f -> BatteryChemistry.STANDARD
        hvLatched -> BatteryChemistry.HIGH_VOLTAGE
        evidence > 4f -> BatteryChemistry.HIGH_VOLTAGE
        evidence < -4f -> BatteryChemistry.STANDARD
        settledTicks > 400 && smoothedOcvV in 3.5f..4.18f && evidence <= 0f -> BatteryChemistry.STANDARD
        else -> BatteryChemistry.UNKNOWN
    }

    private fun current(valid: Boolean, gaugePct: Int, sagMv: Float): BatteryTruthState {
        val chem = chemistry()
        val resting = if (smoothedOcvV > 0f) smoothedOcvV * 1000f else 0f
        if (!valid || smoothedOcvV <= 0f || chem == BatteryChemistry.UNKNOWN || gaugePct !in 0..100) {
            return BatteryTruthState(false, -1f, 0f, chem, rOhm * 1000f, sagMv, resting, 0,
                chargeDone, dischargeDone, runLabel(), fullChargeMv, fullChargeMah, rSamples, calCapMah, calSlow)
        }
        val learned = if (dischargeDone) BatteryTruthMath.socFromLearned(smoothedOcvV * 1000f, learnedMv) else -1f
        val raw = if (learned >= 0f) learned else BatteryTruthMath.socFromOcv(smoothedOcvV, chem == BatteryChemistry.HIGH_VOLTAGE)
        // Safety: never stray far from the gauge, a curve is an estimate.
        val pct = raw.coerceIn((gaugePct - 20).coerceAtLeast(0).toFloat(), (gaugePct + 20).coerceAtMost(100).toFloat())
        val conf = (30 + (rSamples.coerceAtMost(10) * 4) + (settledTicks / 40).coerceAtMost(30) +
            (if (chargeDone) 10 else 0) + (if (dischargeDone) 15 else 0)).coerceIn(0, 100)
        return BatteryTruthState(true, pct, pct - gaugePct, chem, rOhm * 1000f, sagMv, resting, conf,
            chargeDone, dischargeDone, runLabel(), fullChargeMv, fullChargeMah, rSamples, calCapMah, calSlow)
    }

    private fun runLabel(): String = when {
        chargeRun && chargeStartPct <= 40 -> "charge"
        dischargeRun -> "discharge"
        else -> ""
    }
}
