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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of asking GitHub for the newest release. */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val current: String, val isPrerelease: Boolean = false) : UpdateState()
    data class Available(
        val current: String,
        val latest: String,
        val notes: String,
        val downloadUrl: String,
        val pageUrl: String,
        val isPrerelease: Boolean = false
    ) : UpdateState()

    sealed class Failed(open val reason: String) : UpdateState() {
        data class Offline(override val reason: String = "Offline") : Failed(reason)
        data class RateLimited(
            val retryAtMs: Long,
            override val reason: String = "Rate limited"
        ) : Failed(reason)

        data class NoRelease(override val reason: String = "No releases published yet") : Failed(reason)
        data class Http(val code: Int, override val reason: String = "Server returned $code") : Failed(reason)
        data class Generic(override val reason: String) : Failed(reason)

        companion object {
            operator fun invoke(reason: String): Failed = Generic(reason)
        }
    }
}

object UpdateChecker {
    const val REPO = "Tomushiki/MT6789-Core-Governor"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val PREFS = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_INCLUDE_PRERELEASES = "include_prereleases"
    private const val KEY_ETAG = "cached_etag"
    private const val KEY_CACHED_BODY = "cached_body"
    private const val KEY_RATE_LIMIT_UNTIL = "rate_limit_until_ms"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000L
    private const val RETRY_DELAY_MS = 1500L
    private const val FALLBACK_RATE_LIMIT_MS = 15L * 60 * 1000L
    private const val MAX_NOTES_CHARS = 400

    data class HttpResponse(
        val code: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap()
    )

    fun interface HttpFetcher {
        @Throws(IOException::class)
        fun get(url: String, headers: Map<String, String>): HttpResponse
    }

    interface CacheStore {
        var etag: String?
        var cachedBody: String?
        var rateLimitUntilMs: Long
    }

    data class FetchOutcome(
        val state: UpdateState,
        val shouldMarkChecked: Boolean
    )

    private class PrefsCacheStore(ctx: Context) : CacheStore {
        private val p = prefs(ctx)
        override var etag: String?
            get() = p.getString(KEY_ETAG, null)
            set(value) { p.edit().putString(KEY_ETAG, value).apply() }
        override var cachedBody: String?
            get() = p.getString(KEY_CACHED_BODY, null)
            set(value) { p.edit().putString(KEY_CACHED_BODY, value).apply() }
        override var rateLimitUntilMs: Long
            get() = p.getLong(KEY_RATE_LIMIT_UNTIL, 0L)
            set(value) { p.edit().putLong(KEY_RATE_LIMIT_UNTIL, value).apply() }
    }

    private data class SemVer(
        val core: List<Int>,
        val preRelease: List<String>
    )

    /** Parses numeric core segments of "v2.1.1", "2.1.1-beta+5" etc. */
    fun parseVersion(v: String): List<Int> {
        val core = v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('+').substringBefore('-')
        if (core.isBlank()) return listOf(0)
        return core.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun parseSemVer(v: String): SemVer {
        val cleaned = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val dashIdx = cleaned.indexOf('-')
        val corePart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val prePart = if (dashIdx >= 0) cleaned.substring(dashIdx + 1) else ""
        val core = if (corePart.isBlank()) listOf(0) else corePart.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val pre = if (prePart.isBlank()) emptyList() else prePart.split('.').filter { it.isNotEmpty() }
        return SemVer(core, pre)
    }

    /**
     * SemVer comparison with `v`/`V` stripping, `+build` ignoring, and pre-release precedence
     * (`3.2.0-pre < 3.2.0`, `beta.2 < beta.10`). Tolerates non-SemVer tags gracefully.
     */
    fun compareVersions(a: String, b: String): Int {
        val va = parseSemVer(a)
        val vb = parseSemVer(b)
        val maxCore = maxOf(va.core.size, vb.core.size)
        for (i in 0 until maxCore) {
            val x = va.core.getOrElse(i) { 0 }
            val y = vb.core.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        val aHasPre = va.preRelease.isNotEmpty()
        val bHasPre = vb.preRelease.isNotEmpty()
        if (!aHasPre && !bHasPre) return 0
        if (!aHasPre) return 1
        if (!bHasPre) return -1

        val maxPre = maxOf(va.preRelease.size, vb.preRelease.size)
        for (i in 0 until maxPre) {
            if (i >= va.preRelease.size) return -1
            if (i >= vb.preRelease.size) return 1
            val sa = va.preRelease[i]
            val sb = vb.preRelease[i]
            val ia = sa.toIntOrNull()
            val ib = sb.toIntOrNull()
            when {
                ia != null && ib != null -> if (ia != ib) return ia.compareTo(ib)
                ia != null && ib == null -> return -1 // numeric identifiers have lower precedence than non-numeric
                ia == null && ib != null -> return 1
                else -> {
                    val cmp = sa.compareTo(sb)
                    if (cmp != 0) return cmp
                }
            }
        }
        return 0
    }

    /** True when [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean = compareVersions(latest, current) > 0

    /** Strips basic GitHub release Markdown into readable plain text, capped at [maxChars]. */
    fun stripMarkdown(raw: String, maxChars: Int = MAX_NOTES_CHARS): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        text = text.lines().joinToString("\n") { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^>+\\s?"), "")
                .replace(Regex("^[-*_]{3,}$"), "")
                .replace(Regex("^[-*+]\\s+"), "• ")
        }
        text = text
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars - 1).trimEnd()
        val lastSpace = cut.lastIndexOf(' ')
        val cleanCut = if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut
        return "$cleanCut…"
    }

