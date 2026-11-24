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
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.example.model.LogLevel
import com.example.root.RootShell
import com.example.root.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ProtectionStatus(
    val enabled: Boolean = true,
    val rootAvailable: Boolean = false,
    val needsRoot: Boolean = false,
    val isVerifying: Boolean = false,
    val oomApplied: Boolean = false,
    val dozeWhitelisted: Boolean = false,
    val standbyActive: Boolean = false,
    val phantomKillerSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val phantomKillerOff: Boolean = false,
    val watchdogRunning: Boolean = false,
    val lastVerifiedAtMs: Long = 0L,
    val detailError: String? = null
)

/**
 * Pure shell-command builders and output parsers for root process protection.
 * Kept pure so unit tests can verify every script and parser without Android or `su`.
 */
object ProtectionCommands {
    const val TARGET_OOM_ADJ = -900
    const val FPS_DAEMON_ARG = "com.example.governor.FpsDaemon"
    const val SERVICE_COMPONENT = "com.example/.service.GovernorService"
    const val ACTION_START_GOVERNOR = "com.example.action.START_GOVERNOR"
    const val EXTRA_FROM_BOOT = "com.example.extra.FROM_BOOT"

    const val STOP_FLAG_NAME = "protect.run"
    const val PID_FILE_NAME = "protect.pid"
    const val WATCHDOG_SCRIPT_NAME = "protect_watchdog.sh"
    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_SEC = 120L

