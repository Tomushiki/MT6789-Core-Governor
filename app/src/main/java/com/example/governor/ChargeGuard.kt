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
import android.media.AudioManager
import android.provider.Settings
import com.example.model.LogLevel
import com.example.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** v3.6.6 Charge sleep guard settings (own storage, so they do not touch the main config). */
data class ChargeGuardSettings(
    val enabled: Boolean = false,
    /** Apps that are never stopped. */
    val whitelist: Set<String> = emptySet(),
    /** Apps / system processes that are always stopped on screen-off while charging. */
    val killList: Set<String> = emptySet(),
    /** Also stop every other user app that is not on the whitelist. */
    val killOthers: Boolean = false,
    /** Detect the power button while the screen is off and boost clocks to maximum at once. */
    val fastWake: Boolean = true
)

object ChargeGuardStore {
    private const val PREFS = "charge_guard"
    private val PKG = Regex("^[A-Za-z0-9._]+$")

    fun load(ctx: Context): ChargeGuardSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ChargeGuardSettings(
            enabled = p.getBoolean("enabled", false),
            whitelist = (p.getStringSet("whitelist", emptySet()) ?: emptySet()).toSet(),
            killList = (p.getStringSet("kill", emptySet()) ?: emptySet()).toSet(),
            killOthers = p.getBoolean("others", false),
            fastWake = p.getBoolean("fast_wake", true)
        )
    }

    fun save(ctx: Context, s: ChargeGuardSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putStringSet("whitelist", s.whitelist.toSet())
            .putStringSet("kill", s.killList.toSet())
            .putBoolean("others", s.killOthers)
            .putBoolean("fast_wake", s.fastWake)
            .apply()
    }

    fun isValidPackage(name: String): Boolean = PKG.matches(name)
}

/**
 * v3.6.6 Charge sleep guard.
 *  - Screen off + charging: stops background apps (force-stop through root) that wake the CPU and heat the phone.
 *    The whitelist is never stopped; the always-stop list is stopped on every pass.
 *  - Fast wake: while the screen is off, watches the power button (root getevent) and calls [onWakeHint]
 *    before the screen-on broadcast arrives, so clocks are already boosted when the screen lights.
 */
class ChargeGuard(
    private val ctx: Context,
    private val rootShell: RootShell,
    private val scope: CoroutineScope,
    private val isCharging: () -> Boolean,
    private val onWakeHint: () -> Unit,
    private val log: (LogLevel, String, String) -> Unit
) {
    private var killJob: Job? = null
    private var wakeJob: Job? = null
    @Volatile private var wakeProc: Process? = null

    // v3.7.0: called from the screen receiver and from the engine's stop; one at a time so no job or `getevent` leaks.
    @Synchronized
    fun onScreenOff() {
        stop()
        val s = ChargeGuardStore.load(ctx)
        if (s.enabled) startKillLoop()
        if (s.fastWake) startWakeListener()
    }

    @Synchronized
    fun onScreenOn() = stop()

    @Synchronized
    fun stop() {
        killJob?.cancel(); killJob = null
        stopWakeListener()
    }

    private fun startKillLoop() {
        killJob = scope.launch(Dispatchers.IO) {
            delay(FIRST_PASS_DELAY_MS)
            while (isActive) {
                val s = ChargeGuardStore.load(ctx)
                if (!s.enabled) break
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (isCharging() && am?.isMusicActive != true) stopPass(s)
                delay(PASS_INTERVAL_MS)
            }
        }
    }

    private suspend fun stopPass(s: ChargeGuardSettings) {
        val targets = LinkedHashSet<String>()
        for (p in s.killList) if (ChargeGuardStore.isValidPackage(p)) targets.add(p)
        if (s.killOthers) {
            val r = rootShell.exec("pm list packages -3")
            if (r.isSuccess) {
                for (line in r.stdout.lineSequence()) {
                    val pkg = line.trim().removePrefix("package:")
                    if (pkg.isNotEmpty() && ChargeGuardStore.isValidPackage(pkg)) targets.add(pkg)
                }
            }
        }
        val protectedPkgs = protectedPackages()
        targets.removeAll(s.whitelist)
        targets.removeAll(protectedPkgs)
        if (targets.isEmpty()) return
        var stopped = 0
        for (chunk in targets.chunked(40)) {
            val cmd = chunk.joinToString("; ") { "am force-stop $it" }
            if (rootShell.exec(cmd).isSuccess) stopped += chunk.size
        }
        log(LogLevel.INFO, "ChargeGuard", "Screen off while charging: stopped $stopped background app(s).")
    }

    /** Things that must never be stopped: this app, the system UI, phone, launcher and keyboard. */
    private fun protectedPackages(): Set<String> {
        val out = hashSetOf(
            ctx.packageName, "android", "com.android.systemui", "com.android.phone",
            "com.android.server.telecom", "com.android.providers.telephony"
        )
        try {
            val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            home?.activityInfo?.packageName?.let { out.add(it) }
            val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ime?.substringBefore('/')?.let { if (it.isNotEmpty()) out.add(it) }
        } catch (_: Exception) {
        }
        return out
    }

    private fun startWakeListener() {
        wakeJob = scope.launch(Dispatchers.IO) {
            var proc: Process? = null
            try {
                proc = ProcessBuilder("su", "-c", "exec getevent -l").redirectErrorStream(true).start()
                // The screen may already have come on while the process was starting: do not leave it running.
                if (!isActive) return@launch
                wakeProc = proc
                // stop() may have run between the check above and this assignment and seen no process to
                // destroy; re-check so the blocking read below can never outlive the screen-off period.
                if (!isActive) return@launch
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        if ((line.contains("KEY_POWER") || line.contains("KEY_WAKEUP")) && line.contains("DOWN")) {
                            onWakeHint()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                // Only this job's own process: a newer listener's process must stay alive.
                try { proc?.destroy() } catch (_: Exception) {}
                if (wakeProc === proc) wakeProc = null
            }
        }
    }

    private fun stopWakeListener() {
        wakeJob?.cancel(); wakeJob = null
        try { wakeProc?.destroy() } catch (_: Exception) {}
        wakeProc = null
    }

    private companion object {
        const val FIRST_PASS_DELAY_MS = 15_000L
        const val PASS_INTERVAL_MS = 60_000L
    }
}
