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
    private var layerCache = emptyList<String>()
    private var layerCacheRt = 0L
    private var layerCachePkg = ""
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

    private fun runCmd(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Throwable) { "" }

    /** Layers of the focused app (SurfaceView layers first), cached for a few seconds. */
    private fun candidateLayers(pkg: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (pkg == layerCachePkg && now - layerCacheRt < 5000L) return layerCache
        val names = runCmd("dumpsys", "SurfaceFlinger", "--list").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length < 250 && it.contains(pkg) }
            .toList()
        layerCache = names.sortedBy { if (it.startsWith("SurfaceView")) 0 else 1 }.take(3)
        layerCachePkg = pkg
        layerCacheRt = now
        return layerCache
    }

    /** FPS of one layer from SurfaceFlinger's present timestamps (frames presented in the last second). -1 = no data. */
    private fun layerFps(name: String): Float {
        val text = runCmd("dumpsys", "SurfaceFlinger", "--latency", name)
        val nowNs = System.nanoTime()
        var count = 0
        var any = false
        for (line in text.lineSequence().drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val present = parts[1].toLongOrNull() ?: continue
            if (present <= 0L || present == Long.MAX_VALUE) continue
            any = true
            val age = nowNs - present
            if (age in 0L..1_000_000_000L) count++
        }
        return if (any) count.toFloat() else -1f
    }

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

        emit("OK")
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
            // Backup for apps (games) where the callback is silent or reports 0: SurfaceFlinger layer timestamps.
            try {
                val silent = SystemClock.elapsedRealtime() - lastCbRt > 2500L || lastCbFps <= 0f
                if (currentTask >= 0 && silent && currentPkg.isNotEmpty() && currentPkg != "?") {
                    var best = -1f
                    var bestName = ""
                    for (n in candidateLayers(currentPkg)) {
                        val f = layerFps(n)
                        if (f > best) { best = f; bestName = n }
                    }
                    if (best >= 0f) emit("L $best $bestName")
                    else if (lastErr != "nolayer") { lastErr = "nolayer"; emit("E no layer data for $currentPkg") }
                }
            } catch (t: Throwable) {
                emit("E latency fallback failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            SystemClock.sleep(1000L)
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
                                    fps = it
                                    fpsAtRt = SystemClock.elapsedRealtime()
                                }
                            }
                            line.startsWith("L ") -> {
                                val parts = line.split(' ', limit = 3)
                                parts.getOrNull(1)?.toFloatOrNull()?.let {
                                    latencyFps = it
                                    latencyAtRt = SystemClock.elapsedRealtime()
                                    latencyLayer = parts.getOrNull(2) ?: ""
                                }
                            }
                            line.startsWith("T ") -> taskInfo = line.substring(2)
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