    private const val MARK_DOZE = "__CG_DOZE__"
    private const val MARK_BUCKET = "__CG_BUCKET__"
    private const val MARK_PMAX = "__CG_PMAX__"
    private const val MARK_PMON = "__CG_PMON__"
    private const val MARK_OOM = "__CG_OOM__"

    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    /**
     * `device_config get` and `settings get` return literal `"null"` or `"None"` when unset.
     * Normalize those to Kotlin `null` so restore uses `delete` instead of writing `"null"`.
     */
    fun parseOriginalSetting(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty() || t.equals("null", ignoreCase = true) || t.equals("none", ignoreCase = true)) {
            return null
        }
        return t
    }

    /** True if the current `/proc/<pid>/oom_score_adj` value is missing or differs from [target]. */
    fun needsOomReapply(rawReadBack: String?, target: Int = TARGET_OOM_ADJ): Boolean {
        val current = rawReadBack?.trim()?.toIntOrNull() ?: return true
        return current != target
    }

    /**
     * Batched Doze whitelist + AppOps + Standby bucket + `am set-inactive` command,
     * followed by verification read-backs in the same `su` round-trip.
     */
    fun buildWhitelistAndStandbyCommand(packageName: String): String {
        val qPkg = shellQuote(packageName)
        return buildString {
            append("(dumpsys deviceidle whitelist +$qPkg || cmd deviceidle whitelist +$qPkg) >/dev/null 2>&1; ")
            append("cmd deviceidle except-idle-whitelist +$qPkg >/dev/null 2>&1; ")
            append("cmd appops set $qPkg RUN_IN_BACKGROUND allow >/dev/null 2>&1; ")
            append("cmd appops set $qPkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; ")
            append("am set-standby-bucket $qPkg active >/dev/null 2>&1; ")
            append("am set-inactive $qPkg false >/dev/null 2>&1; ")
            append("echo '$MARK_DOZE'; ")
            append("(dumpsys deviceidle whitelist 2>/dev/null || cmd deviceidle whitelist 2>/dev/null) | grep -F $qPkg | head -n 1; ")
            append("echo '$MARK_BUCKET'; ")
            append("am get-standby-bucket $qPkg 2>/dev/null; true")
        }
    }

    /** Returns Pair(dozeWhitelisted, standbyActive). */
    fun parseWhitelistAndStandby(stdout: String, packageName: String): Pair<Boolean, Boolean> {
        val dozeLines = extractSection(stdout, MARK_DOZE, MARK_BUCKET)
        val bucketLines = extractSection(stdout, MARK_BUCKET, null)
        val dozeOk = dozeLines.any { it.contains(packageName) }
        val bucketText = bucketLines.joinToString(" ").trim().lowercase()
        val standbyOk = bucketText.contains("active") ||
            bucketText.split(Regex("\\s+")).any { it == "5" || it == "10" }
        return dozeOk to standbyOk
    }

    /**
     * Reads `/proc/<pid>/oom_score_adj` first, writes `-900` ONLY when the value differs,
     * and reads back every target PID in a single round-trip. Includes [appPid], `pidof pkg`,
     * and alive `FpsDaemon` found safely via `pgrep -f com.example.governor.FpsDaemon`.
     */
    fun buildApplyOomCommand(
        packageName: String,
        appPid: Int,
        target: Int = TARGET_OOM_ADJ
    ): String {
        val qPkg = shellQuote(packageName)
        val qFps = shellQuote(FPS_DAEMON_ARG)
        return buildString {
            append("echo '$MARK_OOM'; ")
            append("PIDS=\"${if (appPid > 0) appPid.toString() else ""}\"; ")
            append("for p in \$(pidof $qPkg 2>/dev/null) \$(pgrep -f $qFps 2>/dev/null); do ")
            append("case \" \$PIDS \" in *\" \$p \"*) ;; *) PIDS=\"\$PIDS \$p\" ;; esac; ")
            append("done; ")
            append("for p in \$PIDS; do ")
            append("f=\"/proc/\$p/oom_score_adj\"; ")
            append("[ -f \"\$f\" ] || continue; ")
            append("cur=\$(cat \"\$f\" 2>/dev/null | tr -d '\\r\\n '); ")
            append("if [ \"\$cur\" != \"$target\" ]; then printf '%s' '$target' > \"\$f\" 2>/dev/null; fi; ")
            append("after=\$(cat \"\$f\" 2>/dev/null | tr -d '\\r\\n '); ")
            append("echo \"\$p:\$after\"; ")
            append("done; true")
        }
    }

    /** Returns true if at least one PID was checked and all checked PIDs read back [target]. */
    fun parseOomVerifyOutput(stdout: String, target: Int = TARGET_OOM_ADJ): Boolean {
        val lines = extractSection(stdout, MARK_OOM, null)
            .map { it.trim() }
            .filter { it.contains(':') }
        if (lines.isEmpty()) return false
        return lines.all { line ->
            val v = line.substringAfter(':').trim().toIntOrNull()
            v == target
        }
    }

    fun buildReadPhantomCommand(): String =
        "echo '$MARK_PMAX'; device_config get activity_manager max_phantom_processes 2>/dev/null; " +
            "echo '$MARK_PMON'; settings get global settings_enable_monitor_phantom_procs 2>/dev/null; true"

    fun parsePhantomValues(stdout: String): Pair<String?, String?> {
        val maxRaw = extractSection(stdout, MARK_PMAX, MARK_PMON).firstOrNull()
        val monRaw = extractSection(stdout, MARK_PMON, null).firstOrNull()
        return parseOriginalSetting(maxRaw) to parseOriginalSetting(monRaw)
    }

    fun buildDisablePhantomCommand(): String =
        "device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null; " +
            "settings put global settings_enable_monitor_phantom_procs false 2>/dev/null; " +
            buildReadPhantomCommand()

    fun isPhantomDisabled(stdout: String): Boolean {
        val (maxVal, monVal) = parsePhantomValues(stdout)
        val maxOk = maxVal?.toLongOrNull()?.let { it >= 2147483647L } == true
        val monOk = monVal.equals("false", ignoreCase = true) || monVal == "0"
        return maxOk && monOk
    }

    fun buildRestorePhantomCommand(origMax: String?, origMonitor: String?): String {
        val maxCmd = if (origMax == null) {
            "device_config delete activity_manager max_phantom_processes 2>/dev/null"
        } else {
            "device_config put activity_manager max_phantom_processes ${shellQuote(origMax)} 2>/dev/null"
        }
        val monCmd = if (origMonitor == null) {
            "settings delete global settings_enable_monitor_phantom_procs 2>/dev/null"
        } else {
            "settings put global settings_enable_monitor_phantom_procs ${shellQuote(origMonitor)} 2>/dev/null"
        }
        return "$maxCmd; $monCmd; true"
    }

    /**
     * Generates the POSIX `sh` watchdog script stored in `filesDir/protect_watchdog.sh`.
     * Single-instance guarded via pidfile + `kill -0`, checks `protect.run`, restarts
     * `GovernorService` with `FROM_BOOT=true` (so BootGuard applies), and stops/backs off
     * after [MAX_RESTARTS] restarts inside [RESTART_WINDOW_SEC].
     */
    fun buildWatchdogScript(
        packageName: String,
        flagPath: String,
        pidFile: String
    ): String {
        val qPkg = shellQuote(packageName)
        val qFlag = shellQuote(flagPath)
        val qPid = shellQuote(pidFile)
        val qComp = shellQuote(SERVICE_COMPONENT)
        val qAction = shellQuote(ACTION_START_GOVERNOR)
        val qExtra = shellQuote(EXTRA_FROM_BOOT)
        return """
            |#!/system/bin/sh
            |FLAG=$qFlag
            |PIDFILE=$qPid
            |PKG=$qPkg
            |if [ -f "${'$'}PIDFILE" ]; then
            |    OLD_PID=${'$'}(cat "${'$'}PIDFILE" 2>/dev/null | tr -d '\r\n ')
            |    if [ -n "${'$'}OLD_PID" ] && [ "${'$'}OLD_PID" != "${'$'}${'$'}" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then
            |        exit 0
            |    fi
            |fi
            |echo "${'$'}${'$'}" > "${'$'}PIDFILE"
            |RESTARTS=""
            |while true; do
            |    if [ ! -f "${'$'}FLAG" ]; then
            |        rm -f "${'$'}PIDFILE"
            |        exit 0
            |    fi
            |    if ! pidof "${'$'}PKG" >/dev/null 2>&1; then
            |        NOW=${'$'}(date +%s 2>/dev/null || echo 0)
            |        NEW_RESTARTS=""
            |        COUNT=0
            |        for ts in ${'$'}RESTARTS; do
            |            DIFF=${'$'}((NOW - ts))
            |            if [ "${'$'}DIFF" -ge 0 ] && [ "${'$'}DIFF" -lt $RESTART_WINDOW_SEC ]; then
            |                NEW_RESTARTS="${'$'}NEW_RESTARTS ${'$'}ts"
            |                COUNT=${'$'}((COUNT + 1))
            |            fi
            |        done
            |        if [ "${'$'}COUNT" -ge $MAX_RESTARTS ]; then
            |            sleep 60
            |            RESTARTS=""
            |            continue
            |        fi
            |        RESTARTS="${'$'}NEW_RESTARTS ${'$'}NOW"
            |        am start-foreground-service -n $qComp -a $qAction --ez $qExtra true >/dev/null 2>&1 || am startservice -n $qComp -a $qAction --ez $qExtra true >/dev/null 2>&1
            |    fi
            |    sleep 10
            |done
            |""".trimMargin()
    }

    fun buildStartWatchdogCommand(scriptPath: String, pidFile: String): String {
        val qScript = shellQuote(scriptPath)
        val qPid = shellQuote(pidFile)
        return "if [ -f $qPid ] && kill -0 \$(cat $qPid 2>/dev/null) 2>/dev/null; then " +
            "echo RUNNING; " +
            "else " +
            "chmod 0700 $qScript 2>/dev/null; " +
            "(setsid nohup sh $qScript </dev/null >/dev/null 2>&1 &); " +
            "sleep 0.2; " +
            "if [ -f $qPid ] && kill -0 \$(cat $qPid 2>/dev/null) 2>/dev/null; then echo RUNNING; " +
            "else (nohup sh $qScript </dev/null >/dev/null 2>&1 &); sleep 0.2; " +
            "if [ -f $qPid ] && kill -0 \$(cat $qPid 2>/dev/null) 2>/dev/null; then echo RUNNING; else echo STOPPED; fi; fi; " +
            "fi; true"
    }

    fun buildCheckWatchdogCommand(pidFile: String): String {
        val qPid = shellQuote(pidFile)
        return "if [ -f $qPid ] && kill -0 \$(cat $qPid 2>/dev/null) 2>/dev/null; then echo RUNNING; else echo STOPPED; fi; true"
    }

    fun buildStopWatchdogCommand(flagPath: String, pidFile: String): String {
        val qFlag = shellQuote(flagPath)
        val qPid = shellQuote(pidFile)
        return "rm -f $qFlag; " +
            "if [ -f $qPid ]; then p=\$(cat $qPid 2>/dev/null); [ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null; rm -f $qPid; fi; true"
    }

    private fun extractSection(stdout: String, startMarker: String, endMarker: String?): List<String> {
        val result = mutableListOf<String>()
        var inside = false
        for (line in stdout.lines()) {
            val trimmed = line.trim()
            if (trimmed == startMarker) {
                inside = true
                continue
            }
            if (endMarker != null && trimmed == endMarker) {
                break
            }
            if (inside) {
                result.add(line)
            }
        }
        return result
    }
}

