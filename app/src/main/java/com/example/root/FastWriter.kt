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

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * v4.3 Wire format between the app and the root helper (one request line in, one reply line out).
 * Request: `W<TAB>path<TAB>value`. Reply: `CGW:<code>` (0 = written). The helper also prints `CGW:READY` once.
 * Any other line (su banners, app_process warnings) is ignored by the reader.
 */
object FastWriteProtocol {
    const val PREFIX = "CGW:"
    const val READY = "CGW:READY"

    fun encodeRequest(path: String, value: String): String = "W\t$path\t$value\n"

    /** Parsed reply code, or null when [line] is not a reply line (noise, READY). */
    fun parseReply(line: String): Int? {
        val t = line.trim()
        if (!t.startsWith(PREFIX)) return null
        return t.removePrefix(PREFIX).toIntOrNull()
    }

    /** Safe to send? Tabs/newlines in a path or value would break the line format. */
    fun isEncodable(path: String, value: String): Boolean =
        path.none { it == '\t' || it == '\n' || it == '\r' } && value.none { it == '\t' || it == '\n' || it == '\r' }
}

/**
 * v4.3 The helper's write logic (no Android classes). Keeps one open file per path and writes each new value
 * at offset 0 of the same file, so a write is one syscall instead of a shell round trip.
 * A node that cannot seek (some proc files) is reopened for every write instead, which is always correct.
 */
class FastWriteCore {
    private class Entry(val channel: FileChannel, var seekable: Boolean = true)

    private val open = HashMap<String, Entry>()
    private val reopenEveryTime = HashSet<String>()

    /** @return 0 on success, 1 on failure (the app then falls back to its shell, which also repairs permissions). */
    fun write(path: String, value: String): Int {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (path !in reopenEveryTime) {
            val e = open[path]
            if (e != null) {
                val seeked = try {
                    e.channel.position(0L)
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!seeked) {
                    // Cannot rewind this node: from now on open it fresh for every write.
                    reopenEveryTime.add(path)
                    drop(path)
                } else if (tryWrite(e.channel, bytes)) {
                    return 0
                } else {
                    // Stale handle (CPU went offline, node recreated) or the kernel refused the value.
                    drop(path)
                }
            }
        }
        return freshWrite(path, bytes)
    }

    private fun freshWrite(path: String, bytes: ByteArray): Int {
        val ch = try {
            FileOutputStream(File(path)).channel
        } catch (_: Throwable) {
            return 1
        }
        val ok = tryWrite(ch, bytes)
        if (ok && path !in reopenEveryTime) {
            open[path] = Entry(ch)
        } else {
            try { ch.close() } catch (_: Throwable) {}
        }
        return if (ok) 0 else 1
    }

    private fun tryWrite(ch: FileChannel, bytes: ByteArray): Boolean = try {
        val buf = ByteBuffer.wrap(bytes)
        var guard = 0
        while (buf.hasRemaining() && guard++ < 4) ch.write(buf)
        !buf.hasRemaining()
    } catch (_: Throwable) {
        false
    }

    private fun drop(path: String) {
        open.remove(path)?.let { try { it.channel.close() } catch (_: Throwable) {} }
    }

    fun closeAll() {
        for (p in open.keys.toList()) drop(p)
    }

    val openCount: Int get() = open.size
}

/**
 * v4.3 Entry point of the root helper. Started by [FastWriter] as
 * `CLASSPATH=<apk> app_process /system/bin com.example.root.FastWriterMain` inside a `su` shell, so it runs as root
 * with the app's own code. It exits when its stdin closes (app died or the governor stopped).
 */
object FastWriterMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val core = FastWriteCore()
        val out = System.out
        out.println(FastWriteProtocol.READY)
        out.flush()
        val input = System.`in`.bufferedReader()
        try {
            while (true) {
                val line = input.readLine() ?: break
                if (line == "Q") break
                val parts = line.split('\t', limit = 3)
                val code = if (parts.size == 3 && parts[0] == "W") core.write(parts[1], parts[2]) else 2
                out.println("${FastWriteProtocol.PREFIX}$code")
                out.flush()
            }
        } finally {
            core.closeAll()
        }
    }
}
