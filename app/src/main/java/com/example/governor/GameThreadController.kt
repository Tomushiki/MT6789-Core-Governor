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

import com.example.model.GameThreadControllerConfig
import com.example.model.GameThreadHistoryRecord
import com.example.model.GovernorMode
import com.example.model.HardwareSpec
import com.example.model.LogLevel
import com.example.model.ThreadAffinityMapping
import com.example.model.ThreadObservationEntry
import com.example.root.ShellResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveThreadInfo(
    val tid: Int,
    val name: String
)

data class ParsedThreadEnumeration(
    val processName: String = "",
    val threads: List<LiveThreadInfo> = emptyList()
)

enum class GameThreadPreset(
    val title: String,
    val cores: Set<Int>
) {
    BIG("Big 6-7", setOf(6, 7)),
    MID("Mid 3-5", setOf(3, 4, 5)),
    LITTLE("Little 0-2", setOf(0, 1, 2))
}

enum class GameThreadStatusReason {
    OK,
    BACKGROUND_LIVE,
    SHOWING_HISTORY,
    ROOT_UNAVAILABLE,
    NO_GAME_IN_FOREGROUND,
    NO_PID,
    NO_THREADS
}

data class GameThreadInspectionResult(
    val rootAvailable: Boolean = false,
    val foregroundPackage: String = "",
    val targetPackage: String = "",
    val processName: String = "",
    val isSelectedGameForeground: Boolean = false,
    val pid: Int? = null,
    val threads: List<LiveThreadInfo> = emptyList(),
    val statusReason: GameThreadStatusReason = GameThreadStatusReason.NO_GAME_IN_FOREGROUND,
    val availableCpuCount: Int = 8,
    val refreshedAtMs: Long = 0L
)

data class ObservedThreadRow(
    val threadName: String,
    val isLive: Boolean,
    val liveTids: List<Int>,
    val lastKnownTid: Int,
    val lastObservedAtMs: Long,
    val observationCount: Int,
    val matchedMapping: ThreadAffinityMapping?
)

/** Pure conversion, matching, auto-classification, command building, and parsing logic. */
object GameThreadLogic {

    /** Fast scan interval, used right after the game (re)starts and while threads are still being found. */
    const val MIN_POLL_INTERVAL_MS = 2_000L
    /** v4.0.7: scan interval once the thread list has settled. Each scan costs several seconds of root shell time. */
    const val STEADY_POLL_INTERVAL_MS = 12_000L
    /** v4.0.7: interval after a scan that still found new threads to pin. */
    const val ACTIVE_POLL_INTERVAL_MS = 4_000L
    /** v4.0.7: how long after a new game process is tracked that the fast interval is used. */
    const val SETTLE_WINDOW_MS = 30_000L

    /** Delay before the next scan: fast while settling, a bit slower while new threads keep appearing, else steady. */
    fun nextPollDelayMs(nowMs: Long, settleUntilMs: Long, appliedCount: Int): Long = when {
        nowMs < settleUntilMs -> MIN_POLL_INTERVAL_MS
        appliedCount > 0 -> ACTIVE_POLL_INTERVAL_MS
        else -> STEADY_POLL_INTERVAL_MS
    }
    const val UI_CORE_COUNT = 8
    const val MAX_SUPPORTED_CORES = 63

    private val HEX_MASK_REGEX = Regex("^[0-9a-fA-F]{1,16}$")
    private val WS_REGEX = Regex("\\s+")

    fun availableCpuCount(
        hw: HardwareSpec,
        fallbackCores: Int = Runtime.getRuntime().availableProcessors()
    ): Int {
        val allCpus = hw.cpuPolicies.flatMap { it.relatedCpus }.filter { it >= 0 }.distinct()
        val fromPolicies = if (allCpus.isNotEmpty()) {
            maxOf(allCpus.max() + 1, allCpus.size)
        } else {
            0
        }
        val resolved = if (fromPolicies > 0) fromPolicies else fallbackCores
        return resolved.coerceIn(1, MAX_SUPPORTED_CORES)
    }

    private fun validCpuBitLimit(cpuCount: Int): ULong {
        val count = cpuCount.coerceIn(1, MAX_SUPPORTED_CORES)
        return (1UL shl count) - 1UL
    }

    /**
     * Converts selected 0-based CPU core indices into a Linux `taskset` hexadecimal bitmask.
     * Any cores outside [0, availableCpuCount) are filtered out; returns null if empty.
     */
    fun coresToHexMask(cores: Set<Int>, availableCpuCount: Int = UI_CORE_COUNT): String? {
        val limit = availableCpuCount.coerceIn(1, MAX_SUPPORTED_CORES)
        var mask = 0UL
        for (core in cores) {
            if (core in 0 until limit) {
                mask = mask or (1UL shl core)
            }
        }
        return if (mask == 0UL) null else mask.toString(16)
    }

