/*
 * CoreGovernor - custom CPU/GPU throttling governor
 * Copyright (C) 2026 Azrael
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.governor

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * Root helper that reads the focused app's real FPS the same way the Metric monitor does:
 * it registers an android.window.ITaskFpsCallback with the window manager for the focused task.
 *
 * It is NOT started as part of the app process. [FpsDaemonClient] launches it through
 * `su -c app_process ...` with this APK on CLASSPATH, so it runs as root and can call the
 * hidden IWindowManager API (which needs ACCESS_FPS_COUNTER) by reflection.
 *
 * Protocol (stdout, one line each):
 *   OK                      daemon started
 *   T <taskId> <package>    now watching this task
 *   F <fps> <taskId>        FPS value from the callback
 *   E <message>             error (daemon keeps trying)
 * The daemon exits when its stdin is closed (the app died or stopped it).
 */
object FpsDaemon {
    private const val CB_DESCRIPTOR = "android.window.ITaskFpsCallback"

    @Volatile private var currentTask = -1
    @Volatile private var currentProxy: Any? = null
    @Volatile private var currentPkg = ""
    @Volatile private var lastCbRt = 0L
    @Volatile private var lastCbFps = 0f
    private var wm: Any? = null
    private var atm: Any? = null
    private var registerM: Method? = null
    private var unregisterM: Method? = null

    private fun emit(line: String) {
        println(line)
        System.out.flush()
        if (System.out.checkError()) exitProcess(0)
    }

    private class FpsBinder : Binder() {
        init { attachInterface(null, CB_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(CB_DESCRIPTOR)
                val fps = data.readFloat()
                lastCbFps = fps
                lastCbRt = SystemClock.elapsedRealtime()
                emit("F $fps $currentTask")
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun service(name: String): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }

    private fun asInterface(stub: String, binder: IBinder): Any? =
        Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)

