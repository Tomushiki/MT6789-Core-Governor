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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import androidx.annotation.StringRes
import com.example.R
import com.example.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sensor pinning ("Nifty features" knob).
 *
 * Same mechanism as the Boost Sensors feature of SwiftSense: keep a normal SensorEventListener
 * registered on the chosen sensors at a fixed fast sampling period. The sensor hub runs a sensor
 * at the fastest rate any client asks for, so every other app (games, camera, etc.) then gets
 * the fast rate too. No root is needed.
 *
 * - Events are received on a dedicated HandlerThread (display priority) so delivery never waits
 *   on the main thread.
 * - No batching (max report latency 0), so samples are delivered as they are produced.
 * - Releasing drops the rate back (a short register at SENSOR_DELAY_NORMAL + flush + unregister,
 *   as SwiftSense does) so the hub does not stay at the fast rate.
 * - The measured delivery rate per sensor is published once a second so the UI can show it.
 *
 * Settings are kept in their own SharedPreferences, so GovernorConfig is not touched.
 */
class SensorPinner(
    context: Context,
    private val logger: (LogLevel, String, String) -> Unit
) : SensorEventListener {

    enum class Rate(val key: String, val periodUs: Int, val label: String, @StringRes val labelRes: Int) {
        MAX("max", 0, "Max", R.string.nifty_rate_max),
        FAST("fast", 5_000, "Fast (200 Hz)", R.string.nifty_rate_fast),
        MEDIUM("medium", 10_000, "Medium (100 Hz)", R.string.nifty_rate_medium),
        SLOW("slow", 20_000, "Slow (50 Hz)", R.string.nifty_rate_slow),
        VERY_SLOW("very_slow", 60_000, "Very slow (~16 Hz)", R.string.nifty_rate_very_slow);

        companion object {
            fun fromKey(key: String?): Rate = values().firstOrNull { it.key == key } ?: MAX
        }
    }

    data class Config(
        val enabled: Boolean = false,
        val rate: Rate = Rate.MAX,
        val sensorTypes: Set<Int> = setOf(Sensor.TYPE_GYROSCOPE),
        val pauseWhenScreenOff: Boolean = true,
        /** Pin only while a game is detected and running (Game mode). */
        val gameOnly: Boolean = false
    )

    data class SensorInfo(
        val type: Int,
        val label: String,
        val name: String,
        /** Fastest rate the hardware reports (Hz), 0 when the sensor does not report one. */
        val maxHz: Int,
        @StringRes val labelRes: Int = 0
    )

    data class State(
        val config: Config = Config(),
        val available: List<SensorInfo> = emptyList(),
        /** True while listeners are actually registered. */
        val active: Boolean = false,
        /** True while the engine reports Game mode. */
        val gameActive: Boolean = false,
        /** Measured delivery rate per sensor type (Hz), refreshed once a second while active. */
        val measuredHz: Map<Int, Int> = emptyMap()
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val lock = Any()
    private var serviceRunning = false
    private var screenInteractive = true
    private var gameActive = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var registered: List<Sensor> = emptyList()

    // Touched only on the sensor thread.
    private val counts = SparseIntArray()
    private var windowStartMs = 0L

    private val _state = MutableStateFlow(State(config = loadConfig(), available = discoverSensors()))
    val state: StateFlow<State> = _state.asStateFlow()

    fun setConfig(transform: (Config) -> Config) {
        synchronized(lock) {
            val next = transform(_state.value.config)
            saveConfig(next)
            _state.update { it.copy(config = next) }
            reconcile()
        }
    }

    /** Called by the engine when the governor starts and stops. */
    fun setServiceRunning(running: Boolean) {
        synchronized(lock) {
            if (serviceRunning == running) return
            serviceRunning = running
            reconcile()
        }
    }

    /** Called by the engine every cycle with whether Game mode is active; only acts on a change. */
    fun setGameActive(active: Boolean) {
        synchronized(lock) {
            if (gameActive == active) return
            gameActive = active
            _state.update { it.copy(gameActive = active) }
            // Only re-register when this setting actually depends on the game state.
            if (_state.value.config.gameOnly) reconcile()
        }
    }

    fun setScreenInteractive(interactive: Boolean) {
        synchronized(lock) {
            if (screenInteractive == interactive) return
            screenInteractive = interactive
            reconcile()
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun reconcile() {
        val cfg = _state.value.config
        val shouldRun = serviceRunning && cfg.enabled && (screenInteractive || !cfg.pauseWhenScreenOff) &&
            (!cfg.gameOnly || gameActive)
        // Re-register from scratch on every change: simple, and a rate change always takes effect.
        releaseLocked()
        if (shouldRun) registerLocked(cfg)
    }

    private fun registerLocked(cfg: Config) {
        val sm = sensorManager
        if (sm == null) {
            logger(LogLevel.WARN, "SensorPin", "No SensorManager; sensor pinning unavailable.")
            return
        }
        val sensors = cfg.sensorTypes.mapNotNull { sm.getDefaultSensor(it) }
        if (sensors.isEmpty()) {
            logger(LogLevel.WARN, "SensorPin", "None of the selected sensors exist on this device.")
            return
        }
        val t = HandlerThread("CoreGovSensorPin", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        counts.clear()
        windowStartMs = SystemClock.elapsedRealtime()

        val ok = mutableListOf<Sensor>()
        for (s in sensors) {
            try {
                sm.unregisterListener(this, s)
                // 4-arg overload = max report latency 0 (no batching).
                if (sm.registerListener(this, s, cfg.rate.periodUs, h)) {
                    ok += s
                    sm.flush(this)
                } else {
                    logger(LogLevel.WARN, "SensorPin", "Could not register ${s.name}.")
                }
            } catch (e: SecurityException) {
                logger(LogLevel.WARN, "SensorPin", "${s.name}: ${e.message}")
            } catch (e: Throwable) {
                logger(LogLevel.WARN, "SensorPin", "${s.name}: ${e.javaClass.simpleName}")
            }
        }
        registered = ok
        _state.update { it.copy(active = ok.isNotEmpty(), measuredHz = emptyMap()) }
        if (ok.isNotEmpty()) {
            logger(
                LogLevel.INFO,
                "SensorPin",
                "Pinned ${ok.joinToString(", ") { it.name }} at ${cfg.rate.label}."
            )
        }
    }

    private fun releaseLocked() {
        val sm = sensorManager
        val h = handler
        val t = thread
        if (sm != null && registered.isNotEmpty()) {
            for (s in registered) {
                try {
                    // Drop the hub back to the normal rate before leaving, as SwiftSense does.
                    if (h != null) {
                        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, h)
                        sm.flush(this)
                    }
                    sm.unregisterListener(this, s)
                } catch (_: Throwable) {
                }
            }
            try {
                sm.unregisterListener(this)
            } catch (_: Throwable) {
            }
            logger(LogLevel.INFO, "SensorPin", "Released sensors.")
        }
        registered = emptyList()
        handler = null
        thread = null
        t?.quitSafely()
        if (_state.value.active || _state.value.measuredHz.isNotEmpty()) {
            _state.update { it.copy(active = false, measuredHz = emptyMap()) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        counts.put(type, counts.get(type, 0) + 1)
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000L) {
            val hz = HashMap<Int, Int>(counts.size())
            for (i in 0 until counts.size()) {
                hz[counts.keyAt(i)] = ((counts.valueAt(i) * 1000L) / elapsed).toInt()
            }
            counts.clear()
            windowStartMs = now
            _state.update { it.copy(measuredHz = hz) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun discoverSensors(): List<SensorInfo> {
        val sm = sensorManager ?: return emptyList()
        return CANDIDATES.mapNotNull { candidate ->
            val s = sm.getDefaultSensor(candidate.type) ?: return@mapNotNull null
            val maxHz = if (s.minDelay > 0) 1_000_000 / s.minDelay else 0
            SensorInfo(
                type = candidate.type,
                label = candidate.label,
                name = s.name,
                maxHz = maxHz,
                labelRes = candidate.labelRes
            )
        }
    }

    private fun loadConfig(): Config {
        val types = prefs.getString(KEY_TYPES, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(Sensor.TYPE_GYROSCOPE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            rate = Rate.fromKey(prefs.getString(KEY_RATE, null)),
            sensorTypes = types,
            pauseWhenScreenOff = prefs.getBoolean(KEY_PAUSE_OFF, true),
            gameOnly = prefs.getBoolean(KEY_GAME_ONLY, false)
        )
    }

    private fun saveConfig(c: Config) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_RATE, c.rate.key)
            .putString(KEY_TYPES, c.sensorTypes.joinToString(","))
            .putBoolean(KEY_PAUSE_OFF, c.pauseWhenScreenOff)
            .putBoolean(KEY_GAME_ONLY, c.gameOnly)
            .apply()
    }

    private companion object {
        const val PREFS = "coregovernor_sensor_pin"
        const val KEY_ENABLED = "enabled"
        const val KEY_RATE = "rate"
        const val KEY_TYPES = "types"
        const val KEY_PAUSE_OFF = "pause_screen_off"
        const val KEY_GAME_ONLY = "game_only"

        private data class Candidate(val type: Int, val label: String, @StringRes val labelRes: Int)

        private val CANDIDATES = listOf(
            Candidate(Sensor.TYPE_GYROSCOPE, "Gyroscope", R.string.sensor_gyro),
            Candidate(Sensor.TYPE_ACCELEROMETER, "Accelerometer", R.string.sensor_accel),
            Candidate(Sensor.TYPE_GAME_ROTATION_VECTOR, "Game rotation vector", R.string.sensor_game_rot),
            Candidate(Sensor.TYPE_ROTATION_VECTOR, "Rotation vector", R.string.sensor_rot),
            Candidate(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, "Gyroscope (uncalibrated)", R.string.sensor_gyro_uncal),
            Candidate(Sensor.TYPE_LINEAR_ACCELERATION, "Linear acceleration", R.string.sensor_linear_accel),
            Candidate(Sensor.TYPE_GRAVITY, "Gravity", R.string.sensor_gravity),
            Candidate(Sensor.TYPE_MAGNETIC_FIELD, "Magnetometer", R.string.sensor_mag)
        )
    }
}