    /**
     * Parses and validates a hex bitmask (without 0x prefix when normalized).
     * Rejects blank, malformed, zero, or masks referencing cores >= [availableCpuCount].
     */
    fun sanitizeHexMask(rawMask: String, availableCpuCount: Int = MAX_SUPPORTED_CORES): String? {
        val stripped = rawMask.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .trim()
        if (!HEX_MASK_REGEX.matches(stripped)) return null
        val parsed = stripped.toULongOrNull(16) ?: return null
        if (parsed == 0UL) return null
        val allowedBits = validCpuBitLimit(availableCpuCount)
        if ((parsed and allowedBits) != parsed) return null
        return parsed.toString(16)
    }

    /**
     * Storage-safe hex validation (does not depend on discovered CPU count).
     */
    fun normalizeStoredHexMask(rawMask: String): String? =
        sanitizeHexMask(rawMask, MAX_SUPPORTED_CORES)

    fun isValidHexMask(rawMask: String, availableCpuCount: Int = UI_CORE_COUNT): Boolean =
        sanitizeHexMask(rawMask, availableCpuCount) != null

    fun hexMaskToCores(mask: String, availableCpuCount: Int = MAX_SUPPORTED_CORES): Set<Int> {
        val valid = sanitizeHexMask(mask, availableCpuCount) ?: return emptySet()
        val bits = valid.toULongOrNull(16) ?: return emptySet()
        val limit = availableCpuCount.coerceIn(1, MAX_SUPPORTED_CORES)
        val out = linkedSetOf<Int>()
        for (core in 0 until limit) {
            if (((bits shr core) and 1UL) == 1UL) {
                out.add(core)
            }
        }
        return out
    }

