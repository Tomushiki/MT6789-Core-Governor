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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of asking GitHub for the newest release. */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val current: String) : UpdateState()
    data class Available(
        val current: String,
        val latest: String,
        val notes: String,
        val downloadUrl: String,
        val pageUrl: String
    ) : UpdateState()
    data class Failed(val reason: String) : UpdateState()
}

object UpdateChecker {
    const val REPO = "Tomushiki/MT6789-Core-Governor"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_AUTO = "auto_check"
    const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** Parses "v2.1.1", "2.1.1-beta" etc. into numeric parts; non-numeric parts count as 0. */
    fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** True when [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Blocking network call; run on Dispatchers.IO. */
    fun fetch(current: String): UpdateState {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CoreGovernor/$current")
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    if (tag.isBlank()) return UpdateState.Failed("No release tag found")
                    if (!isNewer(tag, current)) return UpdateState.UpToDate(current)
                    val page = json.optString("html_url")
                    var apk = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apk = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    UpdateState.Available(
                        current = current,
                        latest = tag.removePrefix("v"),
                        notes = json.optString("body").take(600),
                        downloadUrl = apk.ifBlank { page },
                        pageUrl = page
                    )
                }
                404 -> UpdateState.Failed("No releases published yet")
                403, 429 -> UpdateState.Failed("GitHub rate limit, try again later")
                else -> UpdateState.Failed("Server returned $code")
            }
        } catch (e: Exception) {
            UpdateState.Failed(e.message?.take(80) ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoCheckEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
    fun autoCheckDue(ctx: Context): Boolean =
        autoCheckEnabled(ctx) &&
            System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST_CHECK, 0L) > AUTO_INTERVAL_MS
    fun markChecked(ctx: Context) =
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
}
