package net.posdaca.oiia.core

import icu.windea.pls.config.config.delegated.CwtLocaleConfig
import icu.windea.pls.lang.util.ParadoxLocaleManager

object ParadoxLocalisationPreference {
    fun preferenceKey(includePreferredLocale: Boolean = true): String {
        return if (includePreferredLocale) preferredLocaleTags().joinToString("|") else ""
    }

    fun preferredLocaleConfig(): CwtLocaleConfig? {
        return runCatching { ParadoxLocaleManager.getPreferredLocaleConfig() }.getOrNull()
    }

    fun languagePriority(
        path: String,
        fallback: List<String>,
        weight: Int = 1,
        includePreferredLocale: Boolean = true
    ): Int {
        val lower = path.lowercase().replace('\\', '/')
        val tags = orderedTags(fallback, includePreferredLocale)
        for ((index, tag) in tags.withIndex()) {
            if (tag in lower) return (tags.size - index) * weight
        }
        return 0
    }

    fun cacheKey(
        fallback: List<String> = emptyList(),
        includePreferredLocale: Boolean = true
    ): String = orderedTags(fallback, includePreferredLocale).joinToString("|")

    private fun orderedTags(fallback: List<String>, includePreferredLocale: Boolean): List<String> {
        val result = linkedSetOf<String>()
        if (includePreferredLocale) result.addAll(preferredLocaleTags())
        fallback.forEach { result.addAll(expandLocaleTags(it)) }
        return result.toList()
    }

    private fun preferredLocaleTags(): List<String> {
        val locale = preferredLocaleConfig() ?: return emptyList()
        val localeIds = buildList {
            add(locale.id)
            add(locale.shortId)
            addAll(locale.codes)
            add(locale.text)
        }
        return localeIds.flatMap { expandLocaleTags(it) }.filterTo(linkedSetOf()) { it.isNotBlank() }.toList()
    }

    private fun expandLocaleTags(locale: String): List<String> {
        val normalized = normalizeLocale(locale)
        if (normalized.isBlank()) return emptyList()
        val tags = when (val base = normalized.removePrefix("l_")) {
            "zh", "zh_cn", "zh_hans", "cn", "simp_chinese", "simplified_chinese", "chinese", "简体中文", "中文" -> listOf("simp_chinese", "l_simp_chinese", "chinese", "l_chinese")
            "en", "en_us", "en_gb", "english", "英语", "英文" -> listOf("english", "l_english")
            "pt", "pt_br", "braz_por", "brazilian_portuguese", "portuguese", "葡萄牙语", "巴西葡萄牙语" -> listOf("braz_por", "l_braz_por")
            "fr", "fr_fr", "french", "法语" -> listOf("french", "l_french")
            "de", "de_de", "german", "德语" -> listOf("german", "l_german")
            "pl", "pl_pl", "polish", "波兰语" -> listOf("polish", "l_polish")
            "ru", "ru_ru", "russian", "俄语" -> listOf("russian", "l_russian")
            "es", "es_es", "spanish", "西班牙语" -> listOf("spanish", "l_spanish")
            "ja", "ja_jp", "japanese", "日语", "日本语" -> listOf("japanese", "l_japanese")
            else -> listOf(base, "l_$base")
        }
        return tags.filterTo(linkedSetOf()) { it.isNotBlank() }.toList()
    }

    private fun normalizeLocale(locale: String): String {
        return locale.trim().trim('"').lowercase()
            .replace('-', '_')
            .replace('.', '_')
            .replace(' ', '_')
            .replace('/', '_')
            .replace('\\', '_')
    }
}
