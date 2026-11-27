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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = exitCode == 0 && error == null
}

/**
 * Single persistent `su` shell session shared by the governor controller.
 * All reads and writes are serialized onto one dedicated dispatcher and guarded by a Mutex.
 */
class RootShell {
    private val mutex = Mutex()
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CoreGov-SuShell").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    var isRootAvailable: Boolean = false
        private set

    private val permissionFixedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val readModeMutex = Mutex()
    @Volatile
    private var useBuiltinRead: Boolean? = null
    var onLog: ((String) -> Unit)? = null

    /** v4.3 optional root helper that holds frequency files open (only the fast switcher's shell turns it on). */
    @Volatile private var fastWriter: FastWriter? = null

    /** Turns on the root helper for this shell's writes. Safe to call again; reads and other commands are unchanged. */
    fun enableFastWriter(apkPath: String) {
        val existing = fastWriter
        if (existing != null) {
            existing.startAsync()
            return
        }
        val fw = FastWriter(apkPath) { msg -> onLog?.invoke(msg) }
        fastWriter = fw
        fw.startAsync()
    }

    /** Stops the helper (governor stopped). It restarts on the next write attempt or [enableFastWriter]. */
    fun releaseFastWriter() {
        fastWriter?.stop()
    }

    suspend fun ensureSession(): Boolean = mutex.withLock {
        withContext(dispatcher) {
            ensureSessionLocked()
        }
    }

