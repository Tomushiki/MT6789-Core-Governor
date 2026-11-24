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

import kotlin.math.min

/**
 * Running totals the device rating is built from. Only device-independent measures are kept, and
 * charging time is never counted. Counts are halved when they grow large, so old use fades slowly.
 */
class RatingAgg(
    var seconds: Long = 0,
    var gameN: Long = 0,
    val fpsHist: IntArray = IntArray(FPS_BINS),
    var effN: Long = 0,
    var sumEff: Double = 0.0,
    var powerN: Long = 0,
    var sumWatts: Double = 0.0,
    var tempN: Long = 0,
    var sumTemp: Double = 0.0,
    var idleN: Long = 0,
    var sumIdleLoad: Double = 0.0
) {
    companion object {
        const val FPS_BINS = 181
        const val DECAY_AT = 400_000L
        const val MIN_WATTS = 0.3f
    }

    fun add(fps: Float, watts: Float, tempC: Float, cpuLoadPct: Float, inGame: Boolean, charging: Boolean) {
        if (charging) return
        seconds++
        if (tempC > 0f && tempC < 120f) { tempN++; sumTemp += tempC }
        if (watts >= MIN_WATTS) { powerN++; sumWatts += watts }
        if (inGame) {
            if (fps > 0f) {
                gameN++
                fpsHist[min(fps.toInt(), FPS_BINS - 1)]++
                if (watts >= MIN_WATTS) { effN++; sumEff += (fps / watts).toDouble() }
            }
        } else if (cpuLoadPct >= 0f) {
            idleN++; sumIdleLoad += cpuLoadPct
        }
        if (seconds >= DECAY_AT) halve()
    }

    private fun halve() {
        seconds /= 2; gameN /= 2; effN /= 2; sumEff /= 2; powerN /= 2; sumWatts /= 2
        tempN /= 2; sumTemp /= 2; idleN /= 2; sumIdleLoad /= 2
        for (i in fpsHist.indices) fpsHist[i] /= 2
    }

    /** 5% low FPS from the histogram, or null when there is no game data. */
    fun p5Fps(): Float? {
        var total = 0L
        for (c in fpsHist) total += c
        if (total <= 0L) return null
        val target = total * 0.05
        var run = 0L
        for (i in fpsHist.indices) {
            run += fpsHist[i]
            if (run >= target) return i.toFloat()
        }
        return null
    }

    fun copy(): RatingAgg = RatingAgg(
        seconds, gameN, fpsHist.copyOf(), effN, sumEff, powerN, sumWatts, tempN, sumTemp, idleN, sumIdleLoad
    )
}

data class Standby(val drainPctPerH: Float, val deepSleepPct: Int)

data class RatingPart(val label: String, val weight: Int, val score: Int?, val detail: String)

data class RatingResult(
    val score: Int?,
    val confidence: String,
    val parts: List<RatingPart>,
    val seconds: Long,
    val progress: Float
)

/** Turns [RatingAgg] into one 0-100 score. Same formula for every phone. Pure. */
object RatingModel {
    const val MIN_SECONDS = 1800L

    private fun lin(v: Float, best: Float, worst: Float): Int {
        val f = (v - worst) / (best - worst)
        return (f.coerceIn(0f, 1f) * 100f).toInt()
    }

    fun compute(a: RatingAgg, standby: Standby?): RatingResult {
        val parts = ArrayList<RatingPart>()
        val p5 = if (a.gameN >= 60) a.p5Fps() else null
        parts += RatingPart(
            "Performance", 25, p5?.let { lin(it, 60f, 15f) },
            p5?.let { "5% low ${it.toInt()} fps" } ?: "needs game play"
        )
        val eff = if (a.effN >= 60) (a.sumEff / a.effN).toFloat() else null
        parts += RatingPart(
            "Efficiency", 20, eff?.let { lin(it, 25f, 4f) },
            eff?.let { String.format(java.util.Locale.US, "%.1f FPS/W", it) } ?: "needs game play"
        )
        val watts = if (a.powerN >= 120) (a.sumWatts / a.powerN).toFloat() else null
        parts += RatingPart(
            "Power draw", 15, watts?.let { lin(it, 1.2f, 6f) },
            watts?.let { String.format(java.util.Locale.US, "%.2f W average", it) } ?: "collecting"
        )
        val temp = if (a.tempN >= 120) (a.sumTemp / a.tempN).toFloat() else null
        parts += RatingPart(
            "Thermals", 10, temp?.let { lin(it, 32f, 46f) },
            temp?.let { String.format(java.util.Locale.US, "%.1f C average", it) } ?: "collecting"
        )
        val idle = if (a.idleN >= 120) (a.sumIdleLoad / a.idleN).toFloat() else null
        parts += RatingPart(
            "Idle load", 15, idle?.let { lin(it, 3f, 30f) },
            idle?.let { String.format(java.util.Locale.US, "%.0f%% CPU outside games", it) } ?: "collecting"
        )
        val sb = standby?.let {
            val drain = lin(it.drainPctPerH, 0.3f, 2.5f)
            if (it.deepSleepPct in 0..100) ((drain * 7 + it.deepSleepPct * 3) / 10) else drain
        }
        parts += RatingPart(
            "Standby", 15, sb,
            standby?.let { String.format(java.util.Locale.US, "%.2f %%/h screen off", it.drainPctPerH) }
                ?: "needs screen-off analysis"
        )

        val scored = parts.filter { it.score != null }
        val progress = (a.seconds.toFloat() / MIN_SECONDS).coerceIn(0f, 1f)
        if (a.seconds < MIN_SECONDS || scored.isEmpty()) {
            return RatingResult(null, "none", parts, a.seconds, progress)
        }
        val wSum = scored.sumOf { it.weight }
        val total = scored.sumOf { it.weight * (it.score ?: 0) } / wSum
        val hours = a.seconds / 3600f
        val conf = when {
            scored.size < 3 || hours < 3f -> "Low"
            hours < 15f -> "Medium"
            else -> "High"
        }
        return RatingResult(total, conf, parts, a.seconds, 1f)
    }

    fun shareText(r: RatingResult, device: String, appVersion: String): String {
        if (r.score == null) return "CoreGovernor rating: not enough data yet."
        val sb = StringBuilder()
        sb.append("CoreGovernor device rating: ").append(r.score).append("/100 (confidence ").append(r.confidence).append(")\n")
        for (p in r.parts) {
            if (p.score != null) sb.append(p.label).append(' ').append(p.score).append(" (").append(p.detail).append(")\n")
        }
        sb.append(String.format(java.util.Locale.US, "Usage logged: %.1f h\n", r.seconds / 3600f))
        sb.append(device).append(" - CoreGovernor ").append(appVersion)
        return sb.toString()
    }
}
