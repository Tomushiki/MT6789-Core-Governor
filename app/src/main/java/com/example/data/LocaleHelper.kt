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
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

data class LocaleOption(
    val tag: String,
    val nativeName: String
)

object LocaleHelper {
    private const val PREFS = "coregovernor_locale"
    private const val KEY_LANG_TAG = "lang_tag"

    private val RTL_LANGUAGES = setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi")

    val SUPPORTED: List<LocaleOption> = listOf(
        LocaleOption("", ""),
        LocaleOption("en", "English"),
        LocaleOption("id", "Bahasa Indonesia"),
        LocaleOption("es", "Español"),
        LocaleOption("pt-BR", "Português (Brasil)"),
        LocaleOption("ru", "Русский"),
        LocaleOption("tr", "Türkçe"),
        LocaleOption("hi", "हिन्दी"),
        LocaleOption("ar", "العربية"),
        LocaleOption("fr", "Français"),
        LocaleOption("vi", "Tiếng Việt"),
        LocaleOption("fa", "فارسی")
    )

    fun normalizeTag(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val canonical = trimmed.replace('_', '-').lowercase(Locale.ROOT)
        return when (canonical) {
            "en" -> "en"
            "id", "in" -> "id"
            "es" -> "es"
            "pt-br", "pt-rbr", "pt" -> "pt-BR"
            "ru" -> "ru"
            "tr" -> "tr"
            "hi" -> "hi"
            "ar" -> "ar"
            "fr" -> "fr"
            "vi" -> "vi"
            "fa", "fa-ir", "per", "fas" -> "fa"
            else -> ""
        }
    }

    fun localeForTag(raw: String?): Locale? {
        return when (normalizeTag(raw)) {
            "" -> null
            "en" -> Locale.ENGLISH
            // Java normalizes "id" to "in", which matches Android's values-in resource qualifier.
            "id" -> Locale("in")
            "es" -> Locale("es")
            "pt-BR" -> Locale("pt", "BR")
            "ru" -> Locale("ru")
            "tr" -> Locale("tr")
            "hi" -> Locale("hi")
            "ar" -> Locale("ar")
            "fr" -> Locale.FRENCH
            "vi" -> Locale("vi")
            "fa" -> Locale("fa")
            else -> null
        }
    }

    fun isRtlLocale(locale: Locale?): Boolean {
        if (locale == null) return false
        return locale.language.lowercase(Locale.ROOT) in RTL_LANGUAGES
    }

    fun getSelectedTag(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG_TAG, "")
        return normalizeTag(raw)
    }

    fun setSelectedTag(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG_TAG, normalized)
            .commit()
    }

    fun systemLocale(): Locale? {
        return try {
            val sysCfg = Resources.getSystem().configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!sysCfg.locales.isEmpty) sysCfg.locales[0] else null
            } else {
                @Suppress("DEPRECATION")
                sysCfg.locale
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun wrap(base: Context): Context {
        val selectedTag = getSelectedTag(base)
        val targetLocale = localeForTag(selectedTag) ?: systemLocale() ?: return base
        Locale.setDefault(targetLocale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(targetLocale)
        config.setLayoutDirection(targetLocale)
        return base.createConfigurationContext(config)
    }

    fun isRtl(context: Context): Boolean {
        val cfgLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = context.resources.configuration.locales
            if (!locales.isEmpty) locales[0] else null
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return isRtlLocale(cfgLocale)
    }
}
