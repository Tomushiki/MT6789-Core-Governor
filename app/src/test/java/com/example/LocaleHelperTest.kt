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

import com.example.data.LocaleHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocaleHelperTest {

    @Test
    fun normalizeTag_handlesSupportedAliasesAndUnknowns() {
        assertEquals("", LocaleHelper.normalizeTag(null))
        assertEquals("", LocaleHelper.normalizeTag(""))
        assertEquals("", LocaleHelper.normalizeTag("   "))
        assertEquals("en", LocaleHelper.normalizeTag("en"))
        assertEquals("id", LocaleHelper.normalizeTag("id"))
        assertEquals("id", LocaleHelper.normalizeTag("in"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt-BR"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt_br"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt-rBR"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt"))
        assertEquals("ar", LocaleHelper.normalizeTag("AR"))
        assertEquals("vi", LocaleHelper.normalizeTag("vi"))
        assertEquals("fa", LocaleHelper.normalizeTag("fa"))
        assertEquals("fa", LocaleHelper.normalizeTag("fa_IR"))
        assertEquals("", LocaleHelper.normalizeTag("xx"))
        assertEquals("", LocaleHelper.normalizeTag("ja-JP"))
    }

    @Test
    fun localeForTag_mapsExpectedLocales() {
        assertNull(LocaleHelper.localeForTag(""))
        assertNull(LocaleHelper.localeForTag("unknown"))
        assertEquals(Locale.ENGLISH, LocaleHelper.localeForTag("en"))

        val indonesian = LocaleHelper.localeForTag("id")!!
        assertTrue(indonesian.language == "in" || indonesian.language == "id")

        val ptBr = LocaleHelper.localeForTag("pt-BR")!!
        assertEquals("pt", ptBr.language)
        assertEquals("BR", ptBr.country)

        assertEquals("ar", LocaleHelper.localeForTag("ar")!!.language)
        assertEquals("ru", LocaleHelper.localeForTag("ru")!!.language)
        assertEquals(Locale.FRENCH, LocaleHelper.localeForTag("fr"))
    }

    @Test
    fun isRtlLocale_identifiesRtlAndLtrCorrectly() {
        assertFalse(LocaleHelper.isRtlLocale(null))
        assertFalse(LocaleHelper.isRtlLocale(Locale.ENGLISH))
        assertFalse(LocaleHelper.isRtlLocale(Locale("in")))
        assertFalse(LocaleHelper.isRtlLocale(Locale("pt", "BR")))
        assertTrue(LocaleHelper.isRtlLocale(Locale("ar")))
        assertTrue(LocaleHelper.isRtlLocale(Locale("iw")))
        assertTrue(LocaleHelper.isRtlLocale(Locale("fa")))
    }
}
