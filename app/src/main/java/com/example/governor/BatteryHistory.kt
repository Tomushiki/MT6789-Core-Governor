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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * v4.2.0: one remembered moment of the battery. Kept small on purpose (one point per minute at most)
 * so a week of history is about 10,000 lines.
 */
data class BatteryHistoryPoint(
    val timeMs: Long,
    val pct: Int,
    val state: BatteryState,
    /** Signed: positive = power going into the battery, negative = power drawn from it. */
    val watts: Float,
    val tempC: Float,
    val voltageMv: Float,
    val screenOn: Boolean
)

/** A stretch of time where the battery was doing one thing (charging, or being used). */
data class BatterySession(
    val state: BatteryState,
    val startMs: Long,
    val endMs: Long,
    val startPct: Int,
    val endPct: Int,
    val avgWatts: Float,
    val peakTempC: Float,
    /** Share of the session the screen was on, 0..1. */
    val screenOnShare: Float
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val deltaPct: Int get() = endPct - startPct
}

/** Time windows the user can pick on the history graphs. */
enum class BatteryRange(val hours: Int) {
    H6(6), H24(24), D7(24 * 7);
}

data class BatteryUsageSummary(
    /** Average % lost per hour while on battery in the window, or -1 when there is not enough data. */
    val drainPctPerHour: Float,
    /** Average % lost per hour with the screen on, or -1. */
    val screenOnDrainPctPerHour: Float,
    /** Average % lost per hour with the screen off, or -1. */
    val screenOffDrainPctPerHour: Float,
    val totalOnBatteryMs: Long,
    val totalChargingMs: Long,
    val avgChargeWatts: Float,
    val avgDrawWatts: Float,
    val peakTempC: Float
)

object BatteryHistoryMath {
    const val MIN_GAP_MS = 60_000L
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    /** A silence this long (app stopped / phone off) closes the current session. */
    const val SESSION_GAP_MS = 10L * 60L * 1000L

    private fun isPlugged(s: BatteryState) = s == BatteryState.CHARGING || s == BatteryState.FULL_PLUGGED

    /** True when [next] is worth keeping after [last] (new minute, or level/state changed). */
    fun shouldRecord(last: BatteryHistoryPoint?, next: BatteryHistoryPoint): Boolean {
        if (next.pct !in 0..100) return false
        if (last == null) return true
        if (next.timeMs < last.timeMs) return false
        if (next.pct != last.pct || next.state != last.state) return next.timeMs - last.timeMs >= 5_000L
        return next.timeMs - last.timeMs >= MIN_GAP_MS
    }

    fun prune(points: List<BatteryHistoryPoint>, nowMs: Long): List<BatteryHistoryPoint> =
        points.filter { nowMs - it.timeMs <= MAX_AGE_MS }

    fun window(points: List<BatteryHistoryPoint>, nowMs: Long, range: BatteryRange): List<BatteryHistoryPoint> {
        val from = nowMs - range.hours * 3_600_000L
        return points.filter { it.timeMs >= from }
    }

    /** Evenly thins a list to at most [maxPoints] for drawing; the last point is always kept. */
    fun thin(points: List<BatteryHistoryPoint>, maxPoints: Int): List<BatteryHistoryPoint> {
        if (maxPoints < 2 || points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints
        val out = ArrayList<BatteryHistoryPoint>(maxPoints + 1)
        var i = 0.0
        while (i < points.size) {
            out.add(points[i.toInt()])
            i += step
        }
        if (out.last() !== points.last()) out.add(points.last())
        return out
    }

    /**
     * Splits the history into "charging" and "using the battery" sessions. Plugged-in-and-full counts as
     * charging time but is merged into the same session. Very short blips (under 2 minutes and under 1%)
     * are dropped so the list stays readable.
     */
    fun sessions(points: List<BatteryHistoryPoint>): List<BatterySession> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<BatterySession>()
        var startIdx = 0
        fun close(endIdx: Int) {
            if (endIdx <= startIdx) return
            val seg = points.subList(startIdx, endIdx + 1)
            val charging = isPlugged(seg.first().state)
            var wSum = 0.0
            var wTime = 0L
            var screenMs = 0L
            var totalMs = 0L
            var peak = 0f
            for (i in 0 until seg.size - 1) {
                val dt = (seg[i + 1].timeMs - seg[i].timeMs).coerceAtLeast(0L)
                wSum += seg[i].watts.toDouble() * dt
                wTime += dt
                totalMs += dt
                if (seg[i].screenOn) screenMs += dt
            }
            seg.forEach { if (it.tempC > peak) peak = it.tempC }
            val dur = seg.last().timeMs - seg.first().timeMs
            val dPct = seg.last().pct - seg.first().pct
            if (dur < 120_000L && abs(dPct) < 1) return
            out.add(
                BatterySession(
                    state = if (charging) BatteryState.CHARGING else BatteryState.DISCHARGING,
                    startMs = seg.first().timeMs,
                    endMs = seg.last().timeMs,
                    startPct = seg.first().pct,
                    endPct = seg.last().pct,
                    avgWatts = if (wTime > 0) (wSum / wTime).toFloat() else 0f,
                    peakTempC = peak,
                    screenOnShare = if (totalMs > 0) screenMs.toFloat() / totalMs else 0f
                )
            )
        }
        for (i in 1 until points.size) {
            val gap = points[i].timeMs - points[i - 1].timeMs
            val modeChanged = isPlugged(points[i].state) != isPlugged(points[i - 1].state) &&
                points[i].state != BatteryState.UNKNOWN && points[i - 1].state != BatteryState.UNKNOWN
            if (gap > SESSION_GAP_MS) {
                close(i - 1)
                startIdx = i
            } else if (modeChanged) {
                close(i - 1)
                startIdx = i - 1
            }
        }
        close(points.size - 1)
        return out
    }