    /** Parses `/releases?per_page=10` JSON (or single release object fallback), applying filters and SemVer ordering. */
    fun evaluateReleasesJson(
        jsonBody: String,
        current: String,
        includePrereleases: Boolean
    ): UpdateState {
        val trimmed = jsonBody.trim()
        if (trimmed.isEmpty()) return UpdateState.Failed.NoRelease()
        val array = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONArray().put(JSONObject(trimmed))
            }
        } catch (e: Exception) {
            return UpdateState.Failed(e.message?.take(80) ?: "Invalid release JSON")
        }

        data class Candidate(
            val tag: String,
            val cleanVersion: String,
            val notes: String,
            val downloadUrl: String,
            val pageUrl: String,
            val prerelease: Boolean
        )

        var best: Candidate? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val isPre = obj.optBoolean("prerelease", false)
            if (isPre && !includePrereleases) continue
            val tag = obj.optString("tag_name").trim()
            if (tag.isBlank()) continue
            val cleanVer = tag.removePrefix("v").removePrefix("V")
            val page = obj.optString("html_url").trim()
            var apkUrl = ""
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    val name = a.optString("name")
                    val url = a.optString("browser_download_url").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        apkUrl = url
                        break
                    }
                }
            }
            val candidate = Candidate(
                tag = tag,
                cleanVersion = cleanVer,
                notes = stripMarkdown(obj.optString("body")),
                downloadUrl = apkUrl.ifBlank { page },
                pageUrl = page,
                prerelease = isPre
            )
            if (best == null || compareVersions(candidate.cleanVersion, best.cleanVersion) > 0) {
                best = candidate
            }
        }

        val chosen = best ?: return UpdateState.Failed.NoRelease()
        return if (isNewer(chosen.cleanVersion, current)) {
            UpdateState.Available(
                current = current,
                latest = chosen.cleanVersion,
                notes = chosen.notes,
                downloadUrl = chosen.downloadUrl,
                pageUrl = chosen.pageUrl,
                isPrerelease = chosen.prerelease
            )
        } else {
            UpdateState.UpToDate(current = current, isPrerelease = chosen.prerelease)
        }
    }

    /** Blocking network call; run on Dispatchers.IO. Kept signature-compatible for callers without Context. */
    fun fetch(current: String): UpdateState {
        return fetchWithCache(
            current = current,
            includePrereleases = false,
            cache = InMemoryCacheStore(),
            nowMs = { System.currentTimeMillis() },
            sleeper = { Thread.sleep(it) },
            fetcher = defaultHttpFetcher
        ).state
    }

    /** Context-backed cached fetch with ETag, rate-limit window guard, retry, and pre-release filter. */
    fun fetch(ctx: Context, current: String): UpdateState {
        val outcome = fetchWithCache(
            current = current,
            includePrereleases = includePrereleases(ctx),
            cache = PrefsCacheStore(ctx),
            nowMs = { System.currentTimeMillis() },
            sleeper = { Thread.sleep(it) },
            fetcher = defaultHttpFetcher
        )
        if (outcome.shouldMarkChecked) {
            markChecked(ctx)
        }
        return outcome.state
    }

    /** Evaluates the already-cached response when toggling "Include pre-releases" without network I/O. */
    fun evaluateFromCache(ctx: Context, current: String): UpdateState? {
        val body = prefs(ctx).getString(KEY_CACHED_BODY, null) ?: return null
        return evaluateReleasesJson(body, current, includePrereleases(ctx))
    }

    fun fetchWithCache(
        current: String,
        includePrereleases: Boolean,
        cache: CacheStore,
        nowMs: () -> Long,
        sleeper: (Long) -> Unit,
        fetcher: HttpFetcher
    ): FetchOutcome {
        val now = nowMs()
        if (cache.rateLimitUntilMs > now) {
            val cached = cache.cachedBody
            if (!cached.isNullOrBlank()) {
                val cachedState = evaluateReleasesJson(cached, current, includePrereleases)
                if (cachedState is UpdateState.Available) {
                    return FetchOutcome(cachedState, shouldMarkChecked = false)
                }
            }
            return FetchOutcome(
                state = UpdateState.Failed.RateLimited(cache.rateLimitUntilMs),
                shouldMarkChecked = false
            )
        }

        val reqHeaders = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("User-Agent", "CoreGovernor/$current")
            val cachedEtag = cache.etag
            if (!cachedEtag.isNullOrBlank() && !cache.cachedBody.isNullOrBlank()) {
                put("If-None-Match", cachedEtag)
            }
        }

        var response: HttpResponse? = null
        var lastIoException: IOException? = null
        for (attempt in 0..1) {
            try {
                val res = fetcher.get(API_URL, reqHeaders)
                if (res.code in 500..599 && attempt == 0) {
                    sleeper(RETRY_DELAY_MS)
                    continue
                }
                response = res
                lastIoException = null
                break
            } catch (io: IOException) {
                lastIoException = io
                if (attempt == 0) {
                    sleeper(RETRY_DELAY_MS)
                }
            } catch (e: Exception) {
                return FetchOutcome(
                    UpdateState.Failed(e.message?.take(80) ?: "Network error"),
                    shouldMarkChecked = false
                )
            }
        }

        if (lastIoException != null || response == null) {
            return FetchOutcome(
                UpdateState.Failed.Offline(lastIoException?.message?.take(80) ?: "Offline"),
                shouldMarkChecked = false
            )
        }

        val res = response
        return when (res.code) {
            200 -> {
                val newEtag = headerIgnoreCase(res.headers, "ETag")
                if (!newEtag.isNullOrBlank()) cache.etag = newEtag
                cache.cachedBody = res.body
                cache.rateLimitUntilMs = 0L
                val parsed = evaluateReleasesJson(res.body, current, includePrereleases)
                FetchOutcome(parsed, shouldMarkChecked = true)
            }
            304 -> {
                val cached = cache.cachedBody
                if (cached.isNullOrBlank()) {
                    FetchOutcome(UpdateState.Failed.NoRelease(), shouldMarkChecked = true)
                } else {
                    cache.rateLimitUntilMs = 0L
                    FetchOutcome(
                        evaluateReleasesJson(cached, current, includePrereleases),
                        shouldMarkChecked = true
                    )
                }
            }
            404 -> FetchOutcome(UpdateState.Failed.NoRelease(), shouldMarkChecked = false)
            403, 429 -> {
                val remaining = headerIgnoreCase(res.headers, "X-RateLimit-Remaining")?.trim()
                val retryAfterHeader = headerIgnoreCase(res.headers, "Retry-After")?.trim()
                val resetHeader = headerIgnoreCase(res.headers, "X-RateLimit-Reset")?.trim()
                val isRateLimit = res.code == 429 ||
                    remaining == "0" ||
                    retryAfterHeader != null ||
                    resetHeader != null
                if (isRateLimit) {
                    val retryAt = computeRetryAtMs(
                        nowMs = nowMs(),
                        retryAfterHeader = retryAfterHeader,
                        resetHeader = resetHeader
                    )
                    cache.rateLimitUntilMs = retryAt
                    FetchOutcome(UpdateState.Failed.RateLimited(retryAt), shouldMarkChecked = false)
                } else {
                    FetchOutcome(UpdateState.Failed.Http(res.code), shouldMarkChecked = false)
                }
            }
            else -> FetchOutcome(UpdateState.Failed.Http(res.code), shouldMarkChecked = false)
        }
    }

    fun computeRetryAtMs(
        nowMs: Long,
        retryAfterHeader: String?,
        resetHeader: String?
    ): Long {
        val retrySec = retryAfterHeader?.toLongOrNull()
        if (retrySec != null && retrySec > 0L) {
            return nowMs + retrySec * 1000L
        }
        val resetEpochSec = resetHeader?.toLongOrNull()
        if (resetEpochSec != null && resetEpochSec * 1000L > nowMs) {
            return resetEpochSec * 1000L
        }
        return nowMs + FALLBACK_RATE_LIMIT_MS
    }

    fun formatRetryTime(retryAtMs: Long, locale: Locale = Locale.getDefault()): String {
        val fmt = SimpleDateFormat("HH:mm", locale)
        return fmt.format(Date(retryAtMs))
    }

    private fun headerIgnoreCase(headers: Map<String, String>, key: String): String? {
        headers[key]?.let { return it }
        return headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    private val defaultHttpFetcher = HttpFetcher { url, headers ->
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headerMap = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (k, vals) ->
                if (k != null && !vals.isNullOrEmpty()) {
                    headerMap[k] = vals.first()
                }
            }
            HttpResponse(code = code, body = body, headers = headerMap)
        } finally {
            conn?.disconnect()
        }
    }

    private class InMemoryCacheStore : CacheStore {
        override var etag: String? = null
        override var cachedBody: String? = null
        override var rateLimitUntilMs: Long = 0L
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoCheckEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
    fun includePrereleases(ctx: Context) = prefs(ctx).getBoolean(KEY_INCLUDE_PRERELEASES, false)
    fun setIncludePrereleases(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_INCLUDE_PRERELEASES, on).apply()
    fun dismissedVersion(ctx: Context): String? = prefs(ctx).getString(KEY_DISMISSED_VERSION, null)
    fun setDismissedVersion(ctx: Context, version: String?) =
        prefs(ctx).edit().putString(KEY_DISMISSED_VERSION, version).apply()
    fun autoCheckDue(ctx: Context): Boolean =
        autoCheckEnabled(ctx) &&
            System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST_CHECK, 0L) > AUTO_INTERVAL_MS
    fun markChecked(ctx: Context) =
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
}
