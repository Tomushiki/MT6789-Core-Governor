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

import com.example.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds the running rating totals and keeps them in rating.json. Saves are atomic (temp file +
 * rename) and serialized by one lock that also takes the data snapshot, so overlapping saves can
 * never write an older state over a newer one.
 */
class RatingStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String, String) -> Unit
) {
    private val lock = Any()
    private val ioLock = Any()
    private var agg = RatingAgg()
    private var lastSaveMs = 0L
    private var lastPublishMs = 0L

    private val _snapshot = MutableStateFlow(RatingAgg())
    val snapshot: StateFlow<RatingAgg> = _snapshot.asStateFlow()

    init {
        load()
        _snapshot.value = synchronized(lock) { agg.copy() }
    }

    fun add(fps: Float, watts: Float, tempC: Float, cpuLoadPct: Float, inGame: Boolean, charging: Boolean) {
        val now = System.currentTimeMillis()
        var publish = false
        var save = false
        synchronized(lock) {
            agg.add(fps, watts, tempC, cpuLoadPct, inGame, charging)
            if (now - lastPublishMs >= 10_000L) { lastPublishMs = now; publish = true }
            if (now - lastSaveMs >= 300_000L) { lastSaveMs = now; save = true }
        }
        if (publish) _snapshot.value = synchronized(lock) { agg.copy() }
        if (save) scope.launch(Dispatchers.IO) { save() }
    }

    fun flush() {
        _snapshot.value = synchronized(lock) { agg.copy() }
        save()
    }

    fun reset() {
        synchronized(lock) { agg = RatingAgg() }
        _snapshot.value = RatingAgg()
        save()
    }

    private fun save() = synchronized(ioLock) {
        try {
            val json = synchronized(lock) { toJson(agg) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            log(LogLevel.WARN, "Rating", "Could not save rating data: ${e.message}")
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            val a = RatingAgg(
                seconds = o.optLong("seconds"), gameN = o.optLong("gameN"),
                effN = o.optLong("effN"), sumEff = o.optDouble("sumEff", 0.0),
                powerN = o.optLong("powerN"), sumWatts = o.optDouble("sumWatts", 0.0),
                tempN = o.optLong("tempN"), sumTemp = o.optDouble("sumTemp", 0.0),
                idleN = o.optLong("idleN"), sumIdleLoad = o.optDouble("sumIdleLoad", 0.0)
            )
            val h = o.optJSONArray("fpsHist")
            if (h != null) for (i in 0 until minOf(h.length(), RatingAgg.FPS_BINS)) a.fpsHist[i] = h.optInt(i)
            synchronized(lock) { agg = a }
        } catch (e: Exception) {
            log(LogLevel.WARN, "Rating", "Could not read rating data, starting fresh: ${e.message}")
        }
    }

    private fun toJson(a: RatingAgg): JSONObject {
        val o = JSONObject()
        o.put("seconds", a.seconds); o.put("gameN", a.gameN)
        o.put("effN", a.effN); o.put("sumEff", a.sumEff)
        o.put("powerN", a.powerN); o.put("sumWatts", a.sumWatts)
        o.put("tempN", a.tempN); o.put("sumTemp", a.sumTemp)
        o.put("idleN", a.idleN); o.put("sumIdleLoad", a.sumIdleLoad)
        val h = JSONArray()
        for (c in a.fpsHist) h.put(c)
        o.put("fpsHist", h)
        return o
    }
}
