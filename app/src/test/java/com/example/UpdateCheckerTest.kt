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

import com.example.governor.UpdateChecker
import com.example.governor.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {
    private class FakeCache(
        override var etag: String? = null,
        override var cachedBody: String? = null,
        override var rateLimitUntilMs: Long = 0L
    ) : UpdateChecker.CacheStore

    @Test fun newerPatchMinorMajor() {
        assertTrue(UpdateChecker.isNewer("v2.1.2", "2.1.1"))
        assertTrue(UpdateChecker.isNewer("2.2.0", "2.1.9"))
        assertTrue(UpdateChecker.isNewer("v3.0", "2.9.9"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v2.1.1", "2.1.1"))
        assertFalse(UpdateChecker.isNewer("2.1", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("1.12.8", "2.1.1"))
    }

    @Test fun numericNotLexicographic() {
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.0"))
    }

    @Test fun suffixesIgnored() {
        assertEquals(listOf(2, 1, 1), UpdateChecker.parseVersion("v2.1.1-beta+5"))
    }

    @Test fun semVerPrereleaseOrderingAndNumericSegments() {
        assertTrue(UpdateChecker.compareVersions("3.2.0-pre", "3.2.0") < 0)
        assertTrue(UpdateChecker.compareVersions("3.2.0", "3.2.0-pre") > 0)
        assertTrue(UpdateChecker.compareVersions("3.3.0-beta.2", "3.3.0-beta.10") < 0)
        assertTrue(UpdateChecker.compareVersions("3.3.0-alpha.1", "3.3.0-beta.1") < 0)
        assertTrue(UpdateChecker.compareVersions("3.3.0-1", "3.3.0-beta") < 0)
        assertTrue(UpdateChecker.isNewer("v3.3.0+build99", "3.2.0"))
        assertFalse(UpdateChecker.isNewer("v3.2.0-rc.1", "3.2.0"))
    }

    @Test fun jsonSkipsDraftsFiltersPrereleasesPicksHighestAndPrefersApk() {
        val json = """
            [
              {"tag_name":"v3.5.0","draft":true,"prerelease":false,"html_url":"https://ex/draft","body":"draft","assets":[]},
              {"tag_name":"v3.4.0-rc.1","draft":false,"prerelease":true,"html_url":"https://ex/rc","body":"## Pre\n- **Fast** build","assets":[{"name":"cg-rc.apk","browser_download_url":"https://ex/cg-rc.apk"}]},
              {"tag_name":"v3.2.5","draft":false,"prerelease":false,"html_url":"https://ex/325","body":"older","assets":[]},
              {"tag_name":"v3.3.0","draft":false,"prerelease":false,"html_url":"https://ex/330","body":"### Changes\n[Details](https://ex) & **fixes**","assets":[{"name":"notes.txt","browser_download_url":"https://ex/notes.txt"},{"name":"CoreGovernor-3.3.0.apk","browser_download_url":"https://ex/cg.apk"}]}
            ]
        """.trimIndent()

        val stableOnly = UpdateChecker.evaluateReleasesJson(json, "3.2.0", includePrereleases = false)
        assertTrue(stableOnly is UpdateState.Available)
        stableOnly as UpdateState.Available
        assertEquals("3.3.0", stableOnly.latest)
        assertEquals("https://ex/cg.apk", stableOnly.downloadUrl)
        assertFalse(stableOnly.isPrerelease)
        assertTrue(stableOnly.notes.contains("Details"))
        assertFalse(stableOnly.notes.contains("**"))
        assertFalse(stableOnly.notes.contains("###"))

        val withPre = UpdateChecker.evaluateReleasesJson(json, "3.2.0", includePrereleases = true)
        assertTrue(withPre is UpdateState.Available)
        withPre as UpdateState.Available
        assertEquals("3.4.0-rc.1", withPre.latest)
        assertEquals("https://ex/cg-rc.apk", withPre.downloadUrl)
        assertTrue(withPre.isPrerelease)
    }

    @Test fun jsonFallbackToHtmlUrlWhenNoApkAsset() {
        val json = """[{"tag_name":"v3.3.0","draft":false,"prerelease":false,"html_url":"https://ex/rel","body":"plain","assets":[{"name":"source.zip","browser_download_url":"https://ex/s.zip"}]}]"""
        val res = UpdateChecker.evaluateReleasesJson(json, "3.2.0", includePrereleases = false) as UpdateState.Available
        assertEquals("https://ex/rel", res.downloadUrl)
    }

    @Test fun etag304ReusesCachedBodyAndMarksChecked() {
        val cachedJson = """[{"tag_name":"v3.3.0","draft":false,"prerelease":false,"html_url":"https://ex/rel","body":"cached","assets":[]}]"""
        val cache = FakeCache(etag = "\"abc\"", cachedBody = cachedJson)
        var sentEtag: String? = null
        val outcome = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = cache,
            nowMs = { 10_000L },
            sleeper = {},
            fetcher = { _, headers ->
                sentEtag = headers["If-None-Match"]
                UpdateChecker.HttpResponse(304)
            }
        )
        assertEquals("\"abc\"", sentEtag)
        assertTrue(outcome.shouldMarkChecked)
        assertTrue(outcome.state is UpdateState.Available)
        assertEquals("3.3.0", (outcome.state as UpdateState.Available).latest)
    }

    @Test fun retriesOnceOnIoOr5xxAndNeverRetries4xx() {
        var ioAttempts = 0
        val cache1 = FakeCache()
        val res1 = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = cache1,
            nowMs = { 10_000L },
            sleeper = {},
            fetcher = { _, _ ->
                ioAttempts++
                if (ioAttempts == 1) throw IOException("timeout")
                UpdateChecker.HttpResponse(200, """[{"tag_name":"v3.2.0","draft":false,"prerelease":false,"html_url":"h","body":"","assets":[]}]""")
            }
        )
        assertEquals(2, ioAttempts)
        assertTrue(res1.state is UpdateState.UpToDate)
        assertTrue(res1.shouldMarkChecked)

        var http5xxAttempts = 0
        val res5xx = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = FakeCache(),
            nowMs = { 10_000L },
            sleeper = {},
            fetcher = { _, _ ->
                http5xxAttempts++
                UpdateChecker.HttpResponse(503)
            }
        )
        assertEquals(2, http5xxAttempts)
        assertTrue(res5xx.state is UpdateState.Failed.Http)
        assertFalse(res5xx.shouldMarkChecked)

        var http404Attempts = 0
        val res404 = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = FakeCache(),
            nowMs = { 10_000L },
            sleeper = {},
            fetcher = { _, _ ->
                http404Attempts++
                UpdateChecker.HttpResponse(404)
            }
        )
        assertEquals(1, http404Attempts)
        assertTrue(res404.state is UpdateState.Failed.NoRelease)
        assertFalse(res404.shouldMarkChecked)
    }

    @Test fun rateLimitStoresRetryAtAndSkipsNetworkUntilExpiry() {
        val cache = FakeCache()
        var calls = 0
        val now = 1_700_000_000_000L
        val first = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = cache,
            nowMs = { now },
            sleeper = {},
            fetcher = { _, _ ->
                calls++
                UpdateChecker.HttpResponse(
                    code = 403,
                    headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to ((now / 1000L) + 600L).toString())
                )
            }
        )
        assertEquals(1, calls)
        assertTrue(first.state is UpdateState.Failed.RateLimited)
        assertEquals(now + 600_000L, cache.rateLimitUntilMs)
        assertFalse(first.shouldMarkChecked)

        val second = UpdateChecker.fetchWithCache(
            current = "3.2.0",
            includePrereleases = false,
            cache = cache,
            nowMs = { now + 60_000L },
            sleeper = {},
            fetcher = { _, _ ->
                calls++
                UpdateChecker.HttpResponse(200, "[]")
            }
        )
        assertEquals(1, calls) // network skipped!
        assertTrue(second.state is UpdateState.Failed.RateLimited)
    }

    @Test fun stripMarkdownCapsLengthAt400() {
        val longBody = "# Header\n" + "**word** ".repeat(100)
        val stripped = UpdateChecker.stripMarkdown(longBody)
        assertTrue(stripped.length <= 400)
        assertTrue(stripped.endsWith("…"))
        assertFalse(stripped.contains("**"))
        assertFalse(stripped.contains("# "))
    }
}