/**
 * Coordinates root keep-alive protections (`oom_score_adj`, Doze/standby, Android 12+ phantom
 * process killer, and the detached root watchdog) with injectable [RootShell] / executor for tests.
 */
class ProcessProtectionManager(
    context: Context,
    private val rootShell: RootShell,
    private val logger: (LogLevel, String, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val flagFile = File(appContext.filesDir, ProtectionCommands.STOP_FLAG_NAME)
    private val pidFile = File(appContext.filesDir, ProtectionCommands.PID_FILE_NAME)
    private val scriptFile = File(appContext.filesDir, ProtectionCommands.WATCHDOG_SCRIPT_NAME)

    private val _status = MutableStateFlow(ProtectionStatus())
    val status: StateFlow<ProtectionStatus> = _status.asStateFlow()

    @Volatile
    private var watchActive: Boolean = false

    fun touchStopFlag() {
        try {
            if (!flagFile.exists()) flagFile.writeText("1")
        } catch (_: Throwable) {
        }
    }

    fun clearStopFlagSynchronously() {
        watchActive = false
        try {
            if (flagFile.exists()) flagFile.delete()
        } catch (_: Throwable) {
        }
    }

    suspend fun applyAndVerify(
        enabled: Boolean,
        watchServiceAlive: Boolean
    ): ProtectionStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            watchActive = watchServiceAlive && enabled
            if (!enabled) {
                clearStopFlagSynchronously()
                val restored = disableProtectionsLocked()
                _status.value = restored
                return@withLock restored
            }

            _status.value = _status.value.copy(enabled = true, isVerifying = true, detailError = null)
            val hasRoot = rootShell.ensureSession()
            if (!hasRoot) {
                val doze = BatteryOptimization.isIgnoring(appContext)
                val failed = ProtectionStatus(
                    enabled = true,
                    rootAvailable = false,
                    needsRoot = true,
                    isVerifying = false,
                    dozeWhitelisted = doze,
                    lastVerifiedAtMs = System.currentTimeMillis()
                )
                _status.value = failed
                logger(LogLevel.WARN, TAG, "Root unavailable for process protection.")
                return@withLock failed
            }

            val pkg = appContext.packageName
            val wlRes = rootShell.exec(ProtectionCommands.buildWhitelistAndStandbyCommand(pkg))
            val (dozeCmdOk, standbyOk) = ProtectionCommands.parseWhitelistAndStandby(wlRes.stdout, pkg)
            val dozeOk = dozeCmdOk || BatteryOptimization.isIgnoring(appContext)

            val oomRes = rootShell.exec(ProtectionCommands.buildApplyOomCommand(pkg, Process.myPid()))
            val oomOk = ProtectionCommands.parseOomVerifyOutput(oomRes.stdout)

            val phantomSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val phantomOff = if (phantomSupported) {
                saveOriginalPhantomIfNeededLocked()
                val pRes = rootShell.exec(ProtectionCommands.buildDisablePhantomCommand())
                ProtectionCommands.isPhantomDisabled(pRes.stdout)
            } else {
                false
            }

            val watchdogOk = if (watchServiceAlive) {
                ensureWatchdogRunningLocked(pkg)
            } else {
                checkWatchdogAliveLocked()
            }

            val updated = ProtectionStatus(
                enabled = true,
                rootAvailable = true,
                needsRoot = false,
                isVerifying = false,
                oomApplied = oomOk,
                dozeWhitelisted = dozeOk,
                standbyActive = standbyOk,
                phantomKillerSupported = phantomSupported,
                phantomKillerOff = phantomOff,
                watchdogRunning = watchdogOk,
                lastVerifiedAtMs = System.currentTimeMillis(),
                detailError = null
            )
            _status.value = updated
            logger(
                LogLevel.INFO,
                TAG,
                "Protection verified: oom=$oomOk, doze=$dozeOk, standby=$standbyOk, phantom=$phantomOff, watchdog=$watchdogOk"
            )
            updated
        }
    }

    /**
     * Periodic ~30s maintenance called by `GovernorService`: reads before writing `oom_score_adj`,
     * re-applies phantom killer disable if Android reset it, and verifies watchdog liveness.
     */
    suspend fun periodicMaintain(enabled: Boolean): ProtectionStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled) {
                clearStopFlagSynchronously()
                val off = disableProtectionsLocked()
                _status.value = off
                return@withLock off
            }
            if (!rootShell.ensureSession()) {
                val noRoot = _status.value.copy(
                    enabled = true,
                    rootAvailable = false,
                    needsRoot = true,
                    isVerifying = false
                )
                _status.value = noRoot
                return@withLock noRoot
            }

            val pkg = appContext.packageName
            val oomRes = rootShell.exec(ProtectionCommands.buildApplyOomCommand(pkg, Process.myPid()))
            val oomOk = ProtectionCommands.parseOomVerifyOutput(oomRes.stdout)

            val phantomSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val phantomOff = if (phantomSupported) {
                val readRes = rootShell.exec(ProtectionCommands.buildReadPhantomCommand())
                if (ProtectionCommands.isPhantomDisabled(readRes.stdout)) {
                    true
                } else {
                    saveOriginalPhantomIfNeededLocked()
                    val reRes = rootShell.exec(ProtectionCommands.buildDisablePhantomCommand())
                    ProtectionCommands.isPhantomDisabled(reRes.stdout)
                }
            } else {
                false
            }

            val watchdogOk = if (watchActive) {
                if (checkWatchdogAliveLocked()) true else ensureWatchdogRunningLocked(pkg)
            } else {
                checkWatchdogAliveLocked()
            }

            val current = _status.value
            val next = current.copy(
                enabled = true,
                rootAvailable = true,
                needsRoot = false,
                isVerifying = false,
                oomApplied = oomOk,
                dozeWhitelisted = current.dozeWhitelisted || BatteryOptimization.isIgnoring(appContext),
                phantomKillerSupported = phantomSupported,
                phantomKillerOff = phantomOff,
                watchdogRunning = watchdogOk,
                lastVerifiedAtMs = System.currentTimeMillis()
            )
            _status.value = next
            next
        }
    }

    suspend fun stopWatchdog() = withContext(Dispatchers.IO) {
        clearStopFlagSynchronously()
        mutex.withLock {
            if (rootShell.isRootAvailable || rootShell.ensureSession()) {
                rootShell.exec(
                    ProtectionCommands.buildStopWatchdogCommand(flagFile.absolutePath, pidFile.absolutePath)
                )
            }
            _status.value = _status.value.copy(watchdogRunning = false)
        }
    }

    private suspend fun disableProtectionsLocked(): ProtectionStatus {
        val hasRoot = rootShell.isRootAvailable || rootShell.ensureSession()
        if (hasRoot) {
            rootShell.exec(
                ProtectionCommands.buildStopWatchdogCommand(flagFile.absolutePath, pidFile.absolutePath)
            )
            restoreOriginalPhantomIfSavedLocked()
        }
        return ProtectionStatus(
            enabled = false,
            rootAvailable = hasRoot,
            needsRoot = false,
            isVerifying = false,
            oomApplied = false,
            dozeWhitelisted = BatteryOptimization.isIgnoring(appContext),
            standbyActive = false,
            phantomKillerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            phantomKillerOff = false,
            watchdogRunning = false,
            lastVerifiedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun saveOriginalPhantomIfNeededLocked() {
        if (prefs.getBoolean(KEY_PHANTOM_SAVED, false)) return
        val res: ShellResult = rootShell.exec(ProtectionCommands.buildReadPhantomCommand())
        if (!res.isSuccess && res.stdout.isBlank()) return
        val (origMax, origMon) = ProtectionCommands.parsePhantomValues(res.stdout)
        prefs.edit()
            .putBoolean(KEY_PHANTOM_SAVED, true)
            .putString(KEY_PHANTOM_ORIG_MAX, origMax)
            .putString(KEY_PHANTOM_ORIG_MON, origMon)
            .apply()
    }

    private suspend fun restoreOriginalPhantomIfSavedLocked() {
        if (!prefs.getBoolean(KEY_PHANTOM_SAVED, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val origMax = prefs.getString(KEY_PHANTOM_ORIG_MAX, null)
            val origMon = prefs.getString(KEY_PHANTOM_ORIG_MON, null)
            rootShell.exec(ProtectionCommands.buildRestorePhantomCommand(origMax, origMon))
            logger(LogLevel.INFO, TAG, "Restored original phantom process killer settings.")
        }
        prefs.edit()
            .remove(KEY_PHANTOM_SAVED)
            .remove(KEY_PHANTOM_ORIG_MAX)
            .remove(KEY_PHANTOM_ORIG_MON)
            .apply()
    }

    private suspend fun ensureWatchdogRunningLocked(packageName: String): Boolean {
        touchStopFlag()
        try {
            val content = ProtectionCommands.buildWatchdogScript(
                packageName = packageName,
                flagPath = flagFile.absolutePath,
                pidFile = pidFile.absolutePath
            )
            scriptFile.writeText(content)
        } catch (t: Throwable) {
            logger(LogLevel.WARN, TAG, "Could not write watchdog script: ${t.message}")
            return false
        }
        val res = rootShell.exec(
            ProtectionCommands.buildStartWatchdogCommand(scriptFile.absolutePath, pidFile.absolutePath)
        )
        return res.stdout.contains("RUNNING")
    }

    private suspend fun checkWatchdogAliveLocked(): Boolean {
        if (!pidFile.exists()) return false
        val res = rootShell.exec(ProtectionCommands.buildCheckWatchdogCommand(pidFile.absolutePath))
        return res.stdout.contains("RUNNING")
    }

    companion object {
        private const val TAG = "Protect"
        private const val PREFS_NAME = "coregovernor_protection"
        private const val KEY_PHANTOM_SAVED = "phantom_orig_saved"
        private const val KEY_PHANTOM_ORIG_MAX = "phantom_orig_max"
        private const val KEY_PHANTOM_ORIG_MON = "phantom_orig_mon"
    }
}
