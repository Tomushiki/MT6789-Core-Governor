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
import com.example.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/**
 * Shared touch "interaction signal" (Smart app profiles + Adaptive refresh rate).
 * Primary: delta of the touchscreen IRQ counter in /proc/interrupts, polled ~10 Hz.
 * Fallback: a lightweight root `cat` of the touch input node (any bytes = touch).
 * Runs only while a feature needs it AND the screen is on.
 */
class InteractionSignal(
    private val rootShell: RootShell,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String, String) -> Unit
) {
    @Volatile var lastTouchMs: Long = 0L
        private set
    @Volatile var source: String = "not started"
        private set
    @Volatile var onTouch: (() -> Unit)? = null

    private val guard = Any()
    private var job: Job? = null
    private var irq = -2
    private var eventNode: String? = null
    private var lastCount = -1L
    private val buf = ByteArray(32768)
    private var useRoot = false
    private var raf: RandomAccessFile? = null
    private var eventProc: Process? = null

    fun setActive(needed: Boolean, screenOn: Boolean) {
        val want = needed && screenOn
        synchronized(guard) {
            if (want && job == null) {
                job = scope.launch { run() }
            } else if (!want && job != null) {
                job?.cancel(); job = null
                lastCount = -1L
                closeIo()
            }
        }
    }

    private fun closeIo() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
        try { eventProc?.destroy() } catch (_: Throwable) {}
        eventProc = null
    }

    private suspend fun run() {
        if (irq == -2) discover()
        if (irq >= 0) {
            while (true) {
                val c = readCount()
                if (c >= 0L) {
                    if (lastCount >= 0L && c != lastCount) {
                        lastTouchMs = System.currentTimeMillis()
                        onTouch?.invoke()
                    }
                    lastCount = c
                }
                delay(100)
            }
        } else {
            val node = eventNode
            if (node == null) {
                source = "unavailable"
                return
            }
            startEventReader(node)
        }
    }

    private fun startEventReader(node: String) {
        try {
            val p = ProcessBuilder("su", "-c", "exec cat $node").redirectErrorStream(true).start()
            eventProc = p
            source = "input events"
            val t = Thread {
                val b = ByteArray(4096)
                try {
                    val ins = p.inputStream
                    while (true) {
                        val n = ins.read(b)
                        if (n < 0) break
                        if (n > 0) {
                            lastTouchMs = System.currentTimeMillis()
                            onTouch?.invoke()
                        }
                    }
                } catch (_: Throwable) {}
            }
            t.isDaemon = true
            t.name = "CoreGov-TouchEvt"
            t.start()
        } catch (e: Throwable) {
            source = "unavailable"
            log(LogLevel.WARN, "Touch", "Touch event reader failed: ${e.message}")
        }
    }

    private suspend fun discover() {
        irq = -1
        try {
            val dev = readText("/proc/bus/input/devices").orEmpty()
            val names = ArrayList<String>()
            for (blockRaw in dev.split("\n\n")) {
                val block = blockRaw.trim()
                if (block.isEmpty()) continue
                val name = Regex("N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1).orEmpty()
                val lower = name.lowercase()
                if (lower.contains("fingerprint") || lower.contains("key") || lower.contains("pwr") ||
                    lower.contains("hall") || lower.contains("headset") || lower.contains("sensor")
                ) continue
                val prop = Regex("B: PROP=([0-9a-fA-F]+)").find(block)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
                val looksTouch = (prop and 2L) != 0L || lower.contains("touch") || lower.contains("tpd") ||
                    lower.contains("ts") && lower.length <= 12
                if (!looksTouch) continue
                names.add(lower)
                if (eventNode == null) {
                    val ev = Regex("event(\\d+)").find(block)?.value
                    if (ev != null) eventNode = "/dev/input/$ev"
                }
            }
            val tokens = ArrayList<String>()
            for (n in names) n.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it != "touch" && it != "screen" && it != "input" }.forEach { tokens.add(it) }
            val generic = listOf("goodix", "gt9", "nvt", "nt36", "novatek", "fts", "focal", "synaptics", "himax", "hx83", "ilitek", "ili", "gsl", "chipone", "icnl", "tcs", "touch", "tpd")
            val ints = readText("/proc/interrupts").orEmpty().lines()
            val skip = listOf("key", "gpio-keys", "fingerprint", "volume", "pwr")
            fun findLine(tok: List<String>): Int {
                for (ln in ints) {
                    val l = ln.lowercase()
                    if (skip.any { l.contains(it) }) continue
                    if (tok.any { l.contains(it) }) {
                        val m = Regex("^\\s*(\\d+):").find(l) ?: continue
                        return m.groupValues[1].toInt()
                    }
                }
                return -1
            }
            irq = findLine(tokens)
            if (irq < 0) irq = findLine(generic)
            source = if (irq >= 0) "IRQ $irq" else if (eventNode != null) "input events" else "unavailable"
            log(LogLevel.INFO, "Touch", "Touch signal source: $source")
        } catch (e: Throwable) {
            irq = -1
            source = "unavailable"
        }
    }

    private suspend fun readText(path: String): String? {
        try {
            val t = File(path).readText()
            if (t.isNotBlank()) return t
        } catch (_: Throwable) {}
        return rootShell.readFile(path)
    }

    private suspend fun readCount(): Long {
        var len = -1
        if (!useRoot) {
            try {
                var r = raf
                if (r == null) { r = RandomAccessFile("/proc/interrupts", "r"); raf = r }
                r.seek(0)
                var total = 0
                while (total < buf.size) {
                    val n = r.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                len = total
            } catch (_: Throwable) {
                useRoot = true
                closeRaf()
            }
            if (len <= 0 && !useRoot) { useRoot = true; closeRaf() }
        }
        if (useRoot) {
            val s = rootShell.readFile("/proc/interrupts") ?: return -1L
            val bytes = s.toByteArray()
            len = minOf(bytes.size, buf.size)
            System.arraycopy(bytes, 0, buf, 0, len)
        }
        return parseCount(len)
    }

    private fun closeRaf() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
    }

    /** Allocation-free scan: find the line for [irq] and sum its per-CPU counters. */
    private fun parseCount(len: Int): Long {
        var i = 0
        while (i < len) {
            while (i < len && buf[i] == ' '.code.toByte()) i++
            var num = 0
            var digits = 0
            while (i < len && buf[i] in 48..57) { num = num * 10 + (buf[i] - 48); i++; digits++ }
            if (digits > 0 && i < len && buf[i] == ':'.code.toByte() && num == irq) {
                i++
                var sum = 0L
                while (i < len) {
                    while (i < len && buf[i] == ' '.code.toByte()) i++
                    if (i < len && buf[i] in 48..57) {
                        var v = 0L
                        while (i < len && buf[i] in 48..57) { v = v * 10 + (buf[i] - 48); i++ }
                        sum += v
                    } else break
                }
                return sum
            }
            while (i < len && buf[i] != '\n'.code.toByte()) i++
            i++
        }
        return -1L
    }
}
