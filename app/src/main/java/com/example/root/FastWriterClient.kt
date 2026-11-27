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

package com.example.root

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * v4.3 Client of the root helper ([FastWriterMain]). The helper starts once as root, keeps the frequency files open and
 * writes each value directly, so a write costs about one pipe round trip instead of a whole shell command.
 *
 * Never blocks the caller to start: until the helper says READY, [write] returns null and the caller uses its normal
 * shell path. Any trouble (helper cannot start, pipe breaks, a call hangs) also returns null, the helper is dropped and
 * restarted after a pause, and after [MAX_FAILURES] failures it stays off for the session.
 */
class FastWriter(
    private val apkPath: String,
    private val onLog: (String) -> Unit
) {
    private enum class State { IDLE, STARTING, READY, FAILED, DISABLED }

    @Volatile private var state = State.IDLE
    @Volatile private var retryAtMs = 0L
    @Volatile private var failures = 0

    private val mutex = Mutex()
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CoreGov-FastWriter").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    val isReady: Boolean get() = state == State.READY

    /** Starts the helper in the background if it is not running and not waiting out a failure pause. */
    @Synchronized
    fun startAsync() {
        val now = android.os.SystemClock.elapsedRealtime()
        val canStart = state == State.IDLE || (state == State.FAILED && now >= retryAtMs)
        if (!canStart) return
        state = State.STARTING
        scope.launch { startHelper() }
    }

    private suspend fun startHelper() {
        mutex.withLock {
            withContext(dispatcher) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val p = ProcessBuilder("su").redirectErrorStream(true).start()
                    process = p
                    val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    writer = w
                    reader = r
                    val apk = "'" + apkPath.replace("'", "'\"'\"'") + "'"
                    w.write("CLASSPATH=$apk exec app_process /system/bin --nice-name=cg-fastwrite ${FastWriterMain::class.java.name}")
                    w.newLine()
                    w.flush()
                    val wd = watchdog(START_TIMEOUT_MS)
                    var ready = false
                    try {
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.trim() == FastWriteProtocol.READY) {
                                ready = true
                                break
                            }
                        }
                    } finally {
                        wd.cancel()
                    }
                    if (ready) {
                        failures = 0
                        state = State.READY
                        onLog("Fast writer ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms (frequency files held open as root)")
                    } else {
                        fail("helper did not start")
                    }
                } catch (t: Throwable) {
                    fail("helper start error: ${t.message}")
                }
            }
        }
    }

    /**
     * Writes in order. Returns one code per write (0 = written), or null when the helper cannot be used right now
     * (the caller then writes through its shell). A non-zero code means only that this write failed.
     */
    suspend fun write(writes: List<Pair<String, String>>): IntArray? {
        if (writes.isEmpty()) return IntArray(0)
        if (state != State.READY) {
            startAsync()
            return null
        }
        if (writes.any { !FastWriteProtocol.isEncodable(it.first, it.second) }) return null
        return mutex.withLock {
            if (state != State.READY) return@withLock null
            withContext(dispatcher) { exchange(writes) }
        }
    }

    private fun exchange(writes: List<Pair<String, String>>): IntArray? {
        val w = writer ?: return null
        val r = reader ?: return null
        val wd = watchdog(CALL_TIMEOUT_MS)
        return try {
            val sb = StringBuilder()
            for ((p, v) in writes) sb.append(FastWriteProtocol.encodeRequest(p, v))
            w.write(sb.toString())
            w.flush()
            val codes = IntArray(writes.size)
            var n = 0
            while (n < writes.size) {
                val line = r.readLine() ?: throw java.io.EOFException("helper closed")
                val code = FastWriteProtocol.parseReply(line) ?: continue
                codes[n++] = code
            }
            codes
        } catch (t: Throwable) {
            fail("helper call failed: ${t.message}")
            null
        } finally {
            wd.cancel()
        }
    }

    /** Kills the helper if a call takes longer than [ms]; that unblocks the pending read with an I/O error. */
    private fun watchdog(ms: Long) = scope.launch {
        delay(ms)
        try { process?.destroy() } catch (_: Throwable) {}
    }

    private fun fail(reason: String) {
        failures++
        teardown()
        if (failures >= MAX_FAILURES) {
            state = State.DISABLED
            onLog("Fast writer off for this session ($reason); frequency writes use the normal shell")
        } else {
            retryAtMs = android.os.SystemClock.elapsedRealtime() + RETRY_PAUSE_MS * failures
            state = State.FAILED
            onLog("Fast writer problem ($reason); using the normal shell, retrying later")
        }
    }

    private fun teardown() {
        try { writer?.apply { write("Q\n"); flush(); close() } } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        writer = null
        reader = null
        process = null
    }

    /** Stops the helper (the files it held are closed). A later [startAsync] can start it again. */
    fun stop() {
        scope.launch {
            mutex.withLock {
                withContext(dispatcher) {
                    teardown()
                    if (state != State.DISABLED) state = State.IDLE
                }
            }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 6_000L
        const val CALL_TIMEOUT_MS = 2_000L
        const val RETRY_PAUSE_MS = 30_000L
        const val MAX_FAILURES = 3
    }
}