    fun formatCoresLabel(cores: Set<Int>): String {
        if (cores.isEmpty()) return "--"
        val sorted = cores.filter { it >= 0 }.distinct().sorted()
        if (sorted.isEmpty()) return "--"
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1 until sorted.size) {
            val c = sorted[i]
            if (c == end + 1) {
                end = c
            } else {
                parts.add(if (start == end) "$start" else "$start-$end")
                start = c
                end = c
            }
        }
        parts.add(if (start == end) "$start" else "$start-$end")
        return parts.joinToString(", ")
    }

    fun formatMaskLabel(mask: String, availableCpuCount: Int = UI_CORE_COUNT): String {
        val cores = hexMaskToCores(mask, availableCpuCount)
        return if (cores.isEmpty()) "Invalid" else formatCoresLabel(cores)
    }

    fun presetMask(preset: GameThreadPreset, availableCpuCount: Int = UI_CORE_COUNT): String? =
        coresToHexMask(preset.cores, availableCpuCount)

    fun matchesMapping(threadName: String, mapping: ThreadAffinityMapping): Boolean {
        val pat = mapping.pattern.trim()
        if (pat.isEmpty() || threadName.isEmpty()) return false
        return if (mapping.exactMatch) {
            threadName == pat
        } else {
            if ('*' in pat || '?' in pat) {
                globToRegex(pat).matches(threadName)
            } else {
                threadName.contains(pat, ignoreCase = true)
            }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var literal = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*' -> {
                    if (literal.isNotEmpty()) {
                        sb.append(Regex.escape(literal.toString()))
                        literal = StringBuilder()
                    }
                    sb.append(".*")
                }
                '?' -> {
                    if (literal.isNotEmpty()) {
                        sb.append(Regex.escape(literal.toString()))
                        literal = StringBuilder()
                    }
                    sb.append('.')
                }
                else -> literal.append(ch)
            }
        }
        if (literal.isNotEmpty()) {
            sb.append(Regex.escape(literal.toString()))
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Conservative fallback classification based strictly on Main, Render, and Worker thread names.
     * Never makes assumptions about unknown thread names.
     */
    fun autoClassifyMask(threadName: String, availableCpuCount: Int = UI_CORE_COUNT): String? {
        val clean = threadName.trim()
        if (clean.isEmpty()) return null
        return when {
            clean.contains("main", ignoreCase = true) ||
                clean.contains("render", ignoreCase = true) ->
                presetMask(GameThreadPreset.BIG, availableCpuCount)
            clean.contains("worker", ignoreCase = true) ->
                presetMask(GameThreadPreset.MID, availableCpuCount)
            else -> null
        }
    }

    fun findMatchingMapping(
        threadName: String,
        foregroundPkg: String,
        config: GameThreadControllerConfig,
        availableCpuCount: Int = UI_CORE_COUNT
    ): ThreadAffinityMapping? {
        val applicableCustom = config.mappings.filter { m ->
            m.pattern.isNotBlank() &&
                (m.packageName.isBlank() || m.packageName == foregroundPkg) &&
                sanitizeHexMask(m.cpuMask, availableCpuCount) != null
        }
        if (applicableCustom.isEmpty()) return null

        val ordered = applicableCustom.filter { it.packageName == foregroundPkg && it.exactMatch } +
            applicableCustom.filter { it.packageName == foregroundPkg && !it.exactMatch } +
            applicableCustom.filter { it.packageName.isBlank() && it.exactMatch } +
            applicableCustom.filter { it.packageName.isBlank() && !it.exactMatch }
        return ordered.firstOrNull { matchesMapping(threadName, it) }
    }

    /**
     * Resolves the CPU affinity hex mask for [threadName] under [foregroundPkg].
     * Custom mappings always take priority; auto-classification runs only when enabled AND
     * there are no custom mappings.
     */
    fun resolveTargetMask(
        threadName: String,
        foregroundPkg: String,
        config: GameThreadControllerConfig,
        availableCpuCount: Int = UI_CORE_COUNT
    ): String? {
        val applicableCustom = config.mappings.filter { m ->
            m.pattern.isNotBlank() &&
                (m.packageName.isBlank() || m.packageName == foregroundPkg) &&
                sanitizeHexMask(m.cpuMask, availableCpuCount) != null
        }

        if (applicableCustom.isNotEmpty()) {
            val matched = findMatchingMapping(threadName, foregroundPkg, config, availableCpuCount)
            return matched?.let { sanitizeHexMask(it.cpuMask, availableCpuCount) }
        }

        return if (config.autoClassify) {
            autoClassifyMask(threadName, availableCpuCount)
        } else {
            null
        }
    }

    fun buildPidLookupCommand(packageName: String): String? {
        val clean = packageName.trim()
        if (!ProcessMonitor.PACKAGE_RE.matches(clean)) return null
        return "pid=\$(pidof -s '$clean' 2>/dev/null); " +
            "if [ -z \"\$pid\" ]; then " +
            "pid=\$(ps -A -o PID=,NAME= 2>/dev/null | while read -r p n; do " +
            "[ \"\$n\" = '$clean' ] && { printf '%s' \"\$p\"; break; }; done); " +
            "fi; " +
            "printf '%s\\n' \"\$pid\""
    }

    fun parsePidOutput(stdout: String): Int? {
        val tokens = stdout.trim().split(WS_REGEX)
        for (tok in tokens) {
            val pid = tok.toIntOrNull()
            if (pid != null && pid > 0) return pid
        }
        return null
    }

    fun buildThreadEnumCommand(pid: Int): String? {
        if (pid <= 0) return null
        return "if [ -d \"/proc/$pid/task\" ]; then " +
            "proc=\$(tr '\\0' '\\n' < \"/proc/$pid/cmdline\" 2>/dev/null | head -n 1 | tr '\\r\\t' '  '); " +
            "[ -z \"\$proc\" ] && [ -r \"/proc/$pid/comm\" ] && proc=\$(cat \"/proc/$pid/comm\" 2>/dev/null | tr '\\n\\r\\t' '   '); " +
            "[ -n \"\$proc\" ] && printf 'PROC\\t%s\\n' \"\$proc\"; " +
            "for t in /proc/$pid/task/*; do " +
            "tid=\${t##*/}; " +
            "case \"\$tid\" in ''|*[!0-9]*) continue ;; esac; " +
            // v3.7.0: `read` is a shell builtin, so a game with hundreds of threads no longer costs
            // hundreds of cat/tr forks (which held the shared root shell for seconds every scan).
            "name=''; " +
            "IFS= read -r name 2>/dev/null < \"\$t/comm\"; " +
            "[ -n \"\$name\" ] && printf '%s\\t%s\\n' \"\$tid\" \"\$name\"; " +
            "done; " +
            "fi"
    }

    /**
     * v4.0.8 backup scan: ONE `grep` reads every thread's comm file ("/proc/PID/task/TID/comm:name" per line) instead of
     * a shell loop. Only used after the loop scan has proved slow and this command has proved to return the same threads.
     */
    fun buildFastThreadEnumCommand(pid: Int): String? {
        if (pid <= 0) return null
        return "if [ -d \"/proc/$pid/task\" ]; then " +
            "proc=\$(tr '\\0' '\\n' < \"/proc/$pid/cmdline\" 2>/dev/null | head -n 1 | tr '\\r\\t' '  '); " +
            "[ -z \"\$proc\" ] && [ -r \"/proc/$pid/comm\" ] && proc=\$(cat \"/proc/$pid/comm\" 2>/dev/null | tr '\\n\\r\\t' '   '); " +
            "[ -n \"\$proc\" ] && printf 'PROC\\t%s\\n' \"\$proc\"; " +
            "grep -H '' /proc/$pid/task/*/comm 2>/dev/null; " +
            "fi"
    }

    fun parseFastThreadEnumOutput(stdout: String, pid: Int): ParsedThreadEnumeration {
        if (stdout.isBlank() || pid <= 0) return ParsedThreadEnumeration()
        var procName = ""
        val prefix = "/proc/$pid/task/"
        val byTid = LinkedHashMap<Int, LiveThreadInfo>()
        for (line in stdout.lineSequence()) {
            if (line.startsWith("PROC\t")) {
                if (procName.isEmpty()) procName = line.substringAfter("PROC\t").trim()
                continue
            }
            if (!line.startsWith(prefix)) continue
            val rest = line.substring(prefix.length)
            val sep = rest.indexOf("/comm:")
            if (sep <= 0) continue
            val tid = rest.substring(0, sep).toIntOrNull() ?: continue
            if (tid <= 0) continue
            val name = rest.substring(sep + "/comm:".length).trim()
            if (name.isEmpty()) continue
            byTid[tid] = LiveThreadInfo(tid = tid, name = name)
        }
        return ParsedThreadEnumeration(processName = procName, threads = byTid.values.sortedBy { it.tid })
    }

    /** A loop scan slower than this makes the one-command scan worth trying. */
    const val SLOW_ENUM_MS = 1_500L

    /** The one-command scan must find at least this share of the loop scan's threads to be trusted. */
    const val FAST_ENUM_MIN_MATCH_PCT = 90

    /** True when the one-command scan returned about the same threads as the loop scan and was clearly faster. */
    fun fastEnumIsTrustworthy(loopCount: Int, loopMs: Long, fastCount: Int, fastMs: Long): Boolean =
        loopCount > 0 && fastCount * 100 >= loopCount * FAST_ENUM_MIN_MATCH_PCT &&
            fastCount * 100 <= loopCount * (200 - FAST_ENUM_MIN_MATCH_PCT) && fastMs * 2 <= loopMs

    fun parseThreadList(stdout: String): List<LiveThreadInfo> {
        if (stdout.isBlank()) return emptyList()
        val byTid = LinkedHashMap<Int, LiveThreadInfo>()
        for (line in stdout.lineSequence()) {
            if (line.isBlank()) continue
            val tabIdx = line.indexOf('\t')
            if (tabIdx <= 0 || tabIdx >= line.length - 1) continue
            val tid = line.substring(0, tabIdx).trim().toIntOrNull() ?: continue
            if (tid <= 0) continue
            val name = line.substring(tabIdx + 1).trim()
            if (name.isEmpty()) continue
            byTid[tid] = LiveThreadInfo(tid = tid, name = name)
        }
        return byTid.values.sortedBy { it.tid }
    }

    fun parseThreadEnumOutput(stdout: String): ParsedThreadEnumeration {
        if (stdout.isBlank()) return ParsedThreadEnumeration()
        var procName = ""
        for (line in stdout.lineSequence()) {
            if (line.startsWith("PROC\t")) {
                procName = line.substringAfter("PROC\t").trim()
                break
            }
        }
        return ParsedThreadEnumeration(
            processName = procName,
            threads = parseThreadList(stdout)
        )
    }

    fun buildTasksetCommand(mask: String, tid: Int, availableCpuCount: Int = UI_CORE_COUNT): String? {
        if (tid <= 0) return null
        val validMask = sanitizeHexMask(mask, availableCpuCount) ?: return null
        return "taskset -p $validMask $tid"
    }

    /**
     * Signature covers ONLY enforcement configuration (excluding persisted threadHistory/timestamps)
     * so saving thread history never triggers unnecessary taskset re-application.
     */
    fun configSignature(config: GameThreadControllerConfig, availableCpuCount: Int): String = buildString {
        append(config.enabled).append('|')
        append(config.autoClassify).append('|')
        append(availableCpuCount).append('|')
        config.mappings.forEach { m ->
            append(m.packageName).append(':').append(m.exactMatch).append(':')
                .append(m.pattern).append('=').append(m.cpuMask).append(';')
        }
    }
}

