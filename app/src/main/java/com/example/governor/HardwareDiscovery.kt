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

import com.example.model.BatteryPowerSpec
import com.example.model.CpuPolicySpec
import com.example.model.DdrSpec
import com.example.model.GpuSpec
import com.example.model.HardwareSpec
import com.example.model.ThermalSensorSpec
import com.example.root.RootShell

class HardwareDiscovery(private val rootShell: RootShell) {

    suspend fun discover(): HardwareSpec {
        val notes = mutableListOf<String>()
        val rootOk = rootShell.ensureSession()
        if (!rootOk) {
            notes.add("Root access (su) not granted or unavailable.")
            return HardwareSpec(
                discoveredAtMs = System.currentTimeMillis(),
                rootGranted = false,
                discoveryNotes = notes
            )
        }

        val cpuPolicies = discoverCpuPolicies(notes)
        val gpuSpec = discoverGpu(notes)
        val thermalSensors = discoverThermalSensors(notes)
        val batteryPower = discoverBatteryPower(notes)
        val ddrSpec = discoverDdr(notes)

        return HardwareSpec(
            discoveredAtMs = System.currentTimeMillis(),
            rootGranted = true,
            cpuPolicies = cpuPolicies,
            gpu = gpuSpec,
            thermalSensors = thermalSensors,
            batteryPower = batteryPower,
            discoveryNotes = notes,
            ddr = ddrSpec
        )
    }

    private suspend fun discoverDdr(notes: MutableList<String>): DdrSpec? {
        val lsRes = rootShell.exec("ls /sys/class/devfreq 2>/dev/null")
        val entries = lsRes.stdout.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (!lsRes.isSuccess || entries.isEmpty()) {
            notes.add("Memory (DDR) devfreq node not available.")
            return null
        }

        val keywords = listOf("dvfsrc", "ddr", "dmc", "emi", "mif")
        var matchedDir: String? = null
        for (kw in keywords) {
            val candidates = entries.filter { it.contains(kw, ignoreCase = true) }
            for (name in candidates) {
                val basePath = "/sys/class/devfreq/$name"
                val curPath = "$basePath/cur_freq"
                val minPath = "$basePath/min_freq"
                val availPath = "$basePath/available_frequencies"
                val checkRes = rootShell.exec(
                    "[ -r '$curPath' ] && [ -r '$minPath' ] && [ -r '$availPath' ] && echo 'OK'"
                )
                if (checkRes.stdout.contains("OK")) {
                    matchedDir = basePath
                    break
                }
            }
            if (matchedDir != null) break
        }

        if (matchedDir == null) {
            notes.add("Memory (DDR) devfreq node not available.")
            return null
        }

        val curPath = "$matchedDir/cur_freq"
        val minPath = "$matchedDir/min_freq"
        val availPath = "$matchedDir/available_frequencies"
        val availRaw = rootShell.readFile(availPath).orEmpty()
        val availableHz = availRaw.trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .sorted()

        if (availableHz.isEmpty()) {
            notes.add("Memory (DDR) devfreq frequencies unavailable at $matchedDir.")
            return null
        }

        notes.add(
            "Memory (DDR) node ready ($matchedDir): ${availableHz.first() / 1_000_000}–${availableHz.last() / 1_000_000} MHz (${availableHz.size} steps)."
        )
        return DdrSpec(
            basePath = matchedDir,
            curFreqPath = curPath,
            minFreqPath = minPath,
            availableHz = availableHz
        )
    }

