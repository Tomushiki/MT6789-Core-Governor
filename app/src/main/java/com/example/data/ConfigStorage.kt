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

package com.example.data

import android.content.Context
import android.net.Uri
import com.example.model.EfficiencyMode
import com.example.model.ExplorationRate
import com.example.model.FreqRange
import com.example.model.GovernorConfig
import com.example.model.HardwareSpec
import com.example.model.ModeOverride
import com.example.model.ModeProfile
import com.example.model.PowerPolarity
import com.example.model.BoostStrength
import com.example.model.AfterburnConfig
import com.example.model.PowerThrottleConfig
import com.example.model.PowerUnitScale
import com.example.model.PresetItem
import com.example.model.SmartAppMode
import com.example.model.StutterHunterConfig
import com.example.model.StutterHunterMode
import com.example.model.TempThrottleConfig
import com.example.model.ThrottleCurveShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OriginalCpuBackup(
    val policyPath: String,
    val governor: String,
    val minFreqKhz: Long,
    val maxFreqKhz: Long
)

data class OriginalHardwareBackup(
    val cpus: List<OriginalCpuBackup>,
    val fixOppPath: String?
)

class ConfigStorage(private val context: Context) {

    private val configFile = File(context.filesDir, "core_governor_config.json")
    private val presetsFile = File(context.filesDir, "core_governor_presets.json")
    private val backupFile = File(context.filesDir, "original_hardware_backup.json")
    private val perfPinFile = File(context.filesDir, "performance_pin_policies.json")

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configWriteLock = Any()
    private var configSaveJob: Job? = null
    private var pendingConfig: GovernorConfig? = null

    fun loadConfig(): GovernorConfig {
        if (!configFile.exists()) return GovernorConfig()
        return try {
            val obj = JSONObject(configFile.readText())
            jsonToConfig(obj)
        } catch (_: Throwable) {
            GovernorConfig()
        }
    }

    fun saveConfig(config: GovernorConfig) {
        synchronized(configWriteLock) {
            configSaveJob?.cancel()
            configSaveJob = null
            pendingConfig = null
            writeConfigAtomically(config)
        }
    }

    fun saveConfigDebounced(config: GovernorConfig, delayMs: Long = 400L) {
        synchronized(configWriteLock) {
            pendingConfig = config
            configSaveJob?.cancel()
            configSaveJob = configScope.launch {
                delay(delayMs)
                synchronized(configWriteLock) {
                    val toWrite = pendingConfig ?: return@synchronized
                    pendingConfig = null
                    configSaveJob = null
                    writeConfigAtomically(toWrite)
                }
            }
        }
    }

    fun flush() {
        synchronized(configWriteLock) {
            configSaveJob?.cancel()
            configSaveJob = null
            val toWrite = pendingConfig ?: return
            pendingConfig = null
            writeConfigAtomically(toWrite)
        }
    }

    private fun writeConfigAtomically(config: GovernorConfig) {
        val tmpFile = File(context.filesDir, "core_governor_config.json.tmp")
        try {
            tmpFile.writeText(configToJson(config).toString(2))
            if (!tmpFile.renameTo(configFile)) {
                configFile.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        } catch (_: Throwable) {
            try {
                if (tmpFile.exists()) tmpFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun loadPresets(hw: HardwareSpec): List<PresetItem> {
        val defaults = buildBuiltInPresets(hw)
        if (!presetsFile.exists()) return defaults
        return try {
            val arr = JSONArray(presetsFile.readText())
            val userPresets = mutableListOf<PresetItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                userPresets.add(
                    PresetItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        builtIn = obj.optBoolean("builtIn", false),
                        config = jsonToConfig(obj.getJSONObject("config"))
                    )
                )
            }
            defaults + userPresets.filter { !it.builtIn }
        } catch (_: Throwable) {
            defaults
        }
    }

    fun saveUserPresets(presets: List<PresetItem>) {
        val arr = JSONArray()
        presets.filter { !it.builtIn }.forEach { preset ->
            arr.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("builtIn", false)
                    put("config", configToJson(preset.config))
                }
            )
        }
        try {
            presetsFile.writeText(arr.toString(2))
        } catch (_: Throwable) {
        }
    }

    fun saveOriginalHardwareBackup(backup: OriginalHardwareBackup) {
        if (backupFile.exists()) return
        val obj = JSONObject().apply {
            val cpuArr = JSONArray()
            backup.cpus.forEach { cpu ->
                cpuArr.put(JSONObject().apply {
                    put("policyPath", cpu.policyPath)
                    put("governor", cpu.governor)
                    put("minFreqKhz", cpu.minFreqKhz)
                    put("maxFreqKhz", cpu.maxFreqKhz)
                })
            }
            put("cpus", cpuArr)
            put("fixOppPath", backup.fixOppPath ?: "")
        }
        try {
            backupFile.writeText(obj.toString(2))
        } catch (_: Throwable) {
        }
    }

