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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.example.model.LogLevel
import com.example.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

data class WakeStat(val name: String, val ms: Long, val count: Int)

data class OffPeriod(
    val startMs: Long,
    val durMs: Long,
    val startPct: Int,
    val endPct: Int,
    val pctDrop: Float,
    val mah: Float,
    val avgW: Float,
    val deepSleepPct: Int,
    val awakeMs: Long,
    val samples: Int,
    val kernel: List<WakeStat>,
    val apps: List<WakeStat>
) {
    val pctPerHour: Float get() = if (durMs > 0L) pctDrop / (durMs / 3_600_000f) else 0f
}

data class ScreenOffSnapshot(
    val hasData: Boolean = false,
    val periodsCount: Int = 0,
    val offPctPerHToday: Float = -1f,
    val offPctPerHWeek: Float = -1f,
    val onPctPerHWeek: Float = -1f,
    val offMahPerHWeek: Float = -1f,
    val offAvgWWeek: Float = -1f,
    val deepSleepPctWeek: Int = -1,
    val offHoursWeek: Float = 0f,
    val standbyHoursToEmpty: Float = -1f,
    val last: OffPeriod? = null,
    val worst: OffPeriod? = null,
    val topKernel: List<WakeStat> = emptyList(),
    val topApps: List<WakeStat> = emptyList(),
    val appChecks: Int = 0,
    val recent: List<OffPeriod> = emptyList()
)

/**
 * Screen-off power analysis (v2.7.1). Passive: reads the battery counter at screen off / on, the
 * kernel wakeup sources and (every ~10 min while off, only when the device is already awake) the held
 * partial wakelocks. Charging periods are excluded. Records saved atomically in screen_off_log.json.
 */