    fun summary(points: List<BatteryHistoryPoint>): BatteryUsageSummary {
        var onBattMs = 0L
        var chargeMs = 0L
        var chargeW = 0.0
        var drawW = 0.0
        var lostPct = 0.0
        var screenOnMs = 0L
        var screenOffMs = 0L
        var lostOn = 0.0
        var lostOff = 0.0
        var peak = 0f
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dt = b.timeMs - a.timeMs
            if (dt <= 0L || dt > SESSION_GAP_MS) continue
            if (a.tempC > peak) peak = a.tempC
            if (isPlugged(a.state)) {
                chargeMs += dt
                chargeW += a.watts.toDouble() * dt
            } else {
                onBattMs += dt
                drawW += (-a.watts).coerceAtLeast(0f).toDouble() * dt
                val lost = (a.pct - b.pct).coerceAtLeast(0).toDouble()
                lostPct += lost
                if (a.screenOn) { screenOnMs += dt; lostOn += lost } else { screenOffMs += dt; lostOff += lost }
            }
        }
        fun rate(lost: Double, ms: Long) = if (ms >= 10 * 60_000L) (lost / (ms / 3_600_000.0)).toFloat() else -1f
        return BatteryUsageSummary(
            drainPctPerHour = rate(lostPct, onBattMs),
            screenOnDrainPctPerHour = rate(lostOn, screenOnMs),
            screenOffDrainPctPerHour = rate(lostOff, screenOffMs),
            totalOnBatteryMs = onBattMs,
            totalChargingMs = chargeMs,
            avgChargeWatts = if (chargeMs > 0) (chargeW / chargeMs).toFloat() else 0f,
            avgDrawWatts = if (onBattMs > 0) (drawW / onBattMs).toFloat() else 0f,
            peakTempC = peak
        )
    }

    /** Plain-text line format: time,pct,state,watts,temp,mv,screen. */
    fun encode(p: BatteryHistoryPoint): String =
        "${p.timeMs},${p.pct},${p.state.ordinal},${"%.2f".format(java.util.Locale.US, p.watts)}," +
            "${"%.1f".format(java.util.Locale.US, p.tempC)},${p.voltageMv.toInt()},${if (p.screenOn) 1 else 0}"

    fun decode(line: String): BatteryHistoryPoint? {
        val f = line.trim().split(',')
        if (f.size < 7) return null
        return try {
            val st = BatteryState.values().getOrNull(f[2].toInt()) ?: return null
            BatteryHistoryPoint(
                timeMs = f[0].toLong(),
                pct = f[1].toInt(),
                state = st,
                watts = f[3].toFloat(),
                tempC = f[4].toFloat(),
                voltageMv = f[5].toFloat(),
                screenOn = f[6] == "1"
            )
        } catch (_: Throwable) {
            null
        }
    }
}

/** Remembers up to a week of battery history on disk and exposes it to the UI. */
class BatteryHistoryStore(
    private val file: File,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private val points = ArrayList<BatteryHistoryPoint>()
    private var lastSavedMs = 0L
    private var dirty = false
    private val _flow = MutableStateFlow<List<BatteryHistoryPoint>>(emptyList())
    val history: StateFlow<List<BatteryHistoryPoint>> = _flow

    fun load() {
        val loaded = ArrayList<BatteryHistoryPoint>()
        try {
            if (file.exists()) file.forEachLine { l -> BatteryHistoryMath.decode(l)?.let { loaded.add(it) } }
        } catch (_: Throwable) {
        }
        synchronized(points) {
            // Anything recorded before load finished stays after the loaded part.
            val fresh = points.toList()
            points.clear()
            points.addAll(loaded.filter { l -> fresh.isEmpty() || l.timeMs < fresh.first().timeMs })
            points.addAll(fresh)
            _flow.value = points.toList()
        }
    }

    fun record(p: BatteryHistoryPoint) {
        var snapshot: List<BatteryHistoryPoint>? = null
        var toSave: String? = null
        synchronized(points) {
            if (!BatteryHistoryMath.shouldRecord(points.lastOrNull(), p)) return
            points.add(p)
            val pruned = BatteryHistoryMath.prune(points, p.timeMs)
            if (pruned.size != points.size) {
                points.clear()
                points.addAll(pruned)
            }
            dirty = true
            snapshot = points.toList()
            if (p.timeMs - lastSavedMs >= SAVE_INTERVAL_MS) {
                lastSavedMs = p.timeMs
                toSave = serializeLocked()
                dirty = false
            }
        }
        snapshot?.let { _flow.value = it }
        toSave?.let { text -> scope.launch(Dispatchers.IO) { write(text) } }
    }

    fun flush() {
        val text = synchronized(points) {
            if (!dirty) return
            dirty = false
            serializeLocked()
        }
        write(text)
    }

    fun clear() {
        synchronized(points) {
            points.clear()
            dirty = false
        }
        _flow.value = emptyList()
        scope.launch(Dispatchers.IO) { try { file.delete() } catch (_: Throwable) {} }
    }

    private fun serializeLocked(): String =
        points.joinToString("\n") { BatteryHistoryMath.encode(it) }

    private fun write(text: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5 * 60 * 1000L
    }
}
