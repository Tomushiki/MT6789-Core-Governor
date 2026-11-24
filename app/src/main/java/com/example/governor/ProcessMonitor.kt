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

/** One process read from the phone. */
data class ProcSnap(val pid: Int, val uid: Int, val name: String, val rssKb: Long, val ticks: Long)

/** One row in the System tab: an app package (all its processes together) or a system process. */
data class ProcRow(
    val key: String,
    val isApp: Boolean,
    val procCount: Int,
    val cpuPct: Float,
    val ramMb: Float,
    val foreground: Boolean,
    val protectedReason: String?,
    val culprit: Boolean = false
) {
    /** Only real package names can be stopped, and never protected or foreground ones. */
    val killable: Boolean
        get() = protectedReason == null && !foreground && ProcessMonitor.PACKAGE_RE.matches(key)
}

/** Parsing, grouping and protection rules for the System tab. Pure. */
object ProcessMonitor {
    const val STAT_MARK = "===STAT==="
    const val COMMAND = "ps -A -o PID,UID,RSS,ARGS 2>/dev/null; echo ===STAT===; cat /proc/[0-9]*/stat 2>/dev/null"

    val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PS_LINE = Regex("^\\s*(\\d+)\\s+(\\S+)\\s+(\\d+)\\s+(.+)$")
    private val USER_NAME = Regex("^u(\\d+)_a(\\d+)$")
    private val IGNORED = setOf("ps", "cat", "sh", "su", "toybox", "toolbox")

    private val PROTECTED_PREFIXES = listOf(
        "com.android.systemui", "com.android.phone", "com.android.server", "com.android.providers.",
        "com.android.networkstack", "com.android.bluetooth", "com.android.nfc", "com.android.se",
        "com.android.inputmethod.", "com.google.android.inputmethod", "com.android.cellbroadcast",
        "com.android.emergency", "com.android.shell", "com.mediatek.ims", "com.mediatek.telephony"
    )

    fun isAppUid(uid: Int): Boolean = uid % 100000 >= 10000

    fun parseUid(tok: String): Int {
        tok.toIntOrNull()?.let { return it }
        USER_NAME.matchEntire(tok)?.let {
            return it.groupValues[1].toInt() * 100000 + 10000 + it.groupValues[2].toInt()
        }
        return when (tok) {
            "root" -> 0
            "system" -> 1000
            "radio" -> 1001
            "shell" -> 2000
            else -> 1000
        }
    }

    /** Ticks (utime + stime) per pid from /proc/<pid>/stat lines. */
    fun parseStat(text: String): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for (line in text.lineSequence()) {
            val sp = line.indexOf(' ')
            val rp = line.lastIndexOf(')')
            if (sp <= 0 || rp < 0 || rp + 2 > line.length) continue
            val pid = line.substring(0, sp).toIntOrNull() ?: continue
            val f = line.substring(rp + 2).split(' ')
            if (f.size < 13) continue
            val ut = f[11].toLongOrNull() ?: continue
            val st = f[12].toLongOrNull() ?: continue
            out[pid] = ut + st
        }
        return out
    }

    fun parse(output: String): List<ProcSnap> {
        val idx = output.indexOf(STAT_MARK)
        val psPart = if (idx >= 0) output.substring(0, idx) else output
        val statPart = if (idx >= 0) output.substring(idx + STAT_MARK.length) else ""
        val ticks = parseStat(statPart)
        val res = ArrayList<ProcSnap>()
        for (line in psPart.lineSequence()) {
            val m = PS_LINE.matchEntire(line) ?: continue
            val pid = m.groupValues[1].toInt()
            val name = m.groupValues[4].trim().substringBefore(' ')
            if (name.isEmpty() || name.startsWith("[") || name in IGNORED) continue
            val t = ticks[pid] ?: continue
            res += ProcSnap(pid, parseUid(m.groupValues[2]), name, m.groupValues[3].toLongOrNull() ?: 0L, t)
        }
        return res
    }

    fun keyOf(name: String, uid: Int): String {
        val base = name.substringBefore(':')
        return if (isAppUid(uid)) base else base.substringAfterLast('/')
    }

    /** Why a process may not be stopped, or null when it may. [extra] = launcher, keyboard, dialer, this app. */
    fun protectedReason(key: String, uid: Int, extra: Set<String>): String? {
        if (key in extra) return "In use by the system"
        for (p in PROTECTED_PREFIXES) if (key == p || key.startsWith(p)) return "Core system"
        if (isAppUid(uid)) return null
        if (uid == 0) return "Root process"
        if (!PACKAGE_RE.matches(key)) return "System daemon"
        if (key == "android") return "Core system"
        if (key.startsWith("android.hardware.") || key.startsWith("android.system.") || key.startsWith("vendor.")) {
            return "Hardware service"
        }
        return null
    }

    /** CPU is a percent of one core, from the tick change since the last sample. */
    fun group(
        procs: List<ProcSnap>,
        prevTicks: Map<Int, Long>?,
        dtMs: Long,
        foreground: String,
        extraProtected: Set<String>
    ): List<ProcRow> {
        class Acc(val uid: Int) { var cpu = 0f; var rss = 0L; var n = 0 }
        val map = LinkedHashMap<String, Acc>()
        for (p in procs) {
            val key = keyOf(p.name, p.uid)
            if (key.isEmpty()) continue
            val a = map.getOrPut(key) { Acc(p.uid) }
            a.n++
            a.rss += p.rssKb
            if (prevTicks != null && dtMs > 0L) {
                val prev = prevTicks[p.pid]
                if (prev != null && p.ticks >= prev) a.cpu += (p.ticks - prev) * 1000f / dtMs
            }
        }
        return map.map { (key, a) ->
            ProcRow(
                key = key,
                isApp = isAppUid(a.uid),
                procCount = a.n,
                cpuPct = a.cpu,
                ramMb = a.rss / 1024f,
                foreground = key == foreground,
                protectedReason = protectedReason(key, a.uid, extraProtected)
            )
        }.sortedWith(compareByDescending<ProcRow> { it.cpuPct }.thenByDescending { it.ramMb })
    }
}

/**
 * Flags an app as a culprit when its average CPU over the last [window] samples stays above a limit.
 * v2.7.4: samples come every 8 s, so 4 samples keep the same window of about 30 s (was 6 samples at 4 s).
 */
class CulpritTracker(private val window: Int = DEFAULT_WINDOW) {
    companion object {
        const val DEFAULT_WINDOW = 4
    }

    private val hist = HashMap<String, ArrayDeque<Float>>()

    fun update(rows: List<ProcRow>, thresholdPct: Float): List<ProcRow> {
        val seen = HashSet<String>()
        val out = rows.map { r ->
            seen.add(r.key)
            val d = hist.getOrPut(r.key) { ArrayDeque() }
            d.addLast(r.cpuPct)
            while (d.size > window) d.removeFirst()
            val avg = d.sum() / d.size
            val culprit = d.size >= window && avg >= thresholdPct && r.protectedReason == null && !r.foreground
            r.copy(culprit = culprit)
        }
        hist.keys.retainAll(seen)
        return out
    }

    fun clear() = hist.clear()
}
