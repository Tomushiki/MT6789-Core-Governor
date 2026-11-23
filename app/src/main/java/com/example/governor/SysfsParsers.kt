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

import android.os.BatteryManager
import com.example.model.BatteryState
import com.example.model.GpuOpp
import com.example.model.PowerPolarity
import com.example.model.PowerUnitScale
import com.example.model.TempThrottleConfig
import com.example.model.ThrottleCurveShape
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CpuTickSnapshot(
    val cpuId: Int,
    val totalTicks: Long,
    val idleTicks: Long
)

data class ParsedGpufreqStatus(
    val freqKhz: Long? = null,
    val oppIndex: Int? = null,
    val utilPct: Float? = null
)

data class ParsedOppTableResult(
    val sortedOpps: List<GpuOpp>,
    val orderDescription: String
)

data class GpuPositionSelection(
    val targetPos: Int,
    val cappedMaxPos: Int,
    val allowedMinPos: Int,
    val allowedMaxPos: Int,
    val targetOpp: GpuOpp,
    val cappedMaxKhz: Long
)

data class PowerReading(
    val signedWatts: Float,
    val dischargeWatts: Float,
    val chargeWatts: Float,
    val state: BatteryState,
    val currentMa: Float,
    val voltageMv: Float,
    val watts: Float = dischargeWatts
)