/** Pure functions for persisting, merging, pruning, and querying per-game thread history. */
object GameThreadHistoryLogic {

    const val MAX_HISTORY_PER_GAME = 250

    fun resolveInspectionTargetPackage(
        fgPkg: String,
        gamePackages: Set<String>,
        config: GameThreadControllerConfig
    ): String {
        if (fgPkg.isNotBlank() && fgPkg in gamePackages) return fgPkg
        if (config.selectedGamePackage.isNotBlank()) return config.selectedGamePackage.trim()
        if (config.lastObservedGamePackage.isNotBlank()) return config.lastObservedGamePackage.trim()
        val mostRecentFromHistory = config.threadHistory.values
            .maxByOrNull { it.lastObservedAtMs }
            ?.packageName
            ?.trim()
        if (!mostRecentFromHistory.isNullOrBlank()) return mostRecentFromHistory
        return gamePackages.map { it.trim() }.filter { it.isNotEmpty() }.sorted().firstOrNull().orEmpty()
    }

    fun mergeObservation(
        config: GameThreadControllerConfig,
        packageName: String,
        displayName: String = "",
        processName: String = "",
        observedThreads: List<LiveThreadInfo>,
        observedAtMs: Long,
        availableCpuCount: Int = GameThreadLogic.MAX_SUPPORTED_CORES,
        maxEntriesPerGame: Int = MAX_HISTORY_PER_GAME
    ): GameThreadControllerConfig {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || observedThreads.isEmpty()) return config