    fun loadOriginalHardwareBackup(): OriginalHardwareBackup? {
        if (!backupFile.exists()) return null
        return try {
            val obj = JSONObject(backupFile.readText())
            val arr = obj.getJSONArray("cpus")
            val cpus = buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    add(
                        OriginalCpuBackup(
                            policyPath = c.getString("policyPath"),
                            governor = c.getString("governor"),
                            minFreqKhz = c.getLong("minFreqKhz"),
                            maxFreqKhz = c.getLong("maxFreqKhz")
                        )
                    )
                }
            }
            OriginalHardwareBackup(
                cpus = cpus,
                fixOppPath = obj.optString("fixOppPath").ifBlank { null }
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clearOriginalHardwareBackup() {
        try {
            if (backupFile.exists()) backupFile.delete()
        } catch (_: Throwable) {
        }
    }

    fun loadPerformancePinPolicies(): Set<Int> {
        if (!perfPinFile.exists()) return emptySet()
        return try {
            val arr = JSONArray(perfPinFile.readText())
            buildSet {
                for (i in 0 until arr.length()) {
                    add(arr.getInt(i))
                }
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun setPerformancePinPolicy(policyId: Int, enabled: Boolean) {
        val current = loadPerformancePinPolicies().toMutableSet()
        val changed = if (enabled) current.add(policyId) else current.remove(policyId)
        if (!changed) return
        try {
            val arr = JSONArray()
            current.sorted().forEach { arr.put(it) }
            perfPinFile.writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    fun exportToUri(uri: Uri, config: GovernorConfig, presets: List<PresetItem>): Boolean {
        return try {
            val root = JSONObject().apply {
                put("schemaVersion", 2)
                put("exportedAtMs", System.currentTimeMillis())
                put("activeConfig", configToJson(config))
                val arr = JSONArray()
                presets.filter { !it.builtIn }.forEach { preset ->
                    arr.put(JSONObject().apply {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("config", configToJson(preset.config))
                    })
                }
                put("customPresets", arr)
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun importFromUri(uri: Uri): Pair<GovernorConfig, List<PresetItem>>? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return null
            val root = JSONObject(text)
            val cfgObj = if (root.has("activeConfig")) root.getJSONObject("activeConfig") else root
            val importedConfig = jsonToConfig(cfgObj)
            val importedPresets = mutableListOf<PresetItem>()
            if (root.has("customPresets")) {
                val arr = root.getJSONArray("customPresets")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    importedPresets.add(
                        PresetItem(
                            id = p.optString("id", "imp_${System.currentTimeMillis()}_$i"),
                            name = p.optString("name", "Imported Preset ${i + 1}"),
                            builtIn = false,
                            config = jsonToConfig(p.getJSONObject("config"))
                        )
                    )
                }
            }
            importedConfig to importedPresets
        } catch (_: Throwable) {
            null
        }
    }

    fun populateDefaultsForHardware(current: GovernorConfig, hw: HardwareSpec): GovernorConfig {
        var updated = current
        if (updated.selectedTempSensorPath.isBlank() && hw.thermalSensors.isNotEmpty()) {
            val preferred = hw.thermalSensors.firstOrNull { it.category == "Battery" }
                ?: hw.thermalSensors.firstOrNull { it.category == "SoC" }
                ?: hw.thermalSensors.first()
            updated = updated.copy(selectedTempSensorPath = preferred.path)
        }

        val normalCpu = updated.normalMode.cpuRangesKhz.toMutableMap()
        val gameCpu = updated.gameMode.cpuRangesKhz.toMutableMap()

        for (policy in hw.cpuPolicies) {
            val freqs = policy.availableFreqsKhz
            if (freqs.isEmpty()) continue
            val minF = freqs.first()
            val maxF = freqs.last()
            if (!normalCpu.containsKey(policy.policyId)) {
                val modIdx = ((freqs.size - 1) * 0.78f).toInt().coerceIn(0, freqs.lastIndex)
                normalCpu[policy.policyId] = FreqRange(minF, freqs[modIdx])
            }
            if (!gameCpu.containsKey(policy.policyId)) {
                val gameMinIdx = ((freqs.size - 1) * 0.25f).toInt().coerceIn(0, freqs.lastIndex)
                gameCpu[policy.policyId] = FreqRange(freqs[gameMinIdx], maxF)
            }
        }

        val gpuFreqs = hw.gpu.availableFreqsKhz
        val normalGpu = updated.normalMode.gpuRangeKhz ?: if (gpuFreqs.isNotEmpty()) {
            val modIdx = ((gpuFreqs.size - 1) * 0.75f).toInt().coerceIn(0, gpuFreqs.lastIndex)
            FreqRange(gpuFreqs.first(), gpuFreqs[modIdx])
        } else null

        val gameGpu = updated.gameMode.gpuRangeKhz ?: if (gpuFreqs.isNotEmpty()) {
            val minIdx = ((gpuFreqs.size - 1) * 0.25f).toInt().coerceIn(0, gpuFreqs.lastIndex)
            FreqRange(gpuFreqs[minIdx], gpuFreqs.last())
        } else null

        return updated.copy(
            normalMode = updated.normalMode.copy(cpuRangesKhz = normalCpu, gpuRangeKhz = normalGpu),
            gameMode = updated.gameMode.copy(cpuRangesKhz = gameCpu, gpuRangeKhz = gameGpu)
        )
    }

    private fun buildBuiltInPresets(hw: HardwareSpec): List<PresetItem> {
        val base = populateDefaultsForHardware(GovernorConfig(), hw)

        val coolQuietCpuNormal = hw.cpuPolicies.associate { p ->
            val freqs = p.availableFreqsKhz
            val maxIdx = ((freqs.size - 1) * 0.65f).toInt().coerceIn(0, freqs.lastIndex.coerceAtLeast(0))
            p.policyId to FreqRange(freqs.firstOrNull() ?: 0L, freqs.getOrElse(maxIdx) { 0L })
        }
        val coolQuietGpu = hw.gpu.availableFreqsKhz.let { freqs ->
            if (freqs.isEmpty()) null else FreqRange(freqs.first(), freqs[((freqs.size - 1) * 0.60f).toInt()])
        }

        val gamingCpu = hw.cpuPolicies.associate { p ->
            val freqs = p.availableFreqsKhz
            val minIdx = ((freqs.size - 1) * 0.35f).toInt().coerceIn(0, freqs.lastIndex.coerceAtLeast(0))
            p.policyId to FreqRange(freqs.getOrElse(minIdx) { 0L }, freqs.lastOrNull() ?: 0L)
        }
        val gamingGpu = hw.gpu.availableFreqsKhz.let { freqs ->
            if (freqs.isEmpty()) null else FreqRange(freqs[((freqs.size - 1) * 0.30f).toInt()], freqs.last())
        }

        return listOf(
            PresetItem(
                id = "builtin_balanced",
                name = "Balanced",
                builtIn = true,
                config = base
            ),
            PresetItem(
                id = "builtin_cool",
                name = "Cool & Efficient",
                builtIn = true,
                config = base.copy(
                    pollIntervalMs = 180L,
                    normalMode = base.normalMode.copy(
                        cpuRangesKhz = coolQuietCpuNormal,
                        gpuRangeKhz = coolQuietGpu,
                        headroomPct = 6f,
                        gpuHeadroomPct = 8f,
                        launchBurstDurationMs = 1000L
                    ),
                    tempThrottle = base.tempThrottle.copy(
                        startTempC = 39f,
                        fullTempC = 50f,
                        curveShape = ThrottleCurveShape.AGGRESSIVE
                    ),
                    powerThrottle = base.powerThrottle.copy(limitWatts = 4.8f, sustainMs = 1500L)
                )
            ),
            PresetItem(
                id = "builtin_gaming",
                name = "Sustained Gaming",
                builtIn = true,
                config = base.copy(
                    pollIntervalMs = 120L,
                    gameMode = base.gameMode.copy(
                        cpuRangesKhz = gamingCpu,
                        gpuRangeKhz = gamingGpu,
                        headroomPct = 28f,
                        rampUpSpeed = 0.95f,
                        rampDownSpeed = 0.03f,
                        gpuHeadroomPct = 25f,
                        gpuRampUpSpeed = 0.95f,
                        gpuRampDownSpeed = 0.04f
                    ),
                    tempThrottle = base.tempThrottle.copy(
                        startTempC = 43f,
                        fullTempC = 58f,
                        curveShape = ThrottleCurveShape.GENTLE,
                        cpuMaxReductionPct = 45f,
                        gpuMaxReductionPct = 55f
                    ),
                    powerThrottle = base.powerThrottle.copy(limitWatts = 7.0f, sustainMs = 2500L, stepPct = 2.5f)
                )
            )
        ) + deviceBuiltInPresets(hw, base)
    }

    /**
     * Device-specific built-ins, listed only on matching hardware. Their [PresetItem.config] is not used
     * for loading: the engine applies them as a patch on the current config (see GovernorEngine.loadPreset).
     */
    private fun deviceBuiltInPresets(hw: HardwareSpec, base: GovernorConfig): List<PresetItem> =
        if (com.example.governor.D8300UserPreset.matches(hw)) {
            listOf(
                PresetItem(
                    id = com.example.governor.D8300UserPreset.ID,
                    name = com.example.governor.D8300UserPreset.NAME,
                    builtIn = true,
                    config = base
                )
            )
        } else emptyList()

    private fun configToJson(c: GovernorConfig): JSONObject {
        return JSONObject().apply {
            put("pollIntervalMs", c.pollIntervalMs)
            put("adaptivePollingEnabled", c.adaptivePollingEnabled)
            put("startOnBoot", c.startOnBoot)
            put("protectFromKillEnabled", c.protectFromKillEnabled)
            put("cpuLoadUsesBusiestCore", c.cpuLoadUsesBusiestCore)
            put("screenOffSavingEnabled", c.screenOffSavingEnabled)
            put("screenOffDelaySec", c.screenOffDelaySec)
            put("screenOffExemptMusic", c.screenOffExemptMusic)
            put("screenOffExemptCharging", c.screenOffExemptCharging)
            put("overlayEnabled", c.overlayEnabled)
            put("overlayClickThrough", c.overlayClickThrough)
            put("overlayGameOnly", c.overlayGameOnly)
            put("overlayExpanded", c.overlayExpanded && !c.overlayDetailed)
            put("overlayDetailed", c.overlayDetailed)
            put("overlayOpacityPct", c.overlayOpacityPct)
            put("overlayBgOpacityPct", c.overlayBgOpacityPct)
            put("overlayCompactScalePct", c.overlayCompactScalePct)
            put("overlayExpandedScalePct", c.overlayExpandedScalePct)
            put("overlayCompactScalePctL", c.overlayCompactScalePctL)
            put("overlayExpandedScalePctL", c.overlayExpandedScalePctL)
            put("overlayCompactXPm", c.overlayCompactXPm)
            put("overlayCompactYPm", c.overlayCompactYPm)
            put("overlayExpandedXPm", c.overlayExpandedXPm)
            put("overlayExpandedYPm", c.overlayExpandedYPm)
            put("overlayDetailedXPm", c.overlayDetailedXPm)
            put("overlayDetailedYPm", c.overlayDetailedYPm)
            put("overlayCompactXPmL", c.overlayCompactXPmL)
            put("overlayCompactYPmL", c.overlayCompactYPmL)
            put("overlayExpandedXPmL", c.overlayExpandedXPmL)
            put("overlayExpandedYPmL", c.overlayExpandedYPmL)
            put("overlayDetailedXPmL", c.overlayDetailedXPmL)
            put("overlayDetailedYPmL", c.overlayDetailedYPmL)
            put("overlayX", c.overlayX)
            put("overlayY", c.overlayY)
            put("ramPinMax", c.ramPinMax)
            put("ramPinGameOnly", c.ramPinGameOnly)
            put("safetyFloorPct", c.safetyFloorPct.toDouble())
            put("selectedTempSensorPath", c.selectedTempSensorPath)
            put("modeOverride", c.modeOverride.name)
            put("normalMode", modeToJson(c.normalMode))
            put("gameMode", modeToJson(c.gameMode))
            put("tempThrottle", JSONObject().apply {
                put("enabled", c.tempThrottle.enabled)
                put("startTempC", c.tempThrottle.startTempC.toDouble())
                put("fullTempC", c.tempThrottle.fullTempC.toDouble())
                put("cpuMaxReductionPct", c.tempThrottle.cpuMaxReductionPct.toDouble())
                put("gpuMaxReductionPct", c.tempThrottle.gpuMaxReductionPct.toDouble())
                put("curveShape", c.tempThrottle.curveShape.name)
                put("reactionSpeed", c.tempThrottle.reactionSpeed.toDouble())
                put("hysteresisC", c.tempThrottle.hysteresisC.toDouble())
                put("chargingAware", c.tempThrottle.chargingAware)
                put("chargingStartOffsetC", c.tempThrottle.chargingStartOffsetC.toDouble())
            })
            put("powerThrottle", JSONObject().apply {
                put("enabled", c.powerThrottle.enabled)
                put("limitWatts", c.powerThrottle.limitWatts.toDouble())
                put("sustainMs", c.powerThrottle.sustainMs)
                put("stepPct", c.powerThrottle.stepPct.toDouble())
                put("releaseStepPct", c.powerThrottle.releaseStepPct.toDouble())
                put("averagingSamples", c.powerThrottle.averagingSamples)
                put("unitScale", c.powerThrottle.unitScale.name)
                put("polarity", c.powerThrottle.polarity.name)
                put("componentAware", c.powerThrottle.componentAware)
            })
            put("powerModelLearningEnabled", c.powerModelLearningEnabled)
            put("efficiencyLearningEnabled", c.efficiencyLearningEnabled)
            put("efficiencyMode", c.efficiencyMode.name)
            put("minConfidencePct", c.minConfidencePct)
            put("explorationRate", c.explorationRate.name)
            put("batteryEstimationEnabled", c.batteryEstimationEnabled)
            put("batteryDesignCapacityOverrideMah", c.batteryDesignCapacityOverrideMah)
            val gamesArr = JSONArray()
            c.gamePackages.sorted().forEach { gamesArr.put(it) }
            put("gamePackages", gamesArr)
            put("smartAppProfilesEnabled", c.smartAppProfilesEnabled)
            put("smartAppMode", c.smartAppMode.name)
            put("adaptiveRefreshEnabled", c.adaptiveRefreshEnabled)
            put("adaptiveIdleHz", c.adaptiveIdleHz)
            put("adaptiveActiveHz", c.adaptiveActiveHz)
            put("adaptiveIdleDelayMs", c.adaptiveIdleDelayMs)
            put("screenOffAnalysisEnabled", c.screenOffAnalysisEnabled)
            put("afterburn", JSONObject().apply {
                put("enabled", c.afterburn.enabled)
                put("strength", c.afterburn.strength.name)
                put("thermalConditionEnabled", c.afterburn.thermalConditionEnabled)
                put("thermalMarginC", c.afterburn.thermalMarginC.toDouble())
                put("batteryConditionEnabled", c.afterburn.batteryConditionEnabled)
                put("batteryThresholdPct", c.afterburn.batteryThresholdPct)
                put("voltageSagEnabled", c.afterburn.voltageSagEnabled)
                put("toast", c.afterburn.toastEnabled)
            })
            val boostArr = JSONArray()
            c.boostPackages.sorted().forEach { boostArr.put(it) }
            put("boostPackages", boostArr)
            put("stutterHunter", JSONObject().apply {
                put("enabled", c.stutterHunter.enabled)
                put("mode", c.stutterHunter.mode.name)
                put("dipPct", c.stutterHunter.dipPct)
                put("cpuThresholdPct", c.stutterHunter.cpuThresholdPct)
                val wlArr = JSONArray()
                c.stutterHunter.whitelist.sorted().forEach { wlArr.put(it) }
                put("whitelist", wlArr)
            })
        }
    }

    private fun modeToJson(m: ModeProfile): JSONObject {
        return JSONObject().apply {
            val cpuObj = JSONObject()
            m.cpuRangesKhz.forEach { (id, range) ->
                cpuObj.put(id.toString(), JSONObject().apply {
                    put("minKhz", range.minKhz)
                    put("maxKhz", range.maxKhz)
                })
            }
            put("cpuRangesKhz", cpuObj)
            m.gpuRangeKhz?.let { g ->
                put("gpuRangeKhz", JSONObject().apply {
                    put("minKhz", g.minKhz)
                    put("maxKhz", g.maxKhz)
                })
            }
            put("headroomPct", m.headroomPct.toDouble())
            put("rampUpSpeed", m.rampUpSpeed.toDouble())
            put("rampDownSpeed", m.rampDownSpeed.toDouble())
            put("gpuHeadroomPct", m.gpuHeadroomPct.toDouble())
            put("gpuRampUpSpeed", m.gpuRampUpSpeed.toDouble())
            put("gpuRampDownSpeed", m.gpuRampDownSpeed.toDouble())
            put("launchBurstEnabled", m.launchBurstEnabled)
            put("launchBurstDurationMs", m.launchBurstDurationMs)
            put("launchSpikeThresholdPct", m.launchSpikeThresholdPct.toDouble())
        }
    }

    private fun jsonToConfig(obj: JSONObject): GovernorConfig {
        val default = GovernorConfig()
        val games = mutableSetOf<String>()
        val gamesArr = obj.optJSONArray("gamePackages")
        if (gamesArr != null) {
            for (i in 0 until gamesArr.length()) {
                games.add(gamesArr.getString(i))
            }
        }
        val boostPkgs = mutableSetOf<String>()
        val boostArr = obj.optJSONArray("boostPackages")
        if (boostArr != null) {
            for (i in 0 until boostArr.length()) boostPkgs.add(boostArr.getString(i))
        }
        val ibObj = obj.optJSONObject("afterburn")
        val shObj = obj.optJSONObject("stutterHunter")
        val tObj = obj.optJSONObject("tempThrottle")
        val pObj = obj.optJSONObject("powerThrottle")

        val loadedDetailed = obj.optBoolean("overlayDetailed", default.overlayDetailed)
        val loadedExpanded = if (loadedDetailed) false else obj.optBoolean("overlayExpanded", default.overlayExpanded)

        return GovernorConfig(
            pollIntervalMs = obj.optLong("pollIntervalMs", default.pollIntervalMs).coerceIn(50L, 1000L),
            adaptivePollingEnabled = obj.optBoolean("adaptivePollingEnabled", default.adaptivePollingEnabled),
            startOnBoot = obj.optBoolean("startOnBoot", default.startOnBoot),
            protectFromKillEnabled = obj.optBoolean("protectFromKillEnabled", default.protectFromKillEnabled),
            cpuLoadUsesBusiestCore = obj.optBoolean("cpuLoadUsesBusiestCore", default.cpuLoadUsesBusiestCore),
            screenOffSavingEnabled = obj.optBoolean("screenOffSavingEnabled", default.screenOffSavingEnabled),
            screenOffDelaySec = obj.optInt("screenOffDelaySec", default.screenOffDelaySec).coerceIn(0, 60),
            screenOffExemptMusic = obj.optBoolean("screenOffExemptMusic", default.screenOffExemptMusic),
            screenOffExemptCharging = obj.optBoolean("screenOffExemptCharging", default.screenOffExemptCharging),
            overlayEnabled = obj.optBoolean("overlayEnabled", default.overlayEnabled),
            overlayClickThrough = obj.optBoolean("overlayClickThrough", default.overlayClickThrough),
            overlayGameOnly = obj.optBoolean("overlayGameOnly", default.overlayGameOnly),
            overlayExpanded = loadedExpanded,
            overlayDetailed = loadedDetailed,
            overlayOpacityPct = obj.optInt("overlayOpacityPct", default.overlayOpacityPct).coerceIn(30, 100),
            overlayBgOpacityPct = obj.optInt("overlayBgOpacityPct", default.overlayBgOpacityPct).coerceIn(0, 100),
            overlayCompactScalePct = obj.optInt("overlayCompactScalePct", default.overlayCompactScalePct).coerceIn(25, 200),
            overlayExpandedScalePct = obj.optInt("overlayExpandedScalePct", default.overlayExpandedScalePct).coerceIn(25, 200),
            overlayCompactScalePctL = obj.optInt("overlayCompactScalePctL", default.overlayCompactScalePctL).let {
                if (it < 0) -1 else it.coerceIn(25, 200)
            },
            overlayExpandedScalePctL = obj.optInt("overlayExpandedScalePctL", default.overlayExpandedScalePctL).let {
                if (it < 0) -1 else it.coerceIn(25, 200)
            },
            overlayCompactXPm = obj.optInt("overlayCompactXPm", default.overlayCompactXPm).coerceIn(0, 1000),
            overlayCompactYPm = obj.optInt("overlayCompactYPm", default.overlayCompactYPm).coerceIn(0, 1000),
            overlayExpandedXPm = obj.optInt("overlayExpandedXPm", default.overlayExpandedXPm).coerceIn(0, 1000),
            overlayExpandedYPm = obj.optInt("overlayExpandedYPm", default.overlayExpandedYPm).coerceIn(0, 1000),
            overlayDetailedXPm = obj.optInt("overlayDetailedXPm", default.overlayDetailedXPm).coerceIn(0, 1000),
            overlayDetailedYPm = obj.optInt("overlayDetailedYPm", default.overlayDetailedYPm).coerceIn(0, 1000),
            overlayCompactXPmL = obj.optInt("overlayCompactXPmL", obj.optInt("overlayCompactXPm", default.overlayCompactXPmL)).coerceIn(0, 1000),
            overlayCompactYPmL = obj.optInt("overlayCompactYPmL", obj.optInt("overlayCompactYPm", default.overlayCompactYPmL)).coerceIn(0, 1000),
            overlayExpandedXPmL = obj.optInt("overlayExpandedXPmL", obj.optInt("overlayExpandedXPm", default.overlayExpandedXPmL)).coerceIn(0, 1000),
            overlayExpandedYPmL = obj.optInt("overlayExpandedYPmL", obj.optInt("overlayExpandedYPm", default.overlayExpandedYPmL)).coerceIn(0, 1000),
            overlayDetailedXPmL = obj.optInt("overlayDetailedXPmL", obj.optInt("overlayDetailedXPm", default.overlayDetailedXPmL)).coerceIn(0, 1000),
            overlayDetailedYPmL = obj.optInt("overlayDetailedYPmL", obj.optInt("overlayDetailedYPm", default.overlayDetailedYPmL)).coerceIn(0, 1000),
            overlayX = obj.optInt("overlayX", default.overlayX),
            overlayY = obj.optInt("overlayY", default.overlayY),
            ramPinMax = obj.optBoolean("ramPinMax", default.ramPinMax),
            ramPinGameOnly = obj.optBoolean("ramPinGameOnly", default.ramPinGameOnly),
            safetyFloorPct = obj.optDouble("safetyFloorPct", default.safetyFloorPct.toDouble()).toFloat().coerceIn(5f, 95f),
            selectedTempSensorPath = obj.optString("selectedTempSensorPath", ""),
            modeOverride = enumOrDefault(obj.optString("modeOverride"), ModeOverride.AUTO),
            normalMode = obj.optJSONObject("normalMode")?.let { jsonToMode(it, default.normalMode) } ?: default.normalMode,
            gameMode = obj.optJSONObject("gameMode")?.let { jsonToMode(it, default.gameMode) } ?: default.gameMode,
            tempThrottle = if (tObj != null) TempThrottleConfig(
                enabled = tObj.optBoolean("enabled", true),
                startTempC = tObj.optDouble("startTempC", 42.0).toFloat(),
                fullTempC = tObj.optDouble("fullTempC", 56.0).toFloat(),
                cpuMaxReductionPct = tObj.optDouble("cpuMaxReductionPct", 55.0).toFloat(),
                gpuMaxReductionPct = tObj.optDouble("gpuMaxReductionPct", 65.0).toFloat(),
                curveShape = enumOrDefault(tObj.optString("curveShape"), ThrottleCurveShape.LINEAR),
                reactionSpeed = tObj.optDouble("reactionSpeed", 0.35).toFloat(),
                hysteresisC = tObj.optDouble("hysteresisC", 2.0).toFloat(),
                chargingAware = tObj.optBoolean("chargingAware", false),
                chargingStartOffsetC = tObj.optDouble("chargingStartOffsetC", 2.0).toFloat().coerceIn(0f, 5f)
            ) else default.tempThrottle,
            powerThrottle = if (pObj != null) PowerThrottleConfig(
                enabled = pObj.optBoolean("enabled", true),
                limitWatts = pObj.optDouble("limitWatts", 6.0).toFloat(),
                sustainMs = pObj.optLong("sustainMs", 2000L),
                stepPct = pObj.optDouble("stepPct", 3.0).toFloat(),
                releaseStepPct = pObj.optDouble("releaseStepPct", 1.0).toFloat(),
                averagingSamples = pObj.optInt("averagingSamples", 10).coerceIn(2, 40),
                unitScale = enumOrDefault(pObj.optString("unitScale"), PowerUnitScale.AUTO),
                polarity = enumOrDefault(pObj.optString("polarity"), PowerPolarity.AUTO_ABS),
                componentAware = pObj.optBoolean("componentAware", true)
            ) else default.powerThrottle,
            powerModelLearningEnabled = obj.optBoolean("powerModelLearningEnabled", default.powerModelLearningEnabled),
            efficiencyLearningEnabled = obj.optBoolean("efficiencyLearningEnabled", default.efficiencyLearningEnabled),
            efficiencyMode = enumOrDefault(obj.optString("efficiencyMode"), default.efficiencyMode),
            minConfidencePct = obj.optInt("minConfidencePct", default.minConfidencePct).coerceIn(20, 95),
            explorationRate = enumOrDefault(obj.optString("explorationRate"), default.explorationRate),
            batteryEstimationEnabled = obj.optBoolean("batteryEstimationEnabled", default.batteryEstimationEnabled),
            batteryDesignCapacityOverrideMah = obj.optInt("batteryDesignCapacityOverrideMah", default.batteryDesignCapacityOverrideMah).coerceAtLeast(0),
            gamePackages = games,
            smartAppProfilesEnabled = obj.optBoolean("smartAppProfilesEnabled", default.smartAppProfilesEnabled),
            smartAppMode = enumOrDefault(obj.optString("smartAppMode"), default.smartAppMode),
            adaptiveRefreshEnabled = obj.optBoolean("adaptiveRefreshEnabled", default.adaptiveRefreshEnabled),
            adaptiveIdleHz = obj.optInt("adaptiveIdleHz", default.adaptiveIdleHz).coerceIn(0, 360),
            adaptiveActiveHz = obj.optInt("adaptiveActiveHz", default.adaptiveActiveHz).coerceIn(0, 360),
            adaptiveIdleDelayMs = obj.optInt("adaptiveIdleDelayMs", default.adaptiveIdleDelayMs).coerceIn(500, 5000),
            screenOffAnalysisEnabled = obj.optBoolean("screenOffAnalysisEnabled", default.screenOffAnalysisEnabled),
            afterburn = if (ibObj != null) AfterburnConfig(
                enabled = ibObj.optBoolean("enabled", false),
                strength = enumOrDefault(ibObj.optString("strength"), BoostStrength.MEDIUM),
                thermalConditionEnabled = ibObj.optBoolean("thermalConditionEnabled", true),
                thermalMarginC = ibObj.optDouble("thermalMarginC", 5.0).toFloat().coerceIn(3f, 8f),
                batteryConditionEnabled = ibObj.optBoolean("batteryConditionEnabled", true),
                batteryThresholdPct = ibObj.optInt("batteryThresholdPct", 40).coerceIn(10, 90),
                voltageSagEnabled = ibObj.optBoolean("voltageSagEnabled", true),
                toastEnabled = ibObj.optBoolean("toast", true)
            ) else default.afterburn,
            boostPackages = boostPkgs,
            stutterHunter = if (shObj != null) {
                val wl = mutableSetOf<String>()
                val wlArr = shObj.optJSONArray("whitelist")
                if (wlArr != null) {
                    for (i in 0 until wlArr.length()) {
                        val pkg = wlArr.optString(i).trim()
                        if (pkg.isNotEmpty()) wl.add(pkg)
                    }
                }
                StutterHunterConfig(
                    enabled = shObj.optBoolean("enabled", false),
                    mode = enumOrDefault(shObj.optString("mode"), StutterHunterMode.NOTIFY),
                    dipPct = shObj.optInt("dipPct", 75).coerceIn(60, 90),
                    cpuThresholdPct = shObj.optInt("cpuThresholdPct", 15).coerceIn(10, 40),
                    whitelist = wl
                )
            } else default.stutterHunter
        )
    }

    private fun jsonToMode(obj: JSONObject, fallback: ModeProfile): ModeProfile {
        val cpuMap = mutableMapOf<Int, FreqRange>()
        val cpuObj = obj.optJSONObject("cpuRangesKhz")
        if (cpuObj != null) {
            cpuObj.keys().forEach { key ->
                val id = key.toIntOrNull() ?: return@forEach
                val r = cpuObj.getJSONObject(key)
                cpuMap[id] = FreqRange(r.getLong("minKhz"), r.getLong("maxKhz"))
            }
        }
        val gpuRange = obj.optJSONObject("gpuRangeKhz")?.let {
            FreqRange(it.getLong("minKhz"), it.getLong("maxKhz"))
        }
        return ModeProfile(
            cpuRangesKhz = cpuMap,
            gpuRangeKhz = gpuRange,
            headroomPct = obj.optDouble("headroomPct", fallback.headroomPct.toDouble()).toFloat(),
            rampUpSpeed = obj.optDouble("rampUpSpeed", fallback.rampUpSpeed.toDouble()).toFloat(),
            rampDownSpeed = obj.optDouble("rampDownSpeed", fallback.rampDownSpeed.toDouble()).toFloat(),
            gpuHeadroomPct = obj.optDouble("gpuHeadroomPct", fallback.gpuHeadroomPct.toDouble()).toFloat(),
            gpuRampUpSpeed = obj.optDouble("gpuRampUpSpeed", fallback.gpuRampUpSpeed.toDouble()).toFloat(),
            gpuRampDownSpeed = obj.optDouble("gpuRampDownSpeed", fallback.gpuRampDownSpeed.toDouble()).toFloat(),
            launchBurstEnabled = obj.optBoolean("launchBurstEnabled", fallback.launchBurstEnabled),
            launchBurstDurationMs = obj.optLong("launchBurstDurationMs", fallback.launchBurstDurationMs),
            launchSpikeThresholdPct = obj.optDouble("launchSpikeThresholdPct", fallback.launchSpikeThresholdPct.toDouble()).toFloat()
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T {
        return try {
            enumValueOf<T>(name)
        } catch (_: Throwable) {
            default
        }
    }
}