object SysfsParsers {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val OPP_IDX_REGEX = Regex("""\[\s*(\d+)\s*\*?\s*\]|OPP\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val OPP_FREQ_REGEX = Regex("""(?:freq|khz)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val MHZ_REGEX = Regex("""(\d+(?:\.\d+)?)\s*MHz""", RegexOption.IGNORE_CASE)
    private val KHZ_REGEX = Regex("""(\d+)\s*KHz""", RegexOption.IGNORE_CASE)
    private val OPP_VOLT_REGEX = Regex("""(?:volt|vgpu|v_gpu)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val WORD_DIGITS_REGEX = Regex("""\b\d+\b""")
    private val STATUS_OPP_PATTERNS = listOf(
        Regex("""(?:cur_opp|current\s+opp|working\s+opp|opp(?:_idx|_index)?)\s*[:=]\s*\[?\s*(-?\d+)\s*\]?""", RegexOption.IGNORE_CASE),
        Regex("""OPP\s*\[?\s*(-?\d+)\s*\]?""", RegexOption.IGNORE_CASE)
    )
    private val STATUS_FREQ_REGEX = Regex("""(?:cur_freq|current\s+freq|gpu_freq|freq)\s*[:=]\s*(\d+)\s*(khz|mhz)?""", RegexOption.IGNORE_CASE)
    private val STATUS_UTIL_REGEX = Regex("""(?:loading|util(?:ization)?|busy)\s*[:=]\s*(\d+(?:\.\d+)?)\s*%?""", RegexOption.IGNORE_CASE)
    private val GPU_UTIL_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

    fun parseProcStat(raw: String): Map<Int, CpuTickSnapshot> {
        val result = mutableMapOf<Int, CpuTickSnapshot>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("cpu") && trimmed.length > 3 && trimmed[3].isDigit()) {
                val parts = trimmed.split(WHITESPACE_REGEX)
                val cpuId = parts[0].removePrefix("cpu").toIntOrNull() ?: return@forEach
                if (parts.size >= 5) {
                    val user = parts[1].toLongOrNull() ?: 0L
                    val nice = parts[2].toLongOrNull() ?: 0L
                    val system = parts[3].toLongOrNull() ?: 0L
                    val idle = parts[4].toLongOrNull() ?: 0L
                    val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                    val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0L
                    val total = user + nice + system + idle + iowait + irq + softirq + steal
                    val idleAll = idle + iowait
                    result[cpuId] = CpuTickSnapshot(cpuId, total, idleAll)
                }
            }
        }
        return result
    }

    fun computeClusterLoadPct(
        relatedCpus: List<Int>,
        prev: Map<Int, CpuTickSnapshot>,
        curr: Map<Int, CpuTickSnapshot>,
        useBusiestCore: Boolean = false
    ): Float {
        if (relatedCpus.isEmpty() || prev.isEmpty()) return 0f
        var sumPct = 0f
        var maxPct = 0f
        var count = 0
        for (cpuId in relatedCpus) {
            val p = prev[cpuId] ?: continue
            val c = curr[cpuId] ?: continue
            val totalDelta = c.totalTicks - p.totalTicks
            val idleDelta = c.idleTicks - p.idleTicks
            if (totalDelta > 0L) {
                val busyDelta = (totalDelta - idleDelta).coerceAtLeast(0L)
                val pct = ((busyDelta.toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
                sumPct += pct
                if (pct > maxPct) maxPct = pct
                count++
            }
        }
        if (count == 0) return 0f
        return if (useBusiestCore) maxPct else (sumPct / count).coerceIn(0f, 100f)
    }

    fun computeTotalCpuLoadPct(
        cpuIds: List<Int>,
        prev: Map<Int, CpuTickSnapshot>,
        curr: Map<Int, CpuTickSnapshot>
    ): Float {
        if (cpuIds.isEmpty() || prev.isEmpty()) return 0f
        var sumPct = 0f
        var count = 0
        for (cpuId in cpuIds) {
            val p = prev[cpuId] ?: continue
            val c = curr[cpuId] ?: continue
            val totalDelta = c.totalTicks - p.totalTicks
            val idleDelta = c.idleTicks - p.idleTicks
            if (totalDelta > 0L) {
                val busyDelta = (totalDelta - idleDelta).coerceAtLeast(0L)
                val pct = ((busyDelta.toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
                sumPct += pct
                count++
            }
        }
        if (count == 0) return 0f
        return (sumPct / count).coerceIn(0f, 100f)
    }

    fun computePerCoreLoadPct(
        cpuIds: List<Int>,
        prev: Map<Int, CpuTickSnapshot>,
        curr: Map<Int, CpuTickSnapshot>
    ): Map<Int, Float> {
        if (cpuIds.isEmpty() || prev.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, Float>()
        for (cpuId in cpuIds) {
            val p = prev[cpuId] ?: continue
            val c = curr[cpuId] ?: continue
            val totalDelta = c.totalTicks - p.totalTicks
            val idleDelta = c.idleTicks - p.idleTicks
            if (totalDelta > 0L) {
                val busyDelta = (totalDelta - idleDelta).coerceAtLeast(0L)
                val pct = ((busyDelta.toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
                result[cpuId] = pct
            }
        }
        return result
    }

    /**
     * Parses MediaTek /proc/gpufreqv2/{gpu_working_opp_table,gpu_signed_opp_table,stack_working_opp_table}.
     * Handles formats such as:
     *   [00] freq: 1000000, volt: 85000, vsram: 90000
     *   [ 0*] freq = 1100000, vgpu = 90000
     *   OPP0: freq=950000 volt=82500
     *   0 1000000 85000
     * Detects whether the raw table starts highest-first or lowest-first and always returns
     * sortedOpps ordered lowest-to-highest frequency, preserving each entry's real oppIndex.
     */
    fun parseGpufreqOppTable(raw: String): ParsedOppTableResult {
        val rawEntries = mutableListOf<GpuOpp>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) return@forEach

            val idxMatch = OPP_IDX_REGEX.find(trimmed)
            var oppIndex = idxMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

            var freqKhz = OPP_FREQ_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (freqKhz == null) {
                MHZ_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { mhz ->
                    freqKhz = (mhz * 1000.0).toLong()
                }
            }
            var voltRaw = OPP_VOLT_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()

            // Fallback for plain whitespace/comma-separated rows like "0  1000000  85000" or "[0] 1000000 85000"
            if (oppIndex == null || freqKhz == null) {
                val nums = WORD_DIGITS_REGEX.findAll(trimmed).mapNotNull { it.value.toLongOrNull() }.toList()
                if (nums.size >= 2) {
                    val candidateIdx = oppIndex ?: nums[0].toInt()
                    val candidateFreq = freqKhz ?: nums.firstOrNull { it >= 10_000L && it != candidateIdx.toLong() }
                    val candidateVolt = voltRaw ?: nums.dropWhile { it != candidateFreq }.drop(1).firstOrNull()
                    if (candidateIdx in 0..255 && candidateFreq != null) {
                        oppIndex = candidateIdx
                        freqKhz = candidateFreq
                        voltRaw = candidateVolt
                    }
                }
            }

            if (oppIndex != null && freqKhz != null && freqKhz!! > 10_000L) {
                rawEntries.add(GpuOpp(oppIndex = oppIndex!!, freqKhz = freqKhz!!, voltRaw = voltRaw))
            }
        }

        val deduped = rawEntries.distinctBy { it.oppIndex }
        if (deduped.isEmpty()) {
            return ParsedOppTableResult(emptyList(), "Empty OPP table")
        }

        val firstFreq = deduped.first().freqKhz
        val lastFreq = deduped.last().freqKhz
        val orderDesc = when {
            deduped.size == 1 -> "Single OPP"
            firstFreq > lastFreq -> "Highest-first in kernel (idx ${deduped.first().oppIndex} = ${firstFreq / 1000}MHz)"
            firstFreq < lastFreq -> "Lowest-first in kernel (idx ${deduped.first().oppIndex} = ${firstFreq / 1000}MHz)"
            else -> "Flat/custom order"
        }

        val sorted = deduped.sortedWith(compareBy<GpuOpp> { it.freqKhz }.thenBy { it.oppIndex })
        return ParsedOppTableResult(sortedOpps = sorted, orderDescription = orderDesc)
    }

    /**
     * Formats MTK gpufreqv2 raw voltage into readable millivolts.
     * MTK gpufreqv2 commonly prints voltage in 10uV steps (e.g., 85000 = 850.0 mV),
     * or uV (e.g., 850000 = 850.0 mV), or direct mV (e.g., 850 = 850.0 mV).
     */
    fun formatVoltMv(voltRaw: Long?): String {
        if (voltRaw == null || voltRaw <= 0L) return "--"
        val mv = when {
            voltRaw >= 200_000L -> voltRaw / 1000f
            voltRaw >= 10_000L -> voltRaw / 100f
            else -> voltRaw.toFloat()
        }
        return "${"%.1f".format(mv)} mV"
    }

    /**
     * Parses /proc/gpufreqv2/gpufreq_status robustly across MT6789/MTK gpufreqv2 variants:
     * Handles lines like:
     *   [GPUFREQ-STATUS] Current OPP: [12], Freq: 750000 KHz, Volt: 75000
     *   cur_opp: 12, cur_freq: 750000, loading: 42%
     *   Working OPP: 12 | Freq: 750 MHz
     *   opp_idx = 12
     */
    fun parseGpufreqStatus(raw: String, oppTable: List<GpuOpp>): ParsedGpufreqStatus {
        var freqKhz: Long? = null
        var oppIdx: Int? = null
        var utilPct: Float? = null

        for (pat in STATUS_OPP_PATTERNS) {
            val match = pat.find(raw)
            val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsed != null && parsed >= 0) {
                oppIdx = parsed
                break
            }
        }

        STATUS_FREQ_REGEX.find(raw)?.let { m ->
            val value = m.groupValues[1].toLongOrNull()
            val unit = m.groupValues.getOrNull(2)?.lowercase().orEmpty()
            if (value != null && value > 0L) {
                freqKhz = when {
                    unit == "mhz" -> value * 1000L
                    unit == "khz" -> value
                    value < 5_000L -> value * 1000L
                    else -> value
                }
            }
        }
        if (freqKhz == null) {
            MHZ_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { mhz ->
                freqKhz = (mhz * 1000.0).toLong()
            }
        }
        if (freqKhz == null) {
            KHZ_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { khz ->
                freqKhz = khz
            }
        }

        // Cross-resolve missing field using the discovered OPP table
        if (freqKhz == null && oppIdx != null) {
            freqKhz = oppTable.firstOrNull { it.oppIndex == oppIdx }?.freqKhz
        }
        if (oppIdx == null && freqKhz != null && oppTable.isNotEmpty()) {
            oppIdx = oppTable.minByOrNull { abs(it.freqKhz - freqKhz!!) }?.oppIndex
        }

        STATUS_UTIL_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { u ->
            utilPct = u.coerceIn(0f, 100f)
        }

        return ParsedGpufreqStatus(freqKhz = freqKhz, oppIndex = oppIdx, utilPct = utilPct)
    }

    fun parseFreqListKhz(raw: String): List<Long> {
        return raw.trim()
            .split(WHITESPACE_REGEX)
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .sorted()
    }

    /**
     * Parses GED loading nodes such as /sys/kernel/ged/hal/gpu_utilization or
     * /sys/module/ged/parameters/gpu_loading (e.g. "42", "42 100", "42%").
     */
    fun parseGpuUtilPct(raw: String): Float? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val nums = GPU_UTIL_NUMBER_REGEX
            .findAll(trimmed)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .toList()
        if (nums.isEmpty()) return null
        return nums.first().coerceIn(0f, 100f)
    }

    fun parseTemperatureC(raw: String, divisorToCelsius: Float): Float? {
        val value = raw.trim().toFloatOrNull() ?: return null
        val temp = value / divisorToCelsius
        return if (temp in -30f..150f) temp else null
    }

    private const val FULL_DEADBAND_WATTS = 0.15f
    private const val FULL_DEADBAND_MA = 50f

    fun parseCapacityMah(raw: String): Int? {
        val v = raw.trim().toLongOrNull() ?: return null
        if (v <= 0L) return null
        // Sysfs charge_full / charge_full_design is usually in uAh (e.g. 5000000), occasionally mAh.
        val mah = if (v > 50_000L) (v / 1000L).toInt() else v.toInt()
        return mah.takeIf { it in 500..30_000 }
    }

    fun parseCycleCount(raw: String): Int? {
        val v = raw.trim().toIntOrNull() ?: return null
        return v.takeIf { it >= 0 }
    }

    fun computePowerReading(
        rawCurrent: Long,
        rawVoltage: Long,
        unitScale: PowerUnitScale,
        polarity: PowerPolarity,
        bmStatus: Int = -1,
        bmPlugged: Int = -1,
        learnedDischargeSign: Int = 0
    ): PowerReading {
        val voltageMv = when (unitScale) {
            PowerUnitScale.UA_UV -> rawVoltage / 1000f
            PowerUnitScale.MA_MV -> rawVoltage.toFloat()
            PowerUnitScale.UA_MV -> rawVoltage.toFloat()
            PowerUnitScale.AUTO -> if (abs(rawVoltage) > 100_000L) rawVoltage / 1000f else rawVoltage.toFloat()
        }

        val rawCurrentMa = when (unitScale) {
            PowerUnitScale.UA_UV -> rawCurrent / 1000f
            PowerUnitScale.MA_MV -> rawCurrent.toFloat()
            PowerUnitScale.UA_MV -> rawCurrent / 1000f
            PowerUnitScale.AUTO -> if (abs(rawCurrent) > 25_000L) rawCurrent / 1000f else rawCurrent.toFloat()
        }

        val absMa = abs(rawCurrentMa)
        val absWatts = if (voltageMv > 0f) ((absMa / 1000f) * (voltageMv / 1000f)).coerceIn(0f, 60f) else 0f

        val bmIsCharging = bmStatus == BatteryManager.BATTERY_STATUS_CHARGING
        val bmIsFull = bmStatus == BatteryManager.BATTERY_STATUS_FULL

        // Positive = battery discharging, negative = battery charging.
        val dischargeSignedMa = when (polarity) {
            PowerPolarity.DISCHARGE_NEGATIVE -> -rawCurrentMa
            PowerPolarity.DISCHARGE_POSITIVE -> rawCurrentMa
            PowerPolarity.INVERT_SIGN -> {
                if (learnedDischargeSign != 0) -learnedDischargeSign * rawCurrentMa else -rawCurrentMa
            }
            PowerPolarity.AUTO_ABS -> when {
                learnedDischargeSign != 0 -> learnedDischargeSign * rawCurrentMa
                bmIsCharging || bmIsFull -> -absMa
                else -> absMa
            }
        }

        val hasExplicitSign = polarity == PowerPolarity.DISCHARGE_NEGATIVE ||
            polarity == PowerPolarity.DISCHARGE_POSITIVE ||
            polarity == PowerPolarity.INVERT_SIGN ||
            learnedDischargeSign != 0

        val state = when {
            voltageMv <= 0f -> BatteryState.UNKNOWN
            bmPlugged == 0 -> BatteryState.DISCHARGING
            bmPlugged > 0 || bmIsCharging || bmIsFull -> when {
                bmIsFull && absWatts <= 0.35f -> BatteryState.FULL_PLUGGED
                hasExplicitSign && !bmIsCharging && dischargeSignedMa > 150f && absWatts > 0.35f ->
                    BatteryState.DISCHARGING
                absWatts <= FULL_DEADBAND_WATTS && absMa <= FULL_DEADBAND_MA ->
                    if (bmIsCharging) BatteryState.CHARGING else BatteryState.FULL_PLUGGED
                hasExplicitSign && dischargeSignedMa > 250f && absWatts > 0.50f && !bmIsCharging ->
                    BatteryState.DISCHARGING
                else -> BatteryState.CHARGING
            }
            hasExplicitSign -> when {
                dischargeSignedMa < -FULL_DEADBAND_MA -> BatteryState.CHARGING
                else -> BatteryState.DISCHARGING
            }
            else -> BatteryState.DISCHARGING
        }

        val dischargeWatts = if (state == BatteryState.DISCHARGING) absWatts else 0f
        val chargeWatts = if (state == BatteryState.CHARGING) absWatts else 0f
        val signedWatts = when (state) {
            BatteryState.DISCHARGING -> -dischargeWatts
            BatteryState.CHARGING -> chargeWatts
            BatteryState.FULL_PLUGGED, BatteryState.UNKNOWN -> 0f
        }

        return PowerReading(
            signedWatts = signedWatts,
            dischargeWatts = dischargeWatts,
            chargeWatts = chargeWatts,
            state = state,
            currentMa = rawCurrentMa,
            voltageMv = voltageMv,
            watts = dischargeWatts
        )
    }
}

object GovernorMath {

    fun stepSmoothedLoad(
        previousSmoothed: Float,
        rawLoadPct: Float,
        headroomPct: Float,
        rampUpSpeed: Float,
        rampDownSpeed: Float
    ): Float {
        val effectiveLoad = (rawLoadPct + headroomPct).coerceIn(0f, 100f)
        val alpha = if (effectiveLoad >= previousSmoothed) {
            rampUpSpeed.coerceIn(0.05f, 1.0f)
        } else {
            rampDownSpeed.coerceIn(0.01f, 1.0f)
        }
        return (previousSmoothed + (effectiveLoad - previousSmoothed) * alpha).coerceIn(0f, 100f)
    }

    /**
     * CPU frequency grading over available frequencies within allowed range and cap %.
     */
    fun selectFrequency(
        availableFreqsKhz: List<Long>,
        allowedMinKhz: Long,
        allowedMaxKhz: Long,
        capPct: Float,
        loadPct: Float,
        forceMaxDuringBurst: Boolean = false
    ): Pair<Long, Long> {
        if (availableFreqsKhz.isEmpty()) return 0L to 0L
        val sorted = availableFreqsKhz.sorted()
        val inRange = sorted.filter { it in allowedMinKhz..allowedMaxKhz }.ifEmpty {
            listOf(sorted.minByOrNull { abs(it - allowedMinKhz) } ?: sorted.first())
        }
        val minFreq = inRange.first()
        val maxFreq = inRange.last()
        val span = (maxFreq - minFreq).coerceAtLeast(0L)
        val capThresholdKhz = minFreq + ((span * (capPct.coerceIn(0f, 100f) / 100f)).toLong())

        val cappedList = inRange.filter { it <= capThresholdKhz }.ifEmpty { listOf(inRange.first()) }
        val cappedMax = cappedList.last()

        val target = if (forceMaxDuringBurst) {
            cappedMax
        } else {
            val idx = ((loadPct.coerceIn(0f, 100f) / 100f) * (cappedList.size - 1))
                .roundToInt()
                .coerceIn(0, cappedList.lastIndex)
            cappedList[idx]
        }
        return target to cappedMax
    }

    /**
     * GPU scaling operating purely on positions in sortedOpps (0 = lowest freq, lastIndex = highest freq).
     * Throttling lowers the highest allowed position in steps through the real OPP list.
     * Converts to sortedOpps[targetPos].oppIndex only when returning the target entry to write.
     */
    fun selectGpuOppPosition(
        sortedOpps: List<GpuOpp>,
        allowedMinKhz: Long,
        allowedMaxKhz: Long,
        capPct: Float,
        loadPct: Float,
        forceMaxDuringBurst: Boolean = false
    ): GpuPositionSelection? {
        if (sortedOpps.isEmpty()) return null
        val last = sortedOpps.lastIndex

        val minPos = sortedOpps.indexOfFirst { it.freqKhz >= allowedMinKhz }
            .let { if (it < 0) last else it }
            .coerceIn(0, last)
        val maxPos = sortedOpps.indexOfLast { it.freqKhz <= allowedMaxKhz }
            .let { if (it < 0) 0 else it }
            .coerceIn(minPos, last)

        val spanPositions = (maxPos - minPos).coerceAtLeast(0)
        val cappedMaxPos = (minPos + (spanPositions * (capPct.coerceIn(0f, 100f) / 100f)).roundToInt())
            .coerceIn(minPos, maxPos)

        val cappedSpan = (cappedMaxPos - minPos).coerceAtLeast(0)
        val targetPos = if (forceMaxDuringBurst) {
            cappedMaxPos
        } else {
            (minPos + ((loadPct.coerceIn(0f, 100f) / 100f) * cappedSpan).roundToInt())
                .coerceIn(minPos, cappedMaxPos)
        }

        return GpuPositionSelection(
            targetPos = targetPos,
            cappedMaxPos = cappedMaxPos,
            allowedMinPos = minPos,
            allowedMaxPos = maxPos,
            targetOpp = sortedOpps[targetPos],
            cappedMaxKhz = sortedOpps[cappedMaxPos].freqKhz
        )
    }

    fun stepTempCap(
        currentTempC: Float,
        prevLatchedPeakTempC: Float,
        prevCapCpuPct: Float,
        prevCapGpuPct: Float,
        config: TempThrottleConfig,
        safetyFloorPct: Float,
        isChargingOrPlugged: Boolean = false
    ): Triple<Float, Float, Float> {
        if (!config.enabled) return Triple(100f, 100f, currentTempC)

        val start = if (isChargingOrPlugged && config.chargingAware) {
            (config.startTempC - config.chargingStartOffsetC.coerceIn(0f, 5f)).coerceAtLeast(20f)
        } else {
            config.startTempC
        }
        val full = config.fullTempC.coerceAtLeast(start + 1f)

        val effectiveTemp = when {
            currentTempC >= prevLatchedPeakTempC -> currentTempC
            currentTempC <= prevLatchedPeakTempC - config.hysteresisC -> currentTempC + config.hysteresisC * 0.1f
            else -> prevLatchedPeakTempC
        }
        val nextLatchedTemp = if (currentTempC >= prevLatchedPeakTempC) {
            currentTempC
        } else if (currentTempC <= prevLatchedPeakTempC - config.hysteresisC) {
            currentTempC
        } else {
            prevLatchedPeakTempC
        }

        val normalized = ((effectiveTemp - start) / (full - start)).coerceIn(0f, 1f)
        val shaped = when (config.curveShape) {
            ThrottleCurveShape.LINEAR -> normalized
            ThrottleCurveShape.GENTLE -> normalized.pow(2f)
            ThrottleCurveShape.AGGRESSIVE -> sqrt(normalized)
        }

        val targetCpuCap = (100f - shaped * config.cpuMaxReductionPct).coerceIn(safetyFloorPct, 100f)
        val targetGpuCap = (100f - shaped * config.gpuMaxReductionPct).coerceIn(safetyFloorPct, 100f)

        val alpha = config.reactionSpeed.coerceIn(0.05f, 1.0f)
        val nextCpu = (prevCapCpuPct + (targetCpuCap - prevCapCpuPct) * alpha).coerceIn(safetyFloorPct, 100f)
        val nextGpu = (prevCapGpuPct + (targetGpuCap - prevCapGpuPct) * alpha).coerceIn(safetyFloorPct, 100f)

        return Triple(nextCpu, nextGpu, nextLatchedTemp)
    }

    fun combineCaps(tempCapPct: Float, powerCapPct: Float, safetyFloorPct: Float): Float {
        return min(tempCapPct, powerCapPct).coerceIn(safetyFloorPct, 100f)
    }

    fun splitPowerCap(basePowerCapPct: Float, gpuShare: Float, floorPct: Float): Pair<Float, Float> {
        val deficit = (100f - basePowerCapPct).coerceAtLeast(0f)
        val gpuFactor = (2f * gpuShare.coerceIn(0f, 1f)).coerceIn(0.4f, 1.6f)
        val cpuFactor = 2f - gpuFactor
        val cpuCap = (100f - deficit * cpuFactor).coerceIn(floorPct, 100f)
        val gpuCap = (100f - deficit * gpuFactor).coerceIn(floorPct, 100f)
        return cpuCap to gpuCap
    }

    fun clusterCutFactor(clusterShareTimesCount: Float): Float {
        return (1f + (clusterShareTimesCount - 1f) * 0.5f).coerceIn(0.7f, 1.3f)
    }
}