class ScreenOffAnalyzer(
    context: Context,
    private val rootShell: RootShell,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val log: (LogLevel, String, String) -> Unit
) {
    private val ctx = context.applicationContext
    private val file = File(ctx.filesDir, "screen_off_log.json")

    private class Batt(
        val level: Int, val mah: Float, val mv: Float, val plugged: Boolean,
        val atMs: Long, val uptime: Long, val elapsed: Long
    )

    private class Day(val day: Long) {
        var onMs = 0L; var onMah = 0f; var onPct = 0f
    }

    private val lock = Any()
    private var onStart: Batt? = null
    private var offStart: Batt? = null
    private var pendingOnEnd: Batt? = null
    private var offKernel: Map<String, LongArray>? = null
    private var offPlugged = false
    private val offAppCounts = HashMap<String, Int>()
    private var offSamples = 0
    private var samplerJob: Job? = null
    private val periods = ArrayList<OffPeriod>()
    private val days = ArrayList<Day>()

    private val _snapshot = MutableStateFlow(ScreenOffSnapshot())
    val snapshot: StateFlow<ScreenOffSnapshot> = _snapshot

    init {
        load()
        rebuild()
    }

    // ------------------------------------------------------------ lifecycle hooks

    /** Governor started: begin tracking from the current screen state. */
    fun begin(screenOn: Boolean) {
        if (!enabled()) return
        if (screenOn) synchronized(lock) { if (onStart == null) onStart = readBatt() } else onScreenOff()
    }

    fun onScreenOff() {
        if (!enabled()) return
        synchronized(lock) {
            if (offStart != null) return
            val b = readBatt()
            pendingOnEnd = if (onStart != null) b else null
            offStart = b
            offPlugged = b.plugged
            offAppCounts.clear()
            offSamples = 0
            offKernel = null
        }
        scope.launch(Dispatchers.IO) {
            val k = readKernel()
            synchronized(lock) { if (offStart != null) offKernel = k }
        }
        samplerJob?.cancel()
        samplerJob = scope.launch(Dispatchers.IO) {
            delay(180_000L)
            while (true) {
                sample()
                delay(600_000L)
            }
        }
    }

    fun onScreenOn() {
        samplerJob?.cancel(); samplerJob = null
        val start: Batt
        val end = readBatt()
        val startKernel: Map<String, LongArray>?
        val apps: HashMap<String, Int>
        val samples: Int
        synchronized(lock) {
            val s = offStart
            if (s == null) {
                if (onStart == null && enabled()) onStart = end
                return
            }
            start = s
            offStart = null
            startKernel = offKernel
            apps = HashMap(offAppCounts)
            samples = offSamples
            val plugged = offPlugged || end.plugged
            val dur = end.atMs - start.atMs
            if (plugged || dur < 180_000L) {
                pendingOnEnd = null
                return
            }
            val os = onStart
            val oe = pendingOnEnd
            if (os != null && oe != null) commitOn(os, oe)
            pendingOnEnd = null
            onStart = end
        }
        scope.launch(Dispatchers.IO) {
            val endKernel = readKernel()
            finalizeOff(start, end, startKernel, endKernel, apps, samples)
        }
    }

    /** Service stop: drop any open off period (cannot be measured reliably), keep saved data. */
    fun stop() {
        samplerJob?.cancel(); samplerJob = null
        synchronized(lock) { offStart = null; pendingOnEnd = null; onStart = null }
    }

    fun resetAll() {
        synchronized(lock) { periods.clear(); days.clear() }
        file.delete()
        rebuild()
    }

    // ------------------------------------------------------------ measuring

    private fun readBatt(): Batt {
        val i = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mv = (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).toFloat()
        var mah = -1f
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (raw > 0L) mah = raw / 1000f
            if (mah > 20000f) mah /= 1000f
        } catch (_: Throwable) {}
        return Batt(pct, mah, mv, plugged, System.currentTimeMillis(), SystemClock.uptimeMillis(), SystemClock.elapsedRealtime())
    }

    private fun capEst(b: Batt): Float {
        if (b.mah <= 0f || b.level < 5) return 0f
        val c = b.mah / (b.level / 100f)
        return if (c in 1000f..12000f) c else 0f
    }

    private fun drop(a: Batt, b: Batt): Pair<Float, Float> {
        val mahD = if (a.mah > 0f && b.mah > 0f) a.mah - b.mah else -1f
        val cap = capEst(a)
        val pct = when {
            mahD >= 0f && cap > 0f -> mahD / cap * 100f
            a.level >= 0 && b.level >= 0 -> (a.level - b.level).coerceAtLeast(0).toFloat()
            else -> 0f
        }
        return pct to mahD
    }

    private fun dayOf(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

    private fun commitOn(a: Batt, b: Batt) {
        val dur = b.atMs - a.atMs
        if (dur < 60_000L || a.plugged || b.plugged) return
        val (pct, mahD) = drop(a, b)
        if (mahD < -5f) return
        val d = dayOf(a.atMs)
        val day = days.firstOrNull { it.day == d } ?: Day(d).also { days.add(it); if (days.size > 14) days.removeAt(0) }
        day.onMs += dur
        day.onPct += pct
        if (mahD > 0f) day.onMah += mahD
    }

    private suspend fun readKernel(): Map<String, LongArray> {
        val out = HashMap<String, LongArray>()
        try {
            val r = rootShell.exec(
                "for d in /sys/class/wakeup/wakeup*; do echo \"\$(cat \$d/name 2>/dev/null)|\$(cat \$d/active_time_ms 2>/dev/null)|\$(cat \$d/total_time_ms 2>/dev/null)|\$(cat \$d/wakeup_count 2>/dev/null)\"; done"
            )
            for (line in r.stdout.lineSequence()) {
                val p = line.split('|')
                if (p.size < 4 || p[0].isBlank()) continue
                val e = out.getOrPut(p[0].trim()) { LongArray(3) }
                e[0] += p[1].trim().toLongOrNull() ?: 0L
                e[1] += p[2].trim().toLongOrNull() ?: 0L
                e[2] += p[3].trim().toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) {}
        return out
    }

    private suspend fun sample() {
        val b = readBatt()
        synchronized(lock) { if (b.plugged) offPlugged = true }
        try {
            val r = rootShell.exec("dumpsys power 2>/dev/null | grep PARTIAL_WAKE_LOCK")
            val seen = HashSet<String>()
            for (line in r.stdout.lineSequence()) {
                val q1 = line.indexOf('\'')
                val q2 = if (q1 >= 0) line.indexOf('\'', q1 + 1) else -1
                if (q1 < 0 || q2 <= q1) continue
                val tag = line.substring(q1 + 1, q2).take(40)
                val uid = Regex("uid=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                var pkg = Regex("WorkSource\\{([^}]*)\\}").find(line)?.groupValues?.get(1)
                    ?.let { ws -> Regex("[A-Za-z][\\w]*(\\.[\\w]+)+").find(ws)?.value }
                if (pkg == null && uid != null) {
                    pkg = try { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() } catch (_: Throwable) { null }
                }
                seen.add("${pkg ?: "uid ${uid ?: "?"}"}: $tag")
            }
            synchronized(lock) {
                offSamples++
                for (k in seen) offAppCounts[k] = (offAppCounts[k] ?: 0) + 1
            }
        } catch (_: Throwable) {}
    }

    private fun finalizeOff(
        a: Batt, b: Batt,
        k0: Map<String, LongArray>?, k1: Map<String, LongArray>,
        apps: Map<String, Int>, samples: Int
    ) {
        val dur = b.atMs - a.atMs
        val (pct, mahD) = drop(a, b)
        if (mahD < -5f) return
        val hours = dur / 3_600_000f
        val avgV = if (a.mv > 0f && b.mv > 0f) (a.mv + b.mv) / 2000f else 3.85f
        val w = if (mahD > 0f && hours > 0f) mahD / 1000f * avgV / hours else 0f
        val up = (b.uptime - a.uptime).coerceAtLeast(0L)
        val el = (b.elapsed - a.elapsed).coerceAtLeast(1L)
        val deep = ((1f - up.toFloat() / el) * 100f).toInt().coerceIn(0, 100)
        val kernel = ArrayList<WakeStat>()
        if (k0 != null) {
            for ((n, v) in k1) {
                val s = k0[n] ?: continue
                val dT = v[1] - s[1]
                val dC = (v[2] - s[2]).toInt()
                if (dT >= 500L) kernel.add(WakeStat(n, dT, dC))
            }
            kernel.sortByDescending { it.ms }
        }
        val appList = apps.entries.sortedByDescending { it.value }.take(5).map { WakeStat(it.key, 0L, it.value) }
        val p = OffPeriod(
            startMs = a.atMs, durMs = dur, startPct = a.level, endPct = b.level, pctDrop = pct,
            mah = mahD.coerceAtLeast(-1f), avgW = w, deepSleepPct = deep, awakeMs = up, samples = samples,
            kernel = kernel.take(5), apps = appList
        )
        synchronized(lock) {
            periods.add(0, p)
            while (periods.size > 40) periods.removeAt(periods.size - 1)
            val d = dayOf(a.atMs)
            // off-time totals are derived from the stored periods, so only save + rebuild here
            if (days.none { it.day == d }) { days.add(Day(d)); if (days.size > 14) days.removeAt(0) }
        }
        save()
        rebuild()
        log(LogLevel.INFO, "ScreenOff", "Screen-off ${dur / 60000} min: ${"%.2f".format(p.pctPerHour)} %/h, deep sleep $deep%")
    }

    // ------------------------------------------------------------ summary

    private fun rebuild() {
        val snap: ScreenOffSnapshot
        synchronized(lock) {
            if (periods.isEmpty() && days.isEmpty()) { _snapshot.value = ScreenOffSnapshot(); return }
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 86_400_000L
            val today0 = dayOf(now) * 86_400_000L - TimeZone.getDefault().getOffset(now)
            val wk = periods.filter { it.startMs >= weekAgo }
            val td = periods.filter { it.startMs >= today0 }
            fun rate(l: List<OffPeriod>): Float {
                val h = l.sumOf { it.durMs } / 3_600_000f
                return if (h > 0.05f) l.sumOf { it.pctDrop.toDouble() }.toFloat() / h else -1f
            }
            val offH = wk.sumOf { it.durMs } / 3_600_000f
            val weekRate = rate(wk)
            val onD = days.filter { it.day >= dayOf(weekAgo) }
            val onH = onD.sumOf { it.onMs } / 3_600_000f
            val onRate = if (onH > 0.05f) onD.sumOf { it.onPct.toDouble() }.toFloat() / onH else -1f
            val mah = wk.filter { it.mah >= 0f }
            val mahH = mah.sumOf { it.durMs } / 3_600_000f
            val mahRate = if (mahH > 0.05f) mah.sumOf { it.mah.toDouble() }.toFloat() / mahH else -1f
            val totDur = wk.sumOf { it.durMs }.coerceAtLeast(1L)
            val wAvg = if (wk.isNotEmpty()) (wk.sumOf { it.avgW.toDouble() * it.durMs } / totDur).toFloat() else -1f
            val deep = if (wk.isNotEmpty()) (wk.sumOf { it.deepSleepPct.toLong() * it.durMs } / totDur).toInt() else -1
            val kMap = HashMap<String, LongArray>()
            val aMap = HashMap<String, Int>()
            var checks = 0
            for (p in wk) {
                for (k in p.kernel) { val e = kMap.getOrPut(k.name) { LongArray(2) }; e[0] += k.ms; e[1] += k.count.toLong() }
                for (a in p.apps) aMap[a.name] = (aMap[a.name] ?: 0) + a.count
                checks += p.samples
            }
            val worst = (wk.filter { it.durMs >= 900_000L }.ifEmpty { wk }).maxByOrNull { it.pctPerHour }
            snap = ScreenOffSnapshot(
                hasData = periods.isNotEmpty(),
                periodsCount = periods.size,
                offPctPerHToday = rate(td),
                offPctPerHWeek = weekRate,
                onPctPerHWeek = onRate,
                offMahPerHWeek = mahRate,
                offAvgWWeek = wAvg,
                deepSleepPctWeek = deep,
                offHoursWeek = offH,
                standbyHoursToEmpty = if (weekRate > 0.01f) 100f / weekRate else -1f,
                last = periods.firstOrNull(),
                worst = worst,
                topKernel = kMap.entries.sortedByDescending { it.value[0] }.take(5).map { WakeStat(it.key, it.value[0], it.value[1].toInt()) },
                topApps = aMap.entries.sortedByDescending { it.value }.take(5).map { WakeStat(it.key, 0L, it.value) },
                appChecks = checks,
                recent = periods.take(5)
            )
        }
        _snapshot.value = snap
    }

    // ------------------------------------------------------------ persistence

    private fun stats(l: List<WakeStat>): JSONArray {
        val a = JSONArray()
        l.forEach { a.put(JSONArray().put(it.name).put(it.ms).put(it.count)) }
        return a
    }

    private fun readStats(a: JSONArray?): List<WakeStat> {
        val out = ArrayList<WakeStat>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val e = a.optJSONArray(i) ?: continue
            out.add(WakeStat(e.optString(0), e.optLong(1), e.optInt(2)))
        }
        return out
    }

    private fun save() {
        try {
            val root = JSONObject()
            synchronized(lock) {
                val pa = JSONArray()
                for (p in periods) {
                    pa.put(JSONObject().apply {
                        put("s", p.startMs); put("d", p.durMs); put("sp", p.startPct); put("ep", p.endPct)
                        put("pd", p.pctDrop.toDouble()); put("mah", p.mah.toDouble()); put("w", p.avgW.toDouble())
                        put("ds", p.deepSleepPct); put("aw", p.awakeMs); put("n", p.samples)
                        put("k", stats(p.kernel)); put("a", stats(p.apps))
                    })
                }
                val da = JSONArray()
                for (d in days) da.put(JSONObject().put("d", d.day).put("om", d.onMs).put("oh", d.onMah.toDouble()).put("op", d.onPct.toDouble()))
                root.put("periods", pa); root.put("days", da)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Throwable) {}
    }

    fun flush() = save()

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val pa = root.optJSONArray("periods")
            if (pa != null) for (i in 0 until pa.length()) {
                try {
                    val o = pa.getJSONObject(i)
                    periods.add(
                        OffPeriod(
                            o.optLong("s"), o.optLong("d"), o.optInt("sp", -1), o.optInt("ep", -1),
                            o.optDouble("pd", 0.0).toFloat(), o.optDouble("mah", -1.0).toFloat(), o.optDouble("w", 0.0).toFloat(),
                            o.optInt("ds", 0), o.optLong("aw", 0L), o.optInt("n", 0),
                            readStats(o.optJSONArray("k")), readStats(o.optJSONArray("a"))
                        )
                    )
                } catch (_: Throwable) {}
            }
            val da = root.optJSONArray("days")
            if (da != null) for (i in 0 until da.length()) {
                try {
                    val o = da.getJSONObject(i)
                    val d = Day(o.optLong("d"))
                    d.onMs = o.optLong("om"); d.onMah = o.optDouble("oh", 0.0).toFloat(); d.onPct = o.optDouble("op", 0.0).toFloat()
                    days.add(d)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            periods.clear(); days.clear()
        }
    }
}