        val existingRecord = config.threadHistory[cleanPkg]
        val byName = LinkedHashMap<String, ThreadObservationEntry>()
        existingRecord?.threads?.forEach { entry ->
            val name = entry.threadName.trim()
            if (name.isNotEmpty()) {
                val prev = byName[name]
                if (prev == null || entry.lastObservedAtMs > prev.lastObservedAtMs) {
                    byName[name] = entry.copy(
                        threadName = name,
                        lastKnownTid = entry.lastKnownTid.coerceAtLeast(0),
                        lastObservedAtMs = entry.lastObservedAtMs.coerceAtLeast(0L),
                        observationCount = entry.observationCount.coerceAtLeast(1)
                    )
                }
            }
        }

        // Collapse multiple live threads sharing a name in the same refresh snapshot without duplicates.
        val seenInSnapshot = LinkedHashMap<String, Int>()
        for (t in observedThreads.sortedBy { it.tid }) {
            val name = t.name.trim()
            if (name.isEmpty() || t.tid <= 0) continue
            if (name !in seenInSnapshot) {
                seenInSnapshot[name] = t.tid
            }
        }
        if (seenInSnapshot.isEmpty()) return config

        for ((name, tid) in seenInSnapshot) {
            val prev = byName[name]
            byName[name] = if (prev != null) {
                prev.copy(
                    lastKnownTid = tid,
                    lastObservedAtMs = maxOf(prev.lastObservedAtMs, observedAtMs),
                    observationCount = (prev.observationCount + 1).coerceAtLeast(1)
                )
            } else {
                ThreadObservationEntry(
                    threadName = name,
                    lastKnownTid = tid,
                    lastObservedAtMs = observedAtMs,
                    observationCount = 1
                )
            }
        }

        val prunedThreads = pruneHistoryEntries(
            entries = byName.values.toList(),
            packageName = cleanPkg,
            config = config,
            availableCpuCount = availableCpuCount,
            maxEntriesPerGame = maxEntriesPerGame.coerceAtLeast(1)
        )

        val resolvedDisplay = displayName.trim().ifEmpty { existingRecord?.displayName.orEmpty() }
        val resolvedProc = processName.trim().ifEmpty { existingRecord?.lastProcessName.orEmpty() }
        val updatedRecord = GameThreadHistoryRecord(
            packageName = cleanPkg,
            displayName = resolvedDisplay,
            lastProcessName = resolvedProc,
            lastObservedAtMs = maxOf(existingRecord?.lastObservedAtMs ?: 0L, observedAtMs),
            threads = prunedThreads
        )