    suspend fun discoverGpu(notes: MutableList<String> = mutableListOf()): GpuSpec {
        val oppTableCandidates = listOf(
            "/proc/gpufreqv2/gpu_working_opp_table",
            "/proc/gpufreqv2/gpu_signed_opp_table",
            "/proc/gpufreqv2/stack_working_opp_table"
        )
        val gedLoadCandidates = listOf(
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading"
        )
        val controlPaths = listOf(
            "/proc/gpufreqv2/gpufreq_status",
            "/proc/gpufreqv2/fix_target_opp_index"
        )

        val allProbePaths = oppTableCandidates + gedLoadCandidates + controlPaths
        val existsRes = rootShell.exec(
            allProbePaths.joinToString("; ") { p -> "[ -e '$p' ] && echo 'EXISTS:$p'" }
        )
        val existing = existsRes.stdout.lines()
            .filter { it.startsWith("EXISTS:") }
            .map { it.removePrefix("EXISTS:").trim() }
            .toSet()

        val readPaths = (oppTableCandidates + controlPaths).filter { it in existing }
        val batch = rootShell.readFilesBatch(readPaths)

        var chosenTablePath: String? = null
        var parsedTable = ParsedOppTableResult(emptyList(), "No OPP table found")
        for (candidate in oppTableCandidates) {
            val content = batch[candidate].orEmpty()
            if (content.isNotBlank()) {
                val res = SysfsParsers.parseGpufreqOppTable(content)
                if (res.sortedOpps.isNotEmpty()) {
                    chosenTablePath = candidate
                    parsedTable = res
                    break
                }
            }
        }

        val statusPath = "/proc/gpufreqv2/gpufreq_status".takeIf { it in existing }
        val fixOppPath = "/proc/gpufreqv2/fix_target_opp_index".takeIf { it in existing }
        val availableGedLoadPaths = gedLoadCandidates.filter { it in existing }

        val sortedFreqs = parsedTable.sortedOpps.map { it.freqKhz }
        val discovered = parsedTable.sortedOpps.isNotEmpty() || fixOppPath != null || statusPath != null

        notes.add(
            if (parsedTable.sortedOpps.isNotEmpty()) {
                "GPU OPP table loaded from $chosenTablePath: ${parsedTable.sortedOpps.size} OPPs " +
                    "(${sortedFreqs.first() / 1000}–${sortedFreqs.last() / 1000} MHz), ${parsedTable.orderDescription}. " +
                    "GED load candidates: ${availableGedLoadPaths.joinToString().ifBlank { "none" }}."
            } else {
                "GPU OPP table empty or unavailable under /proc/gpufreqv2/."
            }
        )

        return GpuSpec(
            discovered = discovered,
            oppTableSourcePath = chosenTablePath,
            detectedOrderNote = parsedTable.orderDescription,
            gpufreqStatusPath = statusPath,
            gpufreqFixOppPath = fixOppPath,
            gedLoadCandidatePaths = availableGedLoadPaths,
            oppTable = parsedTable.sortedOpps,
            availableFreqsKhz = sortedFreqs
        )
    }

    private suspend fun discoverCpuPolicies(notes: MutableList<String>): List<CpuPolicySpec> {
        val lsRes = rootShell.exec("ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null")
        if (!lsRes.isSuccess || lsRes.stdout.isBlank()) {
            notes.add("No cpufreq policy directories found.")
            return emptyList()
        }

        val policyDirs = lsRes.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedBy { path ->
                path.substringAfterLast("policy").toIntOrNull() ?: Int.MAX_VALUE
            }

        val readPaths = policyDirs.flatMap { dir ->
            listOf(
                "$dir/related_cpus",
                "$dir/scaling_available_frequencies",
                "$dir/scaling_governor",
                "$dir/scaling_min_freq",
                "$dir/scaling_max_freq",
                "$dir/cpuinfo_min_freq",
                "$dir/cpuinfo_max_freq"
            )
        }
        val batch = rootShell.readFilesBatch(readPaths)

        val policies = mutableListOf<CpuPolicySpec>()
        for (dir in policyDirs) {
            val id = dir.substringAfterLast("policy").toIntOrNull() ?: continue
            val related = (batch["$dir/related_cpus"] ?: "")
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .sorted()
            val freqs = SysfsParsers.parseFreqListKhz(batch["$dir/scaling_available_frequencies"] ?: "")
            val gov = (batch["$dir/scaling_governor"] ?: "schedutil").ifBlank { "schedutil" }
            val origMin = (batch["$dir/scaling_min_freq"] ?: "").toLongOrNull()
                ?: (batch["$dir/cpuinfo_min_freq"] ?: "").toLongOrNull()
                ?: freqs.firstOrNull() ?: 0L
            val origMax = (batch["$dir/scaling_max_freq"] ?: "").toLongOrNull()
                ?: (batch["$dir/cpuinfo_max_freq"] ?: "").toLongOrNull()
                ?: freqs.lastOrNull() ?: 0L

            val resolvedFreqs = freqs.ifEmpty {
                listOfNotNull(origMin.takeIf { it > 0 }, origMax.takeIf { it > 0 }).distinct().sorted()
            }
            val clusterLabel = buildClusterLabel(id, related, resolvedFreqs)
            policies.add(
                CpuPolicySpec(
                    policyId = id,
                    name = clusterLabel,
                    path = dir,
                    relatedCpus = related,
                    availableFreqsKhz = resolvedFreqs,
                    origGovernor = gov,
                    origMinFreqKhz = origMin,
                    origMaxFreqKhz = origMax
                )
            )
        }
        notes.add("Discovered ${policies.size} CPU policy cluster(s).")
        return policies
    }

    private fun buildClusterLabel(id: Int, cpus: List<Int>, freqs: List<Long>): String {
        val coreRange = when {
            cpus.isEmpty() -> "policy$id"
            cpus.size == 1 -> "CPU ${cpus.first()}"
            else -> "CPU ${cpus.first()}-${cpus.last()}"
        }
        val maxMhz = (freqs.lastOrNull() ?: 0L) / 1000L
        val role = when {
            maxMhz >= 2100 -> "Prime/Big"
            cpus.size >= 4 && maxMhz <= 2050 -> "Efficiency"
            else -> "Cluster $id"
        }
        return "$role ($coreRange)"
    }