    /** Task id of the most recent (top) task. Tries the common signatures across Android versions. */
    private fun topTask(): Pair<Int, String>? {
        val a = atm ?: return null
        val iface = Class.forName("android.app.IActivityTaskManager")

        for (m in iface.methods.filter { it.name == "getTasks" }.sortedBy { it.parameterCount }) {
            try {
                var firstInt = true
                val args = arrayOfNulls<Any>(m.parameterTypes.size)
                for ((i, t) in m.parameterTypes.withIndex()) {
                    args[i] = when (t) {
                        Int::class.javaPrimitiveType -> if (firstInt) { firstInt = false; 1 } else -1
                        Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                }
                val list = m.invoke(a, *args) as? List<*> ?: continue
                val info = list.firstOrNull() ?: continue
                val id = info.javaClass.getField("taskId").getInt(info)
                val top = try { info.javaClass.getField("topActivity").get(info) } catch (_: Throwable) { null }
                val pkg = top?.let { it.javaClass.getMethod("getPackageName").invoke(it) as? String } ?: "?"
                if (id >= 0) return id to pkg
            } catch (_: Throwable) { /* try next signature */ }
        }

        try {
            val info = iface.getMethod("getFocusedRootTaskInfo").invoke(a)
            if (info != null) {
                val id = info.javaClass.getField("taskId").getInt(info)
                if (id >= 0) return id to "focused-root"
            }
        } catch (_: Throwable) { }
        return null
    }

    // ---- Layer measurement (SurfaceFlinger frame timestamps) ----
    @Volatile private var bestLayer = ""
    @Volatile private var scanNow = true
    @Volatile private var lastScanErr = ""
    private var layerCache = emptyList<String>()

    private class Meas(val fps: Float, val detail: String, val sample: String)

    private fun oneLine(t: String): String =
        t.trim().replace('\n', '|').replace('\r', ' ').let { if (it.length > 170) it.substring(0, 170) else it }

    /** Runs dumpsys by absolute path (the root app_process shell may have no PATH). */
    private val dumpLock = Any()
    private fun dumpsys(vararg args: String): String = synchronized(dumpLock) {
        // One dumpsys at a time: parallel dumps made SurfaceFlinger return empty frame tables now and then.
        try {
            val p = ProcessBuilder(listOf("/system/bin/dumpsys", *args)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (t: Throwable) {
            ""
        }
    }

    // On newer Android, `dumpsys SurfaceFlinger --list` can print a long debug dump
    // (RequestedLayerState{...}) instead of plain layer names, so real names are pulled out with a regex.
    private val layerRegex = Regex("SurfaceView\\[[^\\]]*\\](?:\\(BLAST\\))?#\\d+|[\\w.\\$]+/[\\w.\\$]+(?:\\(BLAST\\))?#\\d+")
    private val whitespaceRegex = Regex("\\s+")
    private val skipWords = listOf("InputSink", "Splash", "Letterbox", "Background for", "Bounds for", "Dim Layer", "Task=", "Wallpaper")

    /** Layers of the app (SurfaceView layers first, then newest), up to 6. Only called from the scan thread. */
    private fun candidateLayers(pkg: String): List<String> {
        val out = dumpsys("SurfaceFlinger", "--list")
        val names = LinkedHashSet<String>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.length > 800 || !line.contains(pkg)) continue
            if (skipWords.any { line.contains(it) }) continue
            for (m in layerRegex.findAll(line)) {
                if (m.value.contains(pkg)) names.add(m.value)
            }
        }
        layerCache = names.sortedWith(
            compareBy<String>({ if (it.startsWith("SurfaceView")) 0 else 1 }, { -(it.substringAfterLast('#').toIntOrNull() ?: 0) })
        ).take(6)
        return layerCache
    }

    /** Frames per second from a list of timestamps (ns, CLOCK_MONOTONIC). */
    private fun rate(ts: List<Long>, nowNs: Long): Float {
        val recent = ts.filter { nowNs - it in -100_000_000L..1_000_000_000L }.sorted()
        if (recent.isEmpty()) return 0f
        // The frame table can hold fewer frames than one second at high frame rates (this ROM keeps about 60 rows),
        // so counting rows would never read above ~60. When there are enough frames over a long enough stretch and
        // the layer is still drawing, use the frame spacing instead: it is right at any frame rate.
        if (recent.size >= 20) {
            val span = recent.last() - recent.first()
            if (span >= 300_000_000L && nowNs - recent.last() <= 250_000_000L) {
                return (recent.size - 1) * 1_000_000_000f / span
            }
        }
        return recent.size.toFloat()
    }

    /** FPS of one layer from SurfaceFlinger frame timestamps (best of presented / ready times). fps -1 = no data. */
    private fun layerFps(name: String): Meas {
        val text = dumpsys("SurfaceFlinger", "--latency", name)
        val nowNs = System.nanoTime()
        val present = ArrayList<Long>()
        val ready = ArrayList<Long>()
        for (line in text.lineSequence().drop(1)) {
            val parts = line.trim().split(whitespaceRegex)
            if (parts.size < 3) continue
            val a = parts[1].toLongOrNull()
            if (a != null && a > 0L && a != Long.MAX_VALUE) present.add(a)
            val r = parts[2].toLongOrNull()
            if (r != null && r > 0L && r != Long.MAX_VALUE) ready.add(r)
        }
        if (present.isEmpty() && ready.isEmpty()) return Meas(-1f, "", oneLine(text))
        val fp = rate(present, nowNs)
        val fr = rate(ready, nowNs)
        val spanMs = if (present.size >= 2) (present.max() - present.min()) / 1_000_000L else 0L
        return Meas(maxOf(fp, fr), "rows=${present.size}/${ready.size} presented=${fp.toInt()} ready=${fr.toInt()} span=${spanMs}ms", "")
    }

    // ---- Whole-screen composition rate (works for apps that draw into a SurfaceView, e.g. many games) ----
    private val totalRegex = Regex("(?m)^\\s*totalFrames\\s*=\\s*(\\d+)")
    private val gSamples = ArrayDeque<LongArray>()
    private var globalFull = false

    /** Frames composed by SurfaceFlinger per second over the last ~1.5 s. -1 = not available yet. */
    private fun globalFps(): Float {
        var txt = if (!globalFull) dumpsys("SurfaceFlinger", "--timestats", "-dump", "-maxlayers", "0")
                  else dumpsys("SurfaceFlinger", "--timestats", "-dump")
        var m = totalRegex.find(txt)
        if (m == null && !globalFull) {
            globalFull = true
            txt = dumpsys("SurfaceFlinger", "--timestats", "-dump")
            m = totalRegex.find(txt)
        }
        val c = m?.groupValues?.get(1)?.toLongOrNull() ?: return -1f
        val now = SystemClock.elapsedRealtime()
        if (gSamples.isNotEmpty() && c < gSamples.last()[1]) gSamples.clear()
        gSamples.addLast(longArrayOf(now, c))
        while (gSamples.size > 2 && now - gSamples.first()[0] > 1500L) gSamples.removeFirst()
        val first = gSamples.first()
        val dt = now - first[0]
        return if (dt >= 400L) (c - first[1]) * 1000f / dt else -1f
    }

    /**
     * Background thread: finds the layer of the current app that is producing frames.
     * Fast (1.2 s) while nothing is found or the app is idle, slow (4 s) while a layer is busy.
     */
    private fun scanLoop() {
        var lastScanRt = 0L
        var scannedTask = -2
        while (true) {
            try {
                val pkg = currentPkg
                val task = currentTask
                val now = SystemClock.elapsedRealtime()
                val busy = bestLayer.isNotEmpty() && lastBestFps > 3f
                val due = scanNow || task != scannedTask || now - lastScanRt >= (if (busy) 4000L else 1200L)
                if (pkg.isNotEmpty() && pkg != "?" && task >= 0 && due) {
                    scanNow = false
                    scannedTask = task
                    val cands = candidateLayers(pkg)
                    var best = -1f
                    var bestName = ""
                    var sample = ""
                    for (n in cands) {
                        if (task != currentTask) break
                        val m = layerFps(n)
                        if (m.fps < 0f) { if (sample.isEmpty()) sample = m.sample; continue }
                        if (m.fps > best) { best = m.fps; bestName = n }
                    }
                    if (task == currentTask) {
                        bestLayer = bestName
                        lastScanRt = SystemClock.elapsedRealtime()
                        if (bestName.isEmpty()) {
                            val msg = if (cands.isEmpty()) "no layers match $pkg" else "no frame rows for ${cands.size} layer(s) of $pkg; first=${cands.first()}; sample=$sample"
                            if (msg != lastScanErr) { lastScanErr = msg; emit("E $msg") }
                        } else lastScanErr = ""
                    }
                }
            } catch (t: Throwable) {
                emit("E scan failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            SystemClock.sleep(250L)
        }
    }

    @Volatile private var lastBestFps = 0f
    private var bestFail = 0

    private fun unregisterCurrent() {
        val p = currentProxy ?: return
        try { unregisterM?.invoke(wm, p) } catch (_: Throwable) { }
        currentProxy = null
    }

    private fun register(taskId: Int, pkg: String, binder: IBinder) {
        unregisterCurrent()
        val cbIface = Class.forName("android.window.ITaskFpsCallback")
        val proxy = Proxy.newProxyInstance(cbIface.classLoader, arrayOf(cbIface)) { self, m, args ->
            when (m.name) {
                "asBinder" -> binder
                "onFpsReported" -> {
                    lastCbFps = (args?.get(0) as? Float) ?: 0f
                    lastCbRt = SystemClock.elapsedRealtime()
                    emit("F ${args?.get(0)} $currentTask")
                    null
                }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "CgFpsCallback"
                else -> null
            }
        }
        try {
            registerM!!.invoke(wm, taskId, proxy)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        currentProxy = proxy
        currentTask = taskId
        currentPkg = pkg
        bestLayer = ""
        lastBestFps = 0f
        bestFail = 0
        gSamples.clear()
        scanNow = true
        emit("T $taskId $pkg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val wmBinder = service("window") ?: run { emit("E window service not found"); exitProcess(2) }
            wm = asInterface("android.view.IWindowManager\$Stub", wmBinder)
            service("activity_task")?.let { atm = asInterface("android.app.IActivityTaskManager\$Stub", it) }

            val wmIface = Class.forName("android.view.IWindowManager")
            registerM = wmIface.methods.firstOrNull { it.name == "registerTaskFpsCallback" }
            unregisterM = wmIface.methods.firstOrNull { it.name == "unregisterTaskFpsCallback" }
            if (registerM == null) { emit("E registerTaskFpsCallback not available on this Android version"); exitProcess(3) }
        } catch (t: Throwable) {
            emit("E init failed: ${t.javaClass.simpleName}: ${t.message}")
            exitProcess(2)
        }

        // Exit as soon as the app closes our stdin (app stopped or killed) so no orphan root process is left.
        Thread {
            try { while (System.`in`.read() >= 0) { /* ignore */ } } catch (_: Throwable) { }
            unregisterCurrent()
            exitProcess(0)
        }.apply { isDaemon = true; start() }

        dumpsys("SurfaceFlinger", "--timestats", "-enable")
        emit("OK")
        Thread { scanLoop() }.apply { isDaemon = true; name = "cg-fps-scan"; start() }
        val binder = FpsBinder()
        var lastErr = ""
        while (true) {
            try {
                val top = topTask()
                if (top == null) {
                    if (lastErr != "notask") { lastErr = "notask"; emit("E cannot read the focused task") }
                } else if (top.first != currentTask) {
                    lastErr = ""
                    register(top.first, top.second, binder)
                }
            } catch (t: Throwable) {
                val msg = "register failed: ${t.javaClass.simpleName}: ${t.message}"
                if (msg != lastErr) { lastErr = msg; emit("E $msg") }
                currentTask = -1
            }
            // Layer FPS for ANY app: re-measure only the layer picked by the scan thread, twice a second.
            try {
                val task = currentTask
                val layer = bestLayer
                if (task >= 0 && currentPkg.isNotEmpty() && currentPkg != "?" && layer.isNotEmpty()) {
                    val m = layerFps(layer)
                    if (task == currentTask && m.fps >= 0f) {
                        lastBestFps = m.fps
                        emit("L ${m.fps} $task $layer | ${m.detail}")
                        bestFail = 0
                    } else if (m.fps < 0f && ++bestFail >= 3) {
                        bestLayer = ""        // layer is really gone: let the scan thread pick again right away
                        bestFail = 0
                        scanNow = true
                    }
                }
            } catch (t: Throwable) {
                emit("E latency measure failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            try {
                val task = currentTask
                if (task >= 0) {
                    val g = globalFps()
                    if (g >= 0f && task == currentTask) emit("G $g $task")
                }
            } catch (t: Throwable) {
                emit("E composition measure failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            SystemClock.sleep(450L)
        }
    }
}


/** App-side handle: starts [FpsDaemon] as root and keeps the latest FPS sample. */
class FpsDaemonClient(private val apkPath: String) {
    @Volatile var fps: Float = -1f; private set
    @Volatile var fpsAtRt: Long = 0L; private set
    @Volatile var latencyFps: Float = -1f; private set
    @Volatile var latencyAtRt: Long = 0L; private set
    @Volatile var latencyLayer: String = ""; private set
    @Volatile var taskInfo: String = ""; private set
    @Volatile var taskId: Int = -1; private set
    @Volatile var taskPkg: String = ""; private set
    @Volatile var taskSinceRt: Long = 0L; private set
    @Volatile var fpsTask: Int = -1; private set
    @Volatile var globalFps: Float = -1f; private set
    @Volatile var globalAtRt: Long = 0L; private set
    @Volatile var globalTask: Int = -1; private set
    @Volatile var latencyTask: Int = -1; private set
    @Volatile var started: Boolean = false; private set
    @Volatile var lastError: String = ""; private set
    private val tail = ArrayDeque<String>()
    private var proc: Process? = null

    val isAlive: Boolean get() = proc?.isAlive == true

    fun start(): Boolean {
        if (isAlive) return true
        return try {
            val q = "'" + apkPath.replace("'", "'\"'\"'") + "'"
            val cmd = "CLASSPATH=$q exec /system/bin/app_process /system/bin " +
                "--nice-name=cg_fps com.example.governor.FpsDaemon"
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        when {
                            line == "OK" -> started = true
                            line.startsWith("F ") -> {
                                val parts = line.split(' ')
                                parts.getOrNull(1)?.toFloatOrNull()?.let {
                                    fpsTask = parts.getOrNull(2)?.toIntOrNull() ?: -1
                                    fps = it
                                    fpsAtRt = SystemClock.elapsedRealtime()
                                }
                            }
                            line.startsWith("L ") -> {
                                val parts = line.split(' ', limit = 4)
                                parts.getOrNull(1)?.toFloatOrNull()?.let {
                                    latencyTask = parts.getOrNull(2)?.toIntOrNull() ?: -1
                                    latencyFps = it
                                    latencyAtRt = SystemClock.elapsedRealtime()
                                    latencyLayer = parts.getOrNull(3) ?: ""
                                }
                            }
                            line.startsWith("G ") -> {
                                val parts = line.split(' ')
                                parts.getOrNull(1)?.toFloatOrNull()?.let {
                                    globalTask = parts.getOrNull(2)?.toIntOrNull() ?: -1
                                    globalFps = it
                                    globalAtRt = SystemClock.elapsedRealtime()
                                }
                            }
                            line.startsWith("T ") -> {
                                val parts = line.substring(2).split(' ', limit = 2)
                                taskId = parts.getOrNull(0)?.toIntOrNull() ?: -1
                                taskPkg = parts.getOrNull(1) ?: ""
                                taskSinceRt = SystemClock.elapsedRealtime()
                                taskInfo = line.substring(2)
                            }
                            line.startsWith("E ") -> lastError = line.substring(2)
                            else -> synchronized(tail) {
                                tail.addLast(line)
                                while (tail.size > 6) tail.removeFirst()
                            }
                        }
                    }
                } catch (_: Throwable) { }
            }.apply { isDaemon = true; name = "cg-fps-reader"; start() }
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    fun rawTail(): String = synchronized(tail) { tail.joinToString(" | ") }

    fun stop() {
        val p = proc ?: return
        proc = null
        try { p.outputStream.close() } catch (_: Throwable) { }   // daemon exits on stdin EOF
        try { p.destroy() } catch (_: Throwable) { }
        started = false
        fps = -1f
    }
}