        val updatedMap = config.threadHistory.toMutableMap()
        updatedMap[cleanPkg] = updatedRecord
        return config.copy(
            lastObservedGamePackage = cleanPkg,
            threadHistory = updatedMap
        )
    }

    private fun pruneHistoryEntries(
        entries: List<ThreadObservationEntry>,
        packageName: String,
        config: GameThreadControllerConfig,
        availableCpuCount: Int,
        maxEntriesPerGame: Int
    ): List<ThreadObservationEntry> {
        val sortedByRecency = entries.sortedWith(
            compareByDescending<ThreadObservationEntry> { it.lastObservedAtMs }
                .thenBy { it.threadName }
        )
        if (sortedByRecency.size <= maxEntriesPerGame) return sortedByRecency

        val mapped = sortedByRecency.filter { entry ->
            GameThreadLogic.findMatchingMapping(
                entry.threadName,
                packageName,
                config,
                availableCpuCount
            ) != null
        }
        if (mapped.size >= maxEntriesPerGame) {
            return mapped.take(maxEntriesPerGame)
        }
        val mappedNames = mapped.mapTo(HashSet(mapped.size)) { it.threadName }
        val remaining = maxEntriesPerGame - mapped.size
        val unmappedRecent = sortedByRecency.filter { it.threadName !in mappedNames }.take(remaining)
        return (mapped + unmappedRecent).sortedWith(
            compareByDescending<ThreadObservationEntry> { it.lastObservedAtMs }
                .thenBy { it.threadName }
        )
    }

    fun removeHistoricalThread(
        config: GameThreadControllerConfig,
        packageName: String,
        threadName: String
    ): GameThreadControllerConfig {
        val cleanPkg = packageName.trim()
        val record = config.threadHistory[cleanPkg] ?: return config
        val filtered = record.threads.filterNot { it.threadName == threadName }
        val nextHistory = config.threadHistory.toMutableMap()
        if (filtered.isEmpty()) {
            nextHistory.remove(cleanPkg)
        } else {
            nextHistory[cleanPkg] = record.copy(threads = filtered)
        }
        return config.copy(threadHistory = nextHistory)
    }

    fun clearGameHistory(
        config: GameThreadControllerConfig,
        packageName: String
    ): GameThreadControllerConfig {
        val cleanPkg = packageName.trim()
        if (cleanPkg !in config.threadHistory) return config
        val nextHistory = config.threadHistory.toMutableMap()
        nextHistory.remove(cleanPkg)
        return config.copy(threadHistory = nextHistory)
    }

    fun clearAllHistory(config: GameThreadControllerConfig): GameThreadControllerConfig {
        if (config.threadHistory.isEmpty()) return config
        return config.copy(threadHistory = emptyMap())
    }

    fun buildHistoryRows(
        historyRecord: GameThreadHistoryRecord?,
        liveThreads: List<LiveThreadInfo>,
        isTargetLive: Boolean,
        config: GameThreadControllerConfig,
        targetPackage: String,
        availableCpuCount: Int
    ): List<ObservedThreadRow> {
        val liveTidsByName = LinkedHashMap<String, MutableList<Int>>()
        if (isTargetLive) {
            for (t in liveThreads.sortedBy { it.tid }) {
                liveTidsByName.getOrPut(t.name) { mutableListOf() }.add(t.tid)
            }
        }

        val rowsByName = LinkedHashMap<String, ObservedThreadRow>()
        historyRecord?.threads?.forEach { entry ->
            val tids = liveTidsByName[entry.threadName] ?: emptyList()
            val mapping = GameThreadLogic.findMatchingMapping(
                entry.threadName,
                targetPackage,
                config,
                availableCpuCount
            )
            rowsByName[entry.threadName] = ObservedThreadRow(
                threadName = entry.threadName,
                isLive = tids.isNotEmpty(),
                liveTids = tids,
                lastKnownTid = tids.firstOrNull() ?: entry.lastKnownTid,
                lastObservedAtMs = entry.lastObservedAtMs,
                observationCount = entry.observationCount,
                matchedMapping = mapping
            )
        }

        // Also include any live threads not yet reflected in history snapshot.
        for ((name, tids) in liveTidsByName) {
            if (name !in rowsByName) {
                val mapping = GameThreadLogic.findMatchingMapping(
                    name,
                    targetPackage,
                    config,
                    availableCpuCount
                )
                rowsByName[name] = ObservedThreadRow(
                    threadName = name,
                    isLive = true,
                    liveTids = tids,
                    lastKnownTid = tids.first(),
                    lastObservedAtMs = historyRecord?.lastObservedAtMs ?: 0L,
                    observationCount = 1,
                    matchedMapping = mapping
                )
            }
        }

        return rowsByName.values.sortedWith(
            compareByDescending<ObservedThreadRow> { it.isLive }
                .thenByDescending { it.matchedMapping != null }
                .thenByDescending { it.lastObservedAtMs }
                .thenBy { it.threadName }
        )
    }
}

/**
 * Runtime controller executing thread affinity enforcement at most once per 2 seconds,
 * strictly while enabled, in Game mode, and while a configured game is in the foreground.
 * Never applies taskset to historical entries or stale TIDs.
 */
