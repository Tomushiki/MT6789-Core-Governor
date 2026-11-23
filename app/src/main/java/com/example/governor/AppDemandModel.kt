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

import com.example.model.LogLevel
import com.example.model.SmartAppMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class AppClass { UNKNOWN, LIGHT, MODERATE, HEAVY, NOISY }
enum class AppControl { AUTO, LOCKED, OVERRIDE }
enum class AppOverride { NONE, LIGHT, MODERATE, HEAVY, NEVER }

data class AppControlInfo(
    val control: AppControl = AppControl.AUTO,
    val override: AppOverride = AppOverride.NONE
)

data class AppDemandRow(
    val pkg: String,
    val cls: AppClass,
    val control: AppControl,
    val override: AppOverride,
    val typCpuPct: Int,
    val peakCpuPct: Int,
    val typGpuPct: Int,
    val peakGpuPct: Int,
    val watts: Float,
    val confidencePct: Int,
    val samples: Int,
    val fgSec: Long,
    val effect: String
)

/**
 * Per-app learned demand model (Smart app profiles).
 *
 * Components: 0..2 = CPU clusters (policy order), 3 = GPU. Demand is measured in capacity-independent
 * "work units" (load% x curFreq / maxFreq) so running at low clocks never makes demand look smaller.
 * Hot path ([tick]) is O(1) and allocation-free; statistics are committed at ~1 Hz.
 */