    private fun ensureSessionLocked(): Boolean {
        val current = process
        if (current != null && isAlive(current) && writer != null && reader != null) {
            return isRootAvailable
        }
        destroyProcessLocked()
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            process = p
            writer = w
            reader = r
            val idCheck = execLocked("id -u")
            isRootAvailable = idCheck.isSuccess && idCheck.stdout.trim() == "0"
            if (!isRootAvailable) {
                destroyProcessLocked()
            }
            isRootAvailable
        } catch (t: Throwable) {
            isRootAvailable = false
            destroyProcessLocked()
            false
        }
    }

    @Volatile private var lastSlowLogMs = 0L

    suspend fun exec(command: String): ShellResult {
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        return mutex.withLock {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val res = withContext(dispatcher) {
                if (!ensureSessionLocked()) {
                    return@withContext ShellResult(-1, "", "Root shell unavailable (su failed)")
                }
                execLocked(command)
            }
            // v3.7.0: name whatever holds the shared root shell for long (it delays every power/frequency read).
            val ranMs = android.os.SystemClock.elapsedRealtime() - startedAt
            val waitedMs = startedAt - queuedAt
            if ((ranMs >= 400L || waitedMs >= 600L) && startedAt - lastSlowLogMs >= 5_000L) {
                lastSlowLogMs = startedAt
                val what = command.replace('\n', ' ').take(70)
                onLog?.invoke("Slow root command: ran ${ranMs}ms, waited ${waitedMs}ms: $what")
            }
            res
        }
    }

    /**
     * Reads multiple sysfs/proc files in a single `su` shell round-trip using unique delimiters.
     */
    suspend fun readFilesBatch(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val useBuiltin = resolveReadMode()
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val beginPrefix = "__CGB_${token}_"
        val endPrefix = "__CGE_${token}_"
        val sb = StringBuilder()
        paths.forEachIndexed { idx, path ->
            val qPath = shellQuote(path)
            sb.append("echo '${beginPrefix}${idx}'; ")
            if (useBuiltin) {
                sb.append("{ while IFS= read -r l || [ -n \"\$l\" ]; do echo \"\$l\"; done < $qPath; } 2>/dev/null; ")
            } else {
                sb.append("cat $qPath 2>/dev/null; ")
            }
            sb.append("echo ''; echo '${endPrefix}${idx}'; ")
        }
        val res = exec(sb.toString())
        if (!res.isSuccess && res.stdout.isEmpty()) return emptyMap()

        val map = LinkedHashMap<String, String>(paths.size)
        val lines = res.stdout.lines()
        var currentIdx = -1
        val buffer = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith(beginPrefix) -> {
                    currentIdx = line.removePrefix(beginPrefix).trim().toIntOrNull() ?: -1
                    buffer.clear()
                }
                line.startsWith(endPrefix) -> {
                    if (currentIdx in paths.indices) {
                        map[paths[currentIdx]] = buffer.toString().trim()
                    }
                    currentIdx = -1
                    buffer.clear()
                }
                currentIdx >= 0 -> {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
                }
            }
        }
        return map
    }

    private suspend fun resolveReadMode(): Boolean {
        useBuiltinRead?.let { return it }
        return readModeMutex.withLock {
            useBuiltinRead?.let { return@withLock it }
            val testPath = shellQuote("/sys/devices/system/cpu/possible")
            val token = UUID.randomUUID().toString().replace("-", "").take(12)
            val mCat = "__CG_CAT_${token}__"
            val mBlt = "__CG_BLT_${token}__"
            val mEnd = "__CG_END_${token}__"
            val cmd = "echo '$mCat'; cat $testPath 2>/dev/null; echo ''; echo '$mBlt'; { while IFS= read -r l || [ -n \"\$l\" ]; do echo \"\$l\"; done < $testPath; } 2>/dev/null; echo ''; echo '$mEnd'"
            val res = exec(cmd)
            if (!res.isSuccess) return@withLock false
            var section = 0
            val catBuf = StringBuilder()
            val bltBuf = StringBuilder()
            for (line in res.stdout.lines()) {
                when {
                    line.startsWith(mCat) -> section = 1
                    line.startsWith(mBlt) -> section = 2
                    line.startsWith(mEnd) -> section = 0
                    section == 1 -> {
                        if (catBuf.isNotEmpty()) catBuf.append('\n')
                        catBuf.append(line)
                    }
                    section == 2 -> {
                        if (bltBuf.isNotEmpty()) bltBuf.append('\n')
                        bltBuf.append(line)
                    }
                }
            }
            val builtinOk = catBuf.toString().trim() == bltBuf.toString().trim()
            useBuiltinRead = builtinOk
            onLog?.invoke(if (builtinOk) "Root read mode: builtin" else "Root read mode: cat")
            builtinOk
        }
    }

    suspend fun readFile(path: String): String? {
        val res = exec("cat ${shellQuote(path)}")
        return if (res.isSuccess) res.stdout.trim() else null
    }

    suspend fun writeSysfs(path: String, value: String): ShellResult = writeSysfs(path, value, true)

    private suspend fun writeSysfs(path: String, value: String, viaHelper: Boolean): ShellResult {
        if (viaHelper) {
            val codes = fastWriter?.write(listOf(path to value))
            if (codes != null && codes.isNotEmpty() && codes[0] == 0) return ShellResult(0, "")
            // helper unavailable or refused this write: the shell path below also repairs file permissions
        }
        val q = shellQuote(path)
        val qVal = shellQuote(value)
        val printfCmd = "printf '%s' $qVal > $q"
        val firstTime = permissionFixedPaths.add(path)
        var res = if (firstTime) {
            exec("chmod u+w $q 2>/dev/null; $printfCmd")
        } else {
            exec(printfCmd)
        }
        if (!res.isSuccess) {
            exec("chmod 0644 $q 2>/dev/null")
            res = exec(printfCmd)
            if (!res.isSuccess) {
                exec("chmod 0666 $q 2>/dev/null")
                res = exec(printfCmd)
            }
        }
        return res
    }

    /**
     * v4.1: writes several files in order using ONE shell round trip. Any write that did not report success is
     * retried through [writeSysfs] (same chmod fallbacks as before), in the original order.
     */
    suspend fun writeSysfsOrdered(writes: List<Pair<String, String>>): List<ShellResult> {
        if (writes.isEmpty()) return emptyList()
        val helperCodes = fastWriter?.write(writes)
        if (helperCodes != null && helperCodes.size == writes.size) {
            // One pipe round trip for the whole batch; only a write the helper could not do goes through the shell.
            return writes.mapIndexed { i, (path, value) ->
                if (helperCodes[i] == 0) ShellResult(0, "") else writeSysfs(path, value, false)
            }
        }
        if (writes.size == 1) return listOf(writeSysfs(writes[0].first, writes[0].second, false))
        val res = exec(SysfsBatchWrite.buildCommand(writes) { permissionFixedPaths.add(it) })
        // The shell never ran the batch (su gone): forget the "chmod done" marks so the retry below redoes them.
        if (res.error != null) writes.forEach { permissionFixedPaths.remove(it.first) }
        val codes = SysfsBatchWrite.parseCodes(res.stdout, writes.size)
        return writes.mapIndexed { i, (path, value) ->
            if (codes[i] == 0) ShellResult(0, "") else writeSysfs(path, value, false)
        }
    }

    /**
     * Best-effort synchronous restore helper for crash/shutdown hooks.
     * Avoids suspending when called from an UncaughtExceptionHandler.
     */
    fun writeSysfsBlocking(commands: List<String>) {
        if (commands.isEmpty()) return
        try {
            val p = process
            val w = writer
            if (p != null && isAlive(p) && w != null) {
                for (cmd in commands) {
                    w.write(cmd)
                    w.newLine()
                }
                w.flush()
            } else {
                val fallback = ProcessBuilder("su", "-c", commands.joinToString("; ")).start()
                fallback.waitFor()
            }
        } catch (_: Throwable) {
            // Best effort during crash/teardown
        }
    }

    suspend fun close() = mutex.withLock {
        fastWriter?.stop()
        withContext(dispatcher) {
            destroyProcessLocked()
            isRootAvailable = false
        }
    }

    private fun execLocked(command: String): ShellResult {
        val w = writer ?: return ShellResult(-1, "", "Shell writer closed")
        val r = reader ?: return ShellResult(-1, "", "Shell reader closed")
        val marker = "__CG_END_${UUID.randomUUID().toString().replace("-", "").take(12)}__:"
        return try {
            w.write(command)
            w.newLine()
            w.write("printf '\\n%s%d\\n' '$marker' $?")
            w.newLine()
            w.flush()

            val out = StringBuilder()
            var exitCode = -1
            while (true) {
                val line = r.readLine() ?: break
                val markerIdx = line.indexOf(marker)
                if (markerIdx >= 0) {
                    if (markerIdx > 0) {
                        if (out.isNotEmpty()) out.append('\n')
                        out.append(line.substring(0, markerIdx))
                    }
                    exitCode = line.substring(markerIdx + marker.length).trim().toIntOrNull() ?: 0
                    break
                } else {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(line)
                }
            }
            ShellResult(exitCode = exitCode, stdout = out.toString().trim())
        } catch (t: Throwable) {
            destroyProcessLocked()
            ShellResult(-1, "", t.message ?: "Shell I/O error")
        }
    }

    private fun destroyProcessLocked() {
        try {
            writer?.apply {
                write("exit\n")
                flush()
                close()
            }
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        writer = null
        reader = null
        process = null
    }

    private fun isAlive(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun shellQuote(raw: String): String {
        return "'" + raw.replace("'", "'\"'\"'") + "'"
    }
}