class GameThreadRuntime(
    private val shellExec: suspend (String) -> ShellResult,
    private val log: (LogLevel, String, String) -> Unit = { _, _, _ -> },
    private val onThreadsObserved: (
        packageName: String,
        processName: String,
        observedAtMs: Long,
        threads: List<LiveThreadInfo>
    ) -> Unit = { _, _, _, _ -> }
) {
    private val inspectionMutex = Mutex()
    private var lastRunAtMs: Long = 0L
    private var nextDelayMs: Long = GameThreadLogic.MIN_POLL_INTERVAL_MS
    private var settleUntilMs: Long = 0L
    /** 0 = not tried yet, 1 = one-command scan in use, -1 = tried and rejected (stay on the loop scan). */
    private var fastEnumState: Int = 0
    private var trackedPid: Int = -1
    private var trackedPkg: String = ""
    private var trackedSignature: String = ""
    private val appliedByTid = HashMap<Int, Pair<String, String>>()

    fun reset() {
        lastRunAtMs = 0L
        nextDelayMs = GameThreadLogic.MIN_POLL_INTERVAL_MS
        settleUntilMs = 0L
        trackedPid = -1
        trackedPkg = ""
        trackedSignature = ""
        appliedByTid.clear()
    }

    suspend fun onControlTick(
        nowMs: Long,
        config: GameThreadControllerConfig,
        activeMode: GovernorMode,
        fgPkg: String,
        gamePackages: Set<String>,
        rootAvailable: Boolean,
        availableCpuCount: Int,
        isSleepSaving: Boolean = false
    ) {
        if (!config.enabled ||
            activeMode != GovernorMode.GAME ||
            fgPkg.isBlank() ||
            fgPkg !in gamePackages ||
            !rootAvailable ||
            isSleepSaving
        ) {
            if (trackedPid != -1 || appliedByTid.isNotEmpty() || trackedPkg.isNotEmpty()) {
                reset()
            }
            return
        }

        if (lastRunAtMs > 0L && nowMs - lastRunAtMs < nextDelayMs) {
            return
        }
        lastRunAtMs = nowMs
        // Until a scan succeeds, retry at a moderate pace (a missing process or empty list is not worth hammering).
        nextDelayMs = GameThreadLogic.ACTIVE_POLL_INTERVAL_MS

        val pidCmd = GameThreadLogic.buildPidLookupCommand(fgPkg) ?: return
        val pidRes = try {
            shellExec(pidCmd)
        } catch (_: Throwable) {
            return
        }
        val pid = if (pidRes.isSuccess || pidRes.stdout.isNotBlank()) {
            GameThreadLogic.parsePidOutput(pidRes.stdout)
        } else null
        if (pid == null || pid <= 0) {
            trackedPid = -1
            appliedByTid.clear()
            return
        }

        var parsed: ParsedThreadEnumeration = ParsedThreadEnumeration()
        if (fastEnumState == 1) {
            val fastCmd = GameThreadLogic.buildFastThreadEnumCommand(pid) ?: return
            val fastRes = try {
                shellExec(fastCmd)
            } catch (_: Throwable) {
                return
            }
            parsed = GameThreadLogic.parseFastThreadEnumOutput(fastRes.stdout, pid)
            if (parsed.threads.isEmpty()) {
                fastEnumState = -1
                log(LogLevel.WARN, "GameThreads", "One-command thread scan returned nothing; back to the loop scan.")
            }
        }
        if (parsed.threads.isEmpty()) {
            val enumCmd = GameThreadLogic.buildThreadEnumCommand(pid) ?: return
            val loopStartMs = System.nanoTime() / 1_000_000L
            val enumRes = try {
                shellExec(enumCmd)
            } catch (_: Throwable) {
                return
            }
            val loopMs = System.nanoTime() / 1_000_000L - loopStartMs
            parsed = GameThreadLogic.parseThreadEnumOutput(enumRes.stdout)
            // Backup plan: when the loop scan is slow, try the one-command scan once and keep it only if it matches.
            if (fastEnumState == 0 && loopMs >= GameThreadLogic.SLOW_ENUM_MS && parsed.threads.isNotEmpty()) {
                val fastCmd = GameThreadLogic.buildFastThreadEnumCommand(pid)
                if (fastCmd != null) {
                    val fastStartMs = System.nanoTime() / 1_000_000L
                    val fastParsed = try {
                        GameThreadLogic.parseFastThreadEnumOutput(shellExec(fastCmd).stdout, pid)
                    } catch (_: Throwable) {
                        ParsedThreadEnumeration()
                    }
                    val fastMs = System.nanoTime() / 1_000_000L - fastStartMs
                    if (GameThreadLogic.fastEnumIsTrustworthy(parsed.threads.size, loopMs, fastParsed.threads.size, fastMs)) {
                        fastEnumState = 1
                        log(
                            LogLevel.INFO,
                            "GameThreads",
                            "Thread scan switched to one command: ${loopMs}ms -> ${fastMs}ms (${fastParsed.threads.size} threads)."
                        )
                    } else {
                        fastEnumState = -1
                        log(
                            LogLevel.INFO,
                            "GameThreads",
                            "One-command thread scan not used (loop ${loopMs}ms/${parsed.threads.size} threads, " +
                                "one-command ${fastMs}ms/${fastParsed.threads.size} threads)."
                        )
                    }
                }
            }
        }
        val threads = parsed.threads
        if (threads.isEmpty()) {
            appliedByTid.clear()
            return
        }

        // Capture live threads into persistent history (never used directly as taskset targets).
        onThreadsObserved(fgPkg, parsed.processName, nowMs, threads)

        val sig = GameThreadLogic.configSignature(config, availableCpuCount)
        if (pid != trackedPid || fgPkg != trackedPkg || sig != trackedSignature) {
            settleUntilMs = nowMs + GameThreadLogic.SETTLE_WINDOW_MS
            appliedByTid.clear()
            trackedPid = pid
            trackedPkg = fgPkg
            trackedSignature = sig
        } else {
            val liveTids = threads.mapTo(HashSet(threads.size)) { it.tid }
            appliedByTid.keys.retainAll(liveTids)
        }

        var appliedCount = 0
        for (thread in threads) {
            val targetMask = GameThreadLogic.resolveTargetMask(
                threadName = thread.name,
                foregroundPkg = fgPkg,
                config = config,
                availableCpuCount = availableCpuCount
            ) ?: continue

            val prev = appliedByTid[thread.tid]
            if (prev != null && prev.first == thread.name && prev.second == targetMask) {
                continue
            }

            val tasksetCmd = GameThreadLogic.buildTasksetCommand(
                mask = targetMask,
                tid = thread.tid,
                availableCpuCount = availableCpuCount
            ) ?: continue

            try {
                val res = shellExec(tasksetCmd)
                if (res.isSuccess) {
                    appliedByTid[thread.tid] = thread.name to targetMask
                    appliedCount++
                }
            } catch (_: Throwable) {
                // Ignore individual taskset failure and continue processing remaining threads.
            }
        }

        nextDelayMs = GameThreadLogic.nextPollDelayMs(nowMs, settleUntilMs, appliedCount)

        if (appliedCount > 0) {
            log(
                LogLevel.INFO,
                "GameThreads",
                "Applied CPU affinity to $appliedCount thread(s) for $fgPkg (PID $pid)."
            )
        }
    }

    suspend fun inspectForegroundGame(
        nowMs: Long,
        fgPkg: String,
        gamePackages: Set<String>,
        rootAvailable: Boolean,
        availableCpuCount: Int,
        config: GameThreadControllerConfig? = null
    ): GameThreadInspectionResult = inspectionMutex.withLock {
        val isFgSelected = fgPkg.isNotBlank() && fgPkg in gamePackages
        val targetPkg = if (config == null) {
            if (isFgSelected) fgPkg else ""
        } else {
            GameThreadHistoryLogic.resolveInspectionTargetPackage(fgPkg, gamePackages, config)
        }

        if (!rootAvailable) {
            return@withLock GameThreadInspectionResult(
                rootAvailable = false,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                statusReason = GameThreadStatusReason.ROOT_UNAVAILABLE,
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )
        }
        if (targetPkg.isBlank()) {
            return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = "",
                isSelectedGameForeground = false,
                statusReason = GameThreadStatusReason.NO_GAME_IN_FOREGROUND,
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )
        }

        val pidCmd = GameThreadLogic.buildPidLookupCommand(targetPkg)
            ?: return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                statusReason = if (isFgSelected) {
                    GameThreadStatusReason.NO_PID
                } else {
                    GameThreadStatusReason.SHOWING_HISTORY
                },
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )

        val pidRes = try {
            shellExec(pidCmd)
        } catch (_: Throwable) {
            return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                statusReason = if (isFgSelected) {
                    GameThreadStatusReason.NO_PID
                } else {
                    GameThreadStatusReason.SHOWING_HISTORY
                },
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )
        }
        val pid = GameThreadLogic.parsePidOutput(pidRes.stdout)
            ?: return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                statusReason = if (isFgSelected) {
                    GameThreadStatusReason.NO_PID
                } else {
                    GameThreadStatusReason.SHOWING_HISTORY
                },
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )

        val enumCmd = GameThreadLogic.buildThreadEnumCommand(pid)
            ?: return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                pid = pid,
                statusReason = if (isFgSelected) {
                    GameThreadStatusReason.NO_THREADS
                } else {
                    GameThreadStatusReason.SHOWING_HISTORY
                },
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )

        val enumRes = try {
            shellExec(enumCmd)
        } catch (_: Throwable) {
            return@withLock GameThreadInspectionResult(
                rootAvailable = true,
                foregroundPackage = fgPkg,
                targetPackage = targetPkg,
                isSelectedGameForeground = isFgSelected,
                pid = pid,
                statusReason = if (isFgSelected) {
                    GameThreadStatusReason.NO_THREADS
                } else {
                    GameThreadStatusReason.SHOWING_HISTORY
                },
                availableCpuCount = availableCpuCount,
                refreshedAtMs = nowMs
            )
        }

        val parsed = GameThreadLogic.parseThreadEnumOutput(enumRes.stdout)
        val threads = parsed.threads
        if (threads.isNotEmpty()) {
            onThreadsObserved(targetPkg, parsed.processName, nowMs, threads)
        }
        val statusReason = when {
            threads.isEmpty() && isFgSelected -> GameThreadStatusReason.NO_THREADS
            threads.isEmpty() -> GameThreadStatusReason.SHOWING_HISTORY
            isFgSelected -> GameThreadStatusReason.OK
            else -> GameThreadStatusReason.BACKGROUND_LIVE
        }
        return@withLock GameThreadInspectionResult(
            rootAvailable = true,
            foregroundPackage = fgPkg,
            targetPackage = targetPkg,
            processName = parsed.processName,
            isSelectedGameForeground = isFgSelected,
            pid = pid,
            threads = threads,
            statusReason = statusReason,
            availableCpuCount = availableCpuCount,
            refreshedAtMs = nowMs
        )
    }
}