class AppDemandModel(
    private val file: File,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String, String) -> Unit,
    private val categoryResolver: (String) -> Int
) {
    private class Prior(var n: Int, val p95: FloatArray, val typ: FloatArray)

    private class Rec {
        var category = -2
        var n = 0
        var fgSec = 0L
        var lastSeen = 0L
        val typ = FloatArray(12)
        val hist = IntArray(64)
        val histN = IntArray(4)
        val burst = FloatArray(4)
        var watts = 0f
        var wN = 0
        var fps = 0f
        val bucketSec = IntArray(3)
        var control = AppControl.AUTO
        var override = AppOverride.NONE
        var frozenClass = AppClass.UNKNOWN
        var cls = AppClass.UNKNOWN
    }

    private val lock = Any()
    private val recs = HashMap<String, Rec>()
    private val priors = HashMap<Int, Prior>()

    /** Outputs read by the engine every tick. ceil: 100 = no extra limit; floor: 0 = none (work units %). */
    val ceilWork = FloatArray(4) { 100f }
    val floorWork = FloatArray(4)
    @Volatile var outputsActive = false
        private set

    @Volatile var uiActive = false
    private val _rows = MutableStateFlow<List<AppDemandRow>>(emptyList())
    val rows: StateFlow<List<AppDemandRow>> = _rows
    private val _controls = MutableStateFlow<Map<String, AppControlInfo>>(emptyMap())
    val controls: StateFlow<Map<String, AppControlInfo>> = _controls

    // current app state
    private var curPkg = ""
    private var curRec: Rec? = null
    private var warmUntilMs = 0L
    private var holdUntilMs = 0L
    private var statsStartMs = 0L
    private var lastTickMs = 0L
    private var lastCommitMs = 0L
    private var lastTouchSeen = 0L
    private var lastRestoreLogMs = 0L
    private var candCls = AppClass.UNKNOWN
    private var candSinceMs = 0L
    private var curConf = 0f
    private var curEffClass = AppClass.UNKNOWN
    private var curMode = SmartAppMode.BALANCED
    private val tgtCeil = FloatArray(4) { 100f }
    private val tgtFloor = FloatArray(4)
    private val sum = FloatArray(4)
    private val mx = FloatArray(4)
    private var accN = 0
    private var wSum = 0f
    private var wN = 0
    private var fpsMax = 0f
    private var nComp = 0

    private var dirty = false
    private var lastSaveMs = 0L

    init {
        seedPriors()
        load()
        publishControls()
    }

    // ---------------------------------------------------------------- hot path

    fun tick(
        nowMs: Long,
        pkg: String,
        isGame: Boolean,
        mode: SmartAppMode,
        cpuClusters: Int,
        load: FloatArray,
        freqRatio: FloatArray,
        fps: Float,
        discharging: Boolean,
        watts: Float,
        touchAtMs: Long
    ) {
        if (pkg.isBlank()) return
        synchronized(lock) {
            curMode = mode
            nComp = cpuClusters.coerceIn(0, 3)
            if (pkg != curPkg) switchTo(pkg, nowMs)
            val rec = curRec ?: return
            val dt = ((nowMs - lastTickMs) / 1000f).coerceIn(0f, 0.5f)
            lastTickMs = nowMs

            for (c in 0 until 4) {
                val l = load[c]
                val r = freqRatio[c]
                if (l < 0f || r < 0f) continue
                val d = (l * r).coerceIn(0f, 100f)
                sum[c] += d
                if (d > mx[c]) mx[c] = d
            }
            accN++
            if (discharging && watts > 0f) { wSum += watts; wN++ }
            if (fps > fpsMax) fpsMax = fps

            // reactive restore: demand beyond the lowered ceiling or a touch -> full ceiling at once
            if (outputsActive) {
                var restore = false
                for (c in 0 until 4) {
                    val a = ceilWork[c]
                    if (a < 99f && load[c] >= 85f && freqRatio[c] >= a / 100f * 0.95f) restore = true
                }
                if (restore) {
                    holdUntilMs = nowMs + 5_000L
                    logRestore(nowMs, "load beyond lowered ceiling")
                } else if (touchAtMs > lastTouchSeen && touchAtMs >= nowMs - 400L) {
                    holdUntilMs = maxOf(holdUntilMs, nowMs + 3_000L)
                }
            }
            lastTouchSeen = maxOf(lastTouchSeen, touchAtMs)

            if (nowMs - lastCommitMs >= 1_000L) commit(rec, nowMs, isGame, fps)

            // asymmetric ceiling response
            val full = nowMs < warmUntilMs || nowMs < holdUntilMs || !outputsActive
            for (c in 0 until 4) {
                val t = if (full) 100f else tgtCeil[c]
                val a = ceilWork[c]
                ceilWork[c] = if (t >= a) t else maxOf(t, a - 3f * dt)
                floorWork[c] = if (outputsActive) tgtFloor[c] else 0f
            }
        }
    }

    /** Reset outputs to "no effect" (feature turned off, game, screen off). */
    fun release() {
        synchronized(lock) {
            for (c in 0 until 4) { ceilWork[c] = 100f; floorWork[c] = 0f; tgtCeil[c] = 100f; tgtFloor[c] = 0f }
            outputsActive = false
            curPkg = ""
            curRec = null
            accN = 0
        }
    }

    private fun switchTo(pkg: String, nowMs: Long) {
        curPkg = pkg
        var r = recs[pkg]
        if (r == null) {
            r = Rec()
            r.category = categoryResolver(pkg)
            recs[pkg] = r
            evictIfNeeded(pkg)
        }
        curRec = r
        warmUntilMs = nowMs + 5_000L
        statsStartMs = nowMs + 3_000L
        holdUntilMs = 0L
        for (c in 0 until 4) { ceilWork[c] = 100f; floorWork[c] = 0f; sum[c] = 0f; mx[c] = 0f }
        accN = 0; wSum = 0f; wN = 0; fpsMax = 0f
        lastTickMs = nowMs
        lastCommitMs = nowMs
        refreshTargets(r, false)
    }

    private fun logRestore(nowMs: Long, why: String) {
        if (nowMs - lastRestoreLogMs >= 60_000L) {
            lastRestoreLogMs = nowMs
            log(LogLevel.INFO, "SmartApp", "Full ceiling restored for $curPkg ($why)")
        }
    }

    private fun commit(rec: Rec, nowMs: Long, isGame: Boolean, fps: Float) {
        lastCommitMs = nowMs
        val n = accN
        if (n <= 0) return
        val inv = 1f / n
        val learn = rec.control != AppControl.LOCKED && nowMs >= statsStartMs
        if (learn) {
            var cpuMean = 0f
            val nc = nComp.coerceAtLeast(1)
            for (c in 0 until nComp) cpuMean += sum[c] * inv
            cpuMean /= nc
            val gpuMean = sum[3] * inv
            val m = maxOf(cpuMean, gpuMean * 0.8f)
            val b = if (m < 8f) 0 else if (m < 40f) 1 else 2
            rec.bucketSec[b]++
            for (c in 0 until 4) {
                if (c >= nComp && c != 3) continue
                val mean = sum[c] * inv
                val peak = mx[c]
                val ti = b * 4 + c
                rec.typ[ti] = if (rec.bucketSec[b] <= 1) mean else rec.typ[ti] + (mean - rec.typ[ti]) * 0.05f
                val bin = (peak * 0.16f).toInt().coerceIn(0, 15)
                rec.hist[c * 16 + bin]++
                if (++rec.histN[c] > 3000) halve(rec, c)
                val spike = if (peak - mean > 30f && peak > 40f) 1f else 0f
                rec.burst[c] += (spike - rec.burst[c]) * 0.03f
                if (b != 0) updatePriors(rec, c, mean, peak)
            }
            if (wN > 0) {
                val w = wSum / wN
                rec.watts = if (rec.wN == 0) w else rec.watts + (w - rec.watts) * 0.02f
                rec.wN++
            }
            if (fpsMax > 5f) rec.fps = if (rec.fps <= 0f) fpsMax else rec.fps + (fpsMax - rec.fps) * 0.05f
            rec.n++
            rec.fgSec++
            rec.lastSeen = nowMs
            dirty = true
        }
        for (c in 0 until 4) { sum[c] = 0f; mx[c] = 0f }
        accN = 0; wSum = 0f; wN = 0; fpsMax = 0f
        refreshTargets(rec, isGame)
        if (uiActive) publishRows(nowMs)
    }

    private fun halve(rec: Rec, c: Int) {
        for (i in 0 until 16) rec.hist[c * 16 + i] = rec.hist[c * 16 + i] shr 1
        var t = 0
        for (i in 0 until 16) t += rec.hist[c * 16 + i]
        rec.histN[c] = t
    }

    private fun updatePriors(rec: Rec, c: Int, mean: Float, peak: Float) {
        if (rec.histN[c] < 20) return
        val key = if (rec.category == 0) 100 else rec.category
        updatePrior(priors[101]!!, c, mean, peak, 0.004f)
        val p = priors.getOrPut(key) { newPrior(55f) }
        updatePrior(p, c, mean, peak, 0.008f)
    }

    private fun updatePrior(p: Prior, c: Int, mean: Float, peak: Float, eta: Float) {
        val d = peak - p.p95[c]
        p.p95[c] += d * eta * (if (d > 0f) 0.95f else 0.05f) * 2f
        p.typ[c] += (mean - p.typ[c]) * eta
        if (c == 0) p.n++
    }

    // ---------------------------------------------------------------- classification & targets

    private fun priorFor(rec: Rec): Prior {
        val key = if (rec.category == 0) 100 else rec.category
        return priors[key] ?: priors[101]!!
    }

    private fun ownConfidence(rec: Rec): Float {
        val t = minOf(1f, rec.fgSec / 1800f)
        val s = minOf(1f, rec.n / 1200f)
        return t * 0.7f + s * 0.3f
    }

    private fun p95(rec: Rec, c: Int): Float {
        var tot = 0
        val o = c * 16
        for (i in 0 until 16) tot += rec.hist[o + i]
        if (tot < 20) return -1f
        val target = tot * 0.95f
        var acc = 0
        for (i in 0 until 16) {
            acc += rec.hist[o + i]
            if (acc >= target) return (i + 1) * 6.25f
        }
        return 100f
    }

    private fun blendedP95(rec: Rec, c: Int, conf: Float): Float {
        val pr = priorFor(rec)
        val g = priors[101]!!
        val prior = if (pr === g) g.p95[c] else 0.6f * pr.p95[c] + 0.4f * g.p95[c]
        val own = p95(rec, c)
        return if (own < 0f) prior else conf * own + (1f - conf) * prior
    }

    private fun typInteractive(rec: Rec, c: Int): Float {
        val a = rec.bucketSec[1]
        val b = rec.bucketSec[2]
        return when {
            a + b > 0 -> (rec.typ[4 + c] * a + rec.typ[8 + c] * b) / (a + b)
            else -> rec.typ[c]
        }
    }

    private fun classify(rec: Rec, conf: Float, isGame: Boolean): AppClass {
        if (isGame || rec.category == 0) return AppClass.HEAVY
        var cpuP = 0f
        for (c in 0 until nComp) cpuP = maxOf(cpuP, blendedP95(rec, c, conf))
        val gpuP = blendedP95(rec, 3, conf)
        var burst = 0f
        for (c in 0 until 4) if (rec.histN[c] >= 20) burst = maxOf(burst, rec.burst[c])
        val hi = maxOf(cpuP, gpuP)
        return when {
            burst > 0.25f && hi > 50f -> AppClass.NOISY
            cpuP >= 70f || gpuP >= 70f -> AppClass.HEAVY
            cpuP <= 40f && gpuP <= 35f -> AppClass.LIGHT
            else -> AppClass.MODERATE
        }
    }

    private fun actingThreshold(mode: SmartAppMode): Float = when (mode) {
        SmartAppMode.CONSERVATIVE -> 0.55f
        SmartAppMode.BALANCED -> 0.35f
        SmartAppMode.AGGRESSIVE -> 0.20f
    }

    private fun refreshTargets(rec: Rec, isGame: Boolean) {
        val own = ownConfidence(rec)
        val priorStrength = minOf(0.5f, priorFor(rec).n / 3000f)
        val conf = own + priorStrength * (1f - own)
        curConf = conf
        val now = lastTickMs
        val learned = classify(rec, own, isGame)
        if (learned != rec.cls) {
            if (learned != candCls) { candCls = learned; candSinceMs = now }
            if (rec.cls == AppClass.UNKNOWN || now - candSinceMs >= 30_000L) {
                if (rec.cls != AppClass.UNKNOWN) {
                    log(LogLevel.INFO, "SmartApp", "$curPkg class ${rec.cls.name} -> ${learned.name} (confidence ${(conf * 100).toInt()}%)")
                }
                rec.cls = learned
            }
        } else candCls = learned

        var eff = AppClass.UNKNOWN
        var active = false
        if (isGame) {
            eff = AppClass.HEAVY
        } else when (rec.control) {
            AppControl.OVERRIDE -> when (rec.override) {
                AppOverride.NEVER, AppOverride.NONE -> eff = AppClass.UNKNOWN
                AppOverride.LIGHT -> { eff = AppClass.LIGHT; active = true }
                AppOverride.MODERATE -> { eff = AppClass.MODERATE; active = true }
                AppOverride.HEAVY -> { eff = AppClass.HEAVY; active = true }
            }
            AppControl.LOCKED -> { eff = rec.frozenClass; active = eff != AppClass.UNKNOWN }
            AppControl.AUTO -> if (conf >= actingThreshold(curMode)) { eff = rec.cls; active = eff != AppClass.UNKNOWN }
        }
        curEffClass = eff
        for (c in 0 until 4) { tgtCeil[c] = 100f; tgtFloor[c] = 0f }
        if (active && !isGame) {
            val head = when (curMode) { SmartAppMode.CONSERVATIVE -> 30f; SmartAppMode.BALANCED -> 20f; SmartAppMode.AGGRESSIVE -> 10f }
            val minFrac = when (curMode) { SmartAppMode.CONSERVATIVE -> 50f; SmartAppMode.BALANCED -> 40f; SmartAppMode.AGGRESSIVE -> 30f }
            for (c in 0 until 4) {
                if (c >= nComp && c != 3) continue
                when (eff) {
                    AppClass.LIGHT -> {
                        val v = (blendedP95(rec, c, conf) + head).coerceIn(minFrac, 100f)
                        tgtCeil[c] = if (v >= 95f) 100f else v
                    }
                    AppClass.HEAVY, AppClass.NOISY -> {
                        val k = if (eff == AppClass.HEAVY) {
                            when (curMode) { SmartAppMode.CONSERVATIVE -> 0.35f; SmartAppMode.BALANCED -> 0.5f; SmartAppMode.AGGRESSIVE -> 0.7f }
                        } else {
                            when (curMode) { SmartAppMode.CONSERVATIVE -> 0.3f; SmartAppMode.BALANCED -> 0.4f; SmartAppMode.AGGRESSIVE -> 0.55f }
                        }
                        val t = typInteractive(rec, c)
                        tgtFloor[c] = if (t > 10f) minOf(t * k, if (eff == AppClass.HEAVY) 70f else 50f) else 0f
                    }
                    else -> {}
                }
            }
        }
        outputsActive = active && !isGame
    }

    // ---------------------------------------------------------------- control API (UI thread safe)

    fun setControl(pkg: String, control: AppControl, override: AppOverride = AppOverride.NONE) {
        synchronized(lock) {
            var r = recs[pkg]
            if (r == null) {
                r = Rec(); r.category = categoryResolver(pkg); recs[pkg] = r; evictIfNeeded(pkg)
            }
            if (control == AppControl.LOCKED) {
                r.frozenClass = if (r.cls == AppClass.UNKNOWN) classify(r, ownConfidence(r), false) else r.cls
            }
            r.control = control
            r.override = if (control == AppControl.OVERRIDE) override else AppOverride.NONE
            dirty = true
            val what = when (control) {
                AppControl.AUTO -> "Auto"
                AppControl.LOCKED -> "Locked as ${r.frozenClass.name}"
                AppControl.OVERRIDE -> "Override ${r.override.name}"
            }
            log(LogLevel.INFO, "SmartApp", "$pkg: $what")
            if (pkg == curPkg) { holdUntilMs = 0L; refreshTargets(r, false) }
        }
        publishControls()
    }

    fun resetApp(pkg: String) {
        synchronized(lock) {
            recs.remove(pkg)
            if (pkg == curPkg) { curPkg = ""; curRec = null; for (c in 0 until 4) { ceilWork[c] = 100f; floorWork[c] = 0f }; outputsActive = false }
            dirty = true
            log(LogLevel.INFO, "SmartApp", "$pkg: learned data reset")
        }
        publishControls()
    }

    fun resetAll() {
        synchronized(lock) {
            recs.clear(); curPkg = ""; curRec = null; outputsActive = false
            for (c in 0 until 4) { ceilWork[c] = 100f; floorWork[c] = 0f }
            seedPriors(); dirty = true
        }
        publishControls()
        _rows.value = emptyList()
    }

    private fun publishControls() {
        val m = HashMap<String, AppControlInfo>()
        synchronized(lock) {
            for ((k, v) in recs) if (v.control != AppControl.AUTO) m[k] = AppControlInfo(v.control, v.override)
        }
        _controls.value = m
    }

    private fun publishRows(nowMs: Long) {
        val list = recs.entries.sortedByDescending { it.value.fgSec }.take(40).map { (k, r) ->
            val conf = ownConfidence(r)
            var cpuTyp = 0f
            var cpuPeak = 0f
            val ncc = if (k == curPkg) nComp else 3
            for (c in 0 until ncc) {
                cpuTyp = maxOf(cpuTyp, typInteractive(r, c))
                cpuPeak = maxOf(cpuPeak, maxOf(0f, p95(r, c)))
            }
            val cls = when {
                r.control == AppControl.LOCKED -> r.frozenClass
                r.control == AppControl.OVERRIDE -> when (r.override) {
                    AppOverride.LIGHT -> AppClass.LIGHT; AppOverride.MODERATE -> AppClass.MODERATE
                    AppOverride.HEAVY -> AppClass.HEAVY; else -> AppClass.UNKNOWN
                }
                else -> r.cls
            }
            val effect = when {
                r.control == AppControl.OVERRIDE && r.override == AppOverride.NEVER -> "not controlled"
                k != curPkg -> "-"
                !outputsActive -> if (curConf < actingThreshold(curMode)) "learning" else "no change"
                else -> {
                    var low = 100f
                    var fl = 0f
                    for (c in 0 until 4) { low = minOf(low, ceilWork[c]); fl = maxOf(fl, floorWork[c]) }
                    when {
                        nowMs < warmUntilMs -> "warm-up (full ceiling)"
                        nowMs < holdUntilMs -> "boost hold (full ceiling)"
                        low < 99f -> "ceiling lowered ${(100f - low).toInt()}%"
                        fl > 0f -> "floor raised to ${fl.toInt()}%"
                        else -> "no change"
                    }
                }
            }
            AppDemandRow(
                pkg = k, cls = cls, control = r.control, override = r.override,
                typCpuPct = cpuTyp.toInt(), peakCpuPct = cpuPeak.toInt(),
                typGpuPct = typInteractive(r, 3).toInt(), peakGpuPct = maxOf(0f, p95(r, 3)).toInt(),
                watts = r.watts, confidencePct = (conf * 100f).toInt().coerceIn(0, 100),
                samples = r.n, fgSec = r.fgSec, effect = effect
            )
        }
        _rows.value = list
    }

    fun flushIfDue(nowMs: Long) {
        if (dirty && nowMs - lastSaveMs >= 300_000L) {
            lastSaveMs = nowMs
            scope.launch(Dispatchers.IO) { save() }
        }
    }

    // ---------------------------------------------------------------- priors / eviction

    private fun newPrior(v: Float) = Prior(0, FloatArray(4) { v }, FloatArray(4) { v * 0.4f })

    private fun seedPriors() {
        priors.clear()
        priors[101] = newPrior(60f)
        priors[100] = newPrior(85f)
        priors[1] = newPrior(30f)
        priors[5] = newPrior(40f)
        priors[7] = newPrior(45f)
        priors[4] = newPrior(55f)
        priors[3] = newPrior(50f)
        priors[2] = newPrior(50f)
        priors[6] = newPrior(55f)
    }

    private fun evictIfNeeded(keep: String) {
        if (recs.size <= 200) return
        var oldestK: String? = null
        var oldestT = Long.MAX_VALUE
        for ((k, v) in recs) {
            if (k == keep || v.control != AppControl.AUTO) continue
            if (v.lastSeen < oldestT) { oldestT = v.lastSeen; oldestK = k }
        }
        oldestK?.let { recs.remove(it) }
    }

    // ---------------------------------------------------------------- persistence

    fun flush() {
        if (!dirty) return
        save()
    }

    private fun save() {
        try {
            val root = JSONObject()
            val apps = JSONObject()
            val pr = JSONObject()
            synchronized(lock) {
                root.put("v", 1)
                for ((k, p) in priors) {
                    pr.put(k.toString(), JSONObject().apply {
                        put("n", p.n); put("p", arr(p.p95)); put("t", arr(p.typ))
                    })
                }
                for ((k, r) in recs) {
                    apps.put(k, JSONObject().apply {
                        put("c", r.category); put("n", r.n); put("fg", r.fgSec); put("ls", r.lastSeen)
                        put("t", arr(r.typ))
                        put("h", JSONArray().also { a -> r.hist.forEach { a.put(it) } })
                        put("hn", JSONArray().also { a -> r.histN.forEach { a.put(it) } })
                        put("b", arr(r.burst, 100f))
                        put("w", Math.round(r.watts * 1000f) / 1000.0); put("wn", r.wN)
                        put("f", Math.round(r.fps * 10f) / 10.0)
                        put("bs", JSONArray().also { a -> r.bucketSec.forEach { a.put(it) } })
                        put("ctl", r.control.ordinal); put("ov", r.override.ordinal)
                        put("fc", r.frozenClass.ordinal); put("cl", r.cls.ordinal)
                    })
                }
                dirty = false
            }
            root.put("priors", pr)
            root.put("apps", apps)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Throwable) {
            dirty = true
        }
    }

    private fun arr(a: FloatArray, scale: Float = 10f): JSONArray {
        val j = JSONArray()
        for (v in a) j.put(Math.round(v * scale))
        return j
    }

    private fun readArr(j: JSONArray?, out: FloatArray, scale: Float = 10f) {
        if (j == null) return
        for (i in 0 until minOf(out.size, j.length())) out[i] = j.optInt(i, 0) / scale
    }

    private fun readInts(j: JSONArray?, out: IntArray) {
        if (j == null) return
        for (i in 0 until minOf(out.size, j.length())) out[i] = j.optInt(i, 0).coerceAtLeast(0)
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val pr = root.optJSONObject("priors")
            pr?.keys()?.forEach { k ->
                try {
                    val o = pr.getJSONObject(k)
                    val p = newPrior(60f)
                    p.n = o.optInt("n", 0)
                    readArr(o.optJSONArray("p"), p.p95)
                    readArr(o.optJSONArray("t"), p.typ)
                    priors[k.toInt()] = p
                } catch (_: Throwable) {}
            }
            val apps = root.optJSONObject("apps") ?: return
            apps.keys().forEach { k ->
                try {
                    val o = apps.getJSONObject(k)
                    val r = Rec()
                    r.category = o.optInt("c", -2); r.n = o.optInt("n", 0); r.fgSec = o.optLong("fg", 0L); r.lastSeen = o.optLong("ls", 0L)
                    readArr(o.optJSONArray("t"), r.typ)
                    readInts(o.optJSONArray("h"), r.hist)
                    readInts(o.optJSONArray("hn"), r.histN)
                    readArr(o.optJSONArray("b"), r.burst, 100f)
                    r.watts = o.optDouble("w", 0.0).toFloat(); r.wN = o.optInt("wn", 0)
                    r.fps = o.optDouble("f", 0.0).toFloat()
                    readInts(o.optJSONArray("bs"), r.bucketSec)
                    r.control = AppControl.entries.getOrElse(o.optInt("ctl", 0)) { AppControl.AUTO }
                    r.override = AppOverride.entries.getOrElse(o.optInt("ov", 0)) { AppOverride.NONE }
                    r.frozenClass = AppClass.entries.getOrElse(o.optInt("fc", 0)) { AppClass.UNKNOWN }
                    r.cls = AppClass.entries.getOrElse(o.optInt("cl", 0)) { AppClass.UNKNOWN }
                    recs[k] = r
                } catch (_: Throwable) {}
            }
            var guard = 0
            while (recs.size > 200 && guard++ < 400) evictIfNeeded("")
        } catch (_: Throwable) {
            // corrupt or missing file: start empty
            recs.clear()
            seedPriors()
        }
    }
}
