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
import android.hardware.display.DisplayManager
import android.view.Display
import com.example.model.GovernorConfig
import com.example.model.LogLevel
import com.example.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

data class RefreshUiState(
    val supported: List<Int> = emptyList(),
    val currentHz: Int = 0,
    val method: String = "not detected yet",
    val shareMs: List<Pair<Int, Long>> = emptyList(),
    val switches: Int = 0,
    val touchSource: String = "-",
    val note: String = ""
)

/**
 * Adaptive refresh rate. Lowers the panel to the lowest supported rate when idle and
 * boosts to the highest on touch. Original system refresh-rate settings are saved before the first
 * change and always restored (toggle off, service stop, next start after a crash).
 */
class RefreshRateController(
    context: Context,
    private val rootShell: RootShell,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String, String) -> Unit,
    private val interaction: InteractionSignal,
    private val cfgProvider: () -> GovernorConfig,
    private val fpsProvider: () -> Float,
    private val gameProvider: () -> Boolean,
    private val screenOnProvider: () -> Boolean,
    private val onAutoDisable: () -> Unit
) {
    private val appContext = context.applicationContext
    private val origFile = File(appContext.filesDir, "refresh_original.json")

    private val _state = MutableStateFlow(RefreshUiState())
    val state: StateFlow<RefreshUiState> = _state
    @Volatile var uiActive = false

    private var loopJob: Job? = null
    private var workerJob: Job? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pendingHz = 0
    @Volatile private var pendingUrgent = false
    @Volatile private var lastWrittenHz = 0
    @Volatile private var lastWriteAt = 0L
    @Volatile private var modified = false
    @Volatile private var testing = false
    private var method = -1
    private var failStreak = 0
    private var rates: List<Int> = emptyList()
    private var ratesLoaded = false
    private var switches = 0
    private val shareMs = HashMap<Int, Long>()
    private var shareDay = -1
    private var lastLoopMs = 0L
    private var lastPublishMs = 0L
    private var methodNote = "not detected yet"
    private var verifyGen = 0
    @Volatile private var activeHzNow = 0

    fun start() {
        if (loopJob?.isActive == true) return
        interaction.onTouch = { onTouch() }
        workerJob = scope.launch { worker() }
        loopJob = scope.launch {
            restoreIfModified("startup")
            loop()
        }
    }

    /** Stop loops and restore the user's original settings (service stop). */
    fun shutdown() {
        loopJob?.cancel(); loopJob = null
        workerJob?.cancel(); workerJob = null
        interaction.onTouch = null
        scope.launch(NonCancellable) { restoreIfModified("service stop") }
    }

    // -------------------------------------------------------------- discovery

    private fun loadRates() {
        if (ratesLoaded) return
        ratesLoaded = true
        try {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val cur = d.mode
            rates = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .map { it.refreshRate.roundToInt() }
                .distinct().sorted()
        } catch (_: Throwable) {
            rates = emptyList()
        }
    }

    private fun currentHz(): Int = try {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        (dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f).roundToInt()
    } catch (_: Throwable) { 0 }

    private fun idleHz(cfg: GovernorConfig): Int =
        cfg.adaptiveIdleHz.takeIf { it in rates } ?: rates.first()

    private fun activeHz(cfg: GovernorConfig): Int =
        cfg.adaptiveActiveHz.takeIf { it in rates } ?: rates.last()

    // -------------------------------------------------------------- loops

    private fun onTouch() {
        val cfg = cfgProvider()
        if (!cfg.adaptiveRefreshEnabled || rates.size < 2 || testing) return
        request(activeHz(cfg), urgent = true)
    }

    private fun request(hz: Int, urgent: Boolean) {
        if (hz == pendingHz && !urgent) return
        if (hz == lastWrittenHz && hz == pendingHz) return
        pendingHz = hz
        pendingUrgent = urgent
        signal.trySend(Unit)
    }

    private suspend fun loop() {
        while (true) {
            val cfg = cfgProvider()
            loadRates()
            val usable = rates.size >= 2
            if (!cfg.adaptiveRefreshEnabled || !usable) {
                if (modified && !testing) restoreIfModified("feature off")
                if (uiActive) publish(true, if (!usable && ratesLoaded) "Only one refresh rate is supported - feature unavailable." else "")
                delay(if (uiActive) 1000 else 2000)
                continue
            }
            if (!screenOnProvider()) { delay(500); continue }
            val now = System.currentTimeMillis()
            accumulateShare(now)
            if (!testing) {
                val touchAge = now - interaction.lastTouchMs
                val fps = fpsProvider()
                val busy = touchAge < cfg.adaptiveIdleDelayMs || gameProvider() || fps >= FPS_ACTIVE_THRESHOLD
                request(if (busy) activeHz(cfg) else idleHz(cfg), urgent = false)
            }
            if (uiActive && now - lastPublishMs >= 1000L) publish(false, "")
            delay(200)
        }
    }

    private fun accumulateShare(now: Long) {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != shareDay) { shareDay = day; shareMs.clear(); switches = 0 }
        val dt = if (lastLoopMs == 0L) 0L else (now - lastLoopMs).coerceIn(0L, 1000L)
        lastLoopMs = now
        val hz = lastWrittenHz.takeIf { it > 0 } ?: currentHz()
        if (hz > 0 && dt > 0) shareMs[hz] = (shareMs[hz] ?: 0L) + dt
    }

    private fun publish(off: Boolean, note: String) {
        lastPublishMs = System.currentTimeMillis()
        val cur = currentHz()
        _state.value = RefreshUiState(
            supported = rates,
            currentHz = cur,
            method = methodNote,
            shareMs = shareMs.entries.sortedBy { it.key }.map { it.key to it.value },
            switches = switches,
            touchSource = interaction.source,
            note = note
        )
    }

    private suspend fun worker() {
        for (u in signal) {
            val cfg = cfgProvider()
            if (!cfg.adaptiveRefreshEnabled || testing) continue
            val urgent = pendingUrgent
            if (!urgent) {
                val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastWriteAt)
                if (wait > 0) delay(wait)
            }
            val hz = pendingHz
            if (hz <= 0 || hz == lastWrittenHz) continue
            if (!cfgProvider().adaptiveRefreshEnabled) continue
            writeRate(hz, verifyAsync = true)
        }
    }

    // -------------------------------------------------------------- applying

    private suspend fun ensureOriginalSaved() {
        if (modified) return
        if (origFile.exists()) { modified = true; return }
        val mn = readSetting("min_refresh_rate")
        val pk = readSetting("peak_refresh_rate")
        try {
            val tmp = File(origFile.parentFile, origFile.name + ".tmp")
            tmp.writeText(JSONObject().put("min", mn).put("peak", pk).toString())
            if (!tmp.renameTo(origFile)) { origFile.delete(); tmp.renameTo(origFile) }
        } catch (_: Throwable) {}
        modified = true
    }

    private suspend fun readSetting(key: String): String {
        val r = rootShell.exec("cmd settings get system $key 2>/dev/null || settings get system $key")
        val v = r.stdout.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return if (v.isEmpty()) "null" else v
    }

    private fun cmdFor(m: Int, hz: Int): String {
        val v = "$hz.0"
        return when (m) {
            0 -> "(cmd settings put system min_refresh_rate $v && cmd settings put system peak_refresh_rate $v) || " +
                "(settings put system min_refresh_rate $v && settings put system peak_refresh_rate $v)"
            else -> "(cmd settings put system peak_refresh_rate $v) || (settings put system peak_refresh_rate $v)"
        }
    }

    private suspend fun writeRate(hz: Int, verifyAsync: Boolean): Boolean {
        ensureOriginalSaved()
        val order = if (method >= 0) intArrayOf(method) else intArrayOf(0, 1)
        for (m in order) {
            val r = rootShell.exec(cmdFor(m, hz))
            lastWriteAt = System.currentTimeMillis()
            if (r.exitCode != 0) continue
            val prev = lastWrittenHz
            lastWrittenHz = hz
            if (prev != hz) switches++
            if (verifyAsync && method >= 0) {
                val gen = ++verifyGen
                scope.launch { delay(VERIFY_DELAY_MS); verifyLater(hz, gen, m) }
                return true
            }
            delay(VERIFY_DELAY_MS)
            val ok = abs(currentHz() - hz) <= 1
            if (ok) {
                if (method != m) {
                    method = m
                    methodNote = if (m == 0) "settings system min+peak_refresh_rate" else "settings system peak_refresh_rate"
                    log(LogLevel.INFO, "RefreshRate", "Working method detected: $methodNote")
                }
                failStreak = 0
                return true
            }
        }
        registerFailure("could not switch to ${hz}Hz (display reported ${currentHz()}Hz)")
        return false
    }

    private fun verifyLater(hz: Int, gen: Int, m: Int) {
        if (gen != verifyGen || hz != lastWrittenHz) return
        if (abs(currentHz() - hz) <= 1) { failStreak = 0; return }
        registerFailure("display stayed at ${currentHz()}Hz after switching to ${hz}Hz (method $m)")
    }

    private fun registerFailure(why: String) {
        failStreak++
        if (failStreak >= 3) {
            log(LogLevel.WARN, "RefreshRate", "Auto-disabled adaptive refresh rate: $why")
            failStreak = 0
            method = -1
            methodNote = "failed - auto-disabled"
            onAutoDisable()
        } else if (failStreak == 1) {
            log(LogLevel.WARN, "RefreshRate", "Refresh switch not verified: $why")
        }
    }

    suspend fun restoreIfModified(reason: String) {
        if (!modified && !origFile.exists()) return
        try {
            val o = if (origFile.exists()) JSONObject(origFile.readText()) else null
            val mn = o?.optString("min", "null") ?: "null"
            val pk = o?.optString("peak", "null") ?: "null"
            suspend fun put(key: String, v: String) {
                val cmd = if (v == "null") "cmd settings delete system $key || settings delete system $key"
                else "cmd settings put system $key $v || settings put system $key $v"
                rootShell.exec(cmd)
            }
            put("min_refresh_rate", mn)
            put("peak_refresh_rate", pk)
            origFile.delete()
            modified = false
            lastWrittenHz = 0
            pendingHz = 0
            log(LogLevel.INFO, "RefreshRate", "Original refresh-rate settings restored ($reason)")
        } catch (e: Throwable) {
            log(LogLevel.WARN, "RefreshRate", "Restore failed: ${e.message}")
        }
    }

    /** Cycle idle -> active once so the user can check that switching works. */
    fun testSwitch() {
        scope.launch {
            loadRates()
            if (rates.size < 2) return@launch
            val cfg = cfgProvider()
            testing = true
            try {
                val lo = idleHz(cfg)
                val hi = activeHz(cfg)
                writeRate(lo, verifyAsync = false)
                delay(1500)
                writeRate(hi, verifyAsync = false)
                delay(1500)
                log(LogLevel.INFO, "RefreshRate", "Test switch done: ${lo}Hz -> ${hi}Hz (display now ${currentHz()}Hz)")
            } finally {
                testing = false
                if (!cfgProvider().adaptiveRefreshEnabled) withContext(NonCancellable) { restoreIfModified("test finished") }
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 300L
        private const val VERIFY_DELAY_MS = 350L
        private const val FPS_ACTIVE_THRESHOLD = 12f
    }
}