    private suspend fun discoverThermalSensors(notes: MutableList<String>): List<ThermalSensorSpec> {
        val sensors = mutableListOf<ThermalSensorSpec>()

        val battTempPath = "/sys/class/power_supply/battery/temp"
        val battCheck = rootShell.exec("[ -r '$battTempPath' ] && cat '$battTempPath'")
        if (battCheck.isSuccess && battCheck.stdout.isNotBlank()) {
            val tempC = SysfsParsers.parseTemperatureC(battCheck.stdout, 10f) ?: 0f
            sensors.add(
                ThermalSensorSpec(
                    id = "battery_psy",
                    label = "Battery (power_supply)",
                    path = battTempPath,
                    category = "Battery",
                    divisorToCelsius = 10f,
                    initialTempC = tempC
                )
            )
        }

        val tzRes = rootShell.exec("ls -d /sys/class/thermal/thermal_zone* 2>/dev/null")
        val tzDirs = tzRes.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedBy { it.substringAfterLast("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }

        if (tzDirs.isNotEmpty()) {
            val paths = tzDirs.flatMap { listOf("$it/type", "$it/temp") }
            val batch = rootShell.readFilesBatch(paths)
            for (dir in tzDirs) {
                val type = batch["$dir/type"]?.trim().orEmpty()
                val rawTemp = batch["$dir/temp"]?.trim().orEmpty()
                if (type.isBlank() || rawTemp.isBlank()) continue
                val num = rawTemp.toFloatOrNull() ?: continue
                val divisor = if (kotlin.math.abs(num) > 200f) 1000f else 1f
                val tempC = num / divisor
                if (tempC !in -20f..130f) continue

                val category = classifyThermalZone(type)
                val zoneName = dir.substringAfterLast("/")
                sensors.add(
                    ThermalSensorSpec(
                        id = zoneName,
                        label = "$type ($category)",
                        path = "$dir/temp",
                        category = category,
                        divisorToCelsius = divisor,
                        initialTempC = tempC
                    )
                )
            }
        }
        notes.add("Discovered ${sensors.size} thermal sensor(s).")
        return sensors
    }

    private fun classifyThermalZone(type: String): String {
        val lower = type.lowercase()
        return when {
            "batt" in lower || "charger" in lower -> "Battery"
            "cpu" in lower || "mtktscpu" in lower || "ap" in lower -> "CPU"
            "gpu" in lower || "mali" in lower -> "GPU"
            "soc" in lower || "tsap" in lower || "mtkts" in lower -> "SoC"
            "pmic" in lower -> "PMIC"
            "skin" in lower || "shell" in lower -> "Skin"
            else -> "Thermal"
        }
    }

    private suspend fun discoverBatteryPower(notes: MutableList<String>): BatteryPowerSpec {
        val candidatesCurrent = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/Battery/current_now",
            "/sys/class/power_supply/mtk-gauge/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        val candidatesVoltage = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/Battery/voltage_now",
            "/sys/class/power_supply/mtk-gauge/voltage_now",
            "/sys/class/power_supply/bms/voltage_now"
        )
        val candidatesChargeFull = listOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/Battery/charge_full",
            "/sys/class/power_supply/mtk-gauge/charge_full",
            "/sys/class/power_supply/bms/charge_full"
        )
        val candidatesChargeFullDesign = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/Battery/charge_full_design",
            "/sys/class/power_supply/mtk-gauge/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
        )
        val candidatesCycleCount = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/Battery/cycle_count",
            "/sys/class/power_supply/mtk-gauge/cycle_count",
            "/sys/class/power_supply/bms/cycle_count"
        )
        val all = candidatesCurrent + candidatesVoltage +
            listOf("/sys/class/power_supply/battery/status") +
            candidatesChargeFull + candidatesChargeFullDesign + candidatesCycleCount
        val existsRes = rootShell.exec(
            all.joinToString("; ") { p -> "[ -r '$p' ] && echo 'EXISTS:$p'" }
        )
        val existing = existsRes.stdout.lines()
            .filter { it.startsWith("EXISTS:") }
            .map { it.removePrefix("EXISTS:").trim() }
            .toSet()

        val curPath = candidatesCurrent.firstOrNull { it in existing }
        val voltPath = candidatesVoltage.firstOrNull { it in existing }
        val statusPath = "/sys/class/power_supply/battery/status".takeIf { it in existing }
        val chargeFullPath = candidatesChargeFull.firstOrNull { it in existing }
        val chargeFullDesignPath = candidatesChargeFullDesign.firstOrNull { it in existing }
        val cycleCountPath = candidatesCycleCount.firstOrNull { it in existing }

        notes.add(
            if (curPath != null && voltPath != null) {
                "Battery power nodes ready ($curPath)."
            } else {
                "Battery current/voltage nodes partially unavailable."
            }
        )
        return BatteryPowerSpec(
            currentPath = curPath,
            voltagePath = voltPath,
            statusPath = statusPath,
            chargeFullPath = chargeFullPath,
            chargeFullDesignPath = chargeFullDesignPath,
            cycleCountPath = cycleCountPath
        )
    }
}
