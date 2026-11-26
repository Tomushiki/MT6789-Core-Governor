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

package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigStorage
import com.example.governor.GameThreadHistoryLogic
import com.example.governor.GameThreadInspectionResult
import com.example.governor.GameThreadLogic
import com.example.governor.GameThreadPreset
import com.example.governor.GameThreadRuntime
import com.example.governor.GameThreadStatusReason
import com.example.governor.LiveThreadInfo
import com.example.model.CpuPolicySpec
import com.example.model.GameThreadControllerConfig
import com.example.model.GameThreadHistoryRecord
import com.example.model.GovernorConfig
import com.example.model.GovernorMode
import com.example.model.HardwareSpec
import com.example.model.ThreadAffinityMapping
import com.example.model.ThreadObservationEntry
import com.example.root.ShellResult
import com.example.ui.GameThreadsPanel
import com.example.ui.theme.CoreGovernorTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class GameThreadControllerTest {

    @Test
    fun singleAndMultiCoreMaskConversionMatchesLinuxTaskset() {
        assertEquals("1", GameThreadLogic.coresToHexMask(setOf(0), 8))
        assertEquals("2", GameThreadLogic.coresToHexMask(setOf(1), 8))
        assertEquals("7", GameThreadLogic.coresToHexMask(setOf(0, 1, 2), 8))
        assertEquals("38", GameThreadLogic.coresToHexMask(setOf(3, 4, 5), 8))
        assertEquals("c0", GameThreadLogic.coresToHexMask(setOf(6, 7), 8))
        assertEquals("ff", GameThreadLogic.coresToHexMask((0..7).toSet(), 8))

        // Cores >= availableCpuCount are filtered out safely; all-invalid -> null
        assertEquals("3", GameThreadLogic.coresToHexMask(setOf(0, 1, 6, 7), availableCpuCount = 4))
        assertNull(GameThreadLogic.coresToHexMask(setOf(6, 7), availableCpuCount = 4))
        assertNull(GameThreadLogic.coresToHexMask(emptySet(), availableCpuCount = 8))
    }

    @Test
    fun presetsProduceExpectedMasksAndRespectCpuLimits() {
        assertEquals("7", GameThreadLogic.presetMask(GameThreadPreset.LITTLE, 8))
        assertEquals("38", GameThreadLogic.presetMask(GameThreadPreset.MID, 8))
        assertEquals("c0", GameThreadLogic.presetMask(GameThreadPreset.BIG, 8))

        // 6-core CPU: BIG (6-7) cannot produce an invalid mask -> null
        assertEquals("7", GameThreadLogic.presetMask(GameThreadPreset.LITTLE, 6))
        assertEquals("38", GameThreadLogic.presetMask(GameThreadPreset.MID, 6))
        assertNull(GameThreadLogic.presetMask(GameThreadPreset.BIG, 6))

        // HardwareSpec CPU count computation
        val hw6 = HardwareSpec(
            cpuPolicies = listOf(
                CpuPolicySpec(0, "little", "/sys/0", listOf(0, 1, 2, 3), emptyList(), "schedutil", 0L, 0L),
                CpuPolicySpec(4, "big", "/sys/4", listOf(4, 5), emptyList(), "schedutil", 0L, 0L)
            )
        )
        assertEquals(6, GameThreadLogic.availableCpuCount(hw6, fallbackCores = 8))
    }

    @Test
    fun readableMaskLabelsFormatRangesCleanly() {
        assertEquals("0-2", GameThreadLogic.formatMaskLabel("7", 8))
        assertEquals("3-5", GameThreadLogic.formatMaskLabel("38", 8))
        assertEquals("6-7", GameThreadLogic.formatMaskLabel("c0", 8))
        assertEquals("0, 2-3, 7", GameThreadLogic.formatCoresLabel(setOf(7, 2, 0, 3)))
        assertEquals("--", GameThreadLogic.formatCoresLabel(emptySet()))
        assertEquals("Invalid", GameThreadLogic.formatMaskLabel("0", 8))
    }

    @Test
    fun patternAndExactMatchingBehaveAsSpecified() {
        val substringMap = ThreadAffinityMapping(pattern = "Render", cpuMask = "c0", exactMatch = false)
        assertTrue(GameThreadLogic.matchesMapping("RenderThread", substringMap))
        assertTrue(GameThreadLogic.matchesMapping("gl_render_worker", substringMap))
        assertFalse(GameThreadLogic.matchesMapping("AudioMix", substringMap))

        val globMap = ThreadAffinityMapping(pattern = "JobWorker*", cpuMask = "38", exactMatch = false)
        assertTrue(GameThreadLogic.matchesMapping("JobWorker 0", globMap))
        assertFalse(GameThreadLogic.matchesMapping("MainThread", globMap))

        val exactMap = ThreadAffinityMapping(pattern = "UnityMain", cpuMask = "c0", exactMatch = true)
        assertTrue(GameThreadLogic.matchesMapping("UnityMain", exactMap))
        assertFalse(GameThreadLogic.matchesMapping("UnityMain2", exactMap))
        assertFalse(GameThreadLogic.matchesMapping("unitymain", exactMap))
        assertFalse(GameThreadLogic.matchesMapping(" UnityMain", exactMap))
    }

    @Test
    fun autoClassificationConservativeFallbackForMainRenderWorkerOnly() {
        assertEquals("c0", GameThreadLogic.autoClassifyMask("UnityMain", 8))
        assertEquals("c0", GameThreadLogic.autoClassifyMask("RenderThread", 8))
        assertEquals("38", GameThreadLogic.autoClassifyMask("JobWorker_1", 8))
        assertNull(GameThreadLogic.autoClassifyMask("AudioOut_D", 8))
        assertNull(GameThreadLogic.autoClassifyMask("Binder:1234_2", 8))
        assertNull(GameThreadLogic.autoClassifyMask("", 8))
    }

    @Test
    fun customMappingsTakeStrictPriorityOverAutoClassification() {
        val cfgWithCustom = GameThreadControllerConfig(
            enabled = true,
            autoClassify = true,
            mappings = listOf(
                ThreadAffinityMapping(pattern = "UnityMain", cpuMask = "7", exactMatch = true),
                ThreadAffinityMapping(pattern = "RenderThread", cpuMask = "38", exactMatch = false, packageName = "com.game.a")
            )
        )
        // Custom overrides auto (UnityMain -> 7 instead of c0)
        assertEquals("7", GameThreadLogic.resolveTargetMask("UnityMain", "com.game.a", cfgWithCustom, 8))
        // Per-game custom mapping matches com.game.a
        assertEquals("38", GameThreadLogic.resolveTargetMask("RenderThread", "com.game.a", cfgWithCustom, 8))
        // Unmatched thread when custom mappings exist -> null (auto-classify must NOT override or run when custom exists)
        assertNull(GameThreadLogic.resolveTargetMask("JobWorker_0", "com.game.a", cfgWithCustom, 8))

        // When no custom mappings apply, auto-classification fallback applies when enabled
        val cfgAutoOnly = GameThreadControllerConfig(enabled = true, autoClassify = true, mappings = emptyList())
        assertEquals("c0", GameThreadLogic.resolveTargetMask("UnityMain", "com.game.a", cfgAutoOnly, 8))
        assertEquals("38", GameThreadLogic.resolveTargetMask("Worker-2", "com.game.a", cfgAutoOnly, 8))
        assertNull(GameThreadLogic.resolveTargetMask("unknown_thread", "com.game.a", cfgAutoOnly, 8))

        // Empty mappings and autoClassify disabled -> null
        val cfgEmpty = GameThreadControllerConfig(enabled = true, autoClassify = false, mappings = emptyList())
        assertNull(GameThreadLogic.resolveTargetMask("UnityMain", "com.game.a", cfgEmpty, 8))
    }

    @Test
    fun invalidCpuMasksAreRejectedSafely() {
        assertNull(GameThreadLogic.sanitizeHexMask("", 8))
        assertNull(GameThreadLogic.sanitizeHexMask("0", 8))
        assertNull(GameThreadLogic.sanitizeHexMask("0x0", 8))
        assertNull(GameThreadLogic.sanitizeHexMask("xyz", 8))
        assertNull(GameThreadLogic.sanitizeHexMask("-1", 8))
        assertNull(GameThreadLogic.sanitizeHexMask("c0", availableCpuCount = 4))
        assertEquals("c0", GameThreadLogic.sanitizeHexMask("0xC0", availableCpuCount = 8))
        assertNull(GameThreadLogic.buildTasksetCommand("invalid", tid = 100, availableCpuCount = 8))
        assertNull(GameThreadLogic.buildTasksetCommand("c0", tid = 0, availableCpuCount = 8))
        assertNull(GameThreadLogic.buildTasksetCommand("c0", tid = 100, availableCpuCount = 4))
    }

    @Test
    fun threadParsingHandlesMissingExitedAndMalformedEntries() {
        val raw = """
            PROC${'\t'}com.game.play
            101${'\t'}UnityMain
            notanumber${'\t'}BadTid
            102${'\t'}
            103${'\t'}RenderThread
            malformed_line_without_tab
            103${'\t'}RenderThread
        """.trimIndent()
        val parsed = GameThreadLogic.parseThreadList(raw)
        assertEquals(
            listOf(LiveThreadInfo(101, "UnityMain"), LiveThreadInfo(103, "RenderThread")),
            parsed
        )
        val full = GameThreadLogic.parseThreadEnumOutput(raw)
        assertEquals("com.game.play", full.processName)
        assertEquals(parsed, full.threads)
        assertEquals(emptyList<LiveThreadInfo>(), GameThreadLogic.parseThreadList(""))
    }

    @Test
    fun inspectForegroundGameHandlesMissingRootNoGameMissingPidAndExitedProcess() = runTest {
        val cmds = mutableListOf<String>()
        val runtime = GameThreadRuntime(
            shellExec = { cmd ->
                cmds.add(cmd)
                when {
                    cmd.contains("pidof") -> ShellResult(0, "")
                    else -> ShellResult(0, "")
                }
            }
        )

        // Root unavailable -> zero commands
        val noRoot = runtime.inspectForegroundGame(1000L, "com.game.play", setOf("com.game.play"), false, 8)
        assertEquals(GameThreadStatusReason.ROOT_UNAVAILABLE, noRoot.statusReason)
        assertTrue(cmds.isEmpty())

        // No game in foreground
        val noGame = runtime.inspectForegroundGame(1000L, "com.launcher", setOf("com.game.play"), true, 8)
        assertEquals(GameThreadStatusReason.NO_GAME_IN_FOREGROUND, noGame.statusReason)
        assertTrue(cmds.isEmpty())

        // Game foreground but process PID not found / disappeared
        val noPid = runtime.inspectForegroundGame(1000L, "com.game.play", setOf("com.game.play"), true, 8)
        assertEquals(GameThreadStatusReason.NO_PID, noPid.statusReason)
        assertNull(noPid.pid)

        // PID found, /proc/[pid]/task disappeared or unreadable -> NO_THREADS
        val runtimeDisappeared = GameThreadRuntime(
            shellExec = { cmd ->
                if (cmd.contains("pidof")) ShellResult(0, "4321\n") else ShellResult(0, "")
            }
        )
        val noThreads = runtimeDisappeared.inspectForegroundGame(2000L, "com.game.play", setOf("com.game.play"), true, 8)
        assertEquals(GameThreadStatusReason.NO_THREADS, noThreads.statusReason)
        assertEquals(4321, noThreads.pid)
        assertTrue(noThreads.threads.isEmpty())
    }

    @Test
    fun runtimeRespectsEnabledModeForegroundGameIntervalCacheAndTasksetFailures() = runTest {
        val executed = mutableListOf<String>()
        var failTid = -1
        var currentThreadsStdout = "201\tUnityMain\n202\tRenderThread\n203\tAudioOut\n"
        var currentPidStdout = "5000\n"

        val runtime = GameThreadRuntime(
            shellExec = { cmd ->
                executed.add(cmd)
                when {
                    cmd.contains("pidof") -> ShellResult(0, currentPidStdout)
                    cmd.contains("/proc/") -> ShellResult(0, currentThreadsStdout)
                    cmd.startsWith("taskset -p ") -> {
                        val tid = cmd.substringAfterLast(' ').toIntOrNull()
                        if (tid == failTid) {
                            ShellResult(1, "", "No such process")
                        } else {
                            ShellResult(0, "")
                        }
                    }
                    else -> ShellResult(0, "")
                }
            }
        )

        val games = setOf("com.game.play")
        val enabledAutoCfg = GameThreadControllerConfig(enabled = true, autoClassify = true)

        // 1. Disabled -> never runs
        runtime.onControlTick(
            nowMs = 1_000L,
            config = GameThreadControllerConfig(enabled = false, autoClassify = true),
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(executed.isEmpty())

        // 2. Outside Game mode (Normal mode) -> never runs
        runtime.onControlTick(
            nowMs = 2_000L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.NORMAL,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(executed.isEmpty())

        // 3. Forced Game mode while unrelated app is in foreground -> never affects unrelated apps
        runtime.onControlTick(
            nowMs = 3_000L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.unrelated.browser",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(executed.isEmpty())

        // 4. Active in Game mode with selected game -> runs and skips individual taskset failure
        failTid = 201 // simulate tid 201 exiting before taskset runs
        runtime.onControlTick(
            nowMs = 10_000L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        val firstTasksets = executed.filter { it.startsWith("taskset -p ") }
        assertEquals(listOf("taskset -p c0 201", "taskset -p c0 202"), firstTasksets)

        // 5. Interval < 2000ms -> skipped completely (0 new shell commands)
        executed.clear()
        runtime.onControlTick(
            nowMs = 11_999L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(executed.isEmpty())

        // 6. Interval >= 2000ms, unchanged 202 (previously succeeded), failed 201 retries & succeeds
        failTid = -1
        runtime.onControlTick(
            nowMs = 12_000L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        val retryTasksets = executed.filter { it.startsWith("taskset -p ") }
        assertEquals(listOf("taskset -p c0 201"), retryTasksets)

        // 7. Interval >= 2000ms with completely unchanged state -> zero taskset calls
        executed.clear()
        runtime.onControlTick(
            nowMs = 14_000L,
            config = enabledAutoCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(executed.any { it.contains("pidof") })
        assertTrue(executed.none { it.startsWith("taskset -p ") })

        // 8. Mapping change triggers re-application; PID change also resets cache
        executed.clear()
        val updatedCfg = GameThreadControllerConfig(
            enabled = true,
            autoClassify = false,
            mappings = listOf(ThreadAffinityMapping(pattern = "UnityMain", cpuMask = "38", exactMatch = true))
        )
        runtime.onControlTick(
            nowMs = 16_000L,
            config = updatedCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertEquals(
            listOf("taskset -p 38 201"),
            executed.filter { it.startsWith("taskset -p ") }
        )

        // New PID re-applies cleanly
        executed.clear()
        currentPidStdout = "5001\n"
        currentThreadsStdout = "301\tUnityMain\n"
        runtime.onControlTick(
            nowMs = 18_000L,
            config = updatedCfg,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = games,
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertEquals(
            listOf("taskset -p 38 301"),
            executed.filter { it.startsWith("taskset -p ") }
        )
    }

    @Test
    fun mergeObservationRecordsUpdatesDeduplicatesAndHandlesChangedTids() {
        val base = GameThreadControllerConfig()
        val afterFirst = GameThreadHistoryLogic.mergeObservation(
            config = base,
            packageName = "com.game.play",
            displayName = "Play Game",
            processName = "com.game.play",
            observedThreads = listOf(
                LiveThreadInfo(101, "UnityMain"),
                LiveThreadInfo(102, "RenderThread"),
                LiveThreadInfo(103, "UnityMain") // duplicate thread name in same snapshot
            ),
            observedAtMs = 1_000L
        )
        assertEquals("com.game.play", afterFirst.lastObservedGamePackage)
        val firstRec = afterFirst.threadHistory["com.game.play"]
        assertNotNull(firstRec)
        assertEquals("Play Game", firstRec!!.displayName)
        assertEquals("com.game.play", firstRec.lastProcessName)
        assertEquals(1_000L, firstRec.lastObservedAtMs)
        assertEquals(2, firstRec.threads.size)

        // Next session: UnityMain gets a new TID 201, RenderThread exited, AudioThread appears
        val afterSecond = GameThreadHistoryLogic.mergeObservation(
            config = afterFirst,
            packageName = "com.game.play",
            displayName = "Play Game",
            processName = "com.game.play:unity",
            observedThreads = listOf(
                LiveThreadInfo(201, "UnityMain"),
                LiveThreadInfo(205, "AudioThread")
            ),
            observedAtMs = 5_000L
        )
        val rec = afterSecond.threadHistory["com.game.play"]!!
        assertEquals(3, rec.threads.size)
        val unity = rec.threads.first { it.threadName == "UnityMain" }
        assertEquals(201, unity.lastKnownTid)
        assertEquals(5_000L, unity.lastObservedAtMs)
        assertEquals(2, unity.observationCount)

        val render = rec.threads.first { it.threadName == "RenderThread" }
        assertEquals(102, render.lastKnownTid)
        assertEquals(1_000L, render.lastObservedAtMs)
        assertEquals(1, render.observationCount)

        val audio = rec.threads.first { it.threadName == "AudioThread" }
        assertEquals(205, audio.lastKnownTid)
        assertEquals(1, audio.observationCount)
    }

    @Test
    fun historyPruningPreservesMappedThreadsOverOlderUnmappedEntries() {
        val cfg = GameThreadControllerConfig(
            mappings = listOf(
                ThreadAffinityMapping(
                    pattern = "CustomMappedThread",
                    cpuMask = "c0",
                    exactMatch = true,
                    packageName = "com.game.play"
                )
            ),
            threadHistory = mapOf(
                "com.game.play" to GameThreadHistoryRecord(
                    packageName = "com.game.play",
                    lastObservedAtMs = 100L,
                    threads = listOf(
                        ThreadObservationEntry("CustomMappedThread", 10, 100L, 1),
                        ThreadObservationEntry("OldUnmappedThread", 11, 200L, 1)
                    )
                )
            )
        )
        val merged = GameThreadHistoryLogic.mergeObservation(
            config = cfg,
            packageName = "com.game.play",
            observedThreads = listOf(
                LiveThreadInfo(501, "NewUnmappedA"),
                LiveThreadInfo(502, "NewUnmappedB")
            ),
            observedAtMs = 5_000L,
            maxEntriesPerGame = 3
        )
        val names = merged.threadHistory["com.game.play"]!!.threads.map { it.threadName }.toSet()
        assertEquals(setOf("CustomMappedThread", "NewUnmappedA", "NewUnmappedB"), names)
    }

    @Test
    fun removeAndClearHistoryActionsPreserveCustomMappings() {
        val mapping = ThreadAffinityMapping("UnityMain", "c0", exactMatch = true, packageName = "com.game.a")
        val initial = GameThreadControllerConfig(
            enabled = true,
            mappings = listOf(mapping),
            threadHistory = mapOf(
                "com.game.a" to GameThreadHistoryRecord(
                    packageName = "com.game.a",
                    lastObservedAtMs = 200L,
                    threads = listOf(
                        ThreadObservationEntry("UnityMain", 10, 200L, 1),
                        ThreadObservationEntry("Worker0", 11, 200L, 1)
                    )
                ),
                "com.game.b" to GameThreadHistoryRecord(
                    packageName = "com.game.b",
                    lastObservedAtMs = 300L,
                    threads = listOf(ThreadObservationEntry("RenderThread", 20, 300L, 1))
                )
            )
        )

        val afterRemoveOne = GameThreadHistoryLogic.removeHistoricalThread(initial, "com.game.a", "Worker0")
        assertEquals(listOf("UnityMain"), afterRemoveOne.threadHistory["com.game.a"]!!.threads.map { it.threadName })
        assertEquals(listOf(mapping), afterRemoveOne.mappings)

        val afterClearGameA = GameThreadHistoryLogic.clearGameHistory(afterRemoveOne, "com.game.a")
        assertFalse("com.game.a" in afterClearGameA.threadHistory)
        assertTrue("com.game.b" in afterClearGameA.threadHistory)
        assertEquals(listOf(mapping), afterClearGameA.mappings)

        val afterClearAll = GameThreadHistoryLogic.clearAllHistory(afterClearGameA)
        assertTrue(afterClearAll.threadHistory.isEmpty())
        assertEquals(listOf(mapping), afterClearAll.mappings)
    }

    @Test
    fun buildHistoryRowsDistinguishesLiveVsHistoricalAndResolvesMappingsByThreadName() {
        val cfg = GameThreadControllerConfig(
            mappings = listOf(
                ThreadAffinityMapping("LoaderThread", "7", exactMatch = true, packageName = "com.game.a"),
                ThreadAffinityMapping("UnityMain", "c0", exactMatch = true, packageName = "com.game.a")
            ),
            threadHistory = mapOf(
                "com.game.a" to GameThreadHistoryRecord(
                    packageName = "com.game.a",
                    lastObservedAtMs = 10_000L,
                    threads = listOf(
                        ThreadObservationEntry("UnityMain", lastKnownTid = 111, lastObservedAtMs = 10_000L, observationCount = 3),
                        ThreadObservationEntry("LoaderThread", lastKnownTid = 222, lastObservedAtMs = 9_000L, observationCount = 1)
                    )
                )
            )
        )
        // Currently live: UnityMain with NEW TID 777 (LoaderThread exited -> historical only)
        val rows = GameThreadHistoryLogic.buildHistoryRows(
            historyRecord = cfg.threadHistory["com.game.a"],
            liveThreads = listOf(LiveThreadInfo(777, "UnityMain")),
            isTargetLive = true,
            config = cfg,
            targetPackage = "com.game.a",
            availableCpuCount = 8
        )
        assertEquals(2, rows.size)
        val liveRow = rows[0]
        assertEquals("UnityMain", liveRow.threadName)
        assertTrue(liveRow.isLive)
        assertEquals(listOf(777), liveRow.liveTids)
        assertEquals(777, liveRow.lastKnownTid)
        assertEquals("c0", liveRow.matchedMapping?.cpuMask)

        val histRow = rows[1]
        assertEquals("LoaderThread", histRow.threadName)
        assertFalse(histRow.isLive)
        assertTrue(histRow.liveTids.isEmpty())
        assertEquals(222, histRow.lastKnownTid)
        assertEquals("7", histRow.matchedMapping?.cpuMask)
    }

    @Test
    fun historicalEntriesAndStaleTidsAreNeverPassedToTaskset() = runTest {
        val tasksetCmds = mutableListOf<String>()
        var currentPid = ""
        var currentThreads = ""
        val runtime = GameThreadRuntime(
            shellExec = { cmd ->
                if (cmd.startsWith("taskset -p ")) tasksetCmds.add(cmd)
                when {
                    cmd.contains("pidof") -> ShellResult(0, currentPid)
                    cmd.contains("/proc/") -> ShellResult(0, currentThreads)
                    else -> ShellResult(0, "")
                }
            }
        )

        val cfgWithStaleHistory = GameThreadControllerConfig(
            enabled = true,
            autoClassify = false,
            mappings = listOf(
                ThreadAffinityMapping("UnityMain", "c0", exactMatch = true, packageName = "com.game.play"),
                ThreadAffinityMapping("AssetWorker", "38", exactMatch = true, packageName = "com.game.play")
            ),
            threadHistory = mapOf(
                "com.game.play" to GameThreadHistoryRecord(
                    packageName = "com.game.play",
                    lastObservedAtMs = 5_000L,
                    threads = listOf(
                        ThreadObservationEntry("UnityMain", lastKnownTid = 9991, lastObservedAtMs = 5_000L, observationCount = 4),
                        ThreadObservationEntry("AssetWorker", lastKnownTid = 9992, lastObservedAtMs = 5_000L, observationCount = 4)
                    )
                )
            )
        )

        // 1. User is in CoreGovernor -> NEVER applies taskset even with history & mappings
        runtime.onControlTick(
            nowMs = 10_000L,
            config = cfgWithStaleHistory,
            activeMode = GovernorMode.NORMAL,
            fgPkg = "com.aistudio.coregovernor.mt6789",
            gamePackages = setOf("com.game.play"),
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertTrue(tasksetCmds.isEmpty())

        // 2. Game relaunches with NEW live TID 4100 for UnityMain (AssetWorker not spawned yet).
        // Old historical TIDs 9991 / 9992 must NEVER receive taskset.
        currentPid = "4000\n"
        currentThreads = "4100\tUnityMain\n"
        runtime.onControlTick(
            nowMs = 15_000L,
            config = cfgWithStaleHistory,
            activeMode = GovernorMode.GAME,
            fgPkg = "com.game.play",
            gamePackages = setOf("com.game.play"),
            rootAvailable = true,
            availableCpuCount = 8
        )
        assertEquals(listOf("taskset -p c0 4100"), tasksetCmds)
        assertFalse(tasksetCmds.any { it.contains("9991") || it.contains("9992") })
    }

    @Test
    fun inspectForegroundGameHandlesBackgroundLiveAndHistoryFallback() = runTest {
        val observedPackages = mutableListOf<String>()
        var pidOutput = "8888\n"
        var threadsOutput = "PROC\tcom.game.play\n8889\tUnityMain\n"
        val runtime = GameThreadRuntime(
            shellExec = { cmd ->
                when {
                    cmd.contains("pidof") -> ShellResult(0, pidOutput)
                    cmd.contains("/proc/") -> ShellResult(0, threadsOutput)
                    else -> ShellResult(0, "")
                }
            },
            onThreadsObserved = { pkg, _, _, _ -> observedPackages.add(pkg) }
        )

        val cfg = GameThreadControllerConfig(
            selectedGamePackage = "com.game.play",
            lastObservedGamePackage = "com.game.play"
        )

        // CoreGovernor opened while game is still running in background -> BACKGROUND_LIVE + records observation
        val bgLive = runtime.inspectForegroundGame(
            nowMs = 20_000L,
            fgPkg = "com.aistudio.coregovernor.mt6789",
            gamePackages = setOf("com.game.play"),
            rootAvailable = true,
            availableCpuCount = 8,
            config = cfg
        )
        assertEquals(GameThreadStatusReason.BACKGROUND_LIVE, bgLive.statusReason)
        assertEquals("com.game.play", bgLive.targetPackage)
        assertEquals(8888, bgLive.pid)
        assertEquals(listOf(LiveThreadInfo(8889, "UnityMain")), bgLive.threads)
        assertEquals(listOf("com.game.play"), observedPackages)

        // Game process closes -> SHOWING_HISTORY while preserving targetPackage
        pidOutput = ""
        threadsOutput = ""
        val fallback = runtime.inspectForegroundGame(
            nowMs = 25_000L,
            fgPkg = "com.aistudio.coregovernor.mt6789",
            gamePackages = setOf("com.game.play"),
            rootAvailable = true,
            availableCpuCount = 8,
            config = cfg
        )
        assertEquals(GameThreadStatusReason.SHOWING_HISTORY, fallback.statusReason)
        assertEquals("com.game.play", fallback.targetPackage)
        assertNull(fallback.pid)
        assertTrue(fallback.threads.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameThreadConfigStorageTest {

    @Test
    fun missingGameThreadFieldsLoadSafeDefaults() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfgFile = File(ctx.filesDir, "core_governor_config.json")
        try {
            cfgFile.writeText("""{"pollIntervalMs": 140}""")
            val loaded = ConfigStorage(ctx).loadConfig()
            assertEquals(140L, loaded.pollIntervalMs)
            assertFalse(loaded.gameThreadController.enabled)
            assertFalse(loaded.gameThreadController.autoClassify)
            assertEquals("", loaded.gameThreadController.selectedGamePackage)
            assertEquals("", loaded.gameThreadController.lastObservedGamePackage)
            assertTrue(loaded.gameThreadController.mappings.isEmpty())
            assertTrue(loaded.gameThreadController.threadHistory.isEmpty())
            assertEquals(GameThreadControllerConfig(), loaded.gameThreadController)
        } finally {
            cfgFile.delete()
        }
    }

    @Test
    fun configSaveAndLoadRoundTripPersistsAndValidatesGameThreadController() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfgFile = File(ctx.filesDir, "core_governor_config.json")
        try {
            cfgFile.delete()
            val storage = ConfigStorage(ctx)
            val expected = GameThreadControllerConfig(
                enabled = true,
                autoClassify = true,
                selectedGamePackage = "com.game.example",
                lastObservedGamePackage = "com.game.example",
                mappings = listOf(
                    ThreadAffinityMapping(
                        pattern = "UnityMain",
                        cpuMask = "c0",
                        exactMatch = true,
                        packageName = "com.game.example"
                    ),
                    ThreadAffinityMapping(
                        pattern = "Worker",
                        cpuMask = "38",
                        exactMatch = false,
                        packageName = ""
                    )
                ),
                threadHistory = mapOf(
                    "com.game.example" to GameThreadHistoryRecord(
                        packageName = "com.game.example",
                        displayName = "Example Game",
                        lastProcessName = "com.game.example",
                        lastObservedAtMs = 1_700_000_000_000L,
                        threads = listOf(
                            ThreadObservationEntry(
                                threadName = "UnityMain",
                                lastKnownTid = 4242,
                                lastObservedAtMs = 1_700_000_000_000L,
                                observationCount = 7
                            )
                        )
                    )
                )
            )
            storage.saveConfig(
                GovernorConfig(
                    gamePackages = setOf("com.game.example"),
                    gameThreadController = expected
                )
            )
            val rawJson = cfgFile.readText()
            assertTrue(rawJson.contains("gameThreadController"))
            assertTrue(rawJson.contains("threadMaskMap"))
            assertTrue(rawJson.contains("threadHistory"))

            val loaded = storage.loadConfig()
            assertEquals(expected, loaded.gameThreadController)
        } finally {
            cfgFile.delete()
        }
    }

    @Test
    fun legacyPatternMapAndMissingHistoryIn367ConfigLoadSafely() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfgFile = File(ctx.filesDir, "core_governor_config.json")
        try {
            cfgFile.writeText(
                """
                {
                  "gameThreadController": {
                    "enabled": true,
                    "autoClassify": false,
                    "selectedGamePackage": "com.game.v367",
                    "threadMaskMap": {
                      "UnityMain": "0xC0",
                      "BadZero": "0",
                      "Garbage": "not_hex"
                    }
                  }
                }
                """.trimIndent()
            )
            val loaded = ConfigStorage(ctx).loadConfig().gameThreadController
            assertTrue(loaded.enabled)
            assertFalse(loaded.autoClassify)
            assertEquals("com.game.v367", loaded.selectedGamePackage)
            assertEquals("", loaded.lastObservedGamePackage)
            assertTrue(loaded.threadHistory.isEmpty())
            assertEquals(1, loaded.mappings.size)
            assertEquals("UnityMain", loaded.mappings[0].pattern)
            assertEquals("c0", loaded.mappings[0].cpuMask)
            assertFalse(loaded.mappings[0].exactMatch)

            // Malformed/duplicate history entries collapse safely without throwing
            cfgFile.writeText(
                """
                {
                  "gameThreadController": {
                    "mappings": [
                      {"pattern": "", "cpuMask": "c0", "exactMatch": true},
                      {"pattern": "Valid", "cpuMask": "7", "exactMatch": true},
                      {"pattern": "Zero", "cpuMask": "0", "exactMatch": false}
                    ],
                    "threadHistory": {
                      "com.game.bad": {
                        "packageName": "com.game.bad",
                        "lastObservedAtMs": 5000,
                        "threads": [
                          {"threadName": "", "lastKnownTid": 10},
                          {"threadName": "RenderThread", "lastKnownTid": 200, "lastObservedAtMs": 1000, "observationCount": 1},
                          {"threadName": "RenderThread", "lastKnownTid": 300, "lastObservedAtMs": 5000, "observationCount": 2}
                        ]
                      }
                    }
                  }
                }
                """.trimIndent()
            )
            val sanitized = ConfigStorage(ctx).loadConfig().gameThreadController
            assertEquals(1, sanitized.mappings.size)
            assertEquals("Valid", sanitized.mappings[0].pattern)
            assertEquals(1, sanitized.threadHistory["com.game.bad"]!!.threads.size)
            assertEquals(300, sanitized.threadHistory["com.game.bad"]!!.threads[0].lastKnownTid)
        } finally {
            cfgFile.delete()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameThreadsUiHistoryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun threadHistoryShowsFallbackStatusLiveAndHistoricalAndCreatesMappingFromThreadName() {
        val upserted = mutableListOf<ThreadAffinityMapping>()
        val removed = mutableListOf<Pair<String, String>>()
        val clearedGames = mutableListOf<String>()
        var clearedAll = false

        val config = GovernorConfig(
            gamePackages = setOf("com.game.play"),
            gameThreadController = GameThreadControllerConfig(
                enabled = false,
                selectedGamePackage = "com.game.play",
                lastObservedGamePackage = "com.game.play",
                mappings = listOf(
                    ThreadAffinityMapping("UnityMain", "c0", exactMatch = true, packageName = "com.game.play")
                ),
                threadHistory = mapOf(
                    "com.game.play" to GameThreadHistoryRecord(
                        packageName = "com.game.play",
                        displayName = "Play Game",
                        lastProcessName = "com.game.play",
                        lastObservedAtMs = 1_700_000_000_000L,
                        threads = listOf(
                            ThreadObservationEntry("UnityMain", 9001, 1_700_000_000_000L, 2),
                            ThreadObservationEntry("OldAudioWorker", 9002, 1_699_999_000_000L, 1)
                        )
                    )
                )
            )
        )

        val inspection = GameThreadInspectionResult(
            rootAvailable = true,
            foregroundPackage = "com.aistudio.coregovernor.mt6789",
            targetPackage = "com.game.play",
            isSelectedGameForeground = false,
            pid = null,
            threads = emptyList(),
            statusReason = GameThreadStatusReason.SHOWING_HISTORY,
            availableCpuCount = 8,
            refreshedAtMs = 1_700_000_010_000L
        )

        rule.setContent {
            CoreGovernorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GameThreadsPanel(
                        config = config,
                        hardwareSpec = HardwareSpec(),
                        inspection = inspection,
                        isRefreshing = false,
                        installedApps = emptyList(),
                        onRefresh = {},
                        onSetEnabled = {},
                        onSetAutoClassify = {},
                        onSetSelectedGame = {},
                        onUpsertMappings = { upserted.addAll(it) },
                        onUpdateMapping = { _, _ -> },
                        onRemoveMapping = {},
                        onRemoveHistoricalThread = { pkg, name -> removed.add(pkg to name) },
                        onClearGameHistory = { clearedGames.add(it) },
                        onClearAllHistory = { clearedAll = true }
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("gtc_status_message")
            .performScrollTo()
            .assertTextContains("Game is not currently active or live threads are unavailable. Showing saved thread history.")
        rule.onNodeWithTag("gtc_thread_history_card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OldAudioWorker").assertIsDisplayed()

        // Selecting a historical thread populates pattern field with stored thread name (never TID 9002)
        rule.onNodeWithTag("gtc_history_use_1").performScrollTo().performClick()
        rule.onNodeWithTag("gtc_mode_exact").performScrollTo().performClick()
        rule.onNodeWithTag("gtc_save_mapping_button").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(1, upserted.size)
        assertEquals("OldAudioWorker", upserted[0].pattern)
        assertTrue(upserted[0].exactMatch)
        assertFalse(upserted[0].pattern.contains("9002"))

        // Remove individual historical entry
        rule.onNodeWithTag("gtc_history_remove_1").performScrollTo().performClick()
        assertEquals(listOf("com.game.play" to "OldAudioWorker"), removed)

        // Clear game history + clear all history confirmation dialogs
        rule.onNodeWithTag("gtc_clear_game_history_btn").performScrollTo().performClick()
        rule.onNodeWithTag("gtc_confirm_clear_game_btn").performClick()
        assertEquals(listOf("com.game.play"), clearedGames)

        rule.onNodeWithTag("gtc_clear_all_history_btn").performScrollTo().performClick()
        rule.onNodeWithTag("gtc_confirm_clear_all_btn").performClick()
        assertTrue(clearedAll)
    }
}
